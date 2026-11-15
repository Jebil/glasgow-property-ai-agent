package com.glasgow.propertyagent.controller;

import com.glasgow.propertyagent.entity.IndexedPropertyEntity;
import com.glasgow.propertyagent.model.BuyerWorkflowState;
import com.glasgow.propertyagent.repository.IndexedPropertyRepository;
import com.glasgow.propertyagent.service.app.BuyerWorkflowService;
import com.glasgow.propertyagent.service.app.HomeReportAnalyzerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * Dedicated REST controller for Buyer Workflow state (shortlist, hidden/dismissed, viewing status & notes)
 * and Scottish Home Report PDF / Single Survey text analysis (FEAT-05, FEAT-06).
 */
@RestController
@RequestMapping("/api/properties")
public class BuyerWorkflowController {

    private static final Logger log = LoggerFactory.getLogger(BuyerWorkflowController.class);

    private final BuyerWorkflowService buyerWorkflowService;
    private final HomeReportAnalyzerService homeReportAnalyzerService;
    private final IndexedPropertyRepository repository;

    @Autowired
    public BuyerWorkflowController(
            BuyerWorkflowService buyerWorkflowService,
            HomeReportAnalyzerService homeReportAnalyzerService,
            IndexedPropertyRepository repository) {
        this.buyerWorkflowService = buyerWorkflowService;
        this.homeReportAnalyzerService = homeReportAnalyzerService;
        this.repository = repository;
    }

    /**
     * FEAT-05: Summary counts of shortlisted, hidden/dismissed, and Home-Report-inspected properties.
     */
    @GetMapping("/workflow-summary")
    public BuyerWorkflowService.WorkflowSummary getWorkflowSummary() {
        return buyerWorkflowService.getSummary();
    }

    /**
     * FEAT-05: Update shortlist star, hide/dismiss toggle, viewing status, personal notes, or manual HR override.
     */
    @PutMapping("/{listingId}/workflow")
    public BuyerWorkflowState updatePropertyWorkflow(
            @PathVariable String listingId,
            @RequestBody BuyerWorkflowService.WorkflowUpdateRequest req) {
        return buyerWorkflowService.updateWorkflow(listingId, req);
    }

    public record HomeReportTextRequest(String rawText) {}

    /**
     * FEAT-06: Upload a Scottish Home Report PDF (or text file) to extract Market Valuation, m², £/m²,
     * Council Tax Band, EPC Band, and Single Survey Category 2/3 repair warnings.
     */
    @PostMapping(value = "/{listingId}/home-report", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadHomeReportPdf(
            @PathVariable String listingId,
            @RequestParam("file") MultipartFile file) {
        try {
            if (file == null || file.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Please select a Home Report PDF or text file."));
            }
            Integer askingHint = repository != null
                ? repository.findById(listingId).map(IndexedPropertyEntity::getPriceGbp).orElse(null)
                : null;
            HomeReportAnalyzerService.HomeReportAnalysis analysis =
                homeReportAnalyzerService.analyzePdfBytes(file.getBytes(), askingHint);
            BuyerWorkflowState updatedWorkflow = buyerWorkflowService.saveHomeReportAnalysis(listingId, analysis);
            return ResponseEntity.ok(Map.of(
                "listingId", listingId,
                "analysis", analysis,
                "workflow", updatedWorkflow,
                "summaryCounts", buyerWorkflowService.getSummary()
            ));
        } catch (IllegalArgumentException badReq) {
            return ResponseEntity.badRequest().body(Map.of("error", badReq.getMessage()));
        } catch (Exception e) {
            log.warn("Home Report upload parse error for {}: {}", listingId, e.getMessage());
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                .body(Map.of("error", "Failed to parse Home Report: " + e.getMessage()));
        }
    }

    /**
     * FEAT-06: Paste Single Survey / Home Report text directly for instant valuation & Category 2/3 repair analysis.
     */
    @PostMapping(value = "/{listingId}/home-report-text", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> analyzeHomeReportText(
            @PathVariable String listingId,
            @RequestBody HomeReportTextRequest req) {
        try {
            if (req == null || req.rawText() == null || req.rawText().isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Paste Single Survey / Home Report text to analyze."));
            }
            Integer askingHint = repository != null
                ? repository.findById(listingId).map(IndexedPropertyEntity::getPriceGbp).orElse(null)
                : null;
            HomeReportAnalyzerService.HomeReportAnalysis analysis =
                homeReportAnalyzerService.analyzeHomeReportText(req.rawText(), askingHint);
            BuyerWorkflowState updatedWorkflow = buyerWorkflowService.saveHomeReportAnalysis(listingId, analysis);
            return ResponseEntity.ok(Map.of(
                "listingId", listingId,
                "analysis", analysis,
                "workflow", updatedWorkflow,
                "summaryCounts", buyerWorkflowService.getSummary()
            ));
        } catch (IllegalArgumentException badReq) {
            return ResponseEntity.badRequest().body(Map.of("error", badReq.getMessage()));
        } catch (Exception e) {
            log.warn("Home Report text analysis error for {}: {}", listingId, e.getMessage());
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                .body(Map.of("error", "Failed to analyze Home Report text: " + e.getMessage()));
        }
    }
}
