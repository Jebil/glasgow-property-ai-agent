package com.glasgow.propertyagent.service.app;

import com.glasgow.propertyagent.service.ai.PropertyEmbeddingService;

import com.glasgow.propertyagent.entity.IndexedPropertyEntity;
import com.glasgow.propertyagent.model.BuyerWorkflowState;
import com.glasgow.propertyagent.model.ExtractedPropertyMetadata;
import com.glasgow.propertyagent.model.RankedPropertyResponse;
import com.glasgow.propertyagent.model.SchoolAndAreaProfile;
import com.glasgow.propertyagent.model.ScottishCostBreakdown;
import com.glasgow.propertyagent.model.SearchFilterRequest;
import com.glasgow.propertyagent.model.SecondCommuteSummary;
import com.glasgow.propertyagent.model.StationProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * REF-02 & Phase 3: Pure deterministic Stage 2 Multi-Criteria Property Scoring Engine.
 * Integrates Dual-Commuter Household scoring (FEAT-04), Scottish Offers-Over/LBTT/HR cash math (FEAT-01),
 * School Catchment & SIMD Neighbourhood profiles (FEAT-03/07), and Home Report Survey alerts (FEAT-06).
 */
@Service
public class PropertyScoringEngine {

    private static final Logger log = LoggerFactory.getLogger(PropertyScoringEngine.class);

    private enum MatchOutcome { FULL_MATCH, PARTIAL_MATCH, NO_MATCH }
    private record RuleEvaluation(MatchOutcome outcome, String explanation) {}

    private static final Pattern COMMUTE_PATTERN = Pattern.compile("\\b(glasgow central|direct train|commute|public transport|subway|bus|target)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern FREQUENCY_PATTERN = Pattern.compile("\\b(frequency|/hr|per hour|services/hr|tph|trains/hr|buses/hr|every \\d+)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern PARK_RIDE_PATTERN = Pattern.compile("\\b(park & ride|park and ride|station parking|free park|p&r)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern STEP_FREE_PATTERN = Pattern.compile("\\b(step-free|step free|category a|lift access)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SCHOOL_CATCHMENT_PATTERN = Pattern.compile(
        "\\b(school catchment|secondary school|high school|top-tier school|top school|good school|bearsden academy|boclair academy|douglas academy|williamwood|woodfarm|mearns castle|eastwood high|st ninian|jordanhill school|hyndland secondary|bishopbriggs academy|lenzie academy|uddingston grammar|gryffe high|shawlands academy)\\b",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern SIMD_NEIGHBOURHOOD_PATTERN = Pattern.compile(
        "\\b(simd|high-amenity|low deprivation|decile|affluent|prime neighbourhood)\\b",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern DOWNSTAIRS_WC_PATTERN = Pattern.compile("\\b(downstairs wc|cloakroom|ground floor wc|ground floor w\\.c|downstairs toilet)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern ENSUITE_PATTERN = Pattern.compile("\\b(en-suite|ensuite|en suite|attached bathroom)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern GARDEN_FLOW_PATTERN = Pattern.compile("\\b(sightline|direct garden access|garden flow|patio doors to garden|french doors to garden)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern EXTENSION_PATTERN = Pattern.compile("\\b(kitchen extension|rear extension|extend outward|outward extension|extension potential)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern ENCLOSED_LAWN_PATTERN = Pattern.compile("\\b(enclosed|child-safe|flat rear lawn|fenced rear garden|private rear lawn)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SOUTH_GARDEN_PATTERN = Pattern.compile("\\b(south-facing|south facing|south-west|sunny rear garden)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern UTILITY_PATTERN = Pattern.compile("\\b(utility room|utility space|dedicated utility)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern STUDY_PATTERN = Pattern.compile("\\b(home office|study room|dedicated study|dedicated office)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern DRIVEWAY_PATTERN = Pattern.compile(
        "\\b(driveway|2\\+ cars|multi-car parking|off-street parking|dedicated parking|private parking|parking space|allocated parking)\\b",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern CUL_DE_SAC_PATTERN = Pattern.compile("\\b(cul-de-sac|cul de sac|quiet cul)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern WALKIN_PATTERN = Pattern.compile("\\b(walk-in|no major diy|turnkey|immaculate condition)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern NEGATION_PREFIX_PATTERN = Pattern.compile("^(?:no|not|without|avoid|exclude)\\s+(.+)$");

    private static final List<String> SPECIFIC_NAMED_SCHOOLS = List.of(
        "bearsden academy", "boclair academy", "douglas academy", "williamwood", "woodfarm",
        "mearns castle", "eastwood high", "st ninian", "jordanhill school", "hyndland secondary",
        "bishopbriggs academy", "lenzie academy", "uddingston grammar", "gryffe high", "shawlands academy",
        "turnbull high", "holyrood secondary", "holy cross", "dalziel high", "calderglen"
    );

    private final ObjectMapper objectMapper;
    private final PropertyEmbeddingService embeddingService;
    private final GlasgowSchoolAndAreaService schoolAndAreaService;
    private final ScottishOfferAndLbttService offerAndLbttService;

    public PropertyScoringEngine(ObjectMapper objectMapper, PropertyEmbeddingService embeddingService) {
        this(
            objectMapper,
            embeddingService,
            new GlasgowSchoolAndAreaService(objectMapper),
            new ScottishOfferAndLbttService()
        );
    }

    @Autowired
    public PropertyScoringEngine(
            ObjectMapper objectMapper,
            PropertyEmbeddingService embeddingService,
            GlasgowSchoolAndAreaService schoolAndAreaService,
            ScottishOfferAndLbttService offerAndLbttService) {
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.embeddingService = embeddingService;
        this.schoolAndAreaService = schoolAndAreaService != null ? schoolAndAreaService : new GlasgowSchoolAndAreaService(this.objectMapper);
        this.offerAndLbttService = offerAndLbttService != null ? offerAndLbttService : new ScottishOfferAndLbttService();
    }

    public RankedPropertyResponse evaluateAndScoreProperty(
            IndexedPropertyEntity entity,
            ExtractedPropertyMetadata metadata,
            StationProfile station,
            StationCommuteService.TransitEvaluation transit,
            SearchFilterRequest filterRequest) {
        double[] coords = new double[]{entity.getLatitude(), entity.getLongitude()};
        return evaluateAndScoreProperty(entity, metadata, station, transit, null, BuyerWorkflowState.empty(), coords, filterRequest);
    }

    public RankedPropertyResponse evaluateAndScoreProperty(
            IndexedPropertyEntity entity,
            ExtractedPropertyMetadata metadata,
            StationProfile station,
            StationCommuteService.TransitEvaluation transit,
            StationCommuteService.TransitEvaluation secondTransit,
            BuyerWorkflowState workflowState,
            double[] resolvedCoords,
            SearchFilterRequest filterRequest) {

        BuyerWorkflowState effectiveWorkflow = workflowState != null ? workflowState : BuyerWorkflowState.empty();
        double lat = (resolvedCoords != null && resolvedCoords.length >= 2 && resolvedCoords[0] != 0.0)
            ? resolvedCoords[0]
            : entity.getLatitude();
        double lon = (resolvedCoords != null && resolvedCoords.length >= 2 && resolvedCoords[1] != 0.0)
            ? resolvedCoords[1]
            : entity.getLongitude();

        SchoolAndAreaProfile schoolAndArea = schoolAndAreaService.resolveProfile(
            entity.getAddress(), entity.getTitle(), lat, lon
        );
        ScottishCostBreakdown costBreakdown = offerAndLbttService.evaluateCostBreakdown(
            entity, schoolAndArea, effectiveWorkflow, filterRequest
        );

        List<String> matched = new ArrayList<>();
        List<String> compromised = new ArrayList<>();

        // 1. Public Transport Score (0 - 25 points): Single Commuter OR Dual-Commuter Household Blend (FEAT-04)
        int primaryCommuteScore = computeSingleCommuteScore(transit);
        int commuteScore = primaryCommuteScore;
        matched.add("Best transit to " + transit.targetDestination() + ": " + transit.transitRouteSummary());

        SecondCommuteSummary secondSummary = null;
        if (secondTransit != null) {
            int secondCommuteScore = computeSingleCommuteScore(secondTransit);
            commuteScore = Math.max(0, Math.min(25, (int) Math.round(primaryCommuteScore * 0.55 + secondCommuteScore * 0.45)));
            secondSummary = new SecondCommuteSummary(
                secondTransit.targetDestination(),
                secondTransit.distanceFromTargetMiles(),
                secondTransit.bestTransitMode(),
                secondTransit.transitStopName(),
                secondTransit.inVehicleMins(),
                secondTransit.doorToTargetMins(),
                secondTransit.servicesPerHourPeak(),
                secondTransit.frequencyLabel(),
                secondTransit.transitRouteSummary()
            );
            int secondLimit = filterRequest.maxSecondCommuteMins() != null ? filterRequest.maxSecondCommuteMins() : 45;
            if (secondTransit.doorToTargetMins() <= secondLimit) {
                matched.add("Partner B transit to " + secondTransit.targetDestination() + ": " + secondTransit.transitRouteSummary() + " (" + secondTransit.doorToTargetMins() + "m)");
            } else {
                compromised.add("Partner B transit to " + secondTransit.targetDestination() + " is " + secondTransit.doorToTargetMins() + "m (exceeds " + secondLimit + "m target)");
            }
        }

        if (filterRequest.requireFreeStationParking() && station.freeParkAndRide()) {
            matched.add("Free Park & Ride at " + station.stationName() + " (" + station.parkAndRideSpaces() + " spaces)");
        }
        if (filterRequest.requireStepFreeCategoryA() && "A".equalsIgnoreCase(station.accessibilityCategory())) {
            matched.add("Category A step-free station access (" + station.stationName() + ")");
        }

        // 2. Dynamic Criteria Evaluation (0 - 60 points)
        List<SearchFilterRequest.CriterionWeight> criteria = filterRequest.dynamicCriteria() == null
            ? List.of()
            : filterRequest.dynamicCriteria();

        int dynamicScore;
        if (criteria.isEmpty()) {
            int basePoints = 38;
            if (Boolean.TRUE.equals(filterRequest.bypassHardFilters())) {
                List<String> preferredTypes = filterRequest.propertyTypes() == null ? List.of() : filterRequest.propertyTypes();
                if (!preferredTypes.isEmpty() && preferredTypes.stream().noneMatch(t -> t.equalsIgnoreCase(entity.getPropertyType()))) {
                    basePoints -= 8;
                    compromised.add("Property type is " + entity.getPropertyType() + " (sidebar prefers " + String.join("/", preferredTypes) + ")");
                }
                if (filterRequest.radiusMiles() > 0 && transit.distanceFromTargetMiles() > filterRequest.radiusMiles()) {
                    basePoints -= 6;
                    compromised.add("Distance " + transit.distanceFromTargetMiles() + " mi exceeds " + filterRequest.radiusMiles() + " mi sidebar radius");
                }
                if (filterRequest.maxDoorToCentralCommuteMins() > 0 && transit.doorToTargetMins() > filterRequest.maxDoorToCentralCommuteMins()) {
                    basePoints -= 6;
                    compromised.add("Transit " + transit.doorToTargetMins() + "m exceeds " + filterRequest.maxDoorToCentralCommuteMins() + "m sidebar limit");
                }
                if (filterRequest.maxPrice() > 0 && entity.getPriceGbp() > filterRequest.maxPrice()) {
                    basePoints -= 8;
                    compromised.add("Price £" + String.format("%,d", entity.getPriceGbp()) + " exceeds £" + String.format("%,d", filterRequest.maxPrice()) + " sidebar max");
                }
                if (filterRequest.maxUpfrontCashGbp() != null && filterRequest.maxUpfrontCashGbp() > 0
                        && costBreakdown.totalUpfrontCashGbp() > filterRequest.maxUpfrontCashGbp()) {
                    basePoints -= 6;
                    compromised.add("Est. upfront cash £" + String.format("%,d", costBreakdown.totalUpfrontCashGbp())
                        + " exceeds £" + String.format("%,d", filterRequest.maxUpfrontCashGbp()) + " cash limit");
                }
                dynamicScore = Math.max(5, Math.min(60, basePoints));
                matched.add("Showing all DB properties — ranked by Public Transport (" + transit.doorToTargetMins() + "m, " + transit.servicesPerHourPeak() + "/hr), Sidebar Fit & Price Value");
            } else {
                dynamicScore = basePoints;
                matched.add("No soft layout rules active — filtered by Sidebar Cutoffs & ranked by Public Transport (" + transit.doorToTargetMins() + "m, " + transit.servicesPerHourPeak() + "/hr) + Price Value");
            }
        } else {
            int rawCriteriaPoints = 25;
            for (SearchFilterRequest.CriterionWeight cw : criteria) {
                if (cw.rule() == null || cw.rule().isBlank()) continue;
                String priority = cw.priority() == null ? "MEDIUM" : cw.priority();
                int matchGain = switch (priority) {
                    case "MUST_HAVE" -> 12;
                    case "HIGH" -> 8;
                    case "MEDIUM" -> 5;
                    default -> 3;
                };
                int missPenalty = switch (priority) {
                    case "MUST_HAVE" -> 14;
                    case "HIGH" -> 7;
                    case "MEDIUM" -> 3;
                    default -> 0;
                };

                RuleEvaluation eval = evaluateSingleCriterion(cw.rule(), entity, metadata, station, transit, schoolAndArea, effectiveWorkflow);
                if (eval.outcome() == MatchOutcome.FULL_MATCH) {
                    rawCriteriaPoints += matchGain;
                    matched.add("[" + priority + "] " + eval.explanation());
                } else if (eval.outcome() == MatchOutcome.PARTIAL_MATCH) {
                    rawCriteriaPoints += Math.max(1, matchGain / 3);
                    compromised.add("[" + priority + " Partial] " + eval.explanation());
                } else {
                    rawCriteriaPoints -= missPenalty;
                    compromised.add("[" + priority + " Missing] " + eval.explanation());
                }
            }
            dynamicScore = Math.max(0, Math.min(60, rawCriteriaPoints));
        }

        // 3. Value, Price-Drop, Home Report Equity & Budget Headroom Score (0 - 15 points)
        int valueScore = 6;
        if (filterRequest.maxPrice() > 0) {
            double savingsRatio = (double) (filterRequest.maxPrice() - entity.getPriceGbp()) / filterRequest.maxPrice();
            int headroomPts = (int) Math.round(Math.max(0.0, Math.min(1.0, savingsRatio * 2.5)) * 7.0);
            valueScore += headroomPts;
            if (savingsRatio >= 0.12) {
                matched.add("Strong budget headroom (£" + String.format("%,d", filterRequest.maxPrice() - entity.getPriceGbp()) + " below max)");
            }
        }
        if (entity.getBedrooms() > filterRequest.minBedrooms()) {
            valueScore = Math.min(15, valueScore + 2);
            matched.add("Extra bedroom capacity (" + entity.getBedrooms() + " beds)");
        }

        Integer prevPrice = entity.getPreviousPriceGbp();
        int priceDrop = (prevPrice != null && prevPrice > entity.getPriceGbp())
            ? (prevPrice - entity.getPriceGbp())
            : 0;
        if (priceDrop > 0) {
            double dropPct = (double) priceDrop / prevPrice;
            int dropBonus = (dropPct >= 0.05 || priceDrop >= 15_000) ? 2 : 1;
            valueScore = Math.min(15, valueScore + dropBonus);
            matched.add("Asking price reduced by £" + String.format("%,d", priceDrop) + " (was £" + String.format("%,d", prevPrice) + ")");
        }
        if (entity.getPriceQualifier() != null && entity.getPriceQualifier().toLowerCase(Locale.ROOT).contains("fixed")) {
            valueScore = Math.min(15, valueScore + 1);
            matched.add("Fixed Price listing (no Offers Over bidding premium)");
        }

        // FEAT-06: Home Report valuation & Category 3 repair inspection signals
        if (costBreakdown.homeReportVerified() && costBreakdown.estimatedHomeReportGbp() > entity.getPriceGbp()) {
            int equityGap = costBreakdown.estimatedHomeReportGbp() - entity.getPriceGbp();
            valueScore = Math.min(15, valueScore + 1);
            matched.add("Home Report £" + String.format("%,d", costBreakdown.estimatedHomeReportGbp())
                + " is £" + String.format("%,d", equityGap) + " above Offers Over asking price");
        }
        if (effectiveWorkflow.hrCategory3Count() != null && effectiveWorkflow.hrCategory3Count() > 0) {
            valueScore = Math.max(1, valueScore - 2);
            compromised.add("Home Report flags " + effectiveWorkflow.hrCategory3Count() + " Category 3 urgent repair item(s)");
        }

        int overallScore = Math.max(0, Math.min(100, commuteScore + dynamicScore + valueScore));
        String verdict = generateVerdict(metadata, transit, secondSummary, schoolAndArea, costBreakdown, overallScore, criteria.size(), matched.size(), compromised.size());

        List<String> allImageUrls = parseStringList(entity.getAllImagesJson(), entity.getThumbnailUrl());
        List<String> allFloorplanUrls = parseStringList(entity.getAllFloorplansJson(), entity.getFloorplanUrl());
        int downloadedCount = entity.getDownloadedImageCount();
        String cardThumbnailUrl = (entity.getThumbnailUrl() != null && !entity.getThumbnailUrl().isBlank())
            ? entity.getThumbnailUrl()
            : (allImageUrls.isEmpty() ? "" : allImageUrls.get(0));

        return new RankedPropertyResponse(
            entity.getListingId(),
            entity.getTitle(),
            entity.getAddress(),
            entity.getPropertyType(),
            entity.getPriceGbp(),
            prevPrice,
            priceDrop,
            entity.getPriceQualifier(),
            entity.getBedrooms(),
            entity.getRightmoveUrl(),
            cardThumbnailUrl,
            allFloorplanUrls.isEmpty() ? entity.getFloorplanUrl() : allFloorplanUrls.get(0),
            allImageUrls,
            allFloorplanUrls,
            downloadedCount,
            "", // N15: Omit bulky raw description in rank list; loaded on-demand via GET /api/properties/{id}
            station.stationName(),
            transit.distanceToStationMiles() > 0 ? transit.distanceToStationMiles() : entity.getDistanceToStationMiles(),
            station,
            entity.getEstimatedDoorToCentralMins() > 0 ? entity.getEstimatedDoorToCentralMins() : transit.doorToTargetMins(),
            transit.targetDestination(),
            transit.distanceFromTargetMiles(),
            transit.bestTransitMode(),
            transit.transitStopName(),
            transit.inVehicleMins(),
            transit.doorToTargetMins(),
            transit.servicesPerHourPeak(),
            transit.frequencyLabel(),
            transit.transitRouteSummary(),
            transit.alternativeTransitSummary(),
            metadata,
            "", // N15: Omit bulky LLM logs in rank list; loaded on-demand via GET /api/properties/{id}
            overallScore,
            commuteScore,
            dynamicScore,
            valueScore,
            matched,
            compromised,
            verdict,
            lat,
            lon,
            costBreakdown,
            schoolAndArea,
            secondSummary,
            effectiveWorkflow
        );
    }

    private static int computeSingleCommuteScore(StationCommuteService.TransitEvaluation transit) {
        int timePts = Math.max(0, 19 - Math.max(0, transit.doorToTargetMins() - 10));
        int freqPts = transit.servicesPerHourPeak() >= 10 ? 6
            : (transit.servicesPerHourPeak() >= 6 ? 4 : (transit.servicesPerHourPeak() >= 4 ? 3 : 1));
        return Math.max(0, Math.min(25, timePts + freqPts));
    }

    private RuleEvaluation evaluateSingleCriterion(
            String rawRule,
            IndexedPropertyEntity entity,
            ExtractedPropertyMetadata metadata,
            StationProfile station,
            StationCommuteService.TransitEvaluation transit,
            SchoolAndAreaProfile schoolAndArea,
            BuyerWorkflowState workflowState) {

        String ruleLower = rawRule.toLowerCase(Locale.ROOT);
        boolean isFlat = entity.getPropertyType() != null &&
            (entity.getPropertyType().equalsIgnoreCase("Flat") || entity.getPropertyType().equalsIgnoreCase("Apartment"));

        // FEAT-03: Secondary school catchment & named school criterion
        if (SCHOOL_CATCHMENT_PATTERN.matcher(ruleLower).find()) {
            String schoolCombined = (schoolAndArea.nonDenomSecondary() + " " + schoolAndArea.denomSecondary()).toLowerCase(Locale.ROOT);
            for (String named : SPECIFIC_NAMED_SCHOOLS) {
                if (ruleLower.contains(named)) {
                    if (schoolCombined.contains(named)) {
                        return new RuleEvaluation(
                            MatchOutcome.FULL_MATCH,
                            schoolAndArea.neighbourhoodName() + " catchment: " + schoolAndArea.nonDenomSecondary() + " / " + schoolAndArea.denomSecondary()
                        );
                    }
                    return new RuleEvaluation(
                        MatchOutcome.NO_MATCH,
                        "Catchment is " + schoolAndArea.nonDenomSecondary() + " (" + schoolAndArea.neighbourhoodName() + ")"
                    );
                }
            }
            if ("TOP_TIER".equalsIgnoreCase(schoolAndArea.attainmentTier())) {
                return new RuleEvaluation(
                    MatchOutcome.FULL_MATCH,
                    "Top-tier secondary catchment (" + schoolAndArea.nonDenomSecondary() + " • ~" + schoolAndArea.higherPassPctApprox() + "% 5+ Highers)"
                );
            } else if ("STRONG".equalsIgnoreCase(schoolAndArea.attainmentTier())) {
                return new RuleEvaluation(
                    MatchOutcome.PARTIAL_MATCH,
                    "Strong secondary catchment (" + schoolAndArea.nonDenomSecondary() + " • ~" + schoolAndArea.higherPassPctApprox() + "% 5+ Highers)"
                );
            }
            return new RuleEvaluation(
                MatchOutcome.NO_MATCH,
                "Catchment rated " + schoolAndArea.attainmentTier() + " (" + schoolAndArea.nonDenomSecondary() + ")"
            );
        }

        // FEAT-07: SIMD neighbourhood profile criterion
        if (SIMD_NEIGHBOURHOOD_PATTERN.matcher(ruleLower).find()) {
            int targetDecile = extractNumberOrDefault(ruleLower, 8);
            if (targetDecile < 1 || targetDecile > 10) targetDecile = 8;
            if (schoolAndArea.simdDecile() >= targetDecile) {
                return new RuleEvaluation(
                    MatchOutcome.FULL_MATCH,
                    schoolAndArea.neighbourhoodName() + " is SIMD Decile " + schoolAndArea.simdDecile() + "/10 (" + schoolAndArea.councilArea() + ")"
                );
            } else if (schoolAndArea.simdDecile() >= targetDecile - 1) {
                return new RuleEvaluation(
                    MatchOutcome.PARTIAL_MATCH,
                    schoolAndArea.neighbourhoodName() + " is SIMD Decile " + schoolAndArea.simdDecile() + "/10"
                );
            }
            return new RuleEvaluation(
                MatchOutcome.NO_MATCH,
                schoolAndArea.neighbourhoodName() + " is SIMD Decile " + schoolAndArea.simdDecile() + "/10 (below " + targetDecile + ")"
            );
        }

        if (FREQUENCY_PATTERN.matcher(ruleLower).find()) {
            int minFreq = extractNumberOrDefault(ruleLower, 4);
            if (transit.servicesPerHourPeak() >= minFreq) {
                return new RuleEvaluation(
                    MatchOutcome.FULL_MATCH,
                    transit.bestTransitMode() + " runs " + transit.frequencyLabel() + " to " + transit.targetDestination()
                );
            }
            return new RuleEvaluation(
                MatchOutcome.NO_MATCH,
                transit.bestTransitMode() + " runs " + transit.servicesPerHourPeak() + "/hr (below " + minFreq + "/hr target)"
            );
        }

        if (COMMUTE_PATTERN.matcher(ruleLower).find()) {
            int limitMins = extractNumberOrDefault(ruleLower, 25);
            if (transit.doorToTargetMins() <= limitMins) {
                return new RuleEvaluation(
                    MatchOutcome.FULL_MATCH,
                    transit.bestTransitMode() + " to " + transit.targetDestination() + " is " + transit.doorToTargetMins() + "m door-to-target (" + transit.inVehicleMins() + "m ride, " + transit.servicesPerHourPeak() + "/hr)"
                );
            } else if (transit.inVehicleMins() <= limitMins) {
                return new RuleEvaluation(
                    MatchOutcome.PARTIAL_MATCH,
                    transit.bestTransitMode() + " in-vehicle time is " + transit.inVehicleMins() + "m (" + transit.doorToTargetMins() + "m total door-to-" + transit.targetDestination() + ")"
                );
            }
            return new RuleEvaluation(
                MatchOutcome.NO_MATCH,
                "Best transit (" + transit.bestTransitMode() + ") to " + transit.targetDestination() + " takes " + transit.doorToTargetMins() + "m"
            );
        }

        if (PARK_RIDE_PATTERN.matcher(ruleLower).find()) {
            if (station.freeParkAndRide()) {
                return new RuleEvaluation(
                    MatchOutcome.FULL_MATCH,
                    "Free Park & Ride at " + station.stationName() + " (" + station.parkAndRideSpaces() + " spaces)"
                );
            }
            return new RuleEvaluation(MatchOutcome.NO_MATCH, station.stationName() + " has paid parking only");
        }

        if (STEP_FREE_PATTERN.matcher(ruleLower).find()) {
            if ("A".equalsIgnoreCase(station.accessibilityCategory())) {
                return new RuleEvaluation(MatchOutcome.FULL_MATCH, station.stationName() + " has Category A step-free lift access");
            }
            return new RuleEvaluation(MatchOutcome.NO_MATCH, station.stationName() + " is Category " + station.accessibilityCategory() + " (no lift)");
        }

        if (DOWNSTAIRS_WC_PATTERN.matcher(ruleLower).find()) {
            if (isFlat) {
                String descLower = entity.getRawDescription() != null ? entity.getRawDescription().toLowerCase() : "";
                if (!descLower.contains("duplex") && !descLower.contains("split level") && !descLower.contains("maisonette")) {
                    return new RuleEvaluation(MatchOutcome.NO_MATCH, "Apartment on single floor (no separate ground floor WC)");
                }
            }
            if (Boolean.TRUE.equals(metadata.hasDownstairsWc())) {
                return new RuleEvaluation(MatchOutcome.FULL_MATCH, "Downstairs WC / cloakroom confirmed");
            }
            return new RuleEvaluation(MatchOutcome.NO_MATCH, "No downstairs WC mentioned in listing");
        }

        if (ENSUITE_PATTERN.matcher(ruleLower).find()) {
            if (Boolean.TRUE.equals(metadata.hasMasterEnsuite())) {
                return new RuleEvaluation(MatchOutcome.FULL_MATCH, "Master bedroom en-suite confirmed");
            }
            return new RuleEvaluation(MatchOutcome.NO_MATCH, "No master en-suite mentioned in listing");
        }

        if (GARDEN_FLOW_PATTERN.matcher(ruleLower).find()) {
            if (isFlat) {
                return new RuleEvaluation(MatchOutcome.NO_MATCH, "Flat has shared/communal grounds rather than direct lounge-to-garden flow");
            }
            if (Boolean.TRUE.equals(metadata.directGardenAccessFromLiving())) {
                return new RuleEvaluation(MatchOutcome.FULL_MATCH, "Direct living room to garden flow (" + metadata.gardenSightlineFromSofa() + ")");
            } else if (metadata.gardenSightlineFromSofa() != null && metadata.gardenSightlineFromSofa().toLowerCase().contains("partial")) {
                return new RuleEvaluation(MatchOutcome.PARTIAL_MATCH, "Garden access via Kitchen/Diner patio doors rather than main Lounge");
            }
            return new RuleEvaluation(MatchOutcome.NO_MATCH, "No direct living room sightline to rear garden");
        }

        if (EXTENSION_PATTERN.matcher(ruleLower).find()) {
            if (isFlat) {
                return new RuleEvaluation(MatchOutcome.NO_MATCH, "Flat layout does not permit outward rear ground extension");
            }
            if (Boolean.TRUE.equals(metadata.kitchenExtensionPotentialToRear())) {
                return new RuleEvaluation(MatchOutcome.FULL_MATCH, "Generous plot / extension potential mentioned in listing");
            }
            return new RuleEvaluation(MatchOutcome.NO_MATCH, "Outward rear extension potential not mentioned in listing");
        }

        if (ENCLOSED_LAWN_PATTERN.matcher(ruleLower).find()) {
            if (isFlat) {
                String descLower = entity.getRawDescription() != null ? entity.getRawDescription().toLowerCase() : "";
                if (!descLower.contains("private garden") && !descLower.contains("own garden")) {
                    return new RuleEvaluation(MatchOutcome.PARTIAL_MATCH, "Communal resident grounds rather than private enclosed lawn");
                }
            }
            if (Boolean.TRUE.equals(metadata.gardenEnclosedForKids())) {
                return new RuleEvaluation(MatchOutcome.FULL_MATCH, "Enclosed / private rear lawn confirmed");
            }
            return new RuleEvaluation(MatchOutcome.NO_MATCH, "Enclosed child-safe lawn not explicitly confirmed");
        }

        if (SOUTH_GARDEN_PATTERN.matcher(ruleLower).find()) {
            if (metadata.gardenOrientation() != null && metadata.gardenOrientation().toLowerCase().contains("south")) {
                return new RuleEvaluation(MatchOutcome.FULL_MATCH, metadata.gardenOrientation() + " facing rear garden");
            }
            return new RuleEvaluation(MatchOutcome.NO_MATCH, "South-facing orientation not mentioned");
        }

        if (UTILITY_PATTERN.matcher(ruleLower).find()) {
            if (Boolean.TRUE.equals(metadata.hasUtilityRoom())) {
                return new RuleEvaluation(MatchOutcome.FULL_MATCH, "Dedicated utility room confirmed");
            }
            return new RuleEvaluation(MatchOutcome.NO_MATCH, "No dedicated utility room mentioned");
        }

        if (STUDY_PATTERN.matcher(ruleLower).find()) {
            if (Boolean.TRUE.equals(metadata.hasDedicatedStudyOrOffice())) {
                return new RuleEvaluation(MatchOutcome.FULL_MATCH, "Dedicated study / home office space confirmed");
            }
            return new RuleEvaluation(MatchOutcome.NO_MATCH, "No dedicated study/office room mentioned");
        }

        if (DRIVEWAY_PATTERN.matcher(ruleLower).find()) {
            boolean multiCarRequired = ruleLower.contains("2+") || ruleLower.contains("multi-car")
                || ruleLower.contains("two car") || ruleLower.contains("2 car") || ruleLower.contains("multiple cars");
            boolean hasGarage = metadata.garageType() != null && !metadata.garageType().isBlank() && !"None".equalsIgnoreCase(metadata.garageType());
            int capacity = metadata.drivewayCarCapacity() != null ? metadata.drivewayCarCapacity() : (hasGarage ? 1 : 0);

            if (isFlat) {
                String descLower = entity.getRawDescription() != null ? entity.getRawDescription().toLowerCase() : "";
                boolean hasAllocated = descLower.contains("allocated parking") || descLower.contains("private parking")
                    || descLower.contains("designated parking") || descLower.contains("residents parking")
                    || descLower.contains("resident parking") || descLower.contains("allocated space")
                    || hasGarage || capacity >= 1;
                if (hasAllocated && !multiCarRequired) {
                    return new RuleEvaluation(MatchOutcome.FULL_MATCH, "Allocated / private resident parking confirmed for flat");
                }
                return new RuleEvaluation(MatchOutcome.PARTIAL_MATCH, "Flat includes communal / on-street resident parking rather than private multi-car driveway");
            }

            if (multiCarRequired) {
                if (capacity >= 2) {
                    return new RuleEvaluation(MatchOutcome.FULL_MATCH, "Private multi-car driveway (" + capacity + "+ cars)");
                }
                return new RuleEvaluation(MatchOutcome.NO_MATCH, "Multi-car driveway (2+ cars) not confirmed");
            } else {
                if (capacity >= 1) {
                    String detail = capacity > 1
                        ? ("driveway for " + capacity + " cars")
                        : (hasGarage ? (metadata.garageType() + " garage & driveway") : "private driveway / dedicated parking");
                    return new RuleEvaluation(MatchOutcome.FULL_MATCH, "Off-street parking confirmed (" + detail + ")");
                }
                String descLower = entity.getRawDescription() != null ? entity.getRawDescription().toLowerCase() : "";
                if (descLower.contains("driveway") || descLower.contains("allocated parking") || descLower.contains("off-street parking") || descLower.contains("off street parking") || hasGarage) {
                    return new RuleEvaluation(MatchOutcome.FULL_MATCH, "Off-street parking / driveway confirmed in listing");
                }
                return new RuleEvaluation(MatchOutcome.NO_MATCH, "Dedicated off-street parking or driveway not confirmed");
            }
        }

        if (CUL_DE_SAC_PATTERN.matcher(ruleLower).find()) {
            boolean inDesc = entity.getRawDescription() != null && entity.getRawDescription().toLowerCase().contains("cul-de-sac");
            boolean inHighlights = metadata.extractedHighlights() != null && metadata.extractedHighlights().stream().anyMatch(h -> h.toLowerCase().contains("cul-de-sac"));
            if (inDesc || inHighlights) {
                return new RuleEvaluation(MatchOutcome.FULL_MATCH, "Quiet cul-de-sac setting confirmed");
            }
            return new RuleEvaluation(MatchOutcome.NO_MATCH, "Not listed as a cul-de-sac");
        }

        if (WALKIN_PATTERN.matcher(ruleLower).find()) {
            if (workflowState != null && workflowState.hrCategory3Count() != null && workflowState.hrCategory3Count() > 0) {
                return new RuleEvaluation(
                    MatchOutcome.NO_MATCH,
                    "Home Report flags " + workflowState.hrCategory3Count() + " Category 3 urgent repair item(s)"
                );
            }
            if (metadata.interiorCondition() != null && metadata.interiorCondition().toLowerCase().contains("walk-in")) {
                return new RuleEvaluation(MatchOutcome.FULL_MATCH, "Walk-in modern interior condition");
            }
            return new RuleEvaluation(MatchOutcome.PARTIAL_MATCH, "Condition rated as: " + metadata.interiorCondition());
        }

        String trimmedRule = ruleLower.trim();
        if (trimmedRule.contains("no onward chain") || trimmedRule.contains("no chain") || trimmedRule.contains("chain free")) {
            String combinedText = buildCombinedSearchText(entity, metadata);
            if (combinedText.contains("no onward chain") || combinedText.contains("no chain") || combinedText.contains("chain free") || combinedText.contains("chain-free")) {
                return new RuleEvaluation(MatchOutcome.FULL_MATCH, "Chain-free sale confirmed in listing");
            }
            return new RuleEvaluation(MatchOutcome.NO_MATCH, "No chain-free status mentioned in listing");
        }

        Matcher negMatcher = NEGATION_PREFIX_PATTERN.matcher(trimmedRule);
        if (negMatcher.find()) {
            String negatedTarget = negMatcher.group(1).trim();
            if (!negatedTarget.isEmpty()) {
                return evaluateNegatedCriterion(rawRule, negatedTarget, entity, metadata);
            }
        }

        if (embeddingService != null && entity.getListingId() != null) {
            try {
                Map<String, PropertyEmbeddingService.PropertyEmbeddingMatch> batchMatches =
                        embeddingService.findBestMatchesForCriteriaBatch(rawRule);
                PropertyEmbeddingService.PropertyEmbeddingMatch match = batchMatches.get(entity.getListingId());
                if (match != null && match.similarity() >= 0.62) {
                    String imgTag = (match.imageRef() != null && !match.imageRef().isBlank()) ? " (" + match.imageRef() + ")" : "";
                    String cleanSnippet = match.chunkText().replace("Visual Photo Inspection: ", "").replace("Architectural Floorplan Inspection: ", "").replace("search_document: ", "").trim();
                    if (cleanSnippet.length() > 140) cleanSnippet = cleanSnippet.substring(0, 137) + "...";
                    return new RuleEvaluation(MatchOutcome.FULL_MATCH,
                            "AI Semantic Match " + (int) Math.round(match.similarity() * 100) + "%" + imgTag + ": " + cleanSnippet);
                } else if (match != null && match.similarity() >= 0.52) {
                    String cleanSnippet = match.chunkText().replace("Visual Photo Inspection: ", "").replace("Architectural Floorplan Inspection: ", "").replace("search_document: ", "").trim();
                    if (cleanSnippet.length() > 140) cleanSnippet = cleanSnippet.substring(0, 137) + "...";
                    return new RuleEvaluation(MatchOutcome.PARTIAL_MATCH,
                            "AI Partial Match " + (int) Math.round(match.similarity() * 100) + "%: " + cleanSnippet);
                }
            } catch (Exception matchEx) {
                log.debug("Semantic matching fallback for {}: {}", entity.getListingId(), matchEx.getMessage());
            }
        }

        boolean matchedCustom = checkCustomRule(ruleLower, entity, metadata);
        if (matchedCustom) {
            return new RuleEvaluation(MatchOutcome.FULL_MATCH, "Matches criterion: " + rawRule);
        }
        return new RuleEvaluation(MatchOutcome.NO_MATCH, "Does not mention: " + rawRule);
    }

    private RuleEvaluation evaluateNegatedCriterion(
            String rawRule,
            String negatedTarget,
            IndexedPropertyEntity entity,
            ExtractedPropertyMetadata metadata) {
        String combined = buildCombinedSearchText(entity, metadata);

        if (combined.contains("no " + negatedTarget)
                || combined.contains("not " + negatedTarget)
                || combined.contains("without " + negatedTarget)
                || combined.contains("free from " + negatedTarget)) {
            return new RuleEvaluation(MatchOutcome.FULL_MATCH, "Listing explicitly confirms: " + rawRule);
        }

        if (negatedTarget.contains("electric") && negatedTarget.contains("heat")) {
            String heat = metadata.heatingType() != null ? metadata.heatingType().toLowerCase() : "";
            if (heat.contains("electric") || combined.contains("electric heating") || combined.contains("storage heater") || combined.contains("panel heater")) {
                return new RuleEvaluation(MatchOutcome.NO_MATCH, "Excluded feature detected: electric heating");
            }
            return new RuleEvaluation(MatchOutcome.FULL_MATCH, "Confirmed free of electric heating (" + (metadata.heatingType() != null ? metadata.heatingType() : "Gas/Other") + ")");
        }

        if (combined.contains(negatedTarget)) {
            return new RuleEvaluation(MatchOutcome.NO_MATCH, "Excluded feature detected: " + negatedTarget);
        }

        String[] keywords = negatedTarget.replaceAll("[^a-z0-9 ]", " ").trim().split("\\s+");
        List<String> significant = new ArrayList<>();
        for (String kw : keywords) {
            if (kw.length() > 2 && !Set.of("the", "and", "for", "with", "has", "from", "into", "near", "any").contains(kw)) {
                significant.add(kw);
            }
        }
        if (!significant.isEmpty()) {
            long hits = significant.stream().filter(combined::contains).count();
            int requiredHits = significant.size() <= 2 ? significant.size() : (int) Math.ceil(significant.size() * 0.8);
            if (hits >= requiredHits) {
                return new RuleEvaluation(MatchOutcome.NO_MATCH, "Excluded feature detected: " + negatedTarget);
            }
        }
        return new RuleEvaluation(MatchOutcome.FULL_MATCH, "Confirmed free of excluded feature: " + negatedTarget);
    }

    private String buildCombinedSearchText(IndexedPropertyEntity entity, ExtractedPropertyMetadata metadata) {
        String highlightsStr = metadata.extractedHighlights() == null ? "" : String.join(" ", metadata.extractedHighlights());
        return ((entity.getRawDescription() != null ? entity.getRawDescription() : "")
                + " " + (entity.getTitle() != null ? entity.getTitle() : "")
                + " " + highlightsStr
                + " " + (metadata.gardenOrientation() != null ? metadata.gardenOrientation() : "")
                + " " + (metadata.garageType() != null ? metadata.garageType() : "")
                + " " + (metadata.interiorCondition() != null ? metadata.interiorCondition() : "")
                + " " + (metadata.heatingType() != null ? metadata.heatingType() : "")
                + " epc " + (metadata.epcRating() != null ? metadata.epcRating() : "")).toLowerCase();
    }

    private int extractNumberOrDefault(String text, int defaultVal) {
        if (text == null || text.isBlank()) return defaultVal;
        Matcher m1 = Pattern.compile("(?:<=|under|within|max|at most|less than)\\s*(\\d+)").matcher(text);
        if (m1.find()) return Integer.parseInt(m1.group(1));

        Matcher m2 = Pattern.compile("(?:>=|at least|min|minimum)\\s*(\\d+)").matcher(text);
        if (m2.find()) return Integer.parseInt(m2.group(1));

        Matcher m3 = Pattern.compile("(\\d+)\\s*(?:mins|min|m\\b|/hr|services|tph)").matcher(text);
        if (m3.find()) return Integer.parseInt(m3.group(1));

        String digits = text.replaceAll("[^0-9]", " ").trim();
        if (digits.isBlank()) return defaultVal;
        try {
            return Integer.parseInt(digits.split("\\s+")[0]);
        } catch (Exception e) {
            return defaultVal;
        }
    }

    private boolean checkCustomRule(String ruleLower, IndexedPropertyEntity entity, ExtractedPropertyMetadata metadata) {
        String combined = buildCombinedSearchText(entity, metadata);
        if (combined.contains(ruleLower)) {
            return true;
        }
        String[] keywords = ruleLower.replaceAll("[^a-z0-9 ]", " ").trim().split("\\s+");
        int meaningfulCount = 0;
        int hits = 0;
        for (String kw : keywords) {
            if (kw.length() > 2 && !Set.of("the", "and", "for", "with", "has", "from", "into", "near").contains(kw)) {
                meaningfulCount++;
                if (combined.contains(kw)) hits++;
            }
        }
        return meaningfulCount > 0 && hits >= Math.ceil(meaningfulCount * 0.6);
    }

    private String generateVerdict(
            ExtractedPropertyMetadata metadata,
            StationCommuteService.TransitEvaluation transit,
            SecondCommuteSummary secondSummary,
            SchoolAndAreaProfile schoolAndArea,
            ScottishCostBreakdown costBreakdown,
            int overallScore,
            int activeCriteriaCount,
            int matchedCount,
            int compromisedCount) {

        String partnerBSegment = secondSummary != null
            ? String.format(" Partner B to %s: %s (%dm).", secondSummary.targetDestination(), secondSummary.bestTransitMode(), secondSummary.doorToTargetMins())
            : "";

        return String.format(
            "Scored %d/100 across %d active criteria (%d matched, %d compromises). Best transit to %s: %s (%dm door-to-target, %s).%s Catchment: %s (SIMD %d/10). Est. upfront cash £%,d (closing ~£%,d, LBTT £%,d). %s",
            overallScore,
            activeCriteriaCount,
            matchedCount,
            compromisedCount,
            transit.targetDestination(),
            transit.bestTransitMode(),
            transit.doorToTargetMins(),
            transit.frequencyLabel(),
            partnerBSegment,
            schoolAndArea.nonDenomSecondary(),
            schoolAndArea.simdDecile(),
            costBreakdown.totalUpfrontCashGbp(),
            costBreakdown.estimatedClosingPriceGbp(),
            costBreakdown.lbttGbp(),
            Boolean.TRUE.equals(metadata.directGardenAccessFromLiving())
                ? "Lounge/living area opens directly to the rear garden."
                : "Garden access is via the kitchen/diner or rear hallway."
        );
    }

    private List<String> parseStringList(String json, String fallbackSingle) {
        if (json != null && !json.isBlank()) {
            try {
                List<String> parsed = objectMapper.readValue(json, new TypeReference<List<String>>() {});
                if (parsed != null && !parsed.isEmpty()) {
                    return parsed;
                }
            } catch (Exception ignored) {}
        }
        return (fallbackSingle != null && !fallbackSingle.isBlank()) ? List.of(fallbackSingle) : List.of();
    }
}
