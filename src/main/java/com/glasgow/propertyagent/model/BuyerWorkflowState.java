package com.glasgow.propertyagent.model;

import java.util.List;

/**
 * FEAT-05 & FEAT-06: Persistent buyer shortlist, hide/dismiss, viewing status/notes,
 * and inspected Scottish Home Report fields for a property.
 */
public record BuyerWorkflowState(
    boolean shortlisted,
    boolean hidden,
    String viewingStatus, // NONE, BOOKED, VISITED, OFFER_PLACED
    String viewingNotes,
    Integer homeReportValuationGbp,
    Double floorAreaSqm,
    String councilTaxBand,
    String hrEnergyBand,
    Integer hrCategory1Count,
    Integer hrCategory2Count,
    Integer hrCategory3Count,
    List<String> hrRepairWarnings,
    String hrSummary
) {
    public static BuyerWorkflowState empty() {
        return new BuyerWorkflowState(
            false, false, "NONE", "",
            null, null, null, null,
            null, null, null, List.of(), null
        );
    }
}
