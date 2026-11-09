package com.glasgow.propertyagent;

import com.glasgow.propertyagent.service.app.StationCommuteService;
import com.glasgow.propertyagent.service.ai.PropertyEmbeddingService;
import com.glasgow.propertyagent.service.scraping.RightmoveScraperService;
import com.glasgow.propertyagent.service.app.OsmOverpassTransitClient;
import com.glasgow.propertyagent.service.ai.OllamaAgentService;

import tools.jackson.databind.ObjectMapper;
import com.glasgow.propertyagent.entity.IndexedPropertyEntity;
import com.glasgow.propertyagent.model.ExtractedPropertyMetadata;
import com.glasgow.propertyagent.model.RankedPropertyResponse;
import com.glasgow.propertyagent.model.SearchFilterRequest;
import com.glasgow.propertyagent.model.StationProfile;
import com.glasgow.propertyagent.repository.IndexedPropertyRepository;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class Phase2ImprovementsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final StationCommuteService stationService =
        new StationCommuteService(new OsmOverpassTransitClient(objectMapper));
    private final OllamaAgentService ollamaService =
        new OllamaAgentService(objectMapper, null, "http://127.0.0.1:11434", "gemma4:e4b", "gemma4:e4b", 1680, 896, 4);
    private final PropertyEmbeddingService embeddingService =
        new PropertyEmbeddingService(null, null, objectMapper, "http://127.0.0.1:11434", "nomic-embed-text");

    public static void main(String[] args) throws Exception {
        Phase2ImprovementsTest suite = new Phase2ImprovementsTest();
        suite.imp03_dualResolutionVisionEncodingFloorplanPngVsPhotoJpeg();
        suite.imp03_and_imp05_cardThumbnailPreservationAndPriceDropScoring();
        suite.imp05_syncExistingListingDeltaPreservesPeakPriceAcrossSequentialDrops();
        suite.imp06_propertyChunkExtractionDeduplicatesVisionAndDescriptionChunks();
        System.out.println("ALL PHASE 2 IMPROVEMENT TESTS PASSED!");
    }

    @Test
    void imp03_dualResolutionVisionEncodingFloorplanPngVsPhotoJpeg() throws Exception {
        Path tempDir = Files.createTempDirectory("phase2-vision-test");
        Path fpPath = tempDir.resolve("floorplan-01.png");
        Path photoPath = tempDir.resolve("photo-01.jpg");
        try {
            // 1. High-resolution architectural floorplan (2400 x 1800) -> 1680 x 1260 lossless PNG
            BufferedImage fpImg = new BufferedImage(2400, 1800, BufferedImage.TYPE_INT_ARGB);
            Graphics2D gFp = fpImg.createGraphics();
            gFp.setColor(Color.WHITE);
            gFp.fillRect(0, 0, 2400, 1800);
            gFp.setColor(Color.BLACK);
            gFp.drawRect(120, 120, 2160, 1560);
            gFp.drawString("Bedroom 1: 4.25m x 3.60m (13'11 x 11'10) - W / En-Suite", 200, 300);
            gFp.dispose();
            ImageIO.write(fpImg, "png", fpPath.toFile());

            byte[] fpBytes = OllamaAgentService.prepareFloorplanBytesForVision(fpPath, 1680);
            assertNotNull(fpBytes);
            // Verify PNG magic signature: 89 50 4E 47
            assertEquals((byte) 0x89, fpBytes[0]);
            assertEquals((byte) 'P', fpBytes[1]);
            assertEquals((byte) 'N', fpBytes[2]);
            assertEquals((byte) 'G', fpBytes[3]);

            BufferedImage decodedFp = ImageIO.read(new ByteArrayInputStream(fpBytes));
            assertNotNull(decodedFp);
            assertEquals(1680, decodedFp.getWidth());
            assertEquals(1260, decodedFp.getHeight());

            // 2. High-resolution gallery photo (2000 x 1500) -> 896 x 672 compressed JPEG
            BufferedImage photoImg = new BufferedImage(2000, 1500, BufferedImage.TYPE_INT_RGB);
            Graphics2D gPh = photoImg.createGraphics();
            for (int y = 0; y < 1500; y += 25) {
                gPh.setColor(new Color((y * 7) % 255, (y * 3) % 255, 140));
                gPh.fillRect(0, y, 2000, 25);
            }
            gPh.dispose();
            ImageIO.write(photoImg, "jpg", photoPath.toFile());

            byte[] photoBytes = OllamaAgentService.prepareImageBytesForVision(photoPath, 896, 0.82f);
            assertNotNull(photoBytes);
            // Verify JPEG magic signature: FF D8
            assertEquals((byte) 0xFF, photoBytes[0]);
            assertEquals((byte) 0xD8, photoBytes[1]);

            BufferedImage decodedPhoto = ImageIO.read(new ByteArrayInputStream(photoBytes));
            assertNotNull(decodedPhoto);
            assertEquals(896, decodedPhoto.getWidth());
            assertEquals(672, decodedPhoto.getHeight());
        } finally {
            Files.deleteIfExists(fpPath);
            Files.deleteIfExists(photoPath);
            Files.deleteIfExists(tempDir);
        }
    }

    @Test
    void imp03_and_imp05_cardThumbnailPreservationAndPriceDropScoring() {
        IndexedPropertyEntity baseEntity = new IndexedPropertyEntity();
        baseEntity.setListingId("RM-PRICE-BASE");
        baseEntity.setTitle("4 Bed Detached — Uddingston");
        baseEntity.setAddress("Bothwell Road, Uddingston, G71");
        baseEntity.setPropertyType("Detached");
        baseEntity.setPriceGbp(425000);
        baseEntity.setPriceQualifier("Offers Over");
        baseEntity.setBedrooms(4);
        baseEntity.setLatitude(55.8190);
        baseEntity.setLongitude(-4.0820);
        baseEntity.setNearestStationName("Uddingston");
        baseEntity.setDistanceToStationMiles(0.3);
        baseEntity.setThumbnailUrl("/property-media/RM-PRICE-BASE/photo-01-thumb.jpg");
        baseEntity.setAllImagesJson("[\"/property-media/RM-PRICE-BASE/photo-01.jpg\",\"/property-media/RM-PRICE-BASE/photo-02.jpg\"]");
        baseEntity.setAllFloorplansJson("[\"/property-media/RM-PRICE-BASE/floorplan-01.png\"]");
        baseEntity.setRawDescription("Immaculate 4 bedroom detached family villa with utility room and south-facing garden.");

        IndexedPropertyEntity reducedFixedEntity = new IndexedPropertyEntity();
        reducedFixedEntity.setListingId("RM-PRICE-DROP");
        reducedFixedEntity.setTitle("4 Bed Detached — Uddingston");
        reducedFixedEntity.setAddress("Bothwell Road, Uddingston, G71");
        reducedFixedEntity.setPropertyType("Detached");
        reducedFixedEntity.setPriceGbp(425000);
        reducedFixedEntity.setPreviousPriceGbp(450000);
        reducedFixedEntity.setPriceQualifier("Fixed Price");
        reducedFixedEntity.setBedrooms(4);
        reducedFixedEntity.setLatitude(55.8190);
        reducedFixedEntity.setLongitude(-4.0820);
        reducedFixedEntity.setNearestStationName("Uddingston");
        reducedFixedEntity.setDistanceToStationMiles(0.3);
        reducedFixedEntity.setThumbnailUrl("/property-media/RM-PRICE-DROP/photo-01-thumb.jpg");
        reducedFixedEntity.setAllImagesJson("[\"/property-media/RM-PRICE-DROP/photo-01.jpg\",\"/property-media/RM-PRICE-DROP/photo-02.jpg\"]");
        reducedFixedEntity.setAllFloorplansJson("[\"/property-media/RM-PRICE-DROP/floorplan-01.png\"]");
        reducedFixedEntity.setRawDescription("Immaculate 4 bedroom detached family villa with utility room and south-facing garden.");

        ExtractedPropertyMetadata meta = new ExtractedPropertyMetadata(
            true, true, 3, "Rear", true, "Direct (Watch kids from sofa)",
            "Open Plan", true, true, true, "Integral", true, "South",
            2, "Walk-in / Modern", "B", "Gas Central Heating", List.of("South-facing garden")
        );

        StationProfile uddingston = stationService.resolveStation("Uddingston");
        StationCommuteService.TransitEvaluation transit = stationService.evaluateBestPublicTransport(
            baseEntity, uddingston, "Glasgow Central", List.of("Train")
        );
        SearchFilterRequest req = new SearchFilterRequest(
            "Glasgow Central", 15.0, List.of("Detached"), List.of("Train"),
            2, 460000, 4, 40, false, false, List.of(), "rightmove", false
        );

        RankedPropertyResponse scoredBase = ollamaService.evaluateAndScoreProperty(baseEntity, meta, uddingston, transit, req);
        RankedPropertyResponse scoredDrop = ollamaService.evaluateAndScoreProperty(reducedFixedEntity, meta, uddingston, transit, req);

        // IMP-03: Card thumbnailUrl must keep lightweight -thumb.jpg instead of full-resolution photo-01.jpg
        assertEquals("/property-media/RM-PRICE-DROP/photo-01-thumb.jpg", scoredDrop.thumbnailUrl());
        assertEquals(2, scoredDrop.allImageUrls().size());
        assertEquals("/property-media/RM-PRICE-DROP/photo-01.jpg", scoredDrop.allImageUrls().get(0));

        // IMP-05: Price reduction & Fixed Price fields and score bonus
        assertEquals(0, scoredBase.priceDropGbp());
        assertNull(scoredBase.previousPriceGbp());
        assertEquals(450000, scoredDrop.previousPriceGbp());
        assertEquals(25000, scoredDrop.priceDropGbp());
        assertTrue(scoredDrop.valueScore() > scoredBase.valueScore(),
            "Reduced Fixed Price property should earn higher valueScore than unreduced Offers Over baseline");
        assertTrue(scoredDrop.matchedCriteria().stream().anyMatch(m -> m.contains("reduced by £25,000")));
        assertTrue(scoredDrop.matchedCriteria().stream().anyMatch(m -> m.contains("Fixed Price")));
    }

    @Test
    void imp05_syncExistingListingDeltaPreservesPeakPriceAcrossSequentialDrops() {
        AtomicInteger priceUpdates = new AtomicInteger(0);
        AtomicInteger lastSeenTouches = new AtomicInteger(0);
        AtomicReference<Integer> recordedPrevPrice = new AtomicReference<>();
        AtomicReference<Integer> recordedNewPrice = new AtomicReference<>();
        AtomicReference<String> recordedQualifier = new AtomicReference<>();

        IndexedPropertyRepository repoStub = (IndexedPropertyRepository) Proxy.newProxyInstance(
            IndexedPropertyRepository.class.getClassLoader(),
            new Class<?>[]{IndexedPropertyRepository.class},
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "count" -> { return 1L; }
                    case "updateListingPriceChange" -> {
                        priceUpdates.incrementAndGet();
                        recordedPrevPrice.set((Integer) args[1]);
                        recordedNewPrice.set((Integer) args[2]);
                        recordedQualifier.set((String) args[3]);
                        return 1;
                    }
                    case "touchLastSeen" -> {
                        lastSeenTouches.incrementAndGet();
                        return 1;
                    }
                    default -> { return null; }
                }
            }
        );

        RightmoveScraperService scraper = new RightmoveScraperService(
            repoStub, stationService, ollamaService, null, embeddingService, null, null, objectMapper, "TestAgent"
        );

        Map<String, IndexedPropertyRepository.ListingPriceSnapshot> snapshots = new HashMap<>();
        snapshots.put("RM-DELTA-1", new IndexedPropertyRepository.ListingPriceSnapshot() {
            @Override public String getListingId() { return "RM-DELTA-1"; }
            @Override public int getPriceGbp() { return 450000; }
            @Override public Integer getPreviousPriceGbp() { return null; }
            @Override public String getPriceQualifier() { return "Offers Over"; }
        });

        // 1. Unchanged listing only touches lastSeenAt
        scraper.syncExistingListingDelta("RM-DELTA-1", 450000, "Offers Over", snapshots);
        assertEquals(0, priceUpdates.get());
        assertEquals(1, lastSeenTouches.get());

        // 2. First reduction: £450,000 -> £435,000
        scraper.syncExistingListingDelta("RM-DELTA-1", 435000, "Offers Over", snapshots);
        assertEquals(1, priceUpdates.get());
        assertEquals(450000, recordedPrevPrice.get());
        assertEquals(435000, recordedNewPrice.get());

        // 3. Second reduction + qualifier shift: £435,000 -> £425,000 Fixed Price (must preserve original £450,000 peak!)
        scraper.syncExistingListingDelta("RM-DELTA-1", 425000, "Fixed Price", snapshots);
        assertEquals(2, priceUpdates.get());
        assertEquals(450000, recordedPrevPrice.get());
        assertEquals(425000, recordedNewPrice.get());
        assertEquals("Fixed Price", recordedQualifier.get());
        assertEquals(2, scraper.getScrapeJobStatus().priceChangesDetectedCount());
    }

    @Test
    void imp06_propertyChunkExtractionDeduplicatesVisionAndDescriptionChunks() {
        IndexedPropertyEntity entity = new IndexedPropertyEntity();
        entity.setListingId("RM-CHUNK-1");
        entity.setTitle("4 Bed Semi-Detached — Clarkston");
        entity.setAddress("Mearns Road, Clarkston, G76");
        entity.setPropertyType("Semi-Detached");
        entity.setPriceGbp(365000);
        entity.setPriceQualifier("Offers Over");
        entity.setBedrooms(4);
        entity.setNearestStationName("Clarkston");
        entity.setEstimatedDoorToCentralMins(28);
        entity.setLlmResponseLog("""
            === PER-IMAGE VISION LLM RESPONSES (gemma4:e4b, think=false) ===
            Floorplan [1/3] (floorplan-01.png): Ground floor features entrance hallway with downstairs WC, rear lounge with French doors, and dedicated utility room off kitchen.
            Photo [2/3] (photo-01.jpg): Bright modern open-plan kitchen diner with quartz island breakfast bar and integrated larder storage.
            Photo [3/3] (photo-02.jpg): Bright modern open-plan kitchen diner with quartz island breakfast bar and integrated larder storage.
            """);
        entity.setRawDescription("""
            Situated in sought-after Clarkston school catchment, this extended family home features gas central heating with a modern Worcester Bosch combi boiler.

            Enclosed south-west facing level rear lawn ideal for children with monoblock driveway for two cars.
            """);

        List<PropertyEmbeddingService.ChunkRecord> chunks = embeddingService.extractPropertyChunks(entity);
        assertEquals(5, chunks.size(), "Duplicate photo notes must be deduplicated cleanly (1 OVERVIEW + 1 FLOORPLAN + 1 PHOTO + 2 DESCRIPTION)");
        assertTrue(chunks.stream().anyMatch(c -> "FLOORPLAN".equals(c.chunkType()) && c.chunkText().contains("dedicated utility room")));
        assertTrue(chunks.stream().anyMatch(c -> "DESCRIPTION".equals(c.chunkType()) && c.chunkText().contains("combi boiler")));
    }
}
