package com.glasgow.propertyagent.model;

/**
 * FEAT-03 & FEAT-07: Greater Glasgow & commuter-belt secondary school catchment,
 * attainment tier, SIMD decile, Council Tax guide, and area Offers-Over closing norm.
 */
public record SchoolAndAreaProfile(
    String neighbourhoodName,
    String councilArea,
    String nonDenomSecondary,
    String denomSecondary,
    String attainmentTier,     // TOP_TIER, STRONG, SOLID, MIXED
    int higherPassPctApprox,   // Indicative S5/S6 5+ Highers %
    int simdDecile,            // 1..10 (10 = least deprived)
    String councilTaxGuide,    // e.g. "Band E-G"
    int typicalOffersOverPct   // Typical % paid over Offers Over in this micro-market
) {}
