package com.glasgow.propertyagent.service;

import com.glasgow.propertyagent.entity.IndexedPropertyEntity;
import com.glasgow.propertyagent.model.BuyerWorkflowState;
import com.glasgow.propertyagent.model.SchoolAndAreaProfile;
import com.glasgow.propertyagent.model.ScottishCostBreakdown;
import com.glasgow.propertyagent.model.SearchFilterRequest;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FEAT-01: Authentic Scottish "Offers Over" + Home Report LTV + Revenue Scotland LBTT / ADS Calculator.
 *
 * Key Scottish Residential Finance Rules Modeled:
 * 1. Mortgage lenders cap LTV at min(Closing Price, Home Report Valuation). Any bid above the
 *    Home Report valuation must be funded 100% in cash on top of the standard mortgage deposit.
 * 2. Revenue Scotland Residential LBTT slabs:
 *    - Up to £145,000: 0% (£175,000 nil-rate threshold for First-Time Buyers = up to £600 relief)
 *    - £145,001 to £250,000: 2%
 *    - £250,001 to £325,000: 5%
 *    - £325,001 to £750,000: 10%
 *    - Over £750,000: 12%
 * 3. Additional Dwelling Supplement (ADS): 8% on the full purchase price when >= £40,000.
 */
@Service
public class ScottishOfferAndLbttService {

    private static final Pattern EXPLICIT_HR_VALUE_PATTERN = Pattern.compile(
        "home\\s+report\\s+(?:valuation|value|val)?[^£0-9]{0,35}£\\s*([0-9]{2,3}(?:,[0-9]{3})+|\\d{3}\\s*k\\b)",
        Pattern.CASE_INSENSITIVE
    );

    private static final Pattern BELOW_HR_PATTERN = Pattern.compile(
        "£\\s*([0-9]{1,3}(?:,[0-9]{3})+|\\d{1,2}\\s*k)\\s+(?:below|under)\\s+home\\s+report",
        Pattern.CASE_INSENSITIVE
    );

    private static final Pattern FLOOR_AREA_SQM_PATTERN = Pattern.compile(
        "\\b([0-9]{2,4}(?:\\.[0-9]+)?)\\s*(?:sq\\.?\\s*m\\b|m2\\b|m²|square\\s+metres?)",
        Pattern.CASE_INSENSITIVE
    );

    private static final Pattern FLOOR_AREA_SQFT_PATTERN = Pattern.compile(
        "\\b([0-9]{3,5}(?:,[0-9]{3})?(?:\\.[0-9]+)?)\\s*(?:sq\\.?\\s*ft\\b|ft2\\b|ft²|square\\s+feet)",
        Pattern.CASE_INSENSITIVE
    );

    public int calculateLbtt(int purchasePriceGbp, boolean firstTimeBuyer) {
        if (purchasePriceGbp <= 0) return 0;

        int nilThreshold = firstTimeBuyer ? 175_000 : 145_000;
        if (purchasePriceGbp <= nilThreshold) {
            return 0;
        }

        double tax = 0.0;
        if (purchasePriceGbp > nilThreshold) {
            int band2Taxable = Math.min(purchasePriceGbp, 250_000) - nilThreshold;
            if (band2Taxable > 0) {
                tax += band2Taxable * 0.02;
            }
        }
        if (purchasePriceGbp > 250_000) {
            int band3Taxable = Math.min(purchasePriceGbp, 325_000) - 250_000;
            tax += band3Taxable * 0.05;
        }
        if (purchasePriceGbp > 325_000) {
            int band4Taxable = Math.min(purchasePriceGbp, 750_000) - 325_000;
            tax += band4Taxable * 0.10;
        }
        if (purchasePriceGbp > 750_000) {
            int band5Taxable = purchasePriceGbp - 750_000;
            tax += band5Taxable * 0.12;
        }
        return (int) Math.round(tax);
    }

    public int calculateAds(int purchasePriceGbp, boolean additionalDwelling) {
        if (!additionalDwelling || purchasePriceGbp < 40_000) {
            return 0;
        }
        return (int) Math.round(purchasePriceGbp * 0.08);
    }

    public ScottishCostBreakdown evaluateCostBreakdown(
            IndexedPropertyEntity entity,
            SchoolAndAreaProfile schoolAndArea,
            BuyerWorkflowState workflowState,
            SearchFilterRequest filterRequest) {

        int asking = Math.max(0, entity.getPriceGbp());
        String qualifier = entity.getPriceQualifier() != null ? entity.getPriceQualifier().toLowerCase(Locale.ROOT) : "";
        boolean isFixedPrice = qualifier.contains("fixed");

        // 1. Resolve Home Report valuation: uploaded HR > explicit text mention > conservative asking baseline
        Integer uploadedHr = workflowState != null ? workflowState.homeReportValuationGbp() : null;
        Integer parsedTextHr = uploadedHr == null
            ? extractExplicitHrFromDescription(entity.getRawDescription(), asking)
            : null;

        boolean hrVerified = (uploadedHr != null && uploadedHr > 0) || (parsedTextHr != null && parsedTextHr > 0);
        int estimatedHr = (uploadedHr != null && uploadedHr > 0)
            ? uploadedHr
            : ((parsedTextHr != null && parsedTextHr > 0) ? parsedTextHr : asking);

        // 2. Determine Offers-Over closing uplift % and estimated closing price
        Integer userOverridePct = filterRequest != null ? filterRequest.offersOverPremiumPct() : null;
        int areaTypicalPct = schoolAndArea != null ? schoolAndArea.typicalOffersOverPct() : 8;

        int premiumPctApplied;
        int closingPrice;
        if (isFixedPrice && userOverridePct == null) {
            premiumPctApplied = 0;
            closingPrice = asking;
        } else if (userOverridePct != null) {
            premiumPctApplied = userOverridePct;
            closingPrice = (int) Math.round(asking * (1.0 + (userOverridePct / 100.0)));
        } else if (hrVerified && estimatedHr > asking) {
            // When Home Report is already set above "Offers Over" marketing price (classic Scottish pricing),
            // properties in top-tier areas typically close ~2% above HR, or right around HR elsewhere
            boolean isTopTier = schoolAndArea != null && "TOP_TIER".equalsIgnoreCase(schoolAndArea.attainmentTier());
            closingPrice = isTopTier ? (int) Math.round(estimatedHr * 1.02) : estimatedHr;
            premiumPctApplied = asking > 0 ? (int) Math.round(((closingPrice - asking) * 100.0) / asking) : 0;
        } else {
            premiumPctApplied = isFixedPrice ? 0 : areaTypicalPct;
            closingPrice = (int) Math.round(asking * (1.0 + (premiumPctApplied / 100.0)));
        }

        int offersOverPremiumGbp = Math.max(0, closingPrice - asking);

        // 3. Mortgage LTV cap against min(closingPrice, estimatedHomeReportGbp)
        int depositPct = (filterRequest != null && filterRequest.depositPct() != null)
            ? Math.max(0, Math.min(100, filterRequest.depositPct()))
            : 15;
        int lenderValuationBasis = Math.min(closingPrice, estimatedHr);
        int mortgageDepositGbp = (int) Math.round(lenderValuationBasis * (depositPct / 100.0));
        int mortgageLoanGbp = Math.max(0, lenderValuationBasis - mortgageDepositGbp);
        int cashOverHomeReportGbp = Math.max(0, closingPrice - lenderValuationBasis);

        // 4. LBTT & ADS
        boolean additionalDwelling = filterRequest != null && Boolean.TRUE.equals(filterRequest.additionalDwelling());
        boolean firstTimeBuyer = !additionalDwelling
            && filterRequest != null
            && Boolean.TRUE.equals(filterRequest.firstTimeBuyer());

        int lbttGbp = calculateLbtt(closingPrice, firstTimeBuyer);
        int adsGbp = calculateAds(closingPrice, additionalDwelling);
        int totalUpfrontCashGbp = mortgageDepositGbp + cashOverHomeReportGbp + lbttGbp + adsGbp;

        // 5. Floor area & £/m²
        Double floorAreaSqm = (workflowState != null && workflowState.floorAreaSqm() != null && workflowState.floorAreaSqm() > 0)
            ? workflowState.floorAreaSqm()
            : extractFloorAreaSqmFromDescription(entity.getRawDescription());

        Integer pricePerSqmGbp = (floorAreaSqm != null && floorAreaSqm >= 25.0)
            ? (int) Math.round(closingPrice / floorAreaSqm)
            : null;

        return new ScottishCostBreakdown(
            asking,
            estimatedHr,
            hrVerified,
            closingPrice,
            offersOverPremiumGbp,
            cashOverHomeReportGbp,
            premiumPctApplied,
            mortgageLoanGbp,
            mortgageDepositGbp,
            lbttGbp,
            adsGbp,
            totalUpfrontCashGbp,
            floorAreaSqm,
            pricePerSqmGbp
        );
    }

    public Integer extractExplicitHrFromDescription(String rawDescription, int askingPrice) {
        if (rawDescription == null || rawDescription.isBlank()) return null;

        Matcher m1 = EXPLICIT_HR_VALUE_PATTERN.matcher(rawDescription);
        if (m1.find()) {
            Integer val = parseGbpToken(m1.group(1));
            if (val != null && val >= 40_000 && val <= 5_000_000) {
                return val;
            }
        }

        Matcher m2 = BELOW_HR_PATTERN.matcher(rawDescription);
        if (m2.find() && askingPrice > 0) {
            Integer discount = parseGbpToken(m2.group(1));
            if (discount != null && discount > 0 && discount <= 250_000) {
                return askingPrice + discount;
            }
        }
        return null;
    }

    public Double extractFloorAreaSqmFromDescription(String rawDescription) {
        if (rawDescription == null || rawDescription.isBlank()) return null;

        Matcher sqmMatcher = FLOOR_AREA_SQM_PATTERN.matcher(rawDescription);
        if (sqmMatcher.find()) {
            try {
                double sqm = Double.parseDouble(sqmMatcher.group(1));
                if (sqm >= 30.0 && sqm <= 1_200.0) {
                    return Math.round(sqm * 10.0) / 10.0;
                }
            } catch (NumberFormatException ignored) {}
        }

        Matcher sqftMatcher = FLOOR_AREA_SQFT_PATTERN.matcher(rawDescription);
        if (sqftMatcher.find()) {
            try {
                double sqft = Double.parseDouble(sqftMatcher.group(1).replace(",", ""));
                double sqm = sqft * 0.092903;
                if (sqm >= 30.0 && sqm <= 1_200.0) {
                    return Math.round(sqm * 10.0) / 10.0;
                }
            } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    private static Integer parseGbpToken(String token) {
        if (token == null || token.isBlank()) return null;
        String clean = token.toLowerCase(Locale.ROOT).replace(",", "").trim();
        try {
            if (clean.endsWith("k")) {
                int thousands = Integer.parseInt(clean.substring(0, clean.length() - 1).trim());
                return thousands * 1_000;
            }
            return Integer.parseInt(clean);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
