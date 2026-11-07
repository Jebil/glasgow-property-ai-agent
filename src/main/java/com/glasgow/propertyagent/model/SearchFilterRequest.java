package com.glasgow.propertyagent.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record SearchFilterRequest(
    String centerLocation,
    @DecimalMin("0.5") @DecimalMax("50.0") double radiusMiles,
    List<String> propertyTypes,
    List<String> transportModes,
    @Min(0) @Max(60) int minServicesPerHour,
    @Min(0) @Max(10_000_000) int maxPrice,
    @Min(0) @Max(15) int minBedrooms,
    @Min(5) @Max(240) int maxDoorToCentralCommuteMins,
    boolean requireFreeStationParking,
    boolean requireStepFreeCategoryA,
    List<@Valid CriterionWeight> dynamicCriteria,
    String portalProvider,
    Boolean bypassHardFilters,
    // FEAT-04: Dual-Commuter Household Mode (Partner B)
    String secondCenterLocation,
    @Min(5) @Max(240) Integer maxSecondCommuteMins,
    List<String> secondTransportModes,
    // FEAT-01: Scottish Offers Over, Home Report LTV & LBTT Upfront Cash Model
    @Min(-20) @Max(50) Integer offersOverPremiumPct,
    @Min(0) @Max(100) Integer depositPct,
    Boolean firstTimeBuyer,
    Boolean additionalDwelling,
    @Min(0) @Max(10_000_000) Integer maxUpfrontCashGbp,
    // FEAT-05: Buyer Workflow Tab Filter ("ALL", "SHORTLISTED", "HIDDEN")
    String workflowFilter
) {
    public SearchFilterRequest {
        if (portalProvider == null || portalProvider.isBlank()) {
            portalProvider = "rightmove";
        }
        if (bypassHardFilters == null) {
            bypassHardFilters = false;
        }
        if (radiusMiles <= 0.0) {
            radiusMiles = 15.0;
        }
        if (maxDoorToCentralCommuteMins <= 0) {
            maxDoorToCentralCommuteMins = 45;
        }
        if (maxSecondCommuteMins == null || maxSecondCommuteMins <= 0) {
            maxSecondCommuteMins = 45;
        }
        if (depositPct == null) {
            depositPct = 15;
        }
        if (firstTimeBuyer == null) {
            firstTimeBuyer = false;
        }
        if (additionalDwelling == null) {
            additionalDwelling = false;
        }
        if (maxUpfrontCashGbp == null || maxUpfrontCashGbp < 0) {
            maxUpfrontCashGbp = 0;
        }
        if (workflowFilter == null || workflowFilter.isBlank()) {
            workflowFilter = "ALL";
        }
    }

    /**
     * Backward-compatible 13-arg constructor for Phase 1 / Phase 2 / Phase 2.5 callers & tests.
     */
    public SearchFilterRequest(
        String centerLocation,
        double radiusMiles,
        List<String> propertyTypes,
        List<String> transportModes,
        int minServicesPerHour,
        int maxPrice,
        int minBedrooms,
        int maxDoorToCentralCommuteMins,
        boolean requireFreeStationParking,
        boolean requireStepFreeCategoryA,
        List<CriterionWeight> dynamicCriteria,
        String portalProvider,
        Boolean bypassHardFilters
    ) {
        this(
            centerLocation,
            radiusMiles,
            propertyTypes,
            transportModes,
            minServicesPerHour,
            maxPrice,
            minBedrooms,
            maxDoorToCentralCommuteMins,
            requireFreeStationParking,
            requireStepFreeCategoryA,
            dynamicCriteria,
            portalProvider,
            bypassHardFilters,
            null,
            45,
            null,
            null,
            15,
            false,
            false,
            0,
            "ALL"
        );
    }

    public record CriterionWeight(
        @NotBlank String rule,
        String priority // MUST_HAVE, HIGH, MEDIUM, NICE_TO_HAVE
    ) {}
}
