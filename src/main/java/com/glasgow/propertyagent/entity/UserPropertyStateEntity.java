package com.glasgow.propertyagent.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "user_property_state")
public class UserPropertyStateEntity {

    @Id
    @Column(name = "listing_id", nullable = false)
    private String listingId;

    @Column(nullable = false)
    private boolean shortlisted = false;

    @Column(nullable = false)
    private boolean hidden = false;

    @Column(name = "viewing_status", nullable = false, length = 32)
    private String viewingStatus = "NONE";

    @Column(name = "viewing_notes", columnDefinition = "TEXT")
    private String viewingNotes;

    @Column(name = "home_report_valuation_gbp")
    private Integer homeReportValuationGbp;

    @Column(name = "floor_area_sqm")
    private Double floorAreaSqm;

    @Column(name = "council_tax_band", length = 8)
    private String councilTaxBand;

    @Column(name = "hr_energy_band", length = 8)
    private String hrEnergyBand;

    @Column(name = "hr_category1_count")
    private Integer hrCategory1Count;

    @Column(name = "hr_category2_count")
    private Integer hrCategory2Count;

    @Column(name = "hr_category3_count")
    private Integer hrCategory3Count;

    @Column(name = "hr_repair_warnings_json", columnDefinition = "TEXT")
    private String hrRepairWarningsJson;

    @Column(name = "hr_summary", columnDefinition = "TEXT")
    private String hrSummary;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public UserPropertyStateEntity() {}

    public String getListingId() { return listingId; }
    public void setListingId(String listingId) { this.listingId = listingId; }

    public boolean isShortlisted() { return shortlisted; }
    public void setShortlisted(boolean shortlisted) { this.shortlisted = shortlisted; }

    public boolean isHidden() { return hidden; }
    public void setHidden(boolean hidden) { this.hidden = hidden; }

    public String getViewingStatus() { return viewingStatus; }
    public void setViewingStatus(String viewingStatus) { this.viewingStatus = viewingStatus; }

    public String getViewingNotes() { return viewingNotes; }
    public void setViewingNotes(String viewingNotes) { this.viewingNotes = viewingNotes; }

    public Integer getHomeReportValuationGbp() { return homeReportValuationGbp; }
    public void setHomeReportValuationGbp(Integer homeReportValuationGbp) { this.homeReportValuationGbp = homeReportValuationGbp; }

    public Double getFloorAreaSqm() { return floorAreaSqm; }
    public void setFloorAreaSqm(Double floorAreaSqm) { this.floorAreaSqm = floorAreaSqm; }

    public String getCouncilTaxBand() { return councilTaxBand; }
    public void setCouncilTaxBand(String councilTaxBand) { this.councilTaxBand = councilTaxBand; }

    public String getHrEnergyBand() { return hrEnergyBand; }
    public void setHrEnergyBand(String hrEnergyBand) { this.hrEnergyBand = hrEnergyBand; }

    public Integer getHrCategory1Count() { return hrCategory1Count; }
    public void setHrCategory1Count(Integer hrCategory1Count) { this.hrCategory1Count = hrCategory1Count; }

    public Integer getHrCategory2Count() { return hrCategory2Count; }
    public void setHrCategory2Count(Integer hrCategory2Count) { this.hrCategory2Count = hrCategory2Count; }

    public Integer getHrCategory3Count() { return hrCategory3Count; }
    public void setHrCategory3Count(Integer hrCategory3Count) { this.hrCategory3Count = hrCategory3Count; }

    public String getHrRepairWarningsJson() { return hrRepairWarningsJson; }
    public void setHrRepairWarningsJson(String hrRepairWarningsJson) { this.hrRepairWarningsJson = hrRepairWarningsJson; }

    public String getHrSummary() { return hrSummary; }
    public void setHrSummary(String hrSummary) { this.hrSummary = hrSummary; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
