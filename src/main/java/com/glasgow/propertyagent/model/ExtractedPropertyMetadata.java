package com.glasgow.propertyagent.model;

import java.util.List;

/**
 * Exhaustive property metadata schema populated ONCE per listing by the Vision + Text LLM Agent.
 * Stored as JSON in the local database so any future criteria can be queried without re-scraping.
 */
public record ExtractedPropertyMetadata(
    Boolean hasDownstairsWc,
    Boolean hasMasterEnsuite,
    Integer totalBathrooms,
    String livingRoomLocation,              // "Rear", "Front", "Through-Lounge"
    Boolean directGardenAccessFromLiving,   // True if patio/French/bi-fold doors open from living/family room to garden
    String gardenSightlineFromSofa,         // "Direct (Watch kids from sofa)", "Partial via Kitchen/Diner", "None"
    String kitchenLayout,                   // "Open-plan Kitchen/Diner", "Separate Rear Kitchen", "Galley"
    Boolean kitchenExtensionPotentialToRear,// True if plot allows extending kitchen outward into garden
    Boolean hasUtilityRoom,
    Boolean hasDedicatedStudyOrOffice,
    String garageType,                      // "Integral", "Detached", "Converted", "None"
    Boolean gardenEnclosedForKids,          // True if fenced/walled flat lawn suitable for young children
    String gardenOrientation,               // "South", "South-West", "West", "East", "North", "Unknown"
    Integer drivewayCarCapacity,
    String interiorCondition,               // "Walk-in / Modern", "Good / Minor Cosmetic", "Needs Renovation"
    String epcRating,
    String heatingType,
    List<String> extractedHighlights        // Raw selling points used to populate dynamic UI suggestion chips
) {}
