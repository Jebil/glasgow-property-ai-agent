package com.glasgow.propertyagent.service;

import com.glasgow.propertyagent.entity.IndexedPropertyEntity;
import com.glasgow.propertyagent.model.ExtractedPropertyMetadata;
import com.glasgow.propertyagent.model.StationProfile;
import com.glasgow.propertyagent.repository.IndexedPropertyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * Unified portal-agnostic ingestion, lookahead media prefetching, local LLM/Vision extraction,
 * asking-price delta sync, and DB persistence pipeline (REF-01, SIMP-03).
 */
@Service
public class PropertyIngestionPipeline {

    private static final Logger log = LoggerFactory.getLogger(PropertyIngestionPipeline.class);

    public record PageParseOutcome(
        int newlyIndexed,
        int itemsOnPage,
        int unseenInCorridorOnPage,
        boolean reachedEnd
    ) {}

    private record PreloadedMedia(
        String listingId,
        PortalListingCandidate.DetailEnrichment detail,
        PropertyMediaService.DownloadedMediaBundle media
    ) {}

    private record InMemoryPriceSnapshot(
        String listingId,
        int priceGbp,
        Integer previousPriceGbp,
        String priceQualifier
    ) implements IndexedPropertyRepository.ListingPriceSnapshot {
        @Override public String getListingId() { return listingId; }
        @Override public int getPriceGbp() { return priceGbp; }
        @Override public Integer getPreviousPriceGbp() { return previousPriceGbp; }
        @Override public String getPriceQualifier() { return priceQualifier; }
    }

    private final IndexedPropertyRepository repository;
    private final StationCommuteService stationService;
    private final OllamaAgentService ollamaAgent;
    private final PropertyMediaService mediaService;
    private final PropertyEmbeddingService embeddingService;
    private final PropertyMetadataCache metadataCache;
    private final RightmovePortalClient rightmoveClient;
    private final OnTheMarketPortalClient otmClient;
    private final ScrapeJobStateTracker stateTracker;
    private final ObjectMapper objectMapper;
    private final ExecutorService virtualIoExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public PropertyIngestionPipeline(
            IndexedPropertyRepository repository,
            StationCommuteService stationService,
            OllamaAgentService ollamaAgent,
            PropertyMediaService mediaService,
            PropertyEmbeddingService embeddingService,
            PropertyMetadataCache metadataCache,
            RightmovePortalClient rightmoveClient,
            OnTheMarketPortalClient otmClient,
            ScrapeJobStateTracker stateTracker,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.stationService = stationService;
        this.ollamaAgent = ollamaAgent;
        this.mediaService = mediaService;
        this.embeddingService = embeddingService;
        this.metadataCache = metadataCache;
        this.rightmoveClient = rightmoveClient;
        this.otmClient = otmClient;
        this.stateTracker = stateTracker;
        this.objectMapper = objectMapper;
    }

    public Map<String, IndexedPropertyRepository.ListingPriceSnapshot> loadPriceSnapshotMap() {
        Map<String, IndexedPropertyRepository.ListingPriceSnapshot> map = new ConcurrentHashMap<>();
        try {
            List<IndexedPropertyRepository.ListingPriceSnapshot> rows = repository.findAllPriceSnapshots();
            if (rows != null) {
                for (IndexedPropertyRepository.ListingPriceSnapshot row : rows) {
                    if (row != null && row.getListingId() != null) {
                        map.put(row.getListingId(), row);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Price snapshot preload fallback: {}", e.getMessage());
        }
        return map;
    }

    /**
     * IMP-05: Syncs asking-price reductions, increases, or qualifier changes (e.g. Offers Over -> Fixed Price)
     * on existing DB listings in milliseconds without re-downloading media or re-running gemma4:e4b.
     */
    public void syncExistingListingDelta(
            String listingId,
            int currentPriceGbp,
            String currentQualifier,
            Map<String, IndexedPropertyRepository.ListingPriceSnapshot> existingSnapshots) {
        stateTracker.incrementSkippedExisting();
        IndexedPropertyRepository.ListingPriceSnapshot snap = existingSnapshots.get(listingId);
        if (snap == null) return;

        Instant now = Instant.now();
        String normNewQual = (currentQualifier == null || currentQualifier.isBlank()) ? snap.getPriceQualifier() : currentQualifier.trim();
        String normOldQual = snap.getPriceQualifier() == null ? "" : snap.getPriceQualifier().trim();

        boolean priceChanged = currentPriceGbp > 0 && currentPriceGbp != snap.getPriceGbp();
        boolean qualChanged = normNewQual != null && !normNewQual.isBlank() && !normNewQual.equalsIgnoreCase(normOldQual);

        try {
            if (priceChanged) {
                Integer prevToStore = (snap.getPreviousPriceGbp() != null
                        && snap.getPreviousPriceGbp() > snap.getPriceGbp()
                        && currentPriceGbp < snap.getPriceGbp())
                    ? snap.getPreviousPriceGbp()
                    : snap.getPriceGbp();
                repository.updateListingPriceChange(listingId, prevToStore, currentPriceGbp, normNewQual, now, now);
                existingSnapshots.put(listingId, new InMemoryPriceSnapshot(listingId, currentPriceGbp, prevToStore, normNewQual));
                stateTracker.incrementPriceChangesDetected();
                log.info("[{}] Synced asking-price change: £{} -> £{} ({})", listingId, snap.getPriceGbp(), currentPriceGbp, normNewQual);
            } else if (qualChanged) {
                repository.updateListingPriceChange(listingId, snap.getPreviousPriceGbp(), snap.getPriceGbp(), normNewQual, now, now);
                existingSnapshots.put(listingId, new InMemoryPriceSnapshot(listingId, snap.getPriceGbp(), snap.getPreviousPriceGbp(), normNewQual));
                stateTracker.incrementPriceChangesDetected();
                log.info("[{}] Synced price qualifier change: '{}' -> '{}'", listingId, normOldQual, normNewQual);
            } else {
                repository.touchLastSeen(listingId, now);
            }
        } catch (Exception e) {
            log.debug("[{}] Price delta sync skipped: {}", listingId, e.getMessage());
        }
    }

    /**
     * Unified ingestion pass across normalized cards from either Rightmove or OnTheMarket.
     */
    public PageParseOutcome ingestPageCandidates(
            PortalListingCandidate.PageCards pageCards,
            Function<PortalListingCandidate, PortalListingCandidate.DetailEnrichment> detailFetcher,
            String centerLocation,
            double maxRadiusMiles,
            Set<String> seenIdsInCorridor,
            Set<String> seenIdsInJob,
            Map<String, IndexedPropertyRepository.ListingPriceSnapshot> existingSnapshots,
            Set<String> allowedTypes,
            String corridorName,
            int corridorIdx,
            int totalCorridors,
            int humanPage,
            int maxPagesCap) {
        List<PortalListingCandidate> items = pageCards.candidates();
        if (items == null || items.isEmpty()) {
            return new PageParseOutcome(0, 0, 0, true);
        }

        int count = 0;
        int itemsOnPage = items.size();
        int unseenInCorridor = 0;

        if (pageCards.totalResults() > 0) {
            int estPages = Math.min(maxPagesCap, Math.max(1, (int) Math.ceil((double) pageCards.totalResults() / Math.max(1, pageCards.pageSize()))));
            stateTracker.updateEstimatedMaxPages(corridorName, corridorIdx, totalCorridors, humanPage, estPages);
        }

        CompletableFuture<PreloadedMedia> nextPrefetch = null;
        try {
            for (int itemIdx = 0; itemIdx < items.size(); itemIdx++) {
                if (stateTracker.isCancelRequested()) {
                    log.info("Cancellation requested during corridor '{}' page {}", corridorName, humanPage);
                    break;
                }

                PortalListingCandidate item = items.get(itemIdx);
                try {
                    if (item.rawId() == null || item.rawId().isBlank()) continue;

                    if (!seenIdsInCorridor.add(item.rawId())) {
                        continue;
                    }
                    unseenInCorridor++;

                    String listingId = item.listingId();
                    if (!seenIdsInJob.add(listingId)) {
                        continue;
                    }

                    if (existingSnapshots.containsKey(listingId)) {
                        syncExistingListingDelta(listingId, item.priceGbp(), item.priceQualifier(), existingSnapshots);
                        continue;
                    }

                    if (allowedTypes != null && !allowedTypes.isEmpty() && allowedTypes.size() < 5) {
                        if (!allowedTypes.contains(item.normalizedType()) && !"Unknown".equalsIgnoreCase(item.normalizedType())) {
                            continue;
                        }
                    }

                    if (item.priceGbp() <= 0) {
                        stateTracker.incrementFailedItems();
                        log.info("Skipping unpriced/POA {} listing {} ({}) on corridor '{}' page {}",
                            item.portal(), listingId, item.address(), corridorName, humanPage);
                        continue;
                    }

                    StationCommuteService.NearestStationMatch stationMatch =
                        stationService.resolveNearestStation(item.latitude(), item.longitude(), item.address());

                    double distFromTarget = (item.latitude() != 0.0 && item.longitude() != 0.0)
                        ? stationService.calculateDistanceFromCenterMiles(centerLocation, item.latitude(), item.longitude())
                        : stationMatch.distanceFromCentralMiles();
                    if (distFromTarget > maxRadiusMiles) {
                        continue;
                    }

                    stateTracker.updateListingStart(
                        listingId,
                        item.address(),
                        humanPage,
                        "Fetching " + item.portal() + " detail page & floorplans for " + listingId + "..."
                    );

                    // BUG-01: Strictly verify listingId before consuming prefetched media
                    PreloadedMedia currentMedia = null;
                    if (nextPrefetch != null) {
                        try {
                            PreloadedMedia candidate = nextPrefetch.join();
                            if (candidate != null && listingId.equals(candidate.listingId())) {
                                currentMedia = candidate;
                            }
                        } catch (Exception e) {
                            currentMedia = null;
                        }
                        nextPrefetch = null;
                    }

                    if (currentMedia == null) {
                        PortalListingCandidate.DetailEnrichment detail = detailFetcher.apply(item);
                        PropertyMediaService.DownloadedMediaBundle media = mediaService.downloadAllPropertyMedia(
                            listingId,
                            detail.floorplanUrls(),
                            detail.galleryImageUrls()
                        );
                        currentMedia = new PreloadedMedia(listingId, detail, media);
                    }

                    PortalListingCandidate.DetailEnrichment detail = currentMedia.detail();
                    PropertyMediaService.DownloadedMediaBundle media = currentMedia.media();

                    // BUG-01: Scan forward for the exact next ELIGIBLE item on this page before prefetching on Virtual Thread
                    int nextEligibleIdx = findNextEligibleIndex(
                        items, itemIdx + 1, centerLocation, maxRadiusMiles, seenIdsInCorridor, seenIdsInJob, existingSnapshots, allowedTypes
                    );
                    if (nextEligibleIdx >= 0) {
                        final PortalListingCandidate nextItem = items.get(nextEligibleIdx);
                        nextPrefetch = CompletableFuture.supplyAsync(() -> {
                            try {
                                if (stateTracker.isCancelRequested()) return null;
                                String nId = nextItem.listingId();
                                if (seenIdsInJob.contains(nId) || existingSnapshots.containsKey(nId)) return null;
                                PortalListingCandidate.DetailEnrichment nDet = detailFetcher.apply(nextItem);
                                PropertyMediaService.DownloadedMediaBundle nM = mediaService.downloadAllPropertyMedia(
                                    nId,
                                    nDet.floorplanUrls(),
                                    nDet.galleryImageUrls()
                                );
                                return new PreloadedMedia(nId, nDet, nM);
                            } catch (Exception e) {
                                return null;
                            }
                        }, virtualIoExecutor);
                    } else {
                        nextPrefetch = null;
                    }

                    List<String> featuresList = new ArrayList<>(item.keyFeatures());
                    for (String ef : detail.extraFeatures()) {
                        if (!featuresList.contains(ef)) {
                            featuresList.add(ef);
                        }
                    }

                    String combinedDescription = buildCombinedDescription(item, detail.fullDescription(), featuresList);
                    int expectedMediaCount = detail.floorplanUrls().size() + detail.galleryImageUrls().size();
                    stateTracker.updateMediaProgress(0, expectedMediaCount,
                        "Analyzing " + expectedMediaCount + " floorplans & gallery photos on local Ollama...");

                    OllamaAgentService.LlmExtractionResult extraction = ollamaAgent.extractMetadataWithTranscript(
                        listingId,
                        combinedDescription + " " + String.join(" ", featuresList),
                        media,
                        stateTracker::updateMediaProgress
                    );

                    ExtractedPropertyMetadata metadata = assembleMetadata(extraction.metadata(), featuresList, item.bathrooms());

                    StationProfile station = stationMatch.station();
                    double distStation = stationMatch.distanceToStationMiles();

                    IndexedPropertyEntity entity = new IndexedPropertyEntity();
                    entity.setListingId(listingId);
                    entity.setTitle(item.bedrooms() + " Bed " + item.normalizedType() + " — " + item.address());
                    entity.setAddress(item.address());
                    entity.setPropertyType(item.normalizedType());
                    entity.setPriceGbp(item.priceGbp());
                    entity.setPriceQualifier(item.priceQualifier());
                    entity.setBedrooms(item.bedrooms());
                    entity.setDistanceFromGlasgowCentralMiles(stationMatch.distanceFromCentralMiles());
                    entity.setLatitude(item.latitude());
                    entity.setLongitude(item.longitude());

                    List<String> storedPhotos = !media.localPhotoWebUrls().isEmpty() ? media.localPhotoWebUrls() : detail.galleryImageUrls();
                    List<String> storedFloorplans = !media.localFloorplanWebUrls().isEmpty() ? media.localFloorplanWebUrls() : detail.floorplanUrls();

                    entity.setRightmoveUrl(item.listingUrl());
                    entity.setThumbnailUrl(media.localThumbnailWebUrl(item.coverImgUrl()));
                    entity.setFloorplanUrl(!storedFloorplans.isEmpty() ? storedFloorplans.get(0) : detail.floorplanUrl());
                    entity.setAllImagesJson(objectMapper.writeValueAsString(storedPhotos));
                    entity.setAllFloorplansJson(objectMapper.writeValueAsString(storedFloorplans));
                    entity.setDownloadedImageCount(media.totalDownloadedFiles());
                    entity.setRawDescription(
                        combinedDescription.length() > 7800
                            ? combinedDescription.substring(0, 7800)
                            : combinedDescription
                    );
                    entity.setNearestStationName(station.stationName());
                    entity.setDistanceToStationMiles(distStation);
                    entity.setFastestTrainMins(station.fastestTrainToGlasgowCentralMins());
                    entity.setEstimatedDoorToCentralMins(stationService.calculateDoorToCentralMins(distStation, station));
                    entity.setStationFreeParking(station.freeParkAndRide());
                    entity.setStationAccessibilityCategory(station.accessibilityCategory());
                    entity.setHasDownstairsWc(Boolean.TRUE.equals(metadata.hasDownstairsWc()));
                    entity.setHasMasterEnsuite(Boolean.TRUE.equals(metadata.hasMasterEnsuite()));
                    entity.setDirectGardenAccessFromLiving(Boolean.TRUE.equals(metadata.directGardenAccessFromLiving()));
                    entity.setMetadataJson(objectMapper.writeValueAsString(metadata));
                    entity.setLlmResponseLog(extraction.fullLlmResponseLog());
                    entity.setMetadataSource(extraction.metadataSource());
                    entity.setMetadataModel(extraction.metadataModel());
                    Instant now = Instant.now();
                    entity.setAnalyzedAt(now);
                    entity.setIndexedAt(now);
                    entity.setLastSeenAt(now);

                    if (metadataCache != null) {
                        metadataCache.put(listingId, metadata);
                    }
                    stationService.resolveAndPopulateBusInfo(entity);

                    repository.save(entity);
                    existingSnapshots.put(listingId, new InMemoryPriceSnapshot(listingId, item.priceGbp(), null, item.priceQualifier()));
                    if (embeddingService != null) {
                        try {
                            embeddingService.embedProperty(entity);
                        } catch (Exception embEx) {
                            log.warn("[{}] Vector embedding generation skipped: {}", listingId, embEx.getMessage());
                        }
                    }
                    count++;
                    stateTracker.onListingSaved(listingId, item.address());
                } catch (Exception itemEx) {
                    stateTracker.incrementFailedItems();
                    log.warn("Skipping malformed {} listing item (id={}) on corridor '{}' page {}: {}",
                        item.portal(), item.rawId(), corridorName, humanPage, itemEx.getMessage());
                }
            }
        } finally {
            if (nextPrefetch != null) {
                nextPrefetch.cancel(true);
            }
        }

        int maxPages = stateTracker.getCurrentCorridorMaxPages();
        boolean reachedEnd = (unseenInCorridor == 0) || (maxPages > 0 && humanPage >= maxPages);
        return new PageParseOutcome(count, itemsOnPage, unseenInCorridor, reachedEnd);
    }

    private int findNextEligibleIndex(
            List<PortalListingCandidate> items,
            int startIdx,
            String centerLocation,
            double maxRadiusMiles,
            Set<String> seenIdsInCorridor,
            Set<String> seenIdsInJob,
            Map<String, IndexedPropertyRepository.ListingPriceSnapshot> existingSnapshots,
            Set<String> allowedTypes) {
        Set<String> previewRawIds = new HashSet<>(seenIdsInCorridor);
        for (int idx = startIdx; idx < items.size(); idx++) {
            PortalListingCandidate cand = items.get(idx);
            if (cand.rawId() == null || cand.rawId().isBlank() || !previewRawIds.add(cand.rawId())) continue;
            if (seenIdsInJob.contains(cand.listingId()) || existingSnapshots.containsKey(cand.listingId())) continue;

            if (allowedTypes != null && !allowedTypes.isEmpty() && allowedTypes.size() < 5) {
                if (!allowedTypes.contains(cand.normalizedType()) && !"Unknown".equalsIgnoreCase(cand.normalizedType())) {
                    continue;
                }
            }
            if (cand.priceGbp() <= 0) continue;

            double dist = (cand.latitude() != 0.0 && cand.longitude() != 0.0)
                ? stationService.calculateDistanceFromCenterMiles(centerLocation, cand.latitude(), cand.longitude())
                : stationService.resolveNearestStation(cand.latitude(), cand.longitude(), cand.address()).distanceFromCentralMiles();
            if (dist > maxRadiusMiles) continue;

            return idx;
        }
        return -1;
    }

    private String buildCombinedDescription(
            PortalListingCandidate item,
            String detailDescription,
            List<String> featuresList) {
        if (detailDescription != null && !detailDescription.isBlank()) {
            return detailDescription;
        }
        String summary = item.summary() == null ? "" : item.summary();
        if (!summary.isBlank() && !featuresList.isEmpty()) {
            return String.format("%s at %s (Marketed by %s). %s. Key features: %s.",
                item.propertyTitle(), item.address(), item.agentName(), summary, String.join("; ", featuresList));
        } else if (!summary.isBlank()) {
            return String.format("%s at %s (Marketed by %s). %s.",
                item.propertyTitle(), item.address(), item.agentName(), summary);
        } else if (!featuresList.isEmpty()) {
            return String.format("%s at %s (Marketed by %s). %d bedrooms, %d bathrooms. Key features: %s.",
                item.propertyTitle(), item.address(), item.agentName(), item.bedrooms(), item.bathrooms(), String.join("; ", featuresList));
        }
        return String.format("%s at %s (Marketed by %s). %d bedrooms, %d bathrooms.",
            item.propertyTitle(), item.address(), item.agentName(), item.bedrooms(), item.bathrooms());
    }

    /**
     * Downloads ALL gallery photos + ALL floorplans locally and runs local Ollama Vision / LLM analysis across indexed properties.
     */
    public Map<String, Object> reanalyzeIndexedPropertiesWithLlm(int maxCount, boolean onlyFailed) {
        OllamaAgentService.LlmStatusReport status = ollamaAgent.getLlmStatus(true);
        List<IndexedPropertyEntity> all = repository.findAll();
        if (onlyFailed) {
            all = all.stream()
                .filter(p -> "HEURISTIC".equalsIgnoreCase(p.getMetadataSource())
                    || (p.getLlmResponseLog() != null && p.getLlmResponseLog().contains("JSON SYNTHESIS FALLBACK"))
                    || p.getMetadataSource() == null)
                .toList();
            log.info("Filtering re-analysis to {} properties with fallback/heuristic metadata...", all.size());
        }
        int limit = maxCount <= 0 ? all.size() : Math.min(all.size(), maxCount);
        int updated = 0;
        int totalImagesDownloaded = 0;

        stateTracker.initCorridors(limit);

        log.info("Starting pipelined full image download & LLM/Vision analysis for {} properties (onlyFailed={}, Status: {})...",
            limit, onlyFailed, status.modeDescription());
        CompletableFuture<PreloadedMedia> nextPrefetch = null;
        try {
            for (int i = 0; i < limit; i++) {
                if (stateTracker.isCancelRequested()) {
                    log.info("Cancellation requested during re-analysis loop at property {}/{}", i + 1, limit);
                    stateTracker.setStatusMessage("Job cancelled by user.");
                    break;
                }
                IndexedPropertyEntity entity = all.get(i);
                stateTracker.updateReanalysisItemStart(i, limit, entity.getListingId(), entity.getAddress());
                try {
                    PreloadedMedia currentMedia = null;
                    if (nextPrefetch != null) {
                        try {
                            PreloadedMedia candidate = nextPrefetch.join();
                            if (candidate != null && entity.getListingId().equals(candidate.listingId())) {
                                currentMedia = candidate;
                            }
                        } catch (Exception ignored) {}
                        nextPrefetch = null;
                    }
                    if (currentMedia == null) {
                        currentMedia = prepareMediaForReanalysis(entity);
                    }
                    PortalListingCandidate.DetailEnrichment detail = currentMedia.detail();
                    PropertyMediaService.DownloadedMediaBundle media = currentMedia.media();

                    String existingDesc = entity.getRawDescription() != null ? entity.getRawDescription() : "";
                    String descToAnalyze = detail.fullDescription().length() > existingDesc.length()
                        ? detail.fullDescription()
                        : (!existingDesc.isBlank() ? existingDesc : detail.fullDescription());

                    if (media.totalDownloadedFiles() == 0 && descToAnalyze.isBlank()) {
                        log.warn("[{}] Re-analysis skipped: No local/remote media and no description found.", entity.getListingId());
                        continue;
                    }
                    totalImagesDownloaded += media.totalDownloadedFiles();

                    if (i + 1 < limit) {
                        IndexedPropertyEntity nextEntity = all.get(i + 1);
                        nextPrefetch = CompletableFuture.supplyAsync(() -> {
                            try {
                                if (stateTracker.isCancelRequested()) return null;
                                return prepareMediaForReanalysis(nextEntity);
                            } catch (Exception ex) {
                                return null;
                            }
                        }, virtualIoExecutor);
                    } else {
                        nextPrefetch = null;
                    }

                    OllamaAgentService.LlmExtractionResult extraction = ollamaAgent.extractMetadataWithTranscript(
                        entity.getListingId(),
                        descToAnalyze,
                        media,
                        stateTracker::updateMediaProgress
                    );
                    int knownBathrooms = extractKnownBathrooms(entity);
                    List<String> extraFeats = !detail.extraFeatures().isEmpty() ? detail.extraFeatures() : List.of();
                    ExtractedPropertyMetadata meta = assembleMetadata(extraction.metadata(), extraFeats, knownBathrooms);

                    List<String> storedPhotos = !media.localPhotoWebUrls().isEmpty() ? media.localPhotoWebUrls() : detail.galleryImageUrls();
                    List<String> storedFloorplans = !media.localFloorplanWebUrls().isEmpty() ? media.localFloorplanWebUrls() : detail.floorplanUrls();
                    entity.setThumbnailUrl(media.localThumbnailWebUrl(entity.getThumbnailUrl()));
                    if (!storedFloorplans.isEmpty()) entity.setFloorplanUrl(storedFloorplans.get(0));
                    if (!storedPhotos.isEmpty()) entity.setAllImagesJson(objectMapper.writeValueAsString(storedPhotos));
                    if (!storedFloorplans.isEmpty()) entity.setAllFloorplansJson(objectMapper.writeValueAsString(storedFloorplans));
                    entity.setDownloadedImageCount(media.totalDownloadedFiles());
                    if (descToAnalyze.length() > existingDesc.length()) {
                        entity.setRawDescription(descToAnalyze.length() > 7800 ? descToAnalyze.substring(0, 7800) : descToAnalyze);
                    }

                    entity.setHasDownstairsWc(Boolean.TRUE.equals(meta.hasDownstairsWc()));
                    entity.setHasMasterEnsuite(Boolean.TRUE.equals(meta.hasMasterEnsuite()));
                    entity.setDirectGardenAccessFromLiving(Boolean.TRUE.equals(meta.directGardenAccessFromLiving()));
                    entity.setMetadataJson(objectMapper.writeValueAsString(meta));
                    entity.setLlmResponseLog(extraction.fullLlmResponseLog());
                    entity.setMetadataSource(extraction.metadataSource());
                    entity.setMetadataModel(extraction.metadataModel());
                    entity.setAnalyzedAt(Instant.now());

                    repository.save(entity);
                    if (metadataCache != null) {
                        metadataCache.put(entity.getListingId(), meta);
                    }

                    if (embeddingService != null) {
                        try {
                            embeddingService.embedProperty(entity);
                        } catch (Exception embEx) {
                            log.warn("[{}] Vector embedding generation skipped: {}", entity.getListingId(), embEx.getMessage());
                        }
                    }
                    updated++;
                    stateTracker.onReanalysisItemSaved(updated, i + 1, limit, totalImagesDownloaded);
                } catch (Exception e) {
                    stateTracker.incrementFailedItems();
                    log.warn("Failed downloading/analyzing {}: {}", entity.getListingId(), e.getMessage());
                    stateTracker.markCorridorCompleted(i + 1);
                }
            }
        } finally {
            if (nextPrefetch != null) {
                nextPrefetch.cancel(true);
            }
        }

        log.info("Completed full media download ({} files) & metadata analysis for {}/{} properties ({}).",
            totalImagesDownloaded, updated, limit, status.modeDescription());
        return Map.of(
            "reanalyzedCount", updated,
            "totalImagesDownloaded", totalImagesDownloaded,
            "llmStatus", status
        );
    }

    private PreloadedMedia prepareMediaForReanalysis(IndexedPropertyEntity entity) {
        String listingId = entity.getListingId();
        String url = entity.getRightmoveUrl() != null ? entity.getRightmoveUrl() : "";
        String existingDesc = entity.getRawDescription() != null ? entity.getRawDescription() : "";

        PropertyMediaService.DownloadedMediaBundle localExisting = mediaService.loadExistingLocalMedia(listingId);
        if (localExisting.totalDownloadedFiles() > 0 && existingDesc.length() >= 280) {
            PortalListingCandidate.DetailEnrichment cachedDetail = new PortalListingCandidate.DetailEnrichment(
                existingDesc,
                entity.getFloorplanUrl() != null ? entity.getFloorplanUrl() : entity.getThumbnailUrl(),
                localExisting.localFloorplanWebUrls(),
                List.of(),
                localExisting.localPhotoWebUrls()
            );
            return new PreloadedMedia(listingId, cachedDetail, localExisting);
        }

        boolean isRightmove = listingId.startsWith("RM-") || url.contains("rightmove.co.uk");
        PortalListingCandidate.DetailEnrichment remoteDetail;
        if (isRightmove && !url.isBlank()) {
            List<String> seedRemotePhotos = parseRemoteUrlsFromJson(entity.getAllImagesJson());
            remoteDetail = rightmoveClient.fetchDetail(url, entity.getThumbnailUrl(), seedRemotePhotos, List.of(), existingDesc);
        } else if (!url.isBlank()) {
            remoteDetail = otmClient.fetchDetail(url, entity.getThumbnailUrl());
        } else {
            remoteDetail = new PortalListingCandidate.DetailEnrichment(existingDesc, entity.getThumbnailUrl(), List.of(), List.of(), List.of());
        }

        PropertyMediaService.DownloadedMediaBundle finalMedia = localExisting;
        boolean needsDownload = localExisting.totalDownloadedFiles() == 0
            || (localExisting.floorplanFiles().isEmpty() && !remoteDetail.floorplanUrls().isEmpty());
        if (needsDownload && (!remoteDetail.floorplanUrls().isEmpty() || !remoteDetail.galleryImageUrls().isEmpty())) {
            PropertyMediaService.DownloadedMediaBundle downloaded = mediaService.downloadAllPropertyMedia(
                listingId,
                remoteDetail.floorplanUrls(),
                remoteDetail.galleryImageUrls()
            );
            if (downloaded.totalDownloadedFiles() >= localExisting.totalDownloadedFiles()) {
                finalMedia = downloaded;
            }
        }
        return new PreloadedMedia(listingId, remoteDetail, finalMedia);
    }

    private List<String> parseRemoteUrlsFromJson(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<String> parsed = objectMapper.readValue(json, new TypeReference<List<String>>() {});
            if (parsed == null) return List.of();
            return parsed.stream().filter(u -> u != null && u.startsWith("http")).toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    private int extractKnownBathrooms(IndexedPropertyEntity entity) {
        if (entity.getMetadataJson() != null && !entity.getMetadataJson().isBlank()) {
            try {
                ExtractedPropertyMetadata existing = objectMapper.readValue(entity.getMetadataJson(), ExtractedPropertyMetadata.class);
                if (existing != null && existing.totalBathrooms() > 0) {
                    return existing.totalBathrooms();
                }
            } catch (Exception ignored) {}
        }
        return 1;
    }

    /**
     * N6: Reusable metadata assembler ensuring both initial scrape and re-analysis
     * preserve extra listing features, valid bathroom counts, and avoid data loss.
     */
    public static ExtractedPropertyMetadata assembleMetadata(
            ExtractedPropertyMetadata baseMeta,
            List<String> extraFeatures,
            int knownBathrooms) {
        if (baseMeta == null) return null;
        Set<String> mergedHighlights = new LinkedHashSet<>();
        if (baseMeta.extractedHighlights() != null) {
            mergedHighlights.addAll(baseMeta.extractedHighlights());
        }
        if (extraFeatures != null) {
            for (String feat : extraFeatures) {
                if (feat != null && feat.length() <= 68 && !feat.toLowerCase().startsWith("tenure")) {
                    mergedHighlights.add(feat);
                }
            }
        }
        int totalBaths = Math.max(knownBathrooms, baseMeta.totalBathrooms());
        return new ExtractedPropertyMetadata(
            baseMeta.hasDownstairsWc(),
            baseMeta.hasMasterEnsuite(),
            totalBaths,
            baseMeta.livingRoomLocation(),
            baseMeta.directGardenAccessFromLiving(),
            baseMeta.gardenSightlineFromSofa(),
            baseMeta.kitchenLayout(),
            baseMeta.kitchenExtensionPotentialToRear(),
            baseMeta.hasUtilityRoom(),
            baseMeta.hasDedicatedStudyOrOffice(),
            baseMeta.garageType(),
            baseMeta.gardenEnclosedForKids(),
            baseMeta.gardenOrientation(),
            baseMeta.drivewayCarCapacity(),
            baseMeta.interiorCondition(),
            baseMeta.epcRating(),
            baseMeta.heatingType(),
            new ArrayList<>(mergedHighlights)
        );
    }
}
