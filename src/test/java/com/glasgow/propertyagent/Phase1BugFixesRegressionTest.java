package com.glasgow.propertyagent;

import com.glasgow.propertyagent.service.app.StationCommuteService;
import com.glasgow.propertyagent.service.scraping.RightmoveScraperService;
import com.glasgow.propertyagent.service.app.OsmOverpassTransitClient;
import com.glasgow.propertyagent.service.ai.OllamaAgentService;

import tools.jackson.databind.ObjectMapper;
import com.glasgow.propertyagent.entity.IndexedPropertyEntity;
import com.glasgow.propertyagent.model.ExtractedPropertyMetadata;
import com.glasgow.propertyagent.model.RankedPropertyResponse;
import com.glasgow.propertyagent.model.SearchFilterRequest;
import com.glasgow.propertyagent.model.StationProfile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class Phase1BugFixesRegressionTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final StationCommuteService stationService =
        new StationCommuteService(new OsmOverpassTransitClient(objectMapper));
    private final OllamaAgentService ollamaService =
        new OllamaAgentService(objectMapper, null, "http://127.0.0.1:11434", "gemma4:e4b", "gemma4:e4b");

    public static void main(String[] args) {
        Phase1BugFixesRegressionTest suite = new Phase1BugFixesRegressionTest();
        suite.bug02_assembleMetadataPreservesExistingBathroomsWhenLlmReturnsZero();
        suite.bug05_trainPlusSubwayUsesBottleneckRailFrequencyInsteadOfSubway15PerHour();
        suite.bug08_and_bug10_negatedCustomCriteriaAndEmptyCriteriaModesScoreAccurately();
        System.out.println("ALL PHASE 1 REGRESSION TESTS PASSED!");
    }

    @Test
    void bug02_assembleMetadataPreservesExistingBathroomsWhenLlmReturnsZero() {
        ExtractedPropertyMetadata llmMeta = new ExtractedPropertyMetadata(
            true, true, 0, "Rear", true, "Direct (Watch kids from sofa)",
            "Open Plan", true, true, true, "Converted", true, "South",
            2, "Walk-in / Modern", "B", "Gas Central", List.of("Converted garage")
        );

        ExtractedPropertyMetadata assembled = RightmoveScraperService.assembleMetadata(llmMeta, List.of("Solar panels"), 3);
        assertEquals(3, assembled.totalBathrooms(), "Should preserve known 3 bathrooms instead of clamping to 1");
        assertEquals("Converted", assembled.garageType());
        assertTrue(assembled.extractedHighlights().contains("Solar panels"));
    }

    @Test
    void bug05_trainPlusSubwayUsesBottleneckRailFrequencyInsteadOfSubway15PerHour() {
        // Property near Lenzie (fast 10m express to Queen St + Buchanan St Subway transfer to Hillhead, 4 trains/hr peak)
        IndexedPropertyEntity prop = new IndexedPropertyEntity();
        prop.setListingId("RM-TEST-LENZIE");
        prop.setAddress("Kirkintilloch Road, Lenzie, G66");
        prop.setLatitude(55.9225);
        prop.setLongitude(-4.1544);
        prop.setNearestStationName("Lenzie");
        prop.setDistanceToStationMiles(0.2);

        StationProfile lenzie = stationService.resolveStation("Lenzie");
        StationCommuteService.TransitEvaluation eval = stationService.evaluateBestPublicTransport(
            prop, lenzie, "Hillhead Subway", List.of("Subway")
        );

        assertEquals("Train + Subway", eval.bestTransitMode());
        assertTrue(eval.servicesPerHourPeak() < 15,
            "Train + Subway peak frequency must reflect ScotRail bottleneck (<15/hr), got: " + eval.servicesPerHourPeak());
        assertEquals(lenzie.trainsPerHourPeak(), eval.servicesPerHourPeak());
    }

    @Test
    void bug08_and_bug10_negatedCustomCriteriaAndEmptyCriteriaModesScoreAccurately() {
        IndexedPropertyEntity electricProp = new IndexedPropertyEntity();
        electricProp.setListingId("RM-ELEC-1");
        electricProp.setTitle("4 Bed Detached — Bearsden");
        electricProp.setAddress("Drymen Road, Bearsden, G61");
        electricProp.setPropertyType("Detached");
        electricProp.setPriceGbp(395000);
        electricProp.setBedrooms(4);
        electricProp.setLatitude(55.9188);
        electricProp.setLongitude(-4.3331);
        electricProp.setNearestStationName("Bearsden");
        electricProp.setDistanceToStationMiles(0.4);
        electricProp.setRawDescription("Spacious villa with modern electric heating and UPVC conservatory to rear. No onward chain.");

        ExtractedPropertyMetadata meta = new ExtractedPropertyMetadata(
            true, true, 2, "Rear", true, "Direct (Watch kids from sofa)",
            "Open Plan", true, true, false, "Detached", true, "South",
            2, "Walk-in / Modern", "D", "Electric", List.of()
        );

        StationProfile bearsden = stationService.resolveStation("Bearsden");
        StationCommuteService.TransitEvaluation transit = stationService.evaluateBestPublicTransport(
            electricProp, bearsden, "Glasgow Central", List.of("Train")
        );

        // BUG-10: "No electric heating" and "Avoid conservatory" must penalize (compromise) a property that HAS them,
        // while "No onward chain" must match positively when chain-free is stated!
        SearchFilterRequest negatedRequest = new SearchFilterRequest(
            "Glasgow Central", 15.0, List.of("Detached"), List.of("Train"),
            2, 500000, 3, 45, false, false,
            List.of(
                new SearchFilterRequest.CriterionWeight("No electric heating", "MUST_HAVE"),
                new SearchFilterRequest.CriterionWeight("Avoid conservatory", "HIGH"),
                new SearchFilterRequest.CriterionWeight("No onward chain", "MEDIUM")
            ),
            "rightmove",
            false
        );

        RankedPropertyResponse scoredNeg = ollamaService.evaluateAndScoreProperty(
            electricProp, meta, bearsden, transit, negatedRequest
        );
        assertTrue(scoredNeg.compromisedCriteria().stream().anyMatch(c -> c.toLowerCase().contains("electric heating")),
            "Property with electric heating must fail 'No electric heating' rule");
        assertTrue(scoredNeg.compromisedCriteria().stream().anyMatch(c -> c.toLowerCase().contains("conservatory")),
            "Property with conservatory must fail 'Avoid conservatory' rule");
        assertTrue(scoredNeg.matchedCriteria().stream().anyMatch(m -> m.toLowerCase().contains("chain-free")),
            "Property with 'No onward chain' must match chain-free rule");

        // BUG-08: Empty dynamicCriteria with bypassHardFilters=false awards clean baseline without bypass warning
        SearchFilterRequest emptyCriteriaNormal = new SearchFilterRequest(
            "Glasgow Central", 15.0, List.of("Detached"), List.of("Train"),
            2, 500000, 3, 45, false, false,
            List.of(), "rightmove", false
        );
        RankedPropertyResponse scoredEmpty = ollamaService.evaluateAndScoreProperty(
            electricProp, meta, bearsden, transit, emptyCriteriaNormal
        );
        assertEquals(38, scoredEmpty.layoutScore());
        assertTrue(scoredEmpty.matchedCriteria().stream().anyMatch(m -> m.contains("No soft layout rules active")));
    }
}
