package com.glasgow.propertyagent;

import com.glasgow.propertyagent.service.app.StationCommuteService;
import com.glasgow.propertyagent.service.scraping.ScrapeJobStateTracker;
import com.glasgow.propertyagent.service.scraping.PortalListingCandidate;
import com.glasgow.propertyagent.service.scraping.RightmoveScraperService;
import com.glasgow.propertyagent.service.app.PropertyMetadataCache;
import com.glasgow.propertyagent.service.app.OsmOverpassTransitClient;
import com.glasgow.propertyagent.service.scraping.RightmovePortalClient;
import com.glasgow.propertyagent.service.scraping.OnTheMarketPortalClient;
import com.glasgow.propertyagent.service.ai.VisionImagePreprocessor;

import com.glasgow.propertyagent.controller.PropertyAgentController;
import com.glasgow.propertyagent.entity.IndexedPropertyEntity;
import com.glasgow.propertyagent.model.ExtractedPropertyMetadata;
import com.glasgow.propertyagent.model.SearchFilterRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class Phase25RefactoringAndUpgradesTest {

    // Valid minimal 1x1 lossless VP8L RIFF/WEBP image (Rightmove primary image format)
    private static final String MINIMAL_WEBP_BASE64 =
        "UklGRhoAAABXRUJQVlA4TA0AAAAvAAAAEAcQERGIiP4HAA==";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final StationCommuteService stationService =
        new StationCommuteService(new OsmOverpassTransitClient(objectMapper));

    public static void main(String[] args) throws Exception {
        Phase25RefactoringAndUpgradesTest suite = new Phase25RefactoringAndUpgradesTest();
        suite.upg01_twelveMonkeysWebpSpiDecodesWebpAndVisionPreprocessorConvertsToJpeg();
        suite.ref03_externalizedGlasgowTransitNetworkJsonLoadsCompleteCatalog();
        suite.ref01_and_ref05_portalClientsNormalizeCardsAndStateTrackerPushesEvents();
        suite.ref04_declarativeCandidateRowMapperMapsPostgreSqlColumnsAndInstants();
        suite.simp02_and_simp04_jakartaValidationAndCaffeineCachesWork();
        System.out.println("ALL PHASE 2.5 REFACTORING & UPGRADE TESTS PASSED!");
    }

    @Test
    void upg01_twelveMonkeysWebpSpiDecodesWebpAndVisionPreprocessorConvertsToJpeg() throws Exception {
        Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("webp");
        assertTrue(readers.hasNext(), "TwelveMonkeys WebP ImageReader SPI must be registered on classpath");

        byte[] webpBytes = Base64.getDecoder().decode(MINIMAL_WEBP_BASE64);
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(webpBytes));
        assertNotNull(decoded, "ImageIO.read must decode RIFF/WEBP payloads natively");
        assertEquals(1, decoded.getWidth());
        assertEquals(1, decoded.getHeight());

        Path tempDir = Files.createTempDirectory("phase25-webp-test");
        Path webpFile = tempDir.resolve("photo-01.webp");
        Path fpWebpFile = tempDir.resolve("floorplan-01.webp");
        try {
            Files.write(webpFile, webpBytes);
            Files.write(fpWebpFile, webpBytes);

            // Photo preprocessor must convert even tiny .webp files to standard JPEG (FF D8) for Ollama Vision
            byte[] jpegOut = VisionImagePreprocessor.prepareImageBytesForVision(webpFile, 896, 0.82f);
            assertNotNull(jpegOut);
            assertEquals((byte) 0xFF, jpegOut[0], "Converted .webp photo must start with JPEG magic FF");
            assertEquals((byte) 0xD8, jpegOut[1], "Converted .webp photo must start with JPEG magic D8");

            // Floorplan preprocessor must convert .webp floorplans to lossless PNG (89 50 4E 47)
            byte[] pngOut = VisionImagePreprocessor.prepareFloorplanBytesForVision(fpWebpFile, 1680);
            assertNotNull(pngOut);
            assertEquals((byte) 0x89, pngOut[0]);
            assertEquals((byte) 'P', pngOut[1]);
            assertEquals((byte) 'N', pngOut[2]);
            assertEquals((byte) 'G', pngOut[3]);
        } finally {
            Files.deleteIfExists(webpFile);
            Files.deleteIfExists(fpWebpFile);
            Files.deleteIfExists(tempDir);
        }
    }

    @Test
    void ref03_externalizedGlasgowTransitNetworkJsonLoadsCompleteCatalog() {
        assertTrue(stationService.getAllStations().size() >= 115,
            "Expected >= 115 ScotRail stations from glasgow-transit-network.json, got: " + stationService.getAllStations().size());
        assertNotNull(stationService.resolveStation("Uddingston"));
        assertNotNull(stationService.resolveStation("Bearsden"));
        assertNotNull(stationService.resolveStation("Helensburgh Central"));
        assertNotNull(stationService.resolveStation("Lanark"));

        // Verify location lookup for hospitals / Subway hubs from JSON
        double distToQeuh = stationService.calculateDistanceFromCenterMiles("QEUH", 55.8628, -4.3350);
        assertTrue(distToQeuh < 0.5, "Shieldhall/QEUH coordinate should be < 0.5 mile from QEUH in externalized JSON, got: " + distToQeuh);
    }

    @Test
    void ref01_and_ref05_portalClientsNormalizeCardsAndStateTrackerPushesEvents() {
        RightmovePortalClient rmClient = new RightmovePortalClient(null, stationService, objectMapper, "TestUA");
        OnTheMarketPortalClient otmClient = new OnTheMarketPortalClient(objectMapper, "TestUA");

        String rmJson = """
            {
              "props": {
                "pageProps": {
                  "searchResults": {
                    "resultCount": "48",
                    "properties": [
                      {
                        "id": "159001122",
                        "price": {
                          "amount": 385000,
                          "displayPrices": [{ "displayPrice": "£385,000", "displayPriceQualifier": "Offers Over" }]
                        },
                        "displayAddress": "14 Thorn Drive, Bearsden, G61",
                        "propertySubType": "Semi-Detached",
                        "bedrooms": 4,
                        "bathrooms": 2,
                        "location": { "latitude": 55.9188, "longitude": -4.3331 },
                        "propertyUrl": "/properties/159001122",
                        "keyFeatures": [{ "description": "South facing rear garden" }, { "description": "Downstairs WC" }],
                        "propertyImages": {
                          "mainImageSrc": "https://media.rightmove.co.uk/img1.webp",
                          "images": [{ "url": "dir/img1.webp" }]
                        },
                        "summary": "Beautiful extended semi-detached family home.",
                        "customer": { "branchDisplayName": "Rettie Bearsden" }
                      }
                    ]
                  }
                }
              }
            }
            """;

        PortalListingCandidate.PageCards rmCards = rmClient.parseSearchCards(rmJson);
        assertEquals(48, rmCards.totalResults());
        assertEquals(24, rmCards.pageSize());
        assertEquals(1, rmCards.candidates().size());
        PortalListingCandidate rm = rmCards.candidates().get(0);
        assertEquals("RM-159001122", rm.listingId());
        assertEquals(385000, rm.priceGbp());
        assertEquals("Offers Over", rm.priceQualifier());
        assertEquals("Semi-Detached", rm.normalizedType());
        assertEquals(4, rm.bedrooms());
        assertEquals(2, rm.bathrooms());
        assertEquals("https://www.rightmove.co.uk/properties/159001122", rm.listingUrl());

        String otmJson = """
            {
              "props": {
                "pageProps": {
                  "initialReduxState": {
                    "results": {
                      "totalResults": 15,
                      "list": [
                        {
                          "id": "9988776",
                          "price": "£410,000",
                          "price-qualifier": "Fixed Price",
                          "address": "Bothwell Road, Uddingston, G71",
                          "humanised-property-type": "Detached house",
                          "bedrooms": 4,
                          "bathrooms": 3,
                          "location": { "lat": 55.8190, "lon": -4.0820 },
                          "details-url": "/details/9988776/",
                          "features": ["Utility room", "Double garage"]
                        }
                      ]
                    }
                  }
                }
              }
            }
            """;

        PortalListingCandidate.PageCards otmCards = otmClient.parseSearchCards(otmJson);
        assertEquals(15, otmCards.totalResults());
        assertEquals(1, otmCards.candidates().size());
        PortalListingCandidate otm = otmCards.candidates().get(0);
        assertEquals("OTM-9988776", otm.listingId());
        assertEquals(410000, otm.priceGbp());
        assertEquals("Fixed Price", otm.priceQualifier());
        assertEquals("Detached", otm.normalizedType());

        // REF-05: Event-driven SSE push listener verification
        ScrapeJobStateTracker tracker = new ScrapeJobStateTracker();
        List<RightmoveScraperService.ScrapeJobStatus> pushedEvents = new ArrayList<>();
        Runnable unsubscribe = tracker.subscribe(pushedEvents::add);

        assertTrue(tracker.tryAcquireJobLock());
        tracker.beginScrapeJob("Bearsden", 5.0, "rightmove", " • types: Detached");
        tracker.onListingSaved("RM-159001122", "14 Thorn Drive, Bearsden, G61");
        tracker.completeJob("Done", "Indexed 1 property");

        assertEquals(3, pushedEvents.size(), "Expected 3 immediate push events (begin, saved, complete)");
        assertEquals("RUNNING", pushedEvents.get(0).state());
        assertEquals(1, pushedEvents.get(1).newlyIndexedCount());
        assertEquals("COMPLETED", pushedEvents.get(2).state());
        assertFalse(pushedEvents.get(2).running());

        unsubscribe.run();
        tracker.resetStateOnDatabaseClear();
        assertEquals(3, pushedEvents.size(), "Unsubscribed listener must receive no further events");
    }

    @Test
    void ref04_declarativeCandidateRowMapperMapsPostgreSqlColumnsAndInstants() throws Exception {
        Instant now = Instant.parse("2026-11-07T10:30:00Z");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("listing_id", "RM-ROW-1");
        row.put("title", "4 Bed Detached — Clarkston");
        row.put("address", "Mearns Road, Clarkston, G76");
        row.put("property_type", "Detached");
        row.put("price_gbp", 420000);
        row.put("previous_price_gbp", 445000);
        row.put("price_qualifier", "Offers Over");
        row.put("price_changed_at", Timestamp.from(now));
        row.put("last_seen_at", Timestamp.from(now));
        row.put("bedrooms", 4);
        row.put("distance_from_glasgow_central_miles", 5.4);
        row.put("latitude", 55.7845);
        row.put("longitude", -4.2760);
        row.put("nearest_station_name", "Clarkston");
        row.put("station_free_parking", true);
        row.put("has_downstairs_wc", true);
        row.put("downloaded_image_count", 14);
        row.put("analyzed_at", Timestamp.from(now));
        row.put("indexed_at", Timestamp.from(now));
        row.put("llm_response_log", "");

        List<String> colNames = new ArrayList<>(row.keySet());
        List<Object> colValues = new ArrayList<>(row.values());

        ResultSetMetaData metaStub = (ResultSetMetaData) Proxy.newProxyInstance(
            ResultSetMetaData.class.getClassLoader(),
            new Class<?>[]{ResultSetMetaData.class},
            (p, m, a) -> switch (m.getName()) {
                case "getColumnCount" -> colNames.size();
                case "getColumnLabel", "getColumnName" -> colNames.get(((Integer) a[0]) - 1);
                default -> null;
            }
        );

        final Object[] lastVal = new Object[1];
        ResultSet rsStub = (ResultSet) Proxy.newProxyInstance(
            ResultSet.class.getClassLoader(),
            new Class<?>[]{ResultSet.class},
            (p, m, a) -> {
                if ("getMetaData".equals(m.getName())) return metaStub;
                if ("wasNull".equals(m.getName())) return lastVal[0] == null;
                if (a != null && a.length >= 1 && a[0] instanceof Integer idx) {
                    Object val = colValues.get(idx - 1);
                    lastVal[0] = val;
                    return switch (m.getName()) {
                        case "getTimestamp" -> (Timestamp) val;
                        case "getString" -> val != null ? val.toString() : null;
                        case "getInt" -> val != null ? ((Number) val).intValue() : 0;
                        case "getDouble" -> val != null ? ((Number) val).doubleValue() : 0.0;
                        case "getBoolean" -> val != null && (Boolean) val;
                        case "getObject" -> val;
                        default -> val;
                    };
                }
                return null;
            }
        );

        IndexedPropertyEntity entity = PropertyAgentController.CANDIDATE_ROW_MAPPER.mapRow(rsStub, 0);
        assertNotNull(entity);
        assertEquals("RM-ROW-1", entity.getListingId());
        assertEquals(420000, entity.getPriceGbp());
        assertEquals(445000, entity.getPreviousPriceGbp());
        assertEquals(now, entity.getPriceChangedAt());
        assertEquals(now, entity.getIndexedAt());
        assertTrue(entity.isStationFreeParking());
        assertTrue(entity.isHasDownstairsWc());
        assertEquals(14, entity.getDownloadedImageCount());
        assertEquals("", entity.getLlmResponseLog());
    }

    @Test
    void simp02_and_simp04_jakartaValidationAndCaffeineCachesWork() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();

            SearchFilterRequest validReq = new SearchFilterRequest(
                "Glasgow Central", 15.0, List.of("Detached"), List.of("Train"),
                2, 500000, 3, 45, false, false,
                List.of(new SearchFilterRequest.CriterionWeight("Downstairs WC", "HIGH")),
                "rightmove", false
            );
            assertTrue(validator.validate(validReq).isEmpty(), "Valid SearchFilterRequest must have 0 violations");

            SearchFilterRequest invalidReq = new SearchFilterRequest(
                "Glasgow Central", 95.0, List.of("Detached"), List.of("Train"),
                -2, -50000, 0, 500, false, false,
                List.of(new SearchFilterRequest.CriterionWeight("", "HIGH")),
                "rightmove", false
            );
            Set<ConstraintViolation<SearchFilterRequest>> violations = validator.validate(invalidReq);
            assertTrue(violations.size() >= 5,
                "Expected >= 5 violations (radius, minServices, maxPrice, minBedrooms, maxCommute, blank rule), got: " + violations.size());
        }

        PropertyMetadataCache cache = new PropertyMetadataCache();
        ExtractedPropertyMetadata sample = new ExtractedPropertyMetadata(
            true, true, 2, "Rear", true, "Direct", "Open Plan", true,
            true, false, "Detached", true, "South", 2, "Walk-in / Modern", "B", "Gas", List.of()
        );
        ExtractedPropertyMetadata resolved = cache.computeIfAbsent("RM-CACHE-1", id -> sample);
        assertSame(sample, resolved);
        cache.clear();
        ExtractedPropertyMetadata afterClear = cache.computeIfAbsent("RM-CACHE-1", id -> null);
        assertNull(afterClear);
    }
}
