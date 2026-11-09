package com.glasgow.propertyagent;

import com.glasgow.propertyagent.service.PropertyDailyFetchScheduler;
import com.glasgow.propertyagent.service.RightmoveScraperService;
import com.glasgow.propertyagent.service.ScrapeJobStateTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PropertyDailyFetchSchedulerTest {

    private FakeScraperService fakeScraper;
    private PropertyDailyFetchScheduler scheduler;

    static class FakeScraperService extends RightmoveScraperService {
        String lastCenter;
        double lastRadius;
        String lastProvider;
        List<String> lastPropertyTypes;
        int lastMaxPrice;
        int lastMinBedrooms;
        int callCount = 0;
        boolean accepted = true;

        FakeScraperService() {
            super(null, new ScrapeJobStateTracker(), null, null, null, null);
        }

        @Override
        public ScrapeJobStatus startBackgroundScrape(
                String centerLocation,
                double radiusMiles,
                String provider,
                List<String> propertyTypes,
                int maxPrice,
                int minBedrooms) {
            this.callCount++;
            this.lastCenter = centerLocation;
            this.lastRadius = radiusMiles;
            this.lastProvider = provider;
            this.lastPropertyTypes = propertyTypes;
            this.lastMaxPrice = maxPrice;
            this.lastMinBedrooms = minBedrooms;
            return new ScrapeJobStatus(
                accepted, accepted, "SCRAPE", "RUNNING", centerLocation, radiusMiles,
                "", 0, 0, 0, 0, 0, "", "", "", 0, 0, 0, 0, 0, 0, 100L,
                "", "", 0, accepted ? "Job started" : "Background job is already running",
                centerLocation, radiusMiles, 0, 0, List.of()
            );
        }
    }

    @BeforeEach
    void setUp() {
        fakeScraper = new FakeScraperService();
        scheduler = new PropertyDailyFetchScheduler(fakeScraper);
    }

    public static void main(String[] args) {
        PropertyDailyFetchSchedulerTest suite = new PropertyDailyFetchSchedulerTest();
        suite.setUp();
        suite.testScheduledDailyFetchSuccess();
        suite.setUp();
        suite.testScheduledDailyFetchDisabled();
        suite.setUp();
        suite.testManualTrigger();
        suite.setUp();
        suite.testScheduledDailyFetchRejectionWhenBusy();
        suite.setUp();
        suite.testScheduleInfoRecord();
        System.out.println("ALL PropertyDailyFetchSchedulerTest checks PASSED successfully!");
    }

    @Test
    void testScheduledDailyFetchSuccess() {
        scheduler.runDailyPropertyFetch();

        assertEquals(1, fakeScraper.callCount);
        assertEquals("Glasgow Central", fakeScraper.lastCenter);
        assertEquals(15.0, fakeScraper.lastRadius);
        assertEquals("all", fakeScraper.lastProvider);
        assertEquals(List.of("Detached", "Semi-Detached", "Bungalow"), fakeScraper.lastPropertyTypes);
        assertEquals(0, fakeScraper.lastMaxPrice);
        assertEquals(0, fakeScraper.lastMinBedrooms);
        assertNotNull(scheduler.getLastRunTime());
        assertTrue(scheduler.getLastStatus().contains("Job started (SCHEDULED)"));
    }

    @Test
    void testScheduledDailyFetchDisabled() {
        scheduler.setEnabled(false);

        scheduler.runDailyPropertyFetch();

        assertEquals(0, fakeScraper.callCount);
        assertNull(scheduler.getLastRunTime());
        assertEquals("Never executed", scheduler.getLastStatus());
    }

    @Test
    void testManualTrigger() {
        RightmoveScraperService.ScrapeJobStatus status = scheduler.triggerManualFetch();

        assertNotNull(status);
        assertTrue(status.accepted());
        assertEquals(1, fakeScraper.callCount);
        assertEquals("Glasgow Central", fakeScraper.lastCenter);
        assertEquals(15.0, fakeScraper.lastRadius);
        assertEquals("all", fakeScraper.lastProvider);
        assertEquals(List.of("Detached", "Semi-Detached", "Bungalow"), fakeScraper.lastPropertyTypes);
        assertNotNull(scheduler.getLastRunTime());
        assertTrue(scheduler.getLastStatus().contains("Job started (MANUAL_TRIGGER)"));
    }

    @Test
    void testScheduledDailyFetchRejectionWhenBusy() {
        fakeScraper.accepted = false;

        scheduler.runDailyPropertyFetch();

        assertEquals(1, fakeScraper.callCount);
        assertNotNull(scheduler.getLastRunTime());
        assertTrue(scheduler.getLastStatus().contains("Job skipped/rejected (SCHEDULED)"));
    }

    @Test
    void testScheduleInfoRecord() {
        PropertyDailyFetchScheduler.ScheduleInfo info = scheduler.getScheduleInfo();
        assertTrue(info.enabled());
        assertEquals("0 0 4 * * *", info.cron());
        assertEquals("Glasgow Central", info.centerLocation());
        assertEquals(15.0, info.radiusMiles());
        assertEquals("all", info.portalProvider());
        assertEquals(0, info.maxPrice());
        assertEquals(0, info.minBedrooms());
        assertEquals("Never executed", info.lastStatus());
    }
}
