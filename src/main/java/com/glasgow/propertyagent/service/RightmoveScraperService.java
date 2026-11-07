package com.glasgow.propertyagent.service;

import com.glasgow.propertyagent.model.ExtractedPropertyMetadata;
import com.glasgow.propertyagent.repository.IndexedPropertyRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Orchestrates multi-portal radius scraping and local LLM/Vision re-analysis across Greater Glasgow
 * & South Lanarkshire (REF-01, REF-05, SIMP-03).
 *
 * Decomposed into focused collaborators:
 * - {@link ScrapeJobStateTracker}: thread-safe single-flight job state + event-driven SSE notifications
 * - {@link RightmovePortalClient}: Rightmove typeahead, corridor query builder, Next.js card & detail parser
 * - {@link OnTheMarketPortalClient}: OnTheMarket corridor URL builder, Next.js card & detail parser
 * - {@link PropertyIngestionPipeline}: unified portal-agnostic ingestion, lookahead prefetching & persistence
 */
@Service
public class RightmoveScraperService {

    private static final Logger log = LoggerFactory.getLogger(RightmoveScraperService.class);
    private static final int MAX_PAGES_PER_CORRIDOR = 40;

    public record ScrapePassResult(int newlyIndexedCount, long totalIndexedCount, String statusMessage) {}

    public record ScrapeJobStatus(
        boolean running,
        boolean accepted,
        String jobType,
        String state,
        String targetCenter,
        double targetRadiusMiles,
        String currentCorridor,
        int corridorsCompleted,
        int corridorsTotal,
        int currentPage,
        int currentCorridorMaxPages,
        int totalPagesFetched,
        String currentListingId,
        String currentListingAddress,
        String currentStep,
        int currentImageIndex,
        int currentImageTotal,
        int newlyIndexedCount,
        int skippedExistingCount,
        int priceChangesDetectedCount,
        int failedItemCount,
        long totalIndexedCount,
        String startedAt,
        String finishedAt,
        long elapsedSeconds,
        String statusMessage,
        String lastScrapedCenter,
        double lastScrapedRadiusMiles,
        int lastScrapedMaxPrice,
        int lastScrapedMinBedrooms,
        List<String> lastScrapedPropertyTypes
    ) {}

    private final IndexedPropertyRepository repository;
    private final ScrapeJobStateTracker stateTracker;
    private final RightmovePortalClient rightmoveClient;
    private final OnTheMarketPortalClient otmClient;
    private final PropertyIngestionPipeline ingestionPipeline;
    private final PropertyMediaService mediaService;

    @Autowired
    public RightmoveScraperService(
            IndexedPropertyRepository repository,
            ScrapeJobStateTracker stateTracker,
            RightmovePortalClient rightmoveClient,
            OnTheMarketPortalClient otmClient,
            PropertyIngestionPipeline ingestionPipeline,
            PropertyMediaService mediaService) {
        this.repository = repository;
        this.stateTracker = stateTracker;
        this.rightmoveClient = rightmoveClient;
        this.otmClient = otmClient;
        this.ingestionPipeline = ingestionPipeline;
        this.mediaService = mediaService;
    }

    /**
     * Convenience constructor preserved for standalone unit tests without a Spring ApplicationContext.
     */
    public RightmoveScraperService(
            IndexedPropertyRepository repository,
            StationCommuteService stationService,
            OllamaAgentService ollamaAgent,
            PropertyMediaService mediaService,
            PropertyEmbeddingService embeddingService,
            HeadlessChromeService headlessChromeService,
            PropertyMetadataCache metadataCache,
            ObjectMapper objectMapper,
            String userAgent) {
        this.repository = repository;
        this.stateTracker = new ScrapeJobStateTracker(repository);
        this.rightmoveClient = new RightmovePortalClient(headlessChromeService, stationService, objectMapper, userAgent);
        this.otmClient = new OnTheMarketPortalClient(objectMapper, userAgent);
        this.ingestionPipeline = new PropertyIngestionPipeline(
            repository, stationService, ollamaAgent, mediaService, embeddingService,
            metadataCache, this.rightmoveClient, this.otmClient, this.stateTracker, objectMapper
        );
        this.mediaService = mediaService;
    }

    @PostConstruct
    public void seedInitialGlasgowCorridorListings() {
        List<String> fakeSeedIds = List.of(
            "RM-158901234", "RM-158902345", "RM-158903456",
            "RM-158904567", "RM-158905678", "RM-158906789"
        );
        repository.deleteAllById(fakeSeedIds);

        long existing = repository.count();
        stateTracker.resetCachedCount(existing);
        if (existing > 0) {
            log.info("PostgreSQL contains {} verified live properties.", existing);
        } else {
            log.info("PostgreSQL database is currently empty (0 properties). Click 'Fetch All Listings in Radius' in the UI to scrape and download all images.");
        }
    }

    public Runnable subscribeStatusUpdates(Consumer<ScrapeJobStatus> listener) {
        return stateTracker.subscribe(listener);
    }

    public boolean isJobRunning() {
        return stateTracker.isJobRunning();
    }

    public void cancelJob() {
        stateTracker.cancelJob();
    }

    public boolean tryAcquireMaintenanceLock() {
        return stateTracker.tryAcquireJobLock();
    }

    public void releaseMaintenanceLock() {
        stateTracker.releaseJobLock();
    }

    public void resetStateOnDatabaseClear() {
        stateTracker.resetStateOnDatabaseClear();
    }

    public void resetCachedCount(long count) {
        stateTracker.resetCachedCount(count);
    }

    public ScrapeJobStatus getScrapeJobStatus() {
        return stateTracker.getSnapshot();
    }

    public void clearAllDownloadedMedia() {
        if (mediaService != null) {
            mediaService.deleteAllDownloadedMedia();
        }
    }

    public void syncExistingListingDelta(
            String listingId,
            int currentPriceGbp,
            String currentQualifier,
            Map<String, IndexedPropertyRepository.ListingPriceSnapshot> existingSnapshots) {
        ingestionPipeline.syncExistingListingDelta(listingId, currentPriceGbp, currentQualifier, existingSnapshots);
    }

    public static ExtractedPropertyMetadata assembleMetadata(
            ExtractedPropertyMetadata baseMeta,
            List<String> extraFeatures,
            int knownBathrooms) {
        return PropertyIngestionPipeline.assembleMetadata(baseMeta, extraFeatures, knownBathrooms);
    }

    public ScrapeJobStatus startBackgroundScrape(String centerLocation, double radiusMiles) {
        return startBackgroundScrape(centerLocation, radiusMiles, "rightmove", null, 0, 0);
    }

    public ScrapeJobStatus startBackgroundScrape(String centerLocation, double radiusMiles, String provider) {
        return startBackgroundScrape(centerLocation, radiusMiles, provider, null, 0, 0);
    }

    public ScrapeJobStatus startBackgroundScrape(
            String centerLocation,
            double radiusMiles,
            String provider,
            List<String> propertyTypes,
            int maxPrice,
            int minBedrooms) {
        String resolvedCenter = (centerLocation == null || centerLocation.isBlank()) ? "Glasgow Central" : centerLocation.trim();
        double resolvedRadius = radiusMiles <= 0 ? 15.0 : radiusMiles;
        String resolvedProvider = (provider == null || provider.isBlank()) ? "rightmove" : provider.trim().toLowerCase(Locale.ROOT);

        if (!stateTracker.tryAcquireJobLock()) {
            String rejectMsg = String.format(Locale.ROOT,
                "Background job (%s for '%s', %.1f mi) is already running — parallel fetches and queueing are disabled. Please wait for it to finish.",
                stateTracker.getJobType(), stateTracker.getTargetCenter(), stateTracker.getTargetRadiusMiles());
            log.warn("Rejected parallel scrape request (requested center='{}', radius={} mi): {}", resolvedCenter, resolvedRadius, rejectMsg);
            return stateTracker.buildRejectedSnapshot(rejectMsg);
        }

        StringBuilder filterDesc = new StringBuilder();
        if (propertyTypes != null && !propertyTypes.isEmpty() && propertyTypes.size() < 5) {
            filterDesc.append(" • types: ").append(String.join(", ", propertyTypes));
        }
        if (maxPrice > 0 && maxPrice < 5_000_000) {
            filterDesc.append(String.format(Locale.ROOT, " • max: £%,d", maxPrice));
        }
        if (minBedrooms > 1) {
            filterDesc.append(" • min beds: ").append(minBedrooms);
        }

        ScrapeJobStatus initialSnapshot = stateTracker.beginScrapeJob(resolvedCenter, resolvedRadius, resolvedProvider, filterDesc.toString());
        final List<String> fPropertyTypes = propertyTypes != null ? List.copyOf(propertyTypes) : null;

        Thread.ofVirtual().name("bg-property-scraper").start(() -> {
            try {
                ScrapePassResult result = scrapeAndIndexProperties(
                    resolvedCenter, resolvedRadius, resolvedProvider, fPropertyTypes, maxPrice, minBedrooms);
                stateTracker.completeJob("Completed background fetch & local indexing", result.statusMessage());
            } catch (Exception e) {
                log.error("Background scrape & indexing job failed: {}", e.getMessage(), e);
                stateTracker.failJob("Background fetch encountered an error: ", e.getMessage());
            }
        });

        return initialSnapshot;
    }

    public ScrapeJobStatus startBackgroundReanalyzeLlm(int maxCount) {
        return startBackgroundReanalyzeLlm(maxCount, false);
    }

    public ScrapeJobStatus startBackgroundReanalyzeLlm(int maxCount, boolean onlyFailed) {
        if (!stateTracker.tryAcquireJobLock()) {
            String rejectMsg = String.format(Locale.ROOT,
                "Background job (%s for '%s', %.1f mi) is already running — parallel jobs and queueing are disabled. Please wait for it to finish.",
                stateTracker.getJobType(), stateTracker.getTargetCenter(), stateTracker.getTargetRadiusMiles());
            log.warn("Rejected parallel LLM re-scan request: {}", rejectMsg);
            return stateTracker.buildRejectedSnapshot(rejectMsg);
        }

        long dbTotal = repository.count();
        int planned = maxCount <= 0 ? (int) dbTotal : Math.min((int) dbTotal, maxCount);
        ScrapeJobStatus initialSnapshot = stateTracker.beginReanalyzeJob(planned, onlyFailed);

        Thread.ofVirtual().name("bg-property-reanalyzer").start(() -> {
            try {
                Map<String, Object> res = reanalyzeIndexedPropertiesWithLlm(maxCount, onlyFailed);
                String summaryMsg = String.format(Locale.ROOT,
                    "Completed LLM Vision re-scan: updated %s properties (%s local images verified/analyzed).",
                    res.get("reanalyzedCount"), res.get("totalImagesDownloaded"));
                stateTracker.completeJob("Completed local LLM Vision re-scan", summaryMsg);
            } catch (Exception e) {
                log.error("Background LLM re-scan failed: {}", e.getMessage(), e);
                stateTracker.failJob("Background LLM re-scan encountered an error: ", e.getMessage());
            }
        });

        return initialSnapshot;
    }

    public ScrapePassResult scrapeAndIndexRightmove(
            String centerLocation,
            double radiusMiles,
            List<String> propertyTypes,
            int maxPrice,
            int minBedrooms) {
        return scrapeAndIndexProperties(centerLocation, radiusMiles, "rightmove", propertyTypes, maxPrice, minBedrooms);
    }

    public ScrapePassResult scrapeAndIndexProperties(String centerLocation, double radiusMiles, String provider) {
        return scrapeAndIndexProperties(centerLocation, radiusMiles, provider, null, 0, 0);
    }

    public ScrapePassResult scrapeAndIndexProperties(
            String centerLocation,
            double radiusMiles,
            String provider,
            List<String> propertyTypes,
            int maxPrice,
            int minBedrooms) {
        int newlyIndexed = 0;
        log.info("Starting radius scrape: provider='{}', center='{}', radius={} mi, types={}, maxPrice={}, minBedrooms={}",
            provider, centerLocation, radiusMiles, propertyTypes, maxPrice, minBedrooms);

        stateTracker.recordScrapeEnvelope(centerLocation, radiusMiles, propertyTypes, maxPrice, minBedrooms);

        Set<String> seenIdsInJob = ConcurrentHashMap.newKeySet();
        Set<String> allowedTypes = PortalListingCandidate.buildAllowedTypeSet(propertyTypes);
        Map<String, IndexedPropertyRepository.ListingPriceSnapshot> existingSnapshots = ingestionPipeline.loadPriceSnapshotMap();

        if ("rightmove".equalsIgnoreCase(provider) || "all".equalsIgnoreCase(provider)) {
            newlyIndexed += scrapeRightmoveCorridors(
                centerLocation, radiusMiles, seenIdsInJob, existingSnapshots, propertyTypes, allowedTypes, maxPrice, minBedrooms
            );
        }
        if ("onthemarket".equalsIgnoreCase(provider) || "all".equalsIgnoreCase(provider)) {
            if (!stateTracker.isCancelRequested()) {
                newlyIndexed += scrapeOnTheMarketCorridors(
                    centerLocation, radiusMiles, seenIdsInJob, existingSnapshots, allowedTypes, maxPrice, minBedrooms
                );
            }
        }

        long totalCount = repository.count();
        stateTracker.resetCachedCount(totalCount);
        int skipped = stateTracker.getSkippedExistingCount();
        int priceChanges = stateTracker.getPriceChangesDetectedCount();
        String skippedInfo = skipped > 0
            ? String.format(Locale.ROOT, " (%d existing checked%s) •",
                skipped, priceChanges > 0 ? ", " + priceChanges + " price changes synced" : "")
            : " •";
        String status = String.format(Locale.ROOT,
            "Indexed %d new live properties across %d portal pages within %.1f mi of %s%s %d total in local PostgreSQL",
            newlyIndexed, stateTracker.getTotalPagesFetched(), radiusMiles,
            (centerLocation == null || centerLocation.isBlank() ? "Glasgow" : centerLocation),
            skippedInfo, totalCount
        );
        log.info(status);
        return new ScrapePassResult(newlyIndexed, totalCount, status);
    }

    private int scrapeRightmoveCorridors(
            String centerLocation,
            double radiusMiles,
            Set<String> seenIdsInJob,
            Map<String, IndexedPropertyRepository.ListingPriceSnapshot> existingSnapshots,
            List<String> rawPropertyTypes,
            Set<String> allowedTypes,
            int maxPrice,
            int minBedrooms) {
        if (!rightmoveClient.isAvailable()) {
            log.error("Cannot scrape Rightmove: Headless Chrome is not available on this system.");
            stateTracker.setStatusMessage("Cannot scrape Rightmove: Google Chrome not found on system.");
            return 0;
        }

        int added = 0;
        String queryFilters = rightmoveClient.buildRightmoveQueryParams(rawPropertyTypes, maxPrice, minBedrooms);
        List<RightmovePortalClient.RightmoveCorridor> corridors = rightmoveClient.resolveCorridors(centerLocation, radiusMiles);
        stateTracker.initCorridors(corridors.size());

        for (int idx = 0; idx < corridors.size(); idx++) {
            if (stateTracker.isCancelRequested()) {
                log.info("Cancellation requested before starting Rightmove corridor {}", idx + 1);
                break;
            }

            RightmovePortalClient.RightmoveCorridor corridor = corridors.get(idx);
            Set<String> seenIdsInCorridor = new HashSet<>();
            stateTracker.resetCorridorPagination();
            int corridorAdded = 0;

            for (int pageIdx = 0; pageIdx < MAX_PAGES_PER_CORRIDOR; pageIdx++) {
                if (stateTracker.isCancelRequested()) {
                    log.info("Cancellation requested in Rightmove corridor '{}' page {}", corridor.displayName(), pageIdx + 1);
                    break;
                }

                int humanPage = pageIdx + 1;
                stateTracker.updateCorridorPageStart("Rightmove", corridor.displayName(), idx + 1, corridors.size(), humanPage);
                String url = rightmoveClient.buildSearchUrl(corridor, pageIdx, queryFilters);

                try {
                    log.info("Fetching Rightmove live listings via Headless Chrome (corridor '{}' page {}): {}",
                        corridor.displayName(), humanPage, url);
                    String nextDataJson = rightmoveClient.fetchSearchPageNextData(url, corridor.displayName());
                    if (nextDataJson == null) {
                        break;
                    }
                    stateTracker.incrementPagesFetched();

                    PortalListingCandidate.PageCards pageCards = rightmoveClient.parseSearchCards(nextDataJson);
                    PropertyIngestionPipeline.PageParseOutcome outcome = ingestionPipeline.ingestPageCandidates(
                        pageCards, rightmoveClient::fetchDetail, centerLocation, radiusMiles,
                        seenIdsInCorridor, seenIdsInJob, existingSnapshots, allowedTypes,
                        corridor.displayName(), idx + 1, corridors.size(), humanPage, MAX_PAGES_PER_CORRIDOR
                    );
                    added += outcome.newlyIndexed();
                    corridorAdded += outcome.newlyIndexed();
                    log.info("Rightmove corridor '{}' page {}/{}: indexed {} new listings ({} items on page, {} unseen in corridor, {} total pages fetched)",
                        corridor.displayName(), humanPage,
                        (stateTracker.getCurrentCorridorMaxPages() > 0 ? stateTracker.getCurrentCorridorMaxPages() : "?"),
                        outcome.newlyIndexed(), outcome.itemsOnPage(), outcome.unseenInCorridorOnPage(), stateTracker.getTotalPagesFetched());

                    if (outcome.reachedEnd()) {
                        break;
                    }
                    Thread.sleep(500);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    stateTracker.incrementFailedItems();
                    log.warn("Error scraping Rightmove corridor '{}' page {}: {}", corridor.displayName(), humanPage, e.getMessage());
                    break;
                }
            }

            log.info("Completed Rightmove corridor '{}' ({}/{}): indexed {} new listings across {} pages",
                corridor.displayName(), idx + 1, corridors.size(), corridorAdded, stateTracker.getCurrentPage());
            stateTracker.markCorridorCompleted(idx + 1);
        }
        return added;
    }

    private int scrapeOnTheMarketCorridors(
            String centerLocation,
            double radiusMiles,
            Set<String> seenIdsInJob,
            Map<String, IndexedPropertyRepository.ListingPriceSnapshot> existingSnapshots,
            Set<String> allowedTypes,
            int maxPrice,
            int minBedrooms) {
        int added = 0;
        List<String> locations = otmClient.resolveCorridorSlugs(centerLocation);
        stateTracker.initCorridors(locations.size());

        for (int idx = 0; idx < locations.size(); idx++) {
            if (stateTracker.isCancelRequested()) {
                break;
            }
            String loc = locations.get(idx);
            Set<String> seenIdsInCorridor = new HashSet<>();
            stateTracker.resetCorridorPagination();
            int corridorAdded = 0;

            for (int pageIdx = 0; pageIdx < MAX_PAGES_PER_CORRIDOR; pageIdx++) {
                if (stateTracker.isCancelRequested()) {
                    break;
                }
                int humanPage = pageIdx + 1;
                stateTracker.updateCorridorPageStart("OnTheMarket", loc, idx + 1, locations.size(), humanPage);
                String url = otmClient.buildSearchUrl(loc, radiusMiles, allowedTypes, maxPrice, minBedrooms, pageIdx);

                try {
                    log.info("Fetching OnTheMarket live listings (corridor '{}' page {}): {}", loc, humanPage, url);
                    String nextDataJson = otmClient.fetchSearchPageNextData(url, loc);
                    if (nextDataJson == null) {
                        break;
                    }
                    stateTracker.incrementPagesFetched();

                    PortalListingCandidate.PageCards pageCards = otmClient.parseSearchCards(nextDataJson);
                    PropertyIngestionPipeline.PageParseOutcome outcome = ingestionPipeline.ingestPageCandidates(
                        pageCards, otmClient::fetchDetail, centerLocation, radiusMiles,
                        seenIdsInCorridor, seenIdsInJob, existingSnapshots, allowedTypes,
                        loc, idx + 1, locations.size(), humanPage, MAX_PAGES_PER_CORRIDOR
                    );
                    added += outcome.newlyIndexed();
                    corridorAdded += outcome.newlyIndexed();
                    log.info("OnTheMarket corridor '{}' page {}/{}: indexed {} new listings ({} items on page, {} unseen in corridor, {} total pages fetched)",
                        loc, humanPage,
                        (stateTracker.getCurrentCorridorMaxPages() > 0 ? stateTracker.getCurrentCorridorMaxPages() : "?"),
                        outcome.newlyIndexed(), outcome.itemsOnPage(), outcome.unseenInCorridorOnPage(), stateTracker.getTotalPagesFetched());

                    if (outcome.reachedEnd()) {
                        break;
                    }
                    Thread.sleep(350);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    stateTracker.incrementFailedItems();
                    log.warn("Error scraping OnTheMarket corridor '{}' page {}: {}", loc, humanPage, e.getMessage());
                    break;
                }
            }

            log.info("Completed OnTheMarket corridor '{}' ({}/{}): indexed {} new listings across {} pages",
                loc, idx + 1, locations.size(), corridorAdded, stateTracker.getCurrentPage());
            stateTracker.markCorridorCompleted(idx + 1);
        }
        return added;
    }

    public Map<String, Object> reanalyzeIndexedPropertiesWithLlm(int maxCount) {
        return ingestionPipeline.reanalyzeIndexedPropertiesWithLlm(maxCount, false);
    }

    public Map<String, Object> reanalyzeIndexedPropertiesWithLlm(int maxCount, boolean onlyFailed) {
        return ingestionPipeline.reanalyzeIndexedPropertiesWithLlm(maxCount, onlyFailed);
    }
}
