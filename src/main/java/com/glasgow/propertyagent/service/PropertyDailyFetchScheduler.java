package com.glasgow.propertyagent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Scheduled service to fetch new property listings across portals (Rightmove & OnTheMarket) daily.
 * Runs on a configurable cron schedule (default: 04:00 AM daily) and indexes new properties into PostgreSQL.
 */
@Service
public class PropertyDailyFetchScheduler {

    private static final Logger log = LoggerFactory.getLogger(PropertyDailyFetchScheduler.class);

    private final RightmoveScraperService scraperService;

    @Value("${property-agent.schedule.enabled:true}")
    private boolean enabled = true;

    @Value("${property-agent.schedule.cron:0 0 4 * * *}")
    private String cronExpression = "0 0 4 * * *";

    @Value("${property-agent.schedule.center-location:Glasgow Central}")
    private String centerLocation = "Glasgow Central";

    @Value("${property-agent.schedule.radius-miles:15.0}")
    private double radiusMiles = 15.0;

    @Value("${property-agent.schedule.portal-provider:all}")
    private String portalProvider = "all";

    @Value("${property-agent.schedule.max-price:0}")
    private int maxPrice = 0;

    @Value("${property-agent.schedule.min-bedrooms:0}")
    private int minBedrooms = 0;

    @Value("${property-agent.schedule.property-types:}")
    private List<String> propertyTypes;

    private volatile Instant lastRunTime = null;
    private volatile String lastStatus = "Never executed";

    public PropertyDailyFetchScheduler(RightmoveScraperService scraperService) {
        this.scraperService = scraperService;
    }

    /**
     * Scheduled daily job to fetch newly listed properties across portals (Rightmove & OnTheMarket).
     * Runs daily at the configured cron schedule (default 4:00 AM).
     */
    @Scheduled(cron = "${property-agent.schedule.cron:0 0 4 * * *}")
    public void runDailyPropertyFetch() {
        if (!enabled) {
            log.info("Daily property fetch scheduler is disabled (property-agent.schedule.enabled=false). Skipping.");
            return;
        }

        executeFetch("SCHEDULED");
    }

    /**
     * Allows manual or programmatic triggering of the daily fetch job.
     */
    public RightmoveScraperService.ScrapeJobStatus triggerManualFetch() {
        log.info("Manual trigger requested for daily property fetch job.");
        return executeFetch("MANUAL_TRIGGER");
    }

    private RightmoveScraperService.ScrapeJobStatus executeFetch(String triggerSource) {
        this.lastRunTime = Instant.now();
        log.info("[{}] Initiating daily property fetch: center='{}', radius={} mi, provider='{}', maxPrice={}, minBeds={}",
                triggerSource, centerLocation, radiusMiles, portalProvider, maxPrice, minBedrooms);

        List<String> types = (propertyTypes != null && !propertyTypes.isEmpty()) ? propertyTypes : null;
        RightmoveScraperService.ScrapeJobStatus snapshot = scraperService.startBackgroundScrape(
                centerLocation,
                radiusMiles,
                portalProvider,
                types,
                maxPrice,
                minBedrooms
        );

        if (snapshot.accepted()) {
            this.lastStatus = String.format("Job started (%s) at %s", triggerSource, lastRunTime);
            log.info("[{}] Daily property fetch successfully launched in background thread. Initial snapshot: {}",
                    triggerSource, snapshot.statusMessage());
        } else {
            this.lastStatus = String.format("Job skipped/rejected (%s) at %s: %s",
                    triggerSource, lastRunTime, snapshot.statusMessage());
            log.warn("[{}] Daily property fetch could not start: {}", triggerSource, snapshot.statusMessage());
        }

        return snapshot;
    }

    // Setters for testability / runtime re-configuration
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public void setCronExpression(String cronExpression) { this.cronExpression = cronExpression; }
    public void setCenterLocation(String centerLocation) { this.centerLocation = centerLocation; }
    public void setRadiusMiles(double radiusMiles) { this.radiusMiles = radiusMiles; }
    public void setPortalProvider(String portalProvider) { this.portalProvider = portalProvider; }
    public void setMaxPrice(int maxPrice) { this.maxPrice = maxPrice; }
    public void setMinBedrooms(int minBedrooms) { this.minBedrooms = minBedrooms; }
    public void setPropertyTypes(List<String> propertyTypes) { this.propertyTypes = propertyTypes; }

    public boolean isEnabled() { return enabled; }
    public String getCronExpression() { return cronExpression; }
    public String getCenterLocation() { return centerLocation; }
    public double getRadiusMiles() { return radiusMiles; }
    public String getPortalProvider() { return portalProvider; }
    public int getMaxPrice() { return maxPrice; }
    public int getMinBedrooms() { return minBedrooms; }
    public List<String> getPropertyTypes() { return propertyTypes; }
    public Instant getLastRunTime() { return lastRunTime; }
    public String getLastStatus() { return lastStatus; }

    public record ScheduleInfo(
            boolean enabled,
            String cron,
            String centerLocation,
            double radiusMiles,
            String portalProvider,
            int maxPrice,
            int minBedrooms,
            List<String> propertyTypes,
            Instant lastRunTime,
            String lastStatus
    ) {}

    public ScheduleInfo getScheduleInfo() {
        return new ScheduleInfo(
                enabled,
                cronExpression,
                centerLocation,
                radiusMiles,
                portalProvider,
                maxPrice,
                minBedrooms,
                propertyTypes,
                lastRunTime,
                lastStatus
        );
    }
}
