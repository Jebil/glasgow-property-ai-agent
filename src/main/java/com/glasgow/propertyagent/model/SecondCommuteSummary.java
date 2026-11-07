package com.glasgow.propertyagent.model;

/**
 * FEAT-04: Partner B / Second Household Commuter transit summary.
 */
public record SecondCommuteSummary(
    String targetDestination,
    double distanceFromTargetMiles,
    String bestTransitMode,
    String transitStopName,
    int inVehicleMins,
    int doorToTargetMins,
    int servicesPerHourPeak,
    String frequencyLabel,
    String transitRouteSummary
) {}
