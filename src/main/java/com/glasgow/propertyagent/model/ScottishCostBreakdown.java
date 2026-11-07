package com.glasgow.propertyagent.model;

/**
 * FEAT-01 & FEAT-06: Authentic Scottish property purchase cost model:
 * - Models Offers Over closing premium vs Home Report valuation (lenders cap LTV at min(closing, HR))
 * - Tiered Revenue Scotland LBTT + First-Time Buyer relief + 8% Additional Dwelling Supplement (ADS)
 * - Total Upfront Cash Needed = Mortgage Deposit on HR + Cash-over-Home-Report Gap + LBTT + ADS
 * - Floor area (m²) & £/m² value benchmark when floor area is extracted or Home Report uploaded
 */
public record ScottishCostBreakdown(
    int askingPriceGbp,
    int estimatedHomeReportGbp,
    boolean homeReportVerified,
    int estimatedClosingPriceGbp,
    int offersOverPremiumGbp,
    int cashOverHomeReportGbp,
    int premiumPctApplied,
    int mortgageLoanGbp,
    int mortgageDepositGbp,
    int lbttGbp,
    int adsGbp,
    int totalUpfrontCashGbp,
    Double floorAreaSqm,
    Integer pricePerSqmGbp
) {}
