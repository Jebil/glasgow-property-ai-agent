package com.glasgow.propertyagent.service.app;

import com.glasgow.propertyagent.entity.IndexedPropertyEntity;
import com.glasgow.propertyagent.model.StationProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.*;

/**
 * Full Strathclyde / Greater Glasgow Multi-Modal Public Transport Engine (Zero-Key):
 *  - ~115 ScotRail stations across all 7 Strathclyde commuter rail corridors (published timetable mins, peak headways, P&R spaces, ORR step-free categories)
 *  - All 15 SPT Glasgow Subway stations (exact Inner/Outer Circle loop position 0..14 & 4-min peak headway)
 *  - Live OpenStreetMap Overpass / UK NaPTAN bus stop, walking distance & route number integration (cached locally)
 */
@Service
public class StationCommuteService {

    public static final double GLASGOW_CENTRAL_LAT = 55.8591;
    public static final double GLASGOW_CENTRAL_LON = -4.2581;

    private final OsmOverpassTransitClient osmTransitClient;

    private final Map<String, StationProfile> stationRegistry = new LinkedHashMap<>();
    private final Map<String, double[]> stationCoordinates = new LinkedHashMap<>();
    private final Map<String, String> stationCorridorGroup = new LinkedHashMap<>();
    private final Map<String, Boolean> stationTerminatesQueenStHigh = new LinkedHashMap<>();
    private final Map<String, double[]> centerLocationCoordinates = new LinkedHashMap<>();
    private final Map<String, SubwayHub> subwayRegistry = new LinkedHashMap<>();
    private List<ScotRailStationConfig> scotrailStationConfigs = List.of();
    private List<SubwayHub> subwayHubList = List.of();

    public record SubwayHub(
        String name,
        double lat,
        double lon,
        boolean hasParkAndRide,
        int circleIndex // 0..14 around the 15-station SPT Subway loop (24 mins full circle, ~1.6 mins/station)
    ) {}

    public record ModeOption(
        String mode,               // "Train", "Subway", "Train + Subway", "Bus"
        String stopName,
        int inVehicleMins,
        int doorToTargetMins,
        int servicesPerHourPeak,
        String frequencyLabel,
        String routeSummary,
        double effectiveGeneralizedMins
    ) {}

    public record TransitEvaluation(
        String targetDestination,
        double distanceFromTargetMiles,
        String bestTransitMode,
        String transitStopName,
        int inVehicleMins,
        int doorToTargetMins,
        int servicesPerHourPeak,
        String frequencyLabel,
        String transitRouteSummary,
        String alternativeTransitSummary,
        List<ModeOption> allOptions,
        StationProfile bestStation,
        double distanceToStationMiles
    ) {
        public TransitEvaluation(
            String targetDestination,
            double distanceFromTargetMiles,
            String bestTransitMode,
            String transitStopName,
            int inVehicleMins,
            int doorToTargetMins,
            int servicesPerHourPeak,
            String frequencyLabel,
            String transitRouteSummary,
            String alternativeTransitSummary,
            List<ModeOption> allOptions
        ) {
            this(targetDestination, distanceFromTargetMiles, bestTransitMode, transitStopName,
                 inVehicleMins, doorToTargetMins, servicesPerHourPeak, frequencyLabel,
                 transitRouteSummary, alternativeTransitSummary, allOptions, null, 0.0);
        }
    }

    public record StationDist(StationProfile station, double distMiles) {}

    public record EvaluatedTrain(
        ModeOption modeOption,
        StationProfile bestStation,
        double distanceToStationMiles
    ) {}

    private static final Logger log = LoggerFactory.getLogger(StationCommuteService.class);
    private static final String TRANSIT_NETWORK_RESOURCE = "/data/glasgow-transit-network.json";

    public record ScotRailStationConfig(
        String name,
        String corridor,
        String corridorGroup,
        boolean queenStPrimary,
        int fastestMins,
        int avgMins,
        int tphPeak,
        boolean freePr,
        int prSpaces,
        String accessCat,
        String notes,
        double lat,
        double lon
    ) {}

    public record TransitNetworkDataset(
        List<ScotRailStationConfig> scotrailStations,
        List<SubwayHub> subwayHubs,
        Map<String, double[]> additionalLocations
    ) {}

    public record NearestStationMatch(StationProfile station, double distanceToStationMiles, double distanceFromCentralMiles) {}

    public StationCommuteService(OsmOverpassTransitClient osmTransitClient) {
        this(osmTransitClient, new ObjectMapper());
    }

    @Autowired
    public StationCommuteService(OsmOverpassTransitClient osmTransitClient, ObjectMapper objectMapper) {
        this.osmTransitClient = osmTransitClient;
        loadTransitNetworkDataset(objectMapper != null ? objectMapper : new ObjectMapper());
    }

    private void loadTransitNetworkDataset(ObjectMapper mapper) {
        try (InputStream is = StationCommuteService.class.getResourceAsStream(TRANSIT_NETWORK_RESOURCE)) {
            if (is == null) {
                throw new IllegalStateException("Missing transit dataset resource: " + TRANSIT_NETWORK_RESOURCE);
            }
            TransitNetworkDataset dataset = mapper.readValue(is, TransitNetworkDataset.class);
            if (dataset.scotrailStations() != null) {
                this.scotrailStationConfigs = Collections.unmodifiableList(dataset.scotrailStations());
                for (ScotRailStationConfig s : dataset.scotrailStations()) {
                    reg(s.name(), s.corridor(), s.corridorGroup(), s.queenStPrimary(),
                        s.fastestMins(), s.avgMins(), s.tphPeak(), s.freePr(),
                        s.prSpaces(), s.accessCat(), s.notes(), s.lat(), s.lon());
                }
            }
            if (dataset.subwayHubs() != null) {
                List<SubwayHub> sortedSubway = new ArrayList<>(dataset.subwayHubs());
                sortedSubway.sort(Comparator.comparingInt(SubwayHub::circleIndex));
                this.subwayHubList = Collections.unmodifiableList(sortedSubway);
                for (SubwayHub hub : sortedSubway) {
                    registerSubway(hub);
                }
            }
            if (dataset.additionalLocations() != null) {
                dataset.additionalLocations().forEach((k, coords) -> {
                    if (k != null && coords != null && coords.length >= 2) {
                        centerLocationCoordinates.put(k.toLowerCase(Locale.ROOT).trim(), coords);
                    }
                });
            }
            log.info("Loaded Strathclyde transit network dataset: {} ScotRail stations, {} Subway hubs, {} total destination aliases",
                stationRegistry.size(), subwayRegistry.size(), centerLocationCoordinates.size());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load " + TRANSIT_NETWORK_RESOURCE + ": " + e.getMessage(), e);
        }
    }

    private void reg(
            String name,
            String corridor,
            String corridorGroup,
            boolean queenStPrimary,
            int fastestMins,
            int avgMins,
            int tphPeak,
            boolean freePr,
            int prSpaces,
            String accessCat,
            String notes,
            double lat,
            double lon) {
        StationProfile profile = new StationProfile(name, corridor, fastestMins, avgMins, tphPeak, freePr, prSpaces, accessCat, notes);
        String key = name.toLowerCase();
        stationRegistry.put(key, profile);
        stationCoordinates.put(key, new double[]{lat, lon});
        stationCorridorGroup.put(key, corridorGroup);
        stationTerminatesQueenStHigh.put(key, queenStPrimary);
        centerLocationCoordinates.put(key, new double[]{lat, lon});
    }

    private void registerSubway(SubwayHub hub) {
        String key = hub.name().toLowerCase();
        subwayRegistry.put(key, hub);
        centerLocationCoordinates.put(key, new double[]{hub.lat(), hub.lon()});
        // Also register clean short name (e.g. "ibrox", "cessnock", "govan")
        String shortKey = key.replaceAll("\\(.*?\\)", "").replace("subway", "").replace("p&r", "").replace("rail &", "").replace("hub", "").trim();
        if (!shortKey.isBlank()) {
            centerLocationCoordinates.putIfAbsent(shortKey, new double[]{hub.lat(), hub.lon()});
        }
    }

    public Collection<StationProfile> getAllStations() {
        return stationRegistry.values();
    }

    public List<ScotRailStationConfig> getScotRailStationConfigs() {
        return scotrailStationConfigs;
    }

    public List<SubwayHub> getSubwayHubs() {
        return subwayHubList;
    }

    public StationProfile resolveStation(String rawStationName) {
        if (rawStationName == null || rawStationName.isBlank()) {
            return stationRegistry.get("rutherglen");
        }
        String normalized = rawStationName.toLowerCase().trim();
        StationProfile exact = stationRegistry.get(normalized);
        if (exact != null) return exact;

        // Match longest key contained in rawStationName (so "hamilton west" beats "hamilton")
        String bestKey = null;
        for (String key : stationRegistry.keySet()) {
            if (normalized.contains(key) && (bestKey == null || key.length() > bestKey.length())) {
                bestKey = key;
            }
        }
        if (bestKey != null) {
            return stationRegistry.get(bestKey);
        }
        return new StationProfile(rawStationName, "Greater Glasgow ScotRail Network", 25, 30, 2, false, 0, "B", "ScotRail regional / suburban stop");
    }

    public double[] resolveCenterCoordinates(String centerLocation) {
        if (centerLocation == null || centerLocation.isBlank()) {
            return new double[]{GLASGOW_CENTRAL_LAT, GLASGOW_CENTRAL_LON};
        }
        String lower = centerLocation.toLowerCase().trim();
        double[] exact = centerLocationCoordinates.get(lower);
        if (exact != null) return exact;

        // Match longest key contained in centerLocation (so "hamilton west" beats "hamilton")
        String bestKey = null;
        for (String key : centerLocationCoordinates.keySet()) {
            if (lower.contains(key) && (bestKey == null || key.length() > bestKey.length())) {
                bestKey = key;
            }
        }
        if (bestKey != null) {
            return centerLocationCoordinates.get(bestKey);
        }
        return new double[]{GLASGOW_CENTRAL_LAT, GLASGOW_CENTRAL_LON};
    }

    public double[] resolvePropertyCoordinates(IndexedPropertyEntity entity) {
        double propLat = entity.getLatitude();
        double propLon = entity.getLongitude();
        if (propLat != 0.0 && propLon != 0.0) {
            return new double[]{propLat, propLon};
        }
        String stKey = entity.getNearestStationName() == null ? "" : entity.getNearestStationName().toLowerCase();
        double[] stCoords = stationCoordinates.get(stKey);
        if (stCoords != null) {
            return stCoords;
        }
        for (Map.Entry<String, double[]> entry : stationCoordinates.entrySet()) {
            if (stKey.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return new double[]{0.0, 0.0};
    }

    public double calculateDistanceFromCenterMiles(String centerLocation, IndexedPropertyEntity entity) {
        double[] centerCoords = resolveCenterCoordinates(centerLocation);
        double[] propCoords = resolvePropertyCoordinates(entity);
        if (propCoords[0] == 0.0 || propCoords[1] == 0.0) {
            return 999.0;
        }
        return roundOneDecimal(haversineMiles(centerCoords[0], centerCoords[1], propCoords[0], propCoords[1]));
    }

    public double calculateDistanceFromCenterMiles(String centerLocation, double lat, double lon) {
        if (lat == 0.0 || lon == 0.0) {
            return 999.0;
        }
        double[] centerCoords = resolveCenterCoordinates(centerLocation);
        return roundOneDecimal(haversineMiles(centerCoords[0], centerCoords[1], lat, lon));
    }

    /**
     * Evaluates Train (~115 ScotRail stations), Subway (all 15 SPT Subway stations + Rail/Subway interchanges),
     * and Bus (OpenStreetMap Overpass / UK NaPTAN stops & routes) to the user's target station or location.
     */
    public TransitEvaluation evaluateBestPublicTransport(
            IndexedPropertyEntity entity,
            StationProfile nearestRail,
            String targetLocation,
            List<String> allowedModes) {

        String targetLabel = (targetLocation == null || targetLocation.isBlank())
            ? "Glasgow Central"
            : targetLocation.trim();
        String targetLower = targetLabel.toLowerCase();

        double[] propCoords = resolvePropertyCoordinates(entity);
        double[] targetCoords = resolveCenterCoordinates(targetLabel);
        double distToTargetMiles = roundOneDecimal(haversineMiles(propCoords[0], propCoords[1], targetCoords[0], targetCoords[1]));

        boolean allowTrain = allowedModes == null || allowedModes.isEmpty()
            || allowedModes.stream().anyMatch(m -> m.equalsIgnoreCase("Train"));
        boolean allowSubway = allowedModes == null || allowedModes.isEmpty()
            || allowedModes.stream().anyMatch(m -> m.equalsIgnoreCase("Subway"));
        boolean allowBus = allowedModes == null || allowedModes.isEmpty()
            || allowedModes.stream().anyMatch(m -> m.equalsIgnoreCase("Bus"));

        // Evaluate optimal ScotRail candidate station within driving / P&R radius (~3.5 mi), not merely the closest halt
        EvaluatedTrain evaluatedTrain = evaluateTrainOption(entity, nearestRail, targetLabel, targetLower, targetCoords);
        StationProfile bestStation = evaluatedTrain.bestStation();
        double bestStationDist = evaluatedTrain.distanceToStationMiles();

        List<ModeOption> options = new ArrayList<>();

        if (allowTrain) {
            options.add(evaluatedTrain.modeOption());
        }
        if (allowSubway) {
            options.add(evaluateSubwayOption(entity, bestStation, bestStationDist, propCoords, targetLabel, targetLower, targetCoords));
        }
        if (allowBus) {
            options.add(evaluateBusOption(entity, propCoords, targetLabel, targetLower, distToTargetMiles));
        }
        if (options.isEmpty()) {
            options.add(evaluatedTrain.modeOption());
        }

        options.sort(Comparator.comparingDouble(ModeOption::effectiveGeneralizedMins));
        ModeOption best = options.get(0);

        List<String> altSummaries = new ArrayList<>();
        for (int i = 1; i < options.size(); i++) {
            ModeOption alt = options.get(i);
            altSummaries.add(modeIcon(alt.mode()) + " " + alt.mode() + ": " + alt.doorToTargetMins() + "m (" + alt.servicesPerHourPeak() + "/hr)");
        }
        String altText = altSummaries.isEmpty() ? "Single mode filter active" : String.join(" • ", altSummaries);

        return new TransitEvaluation(
            targetLabel,
            distToTargetMiles,
            best.mode(),
            best.stopName(),
            best.inVehicleMins(),
            best.doorToTargetMins(),
            best.servicesPerHourPeak(),
            best.frequencyLabel(),
            best.routeSummary(),
            altText,
            options,
            bestStation,
            bestStationDist
        );
    }

    public List<StationDist> findNearbyCandidateStations(double lat, double lon, double maxRadiusMiles, int maxCount) {
        if (lat == 0.0 || lon == 0.0) {
            return List.of();
        }
        List<StationDist> allByDist = new ArrayList<>();
        for (Map.Entry<String, double[]> entry : stationCoordinates.entrySet()) {
            double[] c = entry.getValue();
            double d = roundOneDecimal(Math.max(0.1, haversineMiles(lat, lon, c[0], c[1])));
            StationProfile profile = stationRegistry.get(entry.getKey());
            if (profile != null) {
                allByDist.add(new StationDist(profile, d));
            }
        }
        allByDist.sort(Comparator.comparingDouble(StationDist::distMiles));
        if (allByDist.isEmpty()) {
            return List.of();
        }

        List<StationDist> candidates = new ArrayList<>();
        candidates.add(allByDist.get(0));
        for (int i = 1; i < allByDist.size() && candidates.size() < maxCount; i++) {
            StationDist sd = allByDist.get(i);
            if (sd.distMiles() <= maxRadiusMiles) {
                candidates.add(sd);
            }
        }
        return candidates;
    }

    public boolean hasNearbyFreeParkingStation(IndexedPropertyEntity entity, double maxMiles) {
        double[] coords = resolvePropertyCoordinates(entity);
        if (coords[0] == 0.0 || coords[1] == 0.0) return false;
        for (Map.Entry<String, double[]> entry : stationCoordinates.entrySet()) {
            double[] c = entry.getValue();
            double d = haversineMiles(coords[0], coords[1], c[0], c[1]);
            if (d <= maxMiles) {
                StationProfile p = stationRegistry.get(entry.getKey());
                if (p != null && p.freeParkAndRide()) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean hasNearbyCategoryAStation(IndexedPropertyEntity entity, double maxMiles) {
        double[] coords = resolvePropertyCoordinates(entity);
        if (coords[0] == 0.0 || coords[1] == 0.0) return false;
        for (Map.Entry<String, double[]> entry : stationCoordinates.entrySet()) {
            double[] c = entry.getValue();
            double d = haversineMiles(coords[0], coords[1], c[0], c[1]);
            if (d <= maxMiles) {
                StationProfile p = stationRegistry.get(entry.getKey());
                if (p != null && "A".equalsIgnoreCase(p.accessibilityCategory())) {
                    return true;
                }
            }
        }
        return false;
    }

    public EvaluatedTrain evaluateTrainOption(
            IndexedPropertyEntity entity,
            StationProfile fallbackRail,
            String targetLabel,
            String targetLower,
            double[] targetCoords) {

        double[] propCoords = resolvePropertyCoordinates(entity);
        List<StationDist> candidates = findNearbyCandidateStations(propCoords[0], propCoords[1], 3.5, 3);
        if (candidates.isEmpty()) {
            double fallbackDist = entity.getDistanceToStationMiles() > 0 ? entity.getDistanceToStationMiles() : 0.7;
            StationProfile st = fallbackRail != null ? fallbackRail : resolveStation(entity.getNearestStationName());
            candidates = List.of(new StationDist(st, fallbackDist));
        }

        List<EvaluatedTrain> evaluated = new ArrayList<>();
        for (StationDist cd : candidates) {
            ModeOption opt = evaluateSingleStationTrainOption(cd.station(), cd.distMiles(), targetLabel, targetLower, targetCoords);
            evaluated.add(new EvaluatedTrain(opt, cd.station(), cd.distMiles()));
        }

        // Select the station that yields the fastest & most frequent journey (lowest effective generalized commute minutes)
        evaluated.sort(Comparator
            .comparingDouble((EvaluatedTrain t) -> t.modeOption().effectiveGeneralizedMins())
            .thenComparingInt(t -> t.modeOption().doorToTargetMins())
            .thenComparing(t -> !t.bestStation().freeParkAndRide())
            .thenComparingDouble(EvaluatedTrain::distanceToStationMiles)
        );

        return evaluated.get(0);
    }

    public ModeOption evaluateSingleStationTrainOption(
            StationProfile railStation,
            double distToStation,
            String targetLabel,
            String targetLower,
            double[] targetCoords) {

        int accessMins;
        String accessDesc;
        if (distToStation <= 0.65) {
            // Walkable station access (~3.1 mph with 1.22x street factor + 2m platform entry)
            accessMins = Math.max(3, (int) Math.round(distToStation * 1.22 * 19.0) + 2);
            accessDesc = accessMins + "m walk";
        } else {
            // Car / drop-off / Park & Ride access (~2.8 mins/mile + 3m park & platform entry)
            accessMins = (int) Math.ceil(distToStation * 2.8) + 3;
            accessDesc = accessMins + "m drive";
        }

        String originKey = railStation.stationName().toLowerCase();
        boolean isQueenStLine = Boolean.TRUE.equals(stationTerminatesQueenStHigh.get(originKey))
            || "NORTH_CLYDE".equals(stationCorridorGroup.get(originKey));
        String cityHubName = Boolean.TRUE.equals(stationTerminatesQueenStHigh.get(originKey))
            ? "Glasgow Queen Street"
            : "Glasgow Central";

        // Also find nearest ScotRail station to the target destination so station-to-station timetable differences work for all 115 stations!
        NearestStationMatch targetRailMatch = resolveNearestStation(targetCoords[0], targetCoords[1], targetLabel);
        StationProfile targetStation = targetRailMatch.station();
        String targetStationKey = targetStation.stationName().toLowerCase();
        double targetEgressMiles = targetRailMatch.distanceToStationMiles();
        int targetEgressMins = targetEgressMiles <= 0.35 ? 2 : (int) Math.ceil(targetEgressMiles * 15.0);

        boolean targetIsCentral = targetLower.contains("glasgow central") || targetLower.equals("glasgow") || targetLower.contains("st enoch") || targetLower.contains("argyle");
        boolean targetIsQueenSt = targetLower.contains("queen street") || targetLower.contains("buchanan");

        int trainMins;
        int freq = railStation.trainsPerHourPeak();
        String routeDesc;

        if (originKey.equals(targetStationKey) && targetEgressMiles <= 1.2) {
            trainMins = 0;
            freq = Math.max(freq, 6);
            routeDesc = String.format("🚆 Local station %s (%s, %.1f mi)", railStation.stationName(), accessDesc, distToStation);
            targetEgressMins = 0;
        } else if (targetIsCentral) {
            int hubTransfer = Boolean.TRUE.equals(stationTerminatesQueenStHigh.get(originKey)) ? 6 : 0; // Queen St -> Central walk/link
            trainMins = railStation.fastestTrainToGlasgowCentralMins() + hubTransfer;
            targetEgressMins = 0;
            routeDesc = String.format("🚆 ScotRail %s → Glasgow Central (%dm train + %s, %d/hr peak)",
                railStation.stationName(), trainMins, accessDesc, freq);
        } else if (targetIsQueenSt) {
            int hubTransfer = isQueenStLine ? 0 : 6; // Central -> Queen St walk/Subway
            trainMins = railStation.fastestTrainToGlasgowCentralMins() + hubTransfer;
            targetEgressMins = 0;
            routeDesc = String.format("🚆 ScotRail %s → Glasgow Queen St (%dm rail + %s, %d/hr peak)",
                railStation.stationName(), trainMins, accessDesc, freq);
        } else if (sameRailCorridor(originKey, targetStationKey)) {
            // Direct intra-corridor ScotRail timetable difference!
            trainMins = Math.max(3, Math.abs(railStation.fastestTrainToGlasgowCentralMins() - targetStation.fastestTrainToGlasgowCentralMins()) + 1);
            freq = Math.min(railStation.trainsPerHourPeak(), Math.max(2, targetStation.trainsPerHourPeak()));
            routeDesc = String.format("🚆 Direct ScotRail %s → %s (%dm train + %s, %d/hr peak)",
                railStation.stationName(), targetStation.stationName(), trainMins, accessDesc, freq);
        } else {
            // Cross-city rail interchange via Glasgow Central / Queen Street / Partick
            int interchangeBuffer = (Boolean.TRUE.equals(stationTerminatesQueenStHigh.get(originKey))
                != Boolean.TRUE.equals(stationTerminatesQueenStHigh.get(targetStationKey))) ? 10 : 6;
            trainMins = railStation.fastestTrainToGlasgowCentralMins() + interchangeBuffer + targetStation.fastestTrainToGlasgowCentralMins();
            freq = Math.max(2, Math.min(railStation.trainsPerHourPeak(), targetStation.trainsPerHourPeak()));
            routeDesc = String.format("🚆 ScotRail %s → %s → %s (%dm rail incl. change, %d/hr)",
                railStation.stationName(), cityHubName, targetStation.stationName(), trainMins, freq);
        }

        int totalDoorMins = accessMins + trainMins + targetEgressMins;
        double effective = totalDoorMins + (30.0 / Math.max(1, freq));
        return new ModeOption(
            "Train",
            railStation.stationName() + " ScotRail (" + distToStation + " mi)",
            trainMins,
            totalDoorMins,
            freq,
            formatFrequency(freq),
            routeDesc,
            effective
        );
    }

    private ModeOption evaluateSubwayOption(
            IndexedPropertyEntity entity,
            StationProfile nearestRail,
            double bestStationDist,
            double[] propCoords,
            String targetLabel,
            String targetLower,
            double[] targetCoords) {

        SubwayHub nearestOriginSubway = null;
        double distPropToSubway = Double.MAX_VALUE;
        for (SubwayHub hub : subwayRegistry.values()) {
            double d = haversineMiles(propCoords[0], propCoords[1], hub.lat(), hub.lon());
            if (d < distPropToSubway) {
                distPropToSubway = d;
                nearestOriginSubway = hub;
            }
        }

        SubwayHub nearestTargetSubway = null;
        double distTargetToSubway = Double.MAX_VALUE;
        for (SubwayHub hub : subwayRegistry.values()) {
            double d = haversineMiles(targetCoords[0], targetCoords[1], hub.lat(), hub.lon());
            if (d < distTargetToSubway) {
                distTargetToSubway = d;
                nearestTargetSubway = hub;
            }
        }

        int subwayFreq = 15; // SPT Glasgow Subway runs every 4 minutes at peak (15 services/hr)
        int hopDiff = Math.abs(nearestOriginSubway.circleIndex() - nearestTargetSubway.circleIndex());
        int shortestHops = Math.min(hopDiff, 15 - hopDiff); // Inner vs Outer Circle
        int circleRideMins = shortestHops == 0 ? 2 : Math.max(3, (int) Math.round(shortestHops * 1.6) + 2);
        int targetEgressMins = distTargetToSubway <= 0.4 ? 2 : (int) Math.ceil(distTargetToSubway * 16.0);

        // Option A: Walk or Park & Ride directly to nearest SPT Subway station
        int accessSubwayMins = distPropToSubway <= 0.7
            ? Math.max(2, (int) Math.round(distPropToSubway * 1.22 * 19.0) + 2)
            : (int) Math.ceil(distPropToSubway * 3.1) + 4;
        int directSubwayDoorMins = accessSubwayMins + circleRideMins + targetEgressMins;

        // Option B: ScotRail into Partick / St Enoch / Buchanan St + SPT Subway transfer
        String originRailKey = nearestRail.stationName().toLowerCase();
        SubwayHub interchangeHub = "NORTH_CLYDE".equals(stationCorridorGroup.get(originRailKey))
            ? subwayRegistry.get("partick rail & subway hub")
            : (Boolean.TRUE.equals(stationTerminatesQueenStHigh.get(originRailKey))
                ? subwayRegistry.get("buchanan street subway")
                : subwayRegistry.get("st enoch subway"));

        int interchangeHops = Math.abs(interchangeHub.circleIndex() - nearestTargetSubway.circleIndex());
        int shortestInterchangeHops = Math.min(interchangeHops, 15 - interchangeHops);
        int subwayLegFromRail = shortestInterchangeHops == 0 ? 0 : Math.max(3, (int) Math.round(shortestInterchangeHops * 1.6) + 2);

        double railDist = bestStationDist > 0 ? bestStationDist : entity.getDistanceToStationMiles();
        int railAccessMins = railDist <= 0.65
            ? Math.max(3, (int) Math.round(railDist * 1.22 * 19.0) + 2)
            : (int) Math.ceil(railDist * 2.8) + 3;
        int railPlusSubwayRide = nearestRail.fastestTrainToGlasgowCentralMins() + 4 + subwayLegFromRail;
        int railPlusSubwayDoorMins = railAccessMins + railPlusSubwayRide + targetEgressMins;

        if (distTargetToSubway <= 2.2 && subwayLegFromRail > 0 && railPlusSubwayDoorMins < directSubwayDoorMins && distPropToSubway > 2.5) {
            int combinedFreq = Math.min(subwayFreq, Math.max(1, nearestRail.trainsPerHourPeak()));
            double effective = railPlusSubwayDoorMins + (30.0 / combinedFreq) - 1.0;
            return new ModeOption(
                "Train + Subway",
                nearestRail.stationName() + " → " + nearestTargetSubway.name(),
                railPlusSubwayRide,
                railPlusSubwayDoorMins,
                combinedFreq,
                "ScotRail (" + nearestRail.trainsPerHourPeak() + "/hr) + Subway (15/hr)",
                String.format("🚆+🚇 ScotRail %s → %s → %s (%dm total, %d/hr peak)",
                    nearestRail.stationName(), interchangeHub.name().replace(" Subway", ""), nearestTargetSubway.name(), railPlusSubwayDoorMins, combinedFreq),
                effective
            );
        }

        double effective = directSubwayDoorMins + (30.0 / subwayFreq);
        return new ModeOption(
            "Subway",
            nearestOriginSubway.name() + " (" + roundOneDecimal(distPropToSubway) + " mi)",
            circleRideMins,
            directSubwayDoorMins,
            subwayFreq,
            "Every 4 mins (15/hr)",
            String.format("🚇 SPT Subway %s → %s (%dm circle ride, %d stops, 15/hr peak)",
                nearestOriginSubway.name(), nearestTargetSubway.name(), circleRideMins, shortestHops),
            effective
        );
    }

    private ModeOption evaluateBusOption(
            IndexedPropertyEntity entity,
            double[] propCoords,
            String targetLabel,
            String targetLower,
            double distToTargetMiles) {

        String addrLower = (entity.getAddress() + " " + entity.getNearestStationName()).toLowerCase();
        String fallbackCorridor = resolveBusCorridorName(addrLower, targetLower);

        OsmOverpassTransitClient.NearbyBusTransitInfo osmBus;
        if (entity.getNearestBusStopName() != null && !entity.getNearestBusStopName().isBlank()) {
            List<String> routes = (entity.getBusRouteRefs() == null || entity.getBusRouteRefs().isBlank())
                ? List.of()
                : Arrays.stream(entity.getBusRouteRefs().split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
            osmBus = new OsmOverpassTransitClient.NearbyBusTransitInfo(
                Boolean.TRUE.equals(entity.getBusOsmVerified()),
                entity.getNearestBusStopName(),
                entity.getDistanceToBusStopMiles() == null ? 0.2 : entity.getDistanceToBusStopMiles(),
                entity.getWalkToBusStopMins() == null ? 4 : entity.getWalkToBusStopMins(),
                2,
                routes,
                entity.getBusPrimaryOperator() == null ? fallbackCorridor : entity.getBusPrimaryOperator(),
                entity.getBusFrequencyPeakPerHour() == null ? 6 : entity.getBusFrequencyPeakPerHour()
            );
        } else {
            // Pure in-memory cache or deterministic corridor fallback — zero network calls during ranking!
            osmBus = osmTransitClient.lookupCachedOrFallback(
                propCoords[0], propCoords[1], distToTargetMiles, fallbackCorridor
            );
        }

        int busFreq = osmBus.estimatedPeakBusesPerHour();
        int walkMins = osmBus.walkToStopMins();
        int inBusMins = Math.max(6, (int) Math.round(distToTargetMiles * 3.3) + 5);
        int doorToTargetMins = walkMins + inBusMins;

        String routeBadge;
        if (!osmBus.routeRefs().isEmpty()) {
            List<String> topRoutes = osmBus.routeRefs().subList(0, Math.min(4, osmBus.routeRefs().size()));
            routeBadge = osmBus.primaryOperator() + " Routes " + String.join("/", topRoutes);
        } else {
            routeBadge = osmBus.primaryOperator();
        }

        String stopDisplay = osmBus.nearestStopName() + " (" + osmBus.nearestStopDistanceMiles() + " mi, " + walkMins + "m walk)";
        String osmTag = osmBus.osmVerified() ? " [NaPTAN/OSM]" : "";
        String routeDesc = String.format("🚌 %s from %s → %s (%dm bus + %dm walk, %d/hr)%s",
            routeBadge, osmBus.nearestStopName(), targetLabel, inBusMins, walkMins, busFreq, osmTag);

        double effective = doorToTargetMins + (30.0 / Math.max(1, busFreq));
        return new ModeOption(
            "Bus",
            stopDisplay,
            inBusMins,
            doorToTargetMins,
            busFreq,
            formatFrequency(busFreq),
            routeDesc,
            effective
        );
    }

    /**
     * Resolves bus transit stop and corridor data at index time.
     * Stored on the entity so future ranking passes are 100% pure in-memory.
     */
    public void resolveAndPopulateBusInfo(IndexedPropertyEntity entity) {
        if (entity.getLatitude() == 0.0 || entity.getLongitude() == 0.0) return;
        String addrLower = (entity.getAddress() + " " + entity.getNearestStationName()).toLowerCase();
        String fallbackCorridor = resolveBusCorridorName(addrLower, "glasgow central");
        OsmOverpassTransitClient.NearbyBusTransitInfo bus = osmTransitClient.lookupNearbyBusStops(
            entity.getLatitude(), entity.getLongitude(), entity.getDistanceFromGlasgowCentralMiles(), fallbackCorridor
        );
        if (bus != null) {
            entity.setNearestBusStopName(bus.nearestStopName());
            entity.setDistanceToBusStopMiles(bus.nearestStopDistanceMiles());
            entity.setWalkToBusStopMins(bus.walkToStopMins());
            entity.setBusFrequencyPeakPerHour(bus.estimatedPeakBusesPerHour());
            entity.setBusRouteRefs(bus.routeRefs() != null ? String.join(", ", bus.routeRefs()) : "");
            entity.setBusPrimaryOperator(bus.primaryOperator());
            entity.setBusOsmVerified(bus.osmVerified());
        }
    }

    private boolean sameRailCorridor(String originKey, String targetStationKey) {
        String g1 = stationCorridorGroup.get(originKey);
        String g2 = stationCorridorGroup.get(targetStationKey);
        return g1 != null && g1.equals(g2);
    }

    private String resolveBusCorridorName(String addrLower, String targetLower) {
        if (addrLower.contains("uddingston") || addrLower.contains("bothwell") || addrLower.contains("bellshill") || addrLower.contains("motherwell") || targetLower.contains("motherwell")) {
            return "First X11 / 240";
        }
        if (addrLower.contains("cambuslang") || addrLower.contains("rutherglen") || addrLower.contains("blantyre") || addrLower.contains("hamilton")) {
            return "First 267 / 18";
        }
        if (addrLower.contains("east kilbride") || addrLower.contains("hairmyres") || targetLower.contains("east kilbride")) {
            return "First 18 / 201";
        }
        if (addrLower.contains("giffnock") || addrLower.contains("newton mearns") || addrLower.contains("clarkston") || addrLower.contains("whitecraigs")) {
            return "First 38 / 4A";
        }
        if (addrLower.contains("bearsden") || addrLower.contains("milngavie")) {
            return "First 60 / 60A";
        }
        if (addrLower.contains("dumbarton") || addrLower.contains("cardross") || addrLower.contains("dalmuir") || addrLower.contains("clydebank")) {
            return "First 1 / 1A / 206";
        }
        if (addrLower.contains("paisley") || addrLower.contains("renfrew") || addrLower.contains("johnstone") || addrLower.contains("glengarnock") || addrLower.contains("beith")) {
            return "McGill's 38 / X23";
        }
        if (addrLower.contains("finnieston") || addrLower.contains("partick") || addrLower.contains("kelvin")) {
            return "First 2 / 3 / 77";
        }
        return "First Glasgow Express";
    }

    private String formatFrequency(int perHour) {
        if (perHour <= 0) return "Hourly";
        int mins = Math.max(3, Math.round(60.0f / perHour));
        return "Every " + mins + "m (" + perHour + "/hr)";
    }

    private String modeIcon(String mode) {
        if (mode == null) return "🚆";
        if (mode.contains("Train") && mode.contains("Subway")) return "🚆+🚇";
        if (mode.equalsIgnoreCase("Subway")) return "🚇";
        if (mode.equalsIgnoreCase("Bus")) return "🚌";
        return "🚆";
    }

    public NearestStationMatch resolveNearestStation(double lat, double lon, String addressFallback) {
        if (lat == 0.0 || lon == 0.0) {
            StationProfile fallback = resolveStation(addressFallback);
            return new NearestStationMatch(fallback, 0.7, 7.5);
        }
        double distFromCentral = roundOneDecimal(haversineMiles(GLASGOW_CENTRAL_LAT, GLASGOW_CENTRAL_LON, lat, lon));
        StationProfile bestStation = null;
        double bestDist = Double.MAX_VALUE;

        for (Map.Entry<String, double[]> entry : stationCoordinates.entrySet()) {
            double[] coords = entry.getValue();
            double d = haversineMiles(lat, lon, coords[0], coords[1]);
            if (d < bestDist) {
                bestDist = d;
                bestStation = stationRegistry.get(entry.getKey());
            }
        }
        if (bestStation == null) {
            bestStation = resolveStation(addressFallback);
            bestDist = 0.8;
        }
        return new NearestStationMatch(bestStation, roundOneDecimal(Math.max(0.1, bestDist)), distFromCentral);
    }

    public static double haversineMiles(double lat1, double lon1, double lat2, double lon2) {
        double rMiles = 3958.8;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return rMiles * c;
    }

    private static double roundOneDecimal(double val) {
        return Math.round(val * 10.0) / 10.0;
    }

    public int calculateDoorToCentralMins(double distanceToStationMiles, StationProfile station) {
        int accessMins = distanceToStationMiles <= 0.65
            ? Math.max(3, (int) Math.round(distanceToStationMiles * 1.22 * 19.0) + 2)
            : (int) Math.ceil(distanceToStationMiles * 2.8) + 3;
        return accessMins + station.fastestTrainToGlasgowCentralMins();
    }

    /**
     * Builds a 100% tool-grounded multi-modal transit dossier for a single property to feed into the local Ollama LLM.
     * Combines the top 3 nearest ScotRail stations (~120-station registry), top 2 SPT Subway hubs (15-station loop),
     * OpenStreetMap Overpass / UK NaPTAN bus stops & routes, and transport sentences from the estate-agent listing.
     */
    public String buildGroundedCommuteDossier(
            IndexedPropertyEntity entity,
            String targetLocation,
            List<String> allowedModes) {

        String targetLabel = (targetLocation == null || targetLocation.isBlank()) ? "Glasgow Central" : targetLocation.trim();
        double[] propCoords = resolvePropertyCoordinates(entity);
        double[] targetCoords = resolveCenterCoordinates(targetLabel);
        double distToTarget = roundOneDecimal(haversineMiles(propCoords[0], propCoords[1], targetCoords[0], targetCoords[1]));

        StringBuilder sb = new StringBuilder();
        sb.append("PROPERTY: ").append(entity.getTitle()).append(" (").append(entity.getAddress()).append(")\n");
        sb.append(String.format(Locale.ROOT, "COORDINATES: (%.4f, %.4f) | TARGET DESTINATION: %s (%.1f miles straight-line)\n\n",
            propCoords[0], propCoords[1], targetLabel, distToTarget));

        // 1. Top 3 Nearest ScotRail Stations
        List<StationDist> nearbyStations = findNearbyCandidateStations(propCoords[0], propCoords[1], 3.5, 3);
        if (nearbyStations.isEmpty()) {
            StationProfile fallback = resolveStation(entity.getNearestStationName());
            nearbyStations = List.of(new StationDist(fallback, entity.getDistanceToStationMiles() > 0 ? entity.getDistanceToStationMiles() : 0.7));
        }

        sb.append("1. TOP 3 NEAREST SCOTRAIL STATIONS (VERIFIED TIMETABLE & ACCESSIBILITY DATA):\n");
        for (int i = 0; i < Math.min(3, nearbyStations.size()); i++) {
            StationDist sd = nearbyStations.get(i);
            StationProfile st = sd.station();

            ModeOption railOpt = evaluateSingleStationTrainOption(st, sd.distMiles(), targetLabel, targetLabel.toLowerCase(), targetCoords);
            String accessMode = sd.distMiles() <= 0.65 ? "Walkable" : "Drive / Park & Ride";
            String parkingInfo = st.freeParkAndRide()
                ? ("Free Park & Ride (" + st.parkAndRideSpaces() + " spaces)")
                : (st.parkAndRideSpaces() > 0 ? "Paid parking (" + st.parkAndRideSpaces() + " spaces)" : "Street/No station car park");

            sb.append(String.format(Locale.ROOT,
                "   - Option #%d: %s Station (%.1f mi — %s) | Line: %s | Route to %s: %d mins door-to-target (%d mins on train, %s peak) | Parking: %s | Step-Free ORR Category %s (%s)\n",
                i + 1, st.stationName(), sd.distMiles(), accessMode, st.corridor(), targetLabel,
                railOpt.doorToTargetMins(), railOpt.inVehicleMins(), railOpt.frequencyLabel(),
                parkingInfo, st.accessibilityCategory(), st.accessibilityNotes()
            ));
        }

        // 2. SPT Glasgow Subway & Rail+Subway Interchange
        StationProfile closestRail = nearbyStations.isEmpty() ? resolveStation(entity.getNearestStationName()) : nearbyStations.get(0).station();
        double closestRailDist = nearbyStations.isEmpty() ? entity.getDistanceToStationMiles() : nearbyStations.get(0).distMiles();
        ModeOption subwayOpt = evaluateSubwayOption(entity, closestRail, closestRailDist, propCoords, targetLabel, targetLabel.toLowerCase(), targetCoords);
        sb.append("\n2. SPT GLASGOW SUBWAY / RAIL+SUBWAY OPTION:\n");
        sb.append(String.format(Locale.ROOT,
            "   - Mode: %s | Stop: %s | Door-to-%s: %d mins (%d mins in-vehicle) | Frequency: %s | Summary: %s\n",
            subwayOpt.mode(), subwayOpt.stopName(), targetLabel, subwayOpt.doorToTargetMins(),
            subwayOpt.inVehicleMins(), subwayOpt.frequencyLabel(), subwayOpt.routeSummary()
        ));

        // 3. OpenStreetMap Overpass / UK NaPTAN Bus Stops & Routes
        String addrLower = (entity.getAddress() + " " + closestRail.stationName()).toLowerCase();
        OsmOverpassTransitClient.NearbyBusTransitInfo osmBus = osmTransitClient.lookupNearbyBusStops(
            propCoords[0], propCoords[1], distToTarget, resolveBusCorridorName(addrLower, targetLabel.toLowerCase())
        );
        ModeOption busOpt = evaluateBusOption(entity, propCoords, targetLabel, targetLabel.toLowerCase(), distToTarget);
        sb.append("\n3. NEARBY BUS STOPS & ROUTES (OPENSTREETMAP / UK NAPTAN):\n");
        sb.append(String.format(Locale.ROOT,
            "   - Nearest Stop: %s (%.2f mi street walk, ~%d mins walk) | Stops within 650m: %d | Operator: %s | Active Route Numbers: %s | Estimated Door-to-%s: %d mins (%s) | OSM Verified: %s\n",
            osmBus.nearestStopName(), osmBus.nearestStopDistanceMiles(), osmBus.walkToStopMins(),
            osmBus.stopsWithin600m(), osmBus.primaryOperator(),
            osmBus.routeRefs().isEmpty() ? "(corridor routes)" : String.join(", ", osmBus.routeRefs()),
            targetLabel, busOpt.doorToTargetMins(), busOpt.frequencyLabel(), osmBus.osmVerified() ? "Yes" : "Corridor Estimate"
        ));

        // 4. Estate-Agent Listing Transport Clues
        List<String> agentTransportSentences = extractTransportMentions(entity.getRawDescription());
        sb.append("\n4. ESTATE AGENT LISTING TRANSPORT & LOCATION MENTIONS:\n");
        if (agentTransportSentences.isEmpty()) {
            sb.append("   - No explicit rail/bus sentences mentioned in estate agent text.\n");
        } else {
            for (String s : agentTransportSentences) {
                sb.append("   - \"").append(s).append("\"\n");
            }
        }

        // 5. Overall Best Evaluated Mode for User's Active Mode Filter
        TransitEvaluation bestEval = evaluateBestPublicTransport(entity, closestRail, targetLabel, allowedModes);
        sb.append("\n5. ENGINE SELECTED BEST MODE (ALLOWED MODES: ").append(allowedModes).append("):\n");
        sb.append("   - Winner: ").append(bestEval.bestTransitMode()).append(" -> ").append(bestEval.transitRouteSummary())
          .append(" (").append(bestEval.doorToTargetMins()).append(" mins total, ").append(bestEval.frequencyLabel()).append(")\n");

        return sb.toString();
    }

    private List<String> extractTransportMentions(String description) {
        if (description == null || description.isBlank()) return List.of();
        String[] sentences = description.split("(?<=[.!?\\n])\\s+");
        List<String> matches = new ArrayList<>();
        List<String> keywords = List.of(
            "station", "train", "rail", "bus", "subway", "underground", "commut",
            "motorway", "m8", "m74", "m77", "m80", "a82", "a737", "glasgow", "park and ride", "walk"
        );
        for (String raw : sentences) {
            String clean = raw.replaceAll("\\s+", " ").trim();
            if (clean.length() < 15 || clean.length() > 320) continue;
            String lower = clean.toLowerCase();
            if (keywords.stream().anyMatch(lower::contains)) {
                matches.add(clean);
                if (matches.size() >= 5) break;
            }
        }
        return matches;
    }
}
