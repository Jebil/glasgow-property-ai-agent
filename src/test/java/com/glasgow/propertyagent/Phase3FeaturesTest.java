package com.glasgow.propertyagent;

import com.glasgow.propertyagent.controller.PropertyAgentController;
import com.glasgow.propertyagent.entity.IndexedPropertyEntity;
import com.glasgow.propertyagent.model.BuyerWorkflowState;
import com.glasgow.propertyagent.model.ExtractedPropertyMetadata;
import com.glasgow.propertyagent.model.RankedPropertyResponse;
import com.glasgow.propertyagent.model.SchoolAndAreaProfile;
import com.glasgow.propertyagent.model.ScottishCostBreakdown;
import com.glasgow.propertyagent.model.SearchFilterRequest;
import com.glasgow.propertyagent.model.StationProfile;
import com.glasgow.propertyagent.service.BuyerWorkflowService;
import com.glasgow.propertyagent.service.GlasgowSchoolAndAreaService;
import com.glasgow.propertyagent.service.HomeReportAnalyzerService;
import com.glasgow.propertyagent.service.OsmOverpassTransitClient;
import com.glasgow.propertyagent.service.PropertyMetadataCache;
import com.glasgow.propertyagent.service.PropertyScoringEngine;
import com.glasgow.propertyagent.service.ScottishOfferAndLbttService;
import com.glasgow.propertyagent.service.StationCommuteService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.*;

class Phase3FeaturesTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final StationCommuteService stationService =
        new StationCommuteService(new OsmOverpassTransitClient(objectMapper), objectMapper);
    private final GlasgowSchoolAndAreaService schoolService =
        new GlasgowSchoolAndAreaService(objectMapper);
    private final ScottishOfferAndLbttService lbttService =
        new ScottishOfferAndLbttService();
    private final HomeReportAnalyzerService homeReportService =
        new HomeReportAnalyzerService(objectMapper, null, "gemma4:e4b");
    private final PropertyScoringEngine scoringEngine =
        new PropertyScoringEngine(objectMapper, null, schoolService, lbttService);

    public static void main(String[] args) throws Exception {
        Phase3FeaturesTest suite = new Phase3FeaturesTest();
        suite.feat01_scottishLbttAdsFirstTimeBuyerAndOffersOverCashModel();
        suite.feat02_transitMapDataExposesAllScotRailCorridorsSubwayLoopAndAreaHubs();
        suite.feat03_and_feat07_glasgowSchoolCatchmentSimdResolutionAndDynamicCriterionScoring();
        suite.feat04_dualCommuterHouseholdScoresPartnerAAndPartnerBSimultaneously();
        suite.feat05_buyerWorkflowShortlistHideViewingNotesPersistence();
        suite.feat06_homeReportPdfFlateDecodeAndSingleSurveyCategoryExtraction();
        System.out.println("ALL PHASE 3 BUYER FEATURE TESTS PASSED!");
    }

    @Test
    void feat01_scottishLbttAdsFirstTimeBuyerAndOffersOverCashModel() {
        // 1. Revenue Scotland LBTT residential slab checks
        assertEquals(0, lbttService.calculateLbtt(145_000, false));
        assertEquals(500, lbttService.calculateLbtt(170_000, false));
        assertEquals(0, lbttService.calculateLbtt(170_000, true), "First-time buyer nil band extends to £175k");
        assertEquals(8_350, lbttService.calculateLbtt(350_000, false));
        assertEquals(7_750, lbttService.calculateLbtt(350_000, true), "FTB max relief is £600 above £175k");
        assertEquals(54_350, lbttService.calculateLbtt(800_000, false));

        // 2. Additional Dwelling Supplement (8% in Scotland >= £40k)
        assertEquals(28_000, lbttService.calculateAds(350_000, true));
        assertEquals(0, lbttService.calculateAds(35_000, true));

        // 3. Offers Over + Home Report LTV cap upfront cash calculation
        IndexedPropertyEntity prop = new IndexedPropertyEntity();
        prop.setListingId("RM-BEARSDEN-350");
        prop.setTitle("4 Bed Semi-Detached — Bearsden");
        prop.setAddress("12 Drymen Road, Bearsden, G61");
        prop.setPropertyType("Semi-Detached");
        prop.setPriceGbp(350_000);
        prop.setPriceQualifier("Offers Over");
        prop.setBedrooms(4);
        prop.setLatitude(55.9189);
        prop.setLongitude(-4.3335);

        SchoolAndAreaProfile bearsdenArea = schoolService.resolveProfile(prop);
        SearchFilterRequest req10Pct = new SearchFilterRequest(
            "Glasgow Central", 15.0, List.of("Semi-Detached"), List.of("Train"),
            2, 500_000, 3, 45, false, false, List.of(), "rightmove", false,
            null, 45, null, 10, 15, false, false, 0, "ALL"
        );

        // Without Home Report uploaded: conservative HR = £350k, closing (+10%) = £385k
        ScottishCostBreakdown unverified = lbttService.evaluateCostBreakdown(prop, bearsdenArea, BuyerWorkflowState.empty(), req10Pct);
        assertFalse(unverified.homeReportVerified());
        assertEquals(350_000, unverified.estimatedHomeReportGbp());
        assertEquals(385_000, unverified.estimatedClosingPriceGbp());
        assertEquals(52_500, unverified.mortgageDepositGbp()); // 15% of £350k HR
        assertEquals(35_000, unverified.cashOverHomeReportGbp()); // £385k - £350k paid 100% in cash
        assertEquals(11_850, unverified.lbttGbp()); // LBTT on £385k
        assertEquals(99_350, unverified.totalUpfrontCashGbp());

        // With verified Home Report uploaded at £375,000 and 125 m²: lender lends 85% of £375k!
        BuyerWorkflowState verifiedWf = new BuyerWorkflowState(
            true, false, "BOOKED", "", 375_000, 125.0, "F", "C", 18, 2, 0, List.of(), "HR £375k"
        );
        ScottishCostBreakdown withHr = lbttService.evaluateCostBreakdown(prop, bearsdenArea, verifiedWf, req10Pct);
        assertTrue(withHr.homeReportVerified());
        assertEquals(375_000, withHr.estimatedHomeReportGbp());
        assertEquals(385_000, withHr.estimatedClosingPriceGbp());
        assertEquals(56_250, withHr.mortgageDepositGbp()); // 15% of £375k HR
        assertEquals(10_000, withHr.cashOverHomeReportGbp()); // Cash gap drops from £35k -> £10k!
        assertEquals(78_100, withHr.totalUpfrontCashGbp());
        assertEquals(3_080, withHr.pricePerSqmGbp());

        // Fixed Price listing defaults to 0% Offers-Over uplift in Auto mode
        prop.setPriceQualifier("Fixed Price");
        SearchFilterRequest autoReq = new SearchFilterRequest(
            "Glasgow Central", 15.0, List.of("Semi-Detached"), List.of("Train"),
            2, 500_000, 3, 45, false, false, List.of(), "rightmove", false
        );
        ScottishCostBreakdown fixedBreak = lbttService.evaluateCostBreakdown(prop, bearsdenArea, BuyerWorkflowState.empty(), autoReq);
        assertEquals(0, fixedBreak.premiumPctApplied());
        assertEquals(350_000, fixedBreak.estimatedClosingPriceGbp());
    }

    @Test
    void feat02_transitMapDataExposesAllScotRailCorridorsSubwayLoopAndAreaHubs() {
        assertTrue(stationService.getScotRailStationConfigs().size() >= 115);
        assertEquals(15, stationService.getSubwayHubs().size(), "SPT Glasgow Subway must have all 15 loop stations");
        assertEquals(0, stationService.getSubwayHubs().get(0).circleIndex());
        assertEquals(14, stationService.getSubwayHubs().get(14).circleIndex());

        PropertyAgentController controller = new PropertyAgentController(
            null, stationService, null, null, null, new PropertyMetadataCache(),
            new BuyerWorkflowService(), homeReportService, schoolService, objectMapper, null
        );
        Map<String, Object> mapData = controller.getTransitMapData("Glasgow Central", "QEUH");
        double[] c1 = (double[]) mapData.get("centerCoords");
        double[] c2 = (double[]) mapData.get("secondCenterCoords");
        assertNotNull(c1);
        assertNotNull(c2);
        assertEquals(55.8591, c1[0], 0.01);
        assertEquals(55.8628, c2[0], 0.02);
        assertFalse(((List<?>) mapData.get("schoolAreas")).isEmpty());
    }

    @Test
    void feat03_and_feat07_glasgowSchoolCatchmentSimdResolutionAndDynamicCriterionScoring() {
        SchoolAndAreaProfile giffnock = schoolService.resolveProfile("18 Braidholm Road, Giffnock, G46 6BZ", "4 Bed Detached", 55.8035, -4.2940);
        assertEquals("TOP_TIER", giffnock.attainmentTier());
        assertEquals(10, giffnock.simdDecile());
        assertTrue(giffnock.nonDenomSecondary().contains("Williamwood"));
        assertTrue(giffnock.denomSecondary().contains("St Ninian"));

        SchoolAndAreaProfile uddingston = schoolService.resolveProfile("7 Bothwell Road, Uddingston, G71", "4 Bed Villa", 55.8165, -4.0795);
        assertTrue(uddingston.nonDenomSecondary().contains("Uddingston Grammar"));
        assertTrue(uddingston.simdDecile() >= 8);

        // Verify Stage 2 dynamic scoring boost for Top-Tier Secondary Catchment + SIMD >= 8
        IndexedPropertyEntity giffnockProp = new IndexedPropertyEntity();
        giffnockProp.setListingId("RM-GIFF-1");
        giffnockProp.setTitle("4 Bed Detached — Giffnock");
        giffnockProp.setAddress("18 Braidholm Road, Giffnock, G46");
        giffnockProp.setPropertyType("Detached");
        giffnockProp.setPriceGbp(440_000);
        giffnockProp.setPriceQualifier("Offers Over");
        giffnockProp.setBedrooms(4);
        giffnockProp.setLatitude(55.8035);
        giffnockProp.setLongitude(-4.2940);
        giffnockProp.setNearestStationName("Giffnock");

        IndexedPropertyEntity blantyreProp = new IndexedPropertyEntity();
        blantyreProp.setListingId("RM-BLAN-1");
        blantyreProp.setTitle("4 Bed Detached — Blantyre");
        blantyreProp.setAddress("Main Street, Blantyre, G72");
        blantyreProp.setPropertyType("Detached");
        blantyreProp.setPriceGbp(440_000);
        blantyreProp.setPriceQualifier("Offers Over");
        blantyreProp.setBedrooms(4);
        blantyreProp.setLatitude(55.7920);
        blantyreProp.setLongitude(-4.0970);
        blantyreProp.setNearestStationName("Blantyre");

        ExtractedPropertyMetadata meta = new ExtractedPropertyMetadata(
            true, true, 2, "Rear", true, "Direct (Watch kids from sofa)",
            "Open Plan", true, true, true, "Detached", true, "South",
            2, "Walk-in / Modern", "C", "Gas Central", List.of()
        );

        SearchFilterRequest schoolReq = new SearchFilterRequest(
            "Glasgow Central", 15.0, List.of("Detached"), List.of("Train"),
            2, 500_000, 4, 45, false, false,
            List.of(
                new SearchFilterRequest.CriterionWeight("Top-tier secondary school catchment", "MUST_HAVE"),
                new SearchFilterRequest.CriterionWeight("High-amenity neighbourhood (SIMD decile >= 8)", "HIGH")
            ),
            "rightmove", false
        );

        StationProfile giffSt = stationService.resolveStation("Giffnock");
        StationProfile blanSt = stationService.resolveStation("Blantyre");
        RankedPropertyResponse giffRanked = scoringEngine.evaluateAndScoreProperty(
            giffnockProp, meta, giffSt,
            stationService.evaluateBestPublicTransport(giffnockProp, giffSt, "Glasgow Central", List.of("Train")),
            schoolReq
        );
        RankedPropertyResponse blanRanked = scoringEngine.evaluateAndScoreProperty(
            blantyreProp, meta, blanSt,
            stationService.evaluateBestPublicTransport(blantyreProp, blanSt, "Glasgow Central", List.of("Train")),
            schoolReq
        );

        assertTrue(giffRanked.layoutScore() >= blanRanked.layoutScore() + 25,
            "Giffnock (TOP_TIER + SIMD 10) must decisively outscore Blantyre (SOLID + SIMD 5) on school & SIMD rules");
    }

    @Test
    void feat04_dualCommuterHouseholdScoresPartnerAAndPartnerBSimultaneously() {
        IndexedPropertyEntity partickProp = new IndexedPropertyEntity();
        partickProp.setListingId("RM-PARTICK-DUAL");
        partickProp.setTitle("4 Bed Townhouse — Broomhill / Partick");
        partickProp.setAddress("Crow Road, Broomhill, Glasgow, G11");
        partickProp.setPropertyType("Terraced");
        partickProp.setPriceGbp(410_000);
        partickProp.setPriceQualifier("Offers Over");
        partickProp.setBedrooms(4);
        partickProp.setLatitude(55.8730);
        partickProp.setLongitude(-4.3160);
        partickProp.setNearestStationName("Partick");
        partickProp.setDistanceToStationMiles(0.35);

        ExtractedPropertyMetadata meta = new ExtractedPropertyMetadata(
            true, true, 2, "Rear", true, "Direct (Watch kids from sofa)",
            "Open Plan", false, true, true, "None", true, "West",
            1, "Walk-in / Modern", "C", "Gas Central", List.of()
        );

        StationProfile partickSt = stationService.resolveStation("Partick");
        StationCommuteService.TransitEvaluation partnerATransit = stationService.evaluateBestPublicTransport(
            partickProp, partickSt, "Glasgow Queen Street", List.of("Train", "Subway")
        );
        StationCommuteService.TransitEvaluation partnerBTransit = stationService.evaluateBestPublicTransport(
            partickProp, partickSt, "Hillhead Subway", List.of("Train", "Subway")
        );

        SearchFilterRequest dualReq = new SearchFilterRequest(
            "Glasgow Queen Street", 15.0, List.of("Terraced"), List.of("Train", "Subway"),
            4, 500_000, 3, 35, false, false, List.of(), "rightmove", false,
            "Hillhead Subway", 25, List.of("Train", "Subway"),
            null, 15, false, false, 0, "ALL"
        );

        RankedPropertyResponse ranked = scoringEngine.evaluateAndScoreProperty(
            partickProp, meta, partickSt, partnerATransit, partnerBTransit,
            BuyerWorkflowState.empty(), new double[]{55.8730, -4.3160}, dualReq
        );

        assertNotNull(ranked.secondCommute(), "Dual-commuter mode must populate secondCommute summary");
        assertEquals("Hillhead Subway", ranked.secondCommute().targetDestination());
        assertTrue(ranked.secondCommute().doorToTargetMins() <= 25,
            "Partick -> Hillhead Subway should be <= 25 mins door-to-target, got: " + ranked.secondCommute().doorToTargetMins());
        assertTrue(ranked.matchedCriteria().stream().anyMatch(m -> m.contains("Partner B transit to Hillhead Subway")));
    }

    @Test
    void feat05_buyerWorkflowShortlistHideViewingNotesPersistence() {
        BuyerWorkflowService wfService = new BuyerWorkflowService();

        BuyerWorkflowState s1 = wfService.updateWorkflow("RM-101", new BuyerWorkflowService.WorkflowUpdateRequest(
            true, null, "BOOKED", "Viewing Saturday 11am — check south garden", null, null, null
        ));
        assertTrue(s1.shortlisted());
        assertFalse(s1.hidden());
        assertEquals("BOOKED", s1.viewingStatus());
        assertTrue(s1.viewingNotes().contains("Saturday 11am"));

        // Hiding a property must automatically clear shortlisted
        BuyerWorkflowState s2 = wfService.updateWorkflow("RM-102", new BuyerWorkflowService.WorkflowUpdateRequest(
            true, null, null, null, null, null, null
        ));
        assertTrue(s2.shortlisted());
        BuyerWorkflowState s2Hidden = wfService.updateWorkflow("RM-102", new BuyerWorkflowService.WorkflowUpdateRequest(
            null, true, null, null, null, null, null
        ));
        assertTrue(s2Hidden.hidden());
        assertFalse(s2Hidden.shortlisted(), "Hiding a property must remove it from active shortlist");

        BuyerWorkflowService.WorkflowSummary summary = wfService.getSummary();
        assertEquals(1L, summary.shortlistedCount());
        assertEquals(1L, summary.hiddenCount());

        Map<String, BuyerWorkflowState> batch = wfService.loadStatesForListings(List.of("RM-101", "RM-102", "RM-999"));
        assertEquals(2, batch.size());
        assertTrue(batch.get("RM-101").shortlisted());
        assertTrue(batch.get("RM-102").hidden());
    }

    @Test
    void feat06_homeReportPdfFlateDecodeAndSingleSurveyCategoryExtraction() throws Exception {
        String singleSurveyStreamContent = """
            BT
            /F1 11 Tf
            72 740 Td
            (Scottish Single Survey - Shepherd Chartered Surveyors) Tj
            0 -18 Td
            (Gross internal floor area of the residentialirs extends to approximately 128 m2.) Tj
            0 -18 Td
            (Council Tax Band: F) Tj
            0 -18 Td
            (Current energy efficiency rating: Band C) Tj
            0 -18 Td
            (Roof coverings   Repair category: 2   Weathered ridge mortar and localized moss noted.) Tj
            0 -18 Td
            (Dampness, rot and infestation   Repair category: 3   High moisture readings recorded to lower rear wall; timber specialist required.) Tj
            0 -18 Td
            (Section 3: Reinstatement cost for insurance purposes is £285,000.) Tj
            0 -18 Td
            (Section 4: Valuation and Market Comments: Having regard to all relevant matters, we are of the opinion that the present market value of the property is £375,000.) Tj
            ET
            """;

        byte[] pdfBytes = buildSyntheticFlatePdf(singleSurveyStreamContent);
        HomeReportAnalyzerService.HomeReportAnalysis analysis = homeReportService.analyzePdfBytes(pdfBytes, 350_000);

        // Must pick Section 4 Market Value (£375,000) and NEVER Section 3 Insurance Reinstatement (£285,000)!
        assertEquals(375_000, analysis.homeReportValuationGbp());
        assertEquals(128.0, analysis.floorAreaSqm());
        assertEquals("F", analysis.councilTaxBand());
        assertEquals("C", analysis.energyBand());
        assertTrue(analysis.category2Count() >= 1);
        assertTrue(analysis.category3Count() >= 1);
        assertTrue(analysis.repairWarnings().stream().anyMatch(w -> w.contains("[Cat 3 Urgent]") && w.toLowerCase().contains("dampness")));

        // Verify Category 3 urgent defect fails "Walk-in condition (no major DIY)" criterion in PropertyScoringEngine
        BuyerWorkflowService wfService = new BuyerWorkflowService();
        BuyerWorkflowState savedWf = wfService.saveHomeReportAnalysis("RM-CAT3-TEST", analysis);
        assertEquals(375_000, savedWf.homeReportValuationGbp());
        assertEquals(1, savedWf.hrCategory3Count());

        IndexedPropertyEntity prop = new IndexedPropertyEntity();
        prop.setListingId("RM-CAT3-TEST");
        prop.setTitle("4 Bed Detached — Clarkston");
        prop.setAddress("Mearns Road, Clarkston, G76");
        prop.setPropertyType("Detached");
        prop.setPriceGbp(350_000);
        prop.setPriceQualifier("Offers Over");
        prop.setBedrooms(4);
        prop.setLatitude(55.7875);
        prop.setLongitude(-4.2760);
        prop.setNearestStationName("Clarkston");

        ExtractedPropertyMetadata meta = new ExtractedPropertyMetadata(
            true, true, 2, "Rear", true, "Direct (Watch kids from sofa)",
            "Open Plan", true, true, true, "Detached", true, "South",
            2, "Walk-in / Modern", "C", "Gas Central", List.of()
        );
        StationProfile clarkston = stationService.resolveStation("Clarkston");
        SearchFilterRequest walkInReq = new SearchFilterRequest(
            "Glasgow Central", 15.0, List.of("Detached"), List.of("Train"),
            2, 500_000, 3, 45, false, false,
            List.of(new SearchFilterRequest.CriterionWeight("Walk-in condition (no major DIY)", "MUST_HAVE")),
            "rightmove", false
        );

        RankedPropertyResponse ranked = scoringEngine.evaluateAndScoreProperty(
            prop, meta, clarkston,
            stationService.evaluateBestPublicTransport(prop, clarkston, "Glasgow Central", List.of("Train")),
            null, savedWf, new double[]{55.7875, -4.2760}, walkInReq
        );

        assertTrue(ranked.compromisedCriteria().stream().anyMatch(c -> c.contains("Category 3 urgent repair")),
            "Home Report Category 3 defect must trigger compromise warning on walk-in condition");
    }

    private static byte[] buildSyntheticFlatePdf(String contentStream) throws Exception {
        byte[] rawStream = contentStream.getBytes(StandardCharsets.ISO_8859_1);
        Deflater deflater = new Deflater();
        deflater.setInput(rawStream);
        deflater.finish();
        byte[] buf = new byte[4096];
        int compressedLen = deflater.deflate(buf);
        deflater.end();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write("%PDF-1.4\n1 0 obj\n<< /Filter /FlateDecode /Length ".getBytes(StandardCharsets.ISO_8859_1));
        out.write(Integer.toString(compressedLen).getBytes(StandardCharsets.ISO_8859_1));
        out.write(" >>\nstream\n".getBytes(StandardCharsets.ISO_8859_1));
        out.write(buf, 0, compressedLen);
        out.write("\nendstream\nendobj\n%%EOF".getBytes(StandardCharsets.ISO_8859_1));
        return out.toByteArray();
    }
}
