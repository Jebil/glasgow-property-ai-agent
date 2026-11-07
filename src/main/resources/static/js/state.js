/**
 * Shared frontend state factories and constants for Glasgow Property AI Agent (REF-06, SIMP-05, Phase 3).
 */
window.PropertyAgentState = (function () {
    const ALL_PROPERTY_TYPES = ['Detached', 'Semi-Detached', 'Terraced', 'Bungalow', 'Flat'];

    const ALL_TRANSPORT_MODES = [
        { id: 'Train', label: '🚆 Train' },
        { id: 'Subway', label: '🚇 Subway' },
        { id: 'Bus', label: '🚌 Bus' }
    ];

    const VIEWING_STATUS_OPTIONS = [
        { id: 'NONE', label: 'No Viewing Yet' },
        { id: 'BOOKED', label: '📅 Viewing Booked' },
        { id: 'VISITED', label: '✅ Visited' },
        { id: 'OFFER_PLACED', label: '📝 Offer Placed' }
    ];

    function createInitialCriteria() {
        return [
            { rule: 'Public transport (Train/Subway/Bus) to target <= 30 mins', priority: 'HIGH' },
            { rule: 'High frequency public transport (>= 6 services/hr)', priority: 'HIGH' },
            { rule: 'Nearest station has Free Park & Ride', priority: 'MEDIUM' },
            { rule: 'Downstairs WC present on ground floor', priority: 'HIGH' },
            { rule: 'Master bedroom has attached en-suite', priority: 'MEDIUM' },
            { rule: 'Living room direct access & sightline to garden', priority: 'HIGH' },
            { rule: 'Plot allows outward rear kitchen extension into garden', priority: 'MEDIUM' }
        ];
    }

    function createResetCriteria() {
        return [
            { rule: 'Public transport (Train/Subway/Bus) to target <= 25 mins', priority: 'MUST_HAVE' },
            { rule: 'High frequency public transport (>= 6 services/hr)', priority: 'HIGH' },
            { rule: 'Nearest station has Free Park & Ride', priority: 'HIGH' },
            { rule: 'Downstairs WC present on ground floor', priority: 'MUST_HAVE' },
            { rule: 'Master bedroom has attached en-suite', priority: 'HIGH' },
            { rule: 'Living room direct access & sightline to garden', priority: 'HIGH' },
            { rule: 'Plot allows outward rear kitchen extension into garden', priority: 'MEDIUM' }
        ];
    }

    /**
     * SIMP-05: Single canonical scrapeJob factory preventing field drift between initialization and DB reset.
     */
    function createDefaultScrapeJob(overrides) {
        return Object.assign({
            running: false,
            accepted: true,
            jobType: 'IDLE',
            state: 'IDLE',
            targetCenter: 'Glasgow Central',
            targetRadiusMiles: 15.0,
            currentCorridor: '',
            corridorsCompleted: 0,
            corridorsTotal: 0,
            currentPage: 0,
            currentCorridorMaxPages: 0,
            totalPagesFetched: 0,
            currentListingId: '',
            currentListingAddress: '',
            currentStep: '',
            currentImageIndex: 0,
            currentImageTotal: 0,
            newlyIndexedCount: 0,
            skippedExistingCount: 0,
            priceChangesDetectedCount: 0,
            failedItemCount: 0,
            totalIndexedCount: 0,
            startedAt: '',
            finishedAt: '',
            elapsedSeconds: 0,
            statusMessage: '',
            lastScrapedCenter: '',
            lastScrapedRadiusMiles: 0,
            lastScrapedMaxPrice: 0,
            lastScrapedMinBedrooms: 0,
            lastScrapedPropertyTypes: []
        }, overrides || {});
    }

    function createInitialFilters() {
        return {
            portalProvider: 'rightmove',
            centerLocation: 'Glasgow Central',
            radiusMiles: 15.0,
            propertyTypes: ALL_PROPERTY_TYPES.slice(),
            transportModes: ['Train', 'Subway', 'Bus'],
            minServicesPerHour: 2,
            maxPrice: 500000,
            minBedrooms: 1,
            maxDoorToCentralCommuteMins: 45,
            requireFreeStationParking: false,
            requireStepFreeCategoryA: false,
            bypassHardFilters: false,
            // FEAT-04: Partner B / Dual-Commuter Household
            secondCenterLocation: '',
            maxSecondCommuteMins: 45,
            secondTransportModes: ['Train', 'Subway', 'Bus'],
            // FEAT-01: Scottish Offers Over, Home Report LTV & LBTT Upfront Cash
            offersOverPremiumPct: null,
            depositPct: 15,
            firstTimeBuyer: false,
            additionalDwelling: false,
            maxUpfrontCashGbp: 0,
            // FEAT-05: Buyer Workflow Tab ("ALL", "SHORTLISTED", "HIDDEN")
            workflowFilter: 'ALL',
            dynamicCriteria: createInitialCriteria()
        };
    }

    return {
        ALL_PROPERTY_TYPES: ALL_PROPERTY_TYPES,
        ALL_TRANSPORT_MODES: ALL_TRANSPORT_MODES,
        VIEWING_STATUS_OPTIONS: VIEWING_STATUS_OPTIONS,
        createInitialCriteria: createInitialCriteria,
        createResetCriteria: createResetCriteria,
        createDefaultScrapeJob: createDefaultScrapeJob,
        createInitialFilters: createInitialFilters
    };
})();
