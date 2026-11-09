package com.glasgow.propertyagent.service.scraping;

import com.glasgow.propertyagent.repository.IndexedPropertyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Thread-safe single-flight job state tracker with event-driven status push listeners (REF-01, REF-05).
 * Replaces fixed-rate @Scheduled SSE polling with zero-idle-overhead push notifications on state transitions.
 */
@Component
public class ScrapeJobStateTracker {

    private static final Logger log = LoggerFactory.getLogger(ScrapeJobStateTracker.class);
    private static final long PROGRESS_THROTTLE_MS = 250L;

    private final AtomicBoolean jobRunning = new AtomicBoolean(false);
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
    private volatile String jobType = "IDLE";
    private volatile String jobState = "IDLE";
    private volatile String targetCenter = "Glasgow Central";
    private volatile double targetRadiusMiles = 15.0;
    private volatile String currentCorridor = "";
    private volatile int corridorsCompleted = 0;
    private volatile int corridorsTotal = 0;
    private volatile int currentPage = 0;
    private volatile int currentCorridorMaxPages = 0;
    private final AtomicInteger totalPagesFetched = new AtomicInteger(0);
    private volatile String currentListingId = "";
    private volatile String currentListingAddress = "";
    private volatile String currentStep = "Idle — ready to fetch";
    private volatile int currentImageIndex = 0;
    private volatile int currentImageTotal = 0;
    private final AtomicInteger newlyIndexedLive = new AtomicInteger(0);
    private final AtomicInteger skippedExistingLive = new AtomicInteger(0);
    private final AtomicInteger priceChangesDetectedLive = new AtomicInteger(0);
    private final AtomicInteger failedItemsLive = new AtomicInteger(0);
    private final AtomicLong cachedTotalCount = new AtomicLong(0);
    private volatile String startedAtStr = "";
    private volatile String finishedAtStr = "";
    private volatile long jobStartEpochMs = 0L;
    private volatile long jobFinishEpochMs = 0L;
    private volatile String statusMessage = "Idle — no background fetch running.";

    // IMP-04: Last completed/started scrape envelope so UI can warn when live sliders exceed indexed scope
    private volatile String lastScrapedCenter = "";
    private volatile double lastScrapedRadiusMiles = 0.0;
    private volatile int lastScrapedMaxPrice = 0;
    private volatile int lastScrapedMinBedrooms = 0;
    private volatile List<String> lastScrapedPropertyTypes = List.of();

    private final CopyOnWriteArrayList<Consumer<RightmoveScraperService.ScrapeJobStatus>> listeners = new CopyOnWriteArrayList<>();
    private final AtomicLong lastBroadcastEpochMs = new AtomicLong(0L);

    public ScrapeJobStateTracker() {}

    @Autowired
    public ScrapeJobStateTracker(IndexedPropertyRepository repository) {
        if (repository != null) {
            try {
                this.cachedTotalCount.set(repository.count());
            } catch (Exception ignored) {}
        }
    }

    public Runnable subscribe(Consumer<RightmoveScraperService.ScrapeJobStatus> listener) {
        if (listener == null) return () -> {};
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    private void notifyListeners(boolean forceImmediate) {
        if (listeners.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (!forceImmediate) {
            long prev = lastBroadcastEpochMs.get();
            if (now - prev < PROGRESS_THROTTLE_MS) {
                return;
            }
            if (!lastBroadcastEpochMs.compareAndSet(prev, now)) {
                return;
            }
        } else {
            lastBroadcastEpochMs.set(now);
        }

        RightmoveScraperService.ScrapeJobStatus snapshot = getSnapshot();
        for (Consumer<RightmoveScraperService.ScrapeJobStatus> listener : listeners) {
            try {
                listener.accept(snapshot);
            } catch (Exception ex) {
                log.debug("SSE listener callback exception: {}", ex.getMessage());
            }
        }
    }

    public void forceBroadcast() {
        notifyListeners(true);
    }

    public boolean isJobRunning() {
        return jobRunning.get();
    }

    public boolean isCancelRequested() {
        return cancelRequested.get();
    }

    public boolean tryAcquireJobLock() {
        return jobRunning.compareAndSet(false, true);
    }

    public void releaseJobLock() {
        jobRunning.set(false);
    }

    public void cancelJob() {
        if (jobRunning.get()) {
            cancelRequested.set(true);
            this.statusMessage = "Cancellation requested by user... terminating background worker.";
            log.info("Cancellation requested for background job '{}'", jobType);
            notifyListeners(true);
        }
    }

    public RightmoveScraperService.ScrapeJobStatus buildRejectedSnapshot(String rejectMsg) {
        return buildSnapshot(false, rejectMsg);
    }

    public RightmoveScraperService.ScrapeJobStatus getSnapshot() {
        return buildSnapshot(true, statusMessage);
    }

    public RightmoveScraperService.ScrapeJobStatus buildSnapshot(boolean accepted, String customMessage) {
        boolean runningNow = jobRunning.get();
        long endMs = runningNow ? System.currentTimeMillis() : (jobFinishEpochMs > 0 ? jobFinishEpochMs : System.currentTimeMillis());
        long elapsedSec = jobStartEpochMs > 0 ? Math.max(0L, (endMs - jobStartEpochMs) / 1000L) : 0L;
        return new RightmoveScraperService.ScrapeJobStatus(
            runningNow,
            accepted,
            jobType,
            jobState,
            targetCenter,
            targetRadiusMiles,
            currentCorridor,
            corridorsCompleted,
            corridorsTotal,
            currentPage,
            currentCorridorMaxPages,
            totalPagesFetched.get(),
            currentListingId,
            currentListingAddress,
            currentStep,
            currentImageIndex,
            currentImageTotal,
            newlyIndexedLive.get(),
            skippedExistingLive.get(),
            priceChangesDetectedLive.get(),
            failedItemsLive.get(),
            cachedTotalCount.get(),
            startedAtStr,
            finishedAtStr,
            elapsedSec,
            customMessage,
            lastScrapedCenter,
            lastScrapedRadiusMiles,
            lastScrapedMaxPrice,
            lastScrapedMinBedrooms,
            lastScrapedPropertyTypes
        );
    }

    public String getJobType() {
        return jobType;
    }

    public String getTargetCenter() {
        return targetCenter;
    }

    public double getTargetRadiusMiles() {
        return targetRadiusMiles;
    }

    public int getCurrentPage() {
        return currentPage;
    }

    public int getCurrentCorridorMaxPages() {
        return currentCorridorMaxPages;
    }

    public int getTotalPagesFetched() {
        return totalPagesFetched.get();
    }

    public int getNewlyIndexedCount() {
        return newlyIndexedLive.get();
    }

    public int getSkippedExistingCount() {
        return skippedExistingLive.get();
    }

    public int getPriceChangesDetectedCount() {
        return priceChangesDetectedLive.get();
    }

    public RightmoveScraperService.ScrapeJobStatus beginScrapeJob(
            String resolvedCenter,
            double resolvedRadius,
            String resolvedProvider,
            String filterDesc) {
        this.cancelRequested.set(false);
        this.jobType = "SCRAPE_AND_INDEX";
        this.jobState = "RUNNING";
        this.targetCenter = resolvedCenter;
        this.targetRadiusMiles = resolvedRadius;
        this.currentCorridor = "Initializing...";
        this.corridorsCompleted = 0;
        this.corridorsTotal = 1;
        this.currentPage = 1;
        this.currentCorridorMaxPages = 1;
        this.totalPagesFetched.set(0);
        this.currentListingId = "";
        this.currentListingAddress = "";
        this.currentStep = "Starting multi-page portal fetch around " + resolvedCenter + " (" + resolvedRadius + " mi via " + resolvedProvider + filterDesc + ")...";
        this.currentImageIndex = 0;
        this.currentImageTotal = 0;
        this.newlyIndexedLive.set(0);
        this.skippedExistingLive.set(0);
        this.priceChangesDetectedLive.set(0);
        this.failedItemsLive.set(0);
        this.jobStartEpochMs = System.currentTimeMillis();
        this.jobFinishEpochMs = 0L;
        this.startedAtStr = Instant.now().toString();
        this.finishedAtStr = "";
        this.statusMessage = String.format(Locale.ROOT,
            "Background multi-page fetch & local indexing started for '%s' (%.1f mi radius via %s%s)...",
            resolvedCenter, resolvedRadius, resolvedProvider, filterDesc);
        notifyListeners(true);
        return getSnapshot();
    }

    public RightmoveScraperService.ScrapeJobStatus beginReanalyzeJob(int planned, boolean onlyFailed) {
        this.cancelRequested.set(false);
        this.jobType = "REANALYZE_LLM";
        this.jobState = "RUNNING";
        this.currentCorridor = onlyFailed ? "Local DB Re-Scan (Failed/Heuristic Only)" : "Local PostgreSQL Re-Scan";
        this.corridorsCompleted = 0;
        this.corridorsTotal = Math.max(1, planned);
        this.currentListingId = "";
        this.currentListingAddress = "";
        this.currentStep = "Starting local image download & Ollama Vision re-scan across " + planned + " DB properties" + (onlyFailed ? " (failed/heuristic only)" : "") + "...";
        this.currentImageIndex = 0;
        this.currentImageTotal = 0;
        this.newlyIndexedLive.set(0);
        this.skippedExistingLive.set(0);
        this.priceChangesDetectedLive.set(0);
        this.failedItemsLive.set(0);
        this.jobStartEpochMs = System.currentTimeMillis();
        this.jobFinishEpochMs = 0L;
        this.startedAtStr = Instant.now().toString();
        this.finishedAtStr = "";
        this.statusMessage = "Background LLM Vision re-scan started across " + planned + " local DB properties...";
        notifyListeners(true);
        return getSnapshot();
    }

    public void recordScrapeEnvelope(String centerLocation, double radiusMiles, List<String> propertyTypes, int maxPrice, int minBedrooms) {
        this.lastScrapedCenter = (centerLocation == null || centerLocation.isBlank()) ? "Glasgow Central" : centerLocation.trim();
        this.lastScrapedRadiusMiles = radiusMiles > 0 ? radiusMiles : 15.0;
        this.lastScrapedMaxPrice = maxPrice > 0 ? maxPrice : 5_000_000;
        this.lastScrapedMinBedrooms = minBedrooms > 0 ? minBedrooms : 1;
        this.lastScrapedPropertyTypes = (propertyTypes == null || propertyTypes.isEmpty())
            ? List.of("Detached", "Semi-Detached", "Terraced", "Bungalow", "Flat")
            : new ArrayList<>(PortalListingCandidate.buildAllowedTypeSet(propertyTypes));
        notifyListeners(false);
    }

    public void initCorridors(int total) {
        this.corridorsTotal = Math.max(1, total);
        this.corridorsCompleted = 0;
        notifyListeners(false);
    }

    public void resetCorridorPagination() {
        this.currentPage = 1;
        this.currentCorridorMaxPages = 0;
    }

    public void updateCorridorPageStart(String portalLabel, String corridorName, int corridorOneBased, int totalCorridors, int humanPage) {
        this.currentPage = humanPage;
        String pageBadge = this.currentCorridorMaxPages > 0
            ? humanPage + "/" + this.currentCorridorMaxPages
            : String.valueOf(humanPage);
        this.currentCorridor = corridorName + " (" + corridorOneBased + "/" + totalCorridors + " • page " + pageBadge + ")";
        this.currentListingId = "";
        this.currentListingAddress = "";
        this.currentImageIndex = 0;
        this.currentImageTotal = 0;
        this.currentStep = "Fetching " + portalLabel + " search results for '" + corridorName + "' — page " + pageBadge + "...";
        this.statusMessage = String.format(Locale.ROOT,
            "Scanning %s %s • +%d newly indexed%s • %d pages read",
            portalLabel, this.currentCorridor, newlyIndexedLive.get(),
            skippedExistingLive.get() > 0 ? " (" + skippedExistingLive.get() + " existing skipped)" : "",
            totalPagesFetched.get());
        notifyListeners(false);
    }

    public void incrementPagesFetched() {
        this.totalPagesFetched.incrementAndGet();
        notifyListeners(false);
    }

    public void updateEstimatedMaxPages(String corridorName, int corridorOneBased, int totalCorridors, int humanPage, int estPages) {
        this.currentCorridorMaxPages = estPages;
        this.currentCorridor = corridorName + " (" + corridorOneBased + "/" + totalCorridors + " • page " + humanPage + "/" + estPages + ")";
        notifyListeners(false);
    }

    public void markCorridorCompleted(int completedCount) {
        this.corridorsCompleted = completedCount;
        notifyListeners(false);
    }

    public void updateListingStart(String listingId, String address, int humanPage, String stepMsg) {
        this.currentListingId = listingId;
        this.currentListingAddress = address;
        this.currentImageIndex = 0;
        this.currentImageTotal = 0;
        this.currentStep = stepMsg;
        this.statusMessage = String.format(Locale.ROOT,
            "Indexing %s (%s) • page %d%s • +%d saved so far",
            listingId, address, humanPage,
            (this.currentCorridorMaxPages > 0 ? "/" + this.currentCorridorMaxPages : ""),
            newlyIndexedLive.get());
        notifyListeners(false);
    }

    public void updateMediaProgress(int currentImg, int totalImgs, String stepMsg) {
        this.currentImageIndex = currentImg;
        this.currentImageTotal = totalImgs;
        this.currentStep = stepMsg;
        notifyListeners(false);
    }

    public void onListingSaved(String listingId, String address) {
        this.cachedTotalCount.incrementAndGet();
        int liveAdded = this.newlyIndexedLive.incrementAndGet();
        this.currentStep = "Saved " + listingId + " (" + address + ") to local PostgreSQL";
        this.statusMessage = String.format(Locale.ROOT,
            "Indexed +%d new properties into local DB (latest: %s — %s)", liveAdded, listingId, address);
        notifyListeners(true);
    }

    public void updateReanalysisItemStart(int zeroBasedIdx, int limit, String listingId, String address) {
        this.currentCorridor = "DB Property " + (zeroBasedIdx + 1) + "/" + limit;
        this.currentListingId = listingId;
        this.currentListingAddress = address;
        this.currentImageIndex = 0;
        this.currentImageTotal = 0;
        this.currentStep = "Downloading/verifying media for " + listingId + " (" + (zeroBasedIdx + 1) + "/" + limit + ")...";
        notifyListeners(false);
    }

    public void onReanalysisItemSaved(int updatedCount, int completedCount, int limit, int totalImagesDownloaded) {
        this.newlyIndexedLive.set(updatedCount);
        this.corridorsCompleted = completedCount;
        this.statusMessage = String.format(Locale.ROOT,
            "Re-scanned %d/%d local DB properties (%d images verified)", updatedCount, limit, totalImagesDownloaded);
        notifyListeners(true);
    }

    public void incrementSkippedExisting() {
        this.skippedExistingLive.incrementAndGet();
        notifyListeners(false);
    }

    public void incrementPriceChangesDetected() {
        this.priceChangesDetectedLive.incrementAndGet();
        notifyListeners(false);
    }

    public void incrementFailedItems() {
        this.failedItemsLive.incrementAndGet();
        notifyListeners(false);
    }

    public void setStatusMessage(String message) {
        this.statusMessage = message;
        notifyListeners(true);
    }

    public void completeJob(String stepSummary, String finalStatusMessage) {
        this.jobState = "COMPLETED";
        this.currentListingId = "";
        this.currentListingAddress = "";
        this.currentImageIndex = 0;
        this.currentImageTotal = 0;
        this.currentStep = stepSummary;
        this.statusMessage = finalStatusMessage;
        this.jobFinishEpochMs = System.currentTimeMillis();
        this.finishedAtStr = Instant.now().toString();
        this.jobRunning.set(false);
        notifyListeners(true);
    }

    public void failJob(String errorPrefix, String errorMsg) {
        this.jobState = "FAILED";
        this.currentStep = "Failed: " + errorMsg;
        this.statusMessage = errorPrefix + errorMsg;
        this.jobFinishEpochMs = System.currentTimeMillis();
        this.finishedAtStr = Instant.now().toString();
        this.jobRunning.set(false);
        notifyListeners(true);
    }

    public void resetCachedCount(long count) {
        this.cachedTotalCount.set(count);
        notifyListeners(false);
    }

    public void resetStateOnDatabaseClear() {
        this.jobType = "IDLE";
        this.jobState = "IDLE";
        this.targetCenter = "Glasgow Central";
        this.currentCorridor = "";
        this.corridorsCompleted = 0;
        this.corridorsTotal = 0;
        this.currentPage = 0;
        this.currentCorridorMaxPages = 0;
        this.totalPagesFetched.set(0);
        this.currentListingId = "";
        this.currentListingAddress = "";
        this.currentStep = "Idle — database cleared";
        this.currentImageIndex = 0;
        this.currentImageTotal = 0;
        this.newlyIndexedLive.set(0);
        this.skippedExistingLive.set(0);
        this.priceChangesDetectedLive.set(0);
        this.failedItemsLive.set(0);
        this.startedAtStr = "";
        this.finishedAtStr = "";
        this.jobStartEpochMs = 0L;
        this.jobFinishEpochMs = 0L;
        this.statusMessage = "Database cleared — 0 properties indexed.";
        this.cachedTotalCount.set(0);
        this.lastScrapedCenter = "";
        this.lastScrapedRadiusMiles = 0.0;
        this.lastScrapedMaxPrice = 0;
        this.lastScrapedMinBedrooms = 0;
        this.lastScrapedPropertyTypes = List.of();
        notifyListeners(true);
    }
}
