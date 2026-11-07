package com.glasgow.propertyagent.model;

/**
 * Deterministic commuter rail profile for Greater Glasgow & South Lanarkshire stations.
 */
public record StationProfile(
    String stationName,
    String corridor,
    int fastestTrainToGlasgowCentralMins,
    int averageTrainToGlasgowCentralMins,
    int trainsPerHourPeak,
    boolean freeParkAndRide,
    int parkAndRideSpaces,
    String accessibilityCategory,   // "A" (Full step-free lifts/level), "B1", "B2", "C"
    String accessibilityNotes
) {}
