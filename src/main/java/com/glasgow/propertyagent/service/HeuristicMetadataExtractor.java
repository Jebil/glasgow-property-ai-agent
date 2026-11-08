package com.glasgow.propertyagent.service;

import com.glasgow.propertyagent.model.ExtractedPropertyMetadata;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * REF-02: Deterministic regex-based property metadata parser used when local Ollama is offline
 * or if JSON synthesis encounters an error.
 */
@Component
public class HeuristicMetadataExtractor {

    private static final Pattern WC_PATTERN = Pattern.compile(
        "\\b(?:w\\.?c\\.?|cloakroom|downstairs toilet|guest (?:wc|toilet)|ground floor (?:wc|toilet))\\b",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern UTILITY_PATTERN = Pattern.compile(
        "\\butility (?:room|space|area)\\b|\\bseparate utility\\b|\\butility\\b(?!\\s*bill)",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern STUDY_WORD_PATTERN = Pattern.compile(
        "\\b(?:study|home office)\\b",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern GARAGE_WORD_PATTERN = Pattern.compile(
        "\\bgarage\\b",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern EPC_PATTERN = Pattern.compile(
        "\\bepc\\s*(?:rating|band|grade)?\\s*[:\\-]?\\s*([a-g])\\b",
        Pattern.CASE_INSENSITIVE
    );

    public ExtractedPropertyMetadata extract(String description) {
        String lower = description == null ? "" : description.toLowerCase();
        boolean wc = WC_PATTERN.matcher(lower).find();
        boolean ensuite = lower.contains("en-suite") || lower.contains("ensuite") || lower.contains("en suite") || lower.contains("master en");

        boolean loungeRear = lower.contains("rear lounge") || lower.contains("rear-facing lounge")
                || lower.contains("lounge to the rear") || lower.contains("lounge with french doors")
                || lower.contains("lounge with patio doors") || lower.contains("lounge with bi-fold")
                || lower.contains("living room with french doors") || lower.contains("family room to the rear");
        boolean hasPatioOrFrench = lower.contains("french doors") || lower.contains("patio doors")
                || lower.contains("bi-fold") || lower.contains("bifold") || lower.contains("sliding doors")
                || lower.contains("doors opening directly onto") || lower.contains("direct access to the rear garden");

        boolean directLivingGarden = loungeRear || ((lower.contains("lounge") && !lower.contains("lounge to front") && !lower.contains("front-facing lounge")) && hasPatioOrFrench && !lower.contains("kitchen/dining area features") && !lower.contains("dining kitchen with french"));
        boolean partialKitchenGarden = !directLivingGarden && hasPatioOrFrench;

        boolean utility = UTILITY_PATTERN.matcher(lower).find();

        boolean study = (lower.contains("home office") || lower.contains("study room") || lower.contains("dedicated office")
                || lower.contains("study/bedroom") || lower.contains("bedroom/study")
                || STUDY_WORD_PATTERN.matcher(lower).find())
                && !lower.contains("post office") && !lower.contains("sorting office") && !lower.contains("estate office") && !lower.contains("ticket office");

        boolean enclosed = lower.contains("enclosed") || lower.contains("fenced") || lower.contains("child-safe")
                || lower.contains("private rear garden") || lower.contains("secure rear garden");
        boolean extensionPotential = lower.contains("extension") || lower.contains("generous plot")
                || lower.contains("corner plot") || lower.contains("substantial plot") || lower.contains("additional lawn space to the side");

        List<String> highlights = new ArrayList<>();
        if (lower.contains("cul-de-sac")) highlights.add("Quiet cul-de-sac setting");
        if (lower.contains("south-facing") || lower.contains("south facing") || lower.contains("sunny rear garden")) highlights.add("South-facing / sunny rear garden");
        if (lower.contains("walk-in") || lower.contains("immaculate") || lower.contains("show home condition")) highlights.add("Walk-in modern condition");
        if (hasPatioOrFrench) highlights.add("Patio / French doors to rear garden");
        if (utility) highlights.add("Dedicated utility room");
        if (study) highlights.add("Dedicated home office / study");
        if (lower.contains("solar")) highlights.add("Solar panels installed");
        if (lower.contains("driveway") || lower.contains("monoblock")) highlights.add("Private driveway");
        if (lower.contains("fitted wardrobe") || lower.contains("built-in wardrobe") || lower.contains("integrated wardrobe") || lower.contains("sliding wardrobe") || lower.contains("fitted robe")) highlights.add("Fitted / built-in wardrobes");
        if (lower.contains("under-stairs") || lower.contains("under stairs") || lower.contains("storage cupboard") || lower.contains("walk-in cupboard")) highlights.add("Under-stairs / integrated storage cupboard");
        if (lower.contains("pantry") || lower.contains("larder")) highlights.add("Pantry / larder storage");
        if (lower.contains("eaves storage") || lower.contains("floored attic") || lower.contains("floored loft") || lower.contains("loft storage")) highlights.add("Floored attic / eaves storage");

        String sightline = directLivingGarden
            ? "Direct (Watch kids from sofa)"
            : (partialKitchenGarden ? "Partial via Kitchen/Diner patio doors" : "Standard window view");

        String orientation = (lower.contains("south-west") || lower.contains("south west")) ? "South-West"
            : ((lower.contains("south-facing") || lower.contains("south facing") || lower.contains("sunny rear")) ? "South" : "Unknown");

        String garageType = "None";
        if (lower.contains("integral garage") || lower.contains("integrated garage")) {
            garageType = "Integral";
        } else if (lower.contains("detached garage")) {
            garageType = "Detached";
        } else if (lower.contains("attached garage")) {
            garageType = "Attached";
        } else if (lower.contains("converted garage") || lower.contains("garage conversion")) {
            garageType = "Converted";
        } else if (GARAGE_WORD_PATTERN.matcher(lower).find()) {
            garageType = "Detached";
        }

        Integer drivewayCapacity = null;
        if (lower.contains("double driveway") || lower.contains("two-car") || lower.contains("two car") || lower.contains("space for two") || lower.contains("multiple vehicles") || lower.contains("multi-car")) {
            drivewayCapacity = 2;
        } else if (lower.contains("triple driveway") || lower.contains("three-car") || lower.contains("three car")) {
            drivewayCapacity = 3;
        } else if (lower.contains("driveway") || lower.contains("monoblock") || lower.contains("off-street parking")
                || lower.contains("off street parking") || lower.contains("allocated parking")
                || lower.contains("dedicated parking") || lower.contains("private parking")
                || lower.contains("residents parking") || lower.contains("resident parking")
                || lower.contains("allocated space") || lower.contains("parking space")) {
            drivewayCapacity = 1;
        }

        Matcher epcMatcher = EPC_PATTERN.matcher(lower);
        String epcRating = epcMatcher.find() ? epcMatcher.group(1).toUpperCase() : null;

        String heatingType = null;
        if (lower.contains("gas central heating") || lower.contains("gas heating") || lower.contains("gch")) {
            heatingType = "Gas Central Heating";
        } else if (lower.contains("heat pump") || lower.contains("air source")) {
            heatingType = "Air Source Heat Pump";
        } else if (lower.contains("electric heating") || lower.contains("electric storage")) {
            heatingType = "Electric";
        } else if (lower.contains("district heating")) {
            heatingType = "District Heating";
        }

        String condition = (lower.contains("walk-in") || lower.contains("immaculate") || lower.contains("show home"))
            ? "Walk-in / Modern"
            : (lower.contains("renovation") || lower.contains("modernisation") || lower.contains("modernization") || lower.contains("in need of upgrade")
                ? "Needs Renovation" : "Good / Minor Cosmetic");

        return new ExtractedPropertyMetadata(
            wc,
            ensuite,
            (wc && ensuite) ? 3 : (ensuite || wc ? 2 : 1),
            directLivingGarden ? "Rear" : "Front",
            directLivingGarden,
            sightline,
            lower.contains("kitchen/dining") || lower.contains("kitchen/diner") || lower.contains("dining kitchen")
                ? "Open-plan Kitchen/Diner" : "Fitted Kitchen",
            extensionPotential,
            utility,
            study,
            garageType,
            enclosed,
            orientation,
            drivewayCapacity,
            condition,
            epcRating,
            heatingType,
            highlights
        );
    }
}
