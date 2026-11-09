/**
 * Alpine.js root controller for Glasgow Commuter & Family Layout AI Property Ranker (REF-05, REF-06, SIMP-05 & Phase 3).
 */
window.propertyAgentApp = function propertyAgentApp() {
    const State = window.PropertyAgentState;
    const Api = window.PropertyAgentApi;
    const MapEngine = window.GlasgowTransitMap;

    return {
        allPropertyTypes: State.ALL_PROPERTY_TYPES,
        allTransportModes: State.ALL_TRANSPORT_MODES,
        viewingStatusOptions: State.VIEWING_STATUS_OPTIONS,
        suggestions: [],
        stations: [],
        results: [],
        totalIndexedCount: 0,
        selectedProperty: null,
        activeModalPhoto: '',
        newRuleText: '',
        newRulePriority: 'HIGH',
        semanticQuery: '',
        semanticQueryActive: '',
        searchingSemantic: false,
        semanticResults: null,
        embeddingStats: { totalProperties: 0, embeddedProperties: 0, totalChunks: 0, embeddingModel: 'nomic-embed-text' },
        embeddingInProgress: false,
        scraping: false,
        clearingDb: false,
        scrapeStatus: '',
        reanalyzing: false,
        scrapeBannerDismissed: false,
        scrapePollTimer: null,
        pollingInFlight: false,
        scrapeJob: State.createDefaultScrapeJob(),
        llmStatus: {},
        ranking: false,
        rankStatus: '',
        rankSequenceId: 0,
        rankAbortController: null,
        rankDebounceTimer: null,
        scrapeEventSource: null,
        sseConnected: false,
        showLogs: false,
        logFilePath: './logs/property-agent.log',
        logLines: [],
        commuteReports: {},
        filters: State.createInitialFilters(),

        // AI chat assistant
        chatOpen: false,
        chatInput: '',
        chatBusy: false,
        chatShowDetails: false,
        chatMessages: [],
        chatHints: [
            'Summarise my current filters',
            'Why is property #1 marked with off-street parking?',
            'What is the average price of detached homes in Bearsden?',
            'Show recent error logs',
            'Fetch latest listings from Rightmove for Glasgow Central'
        ],

        // Phase 3 UI & Domain State (FEAT-01 .. FEAT-07)
        showSecondCommuter: false,
        showScottishFinance: true,
        offersOverSelectValue: 'AUTO',
        viewMode: 'LIST', // 'LIST', 'SPLIT', 'MAP'
        transitMapData: null,
        mapController: null,
        mapPreviewProperty: null,
        mapLayers: { rail: true, subway: true, schools: false, radius: true },
        workflowSummary: { shortlistedCount: 0, hiddenCount: 0, homeReportsCount: 0 },
        compareIds: [],
        compareCache: {},
        showCompareModal: false,
        modalViewingStatus: 'NONE',
        modalViewingNotes: '',
        savingWorkflow: false,
        workflowSavedMsg: '',
        hrRawText: '',
        hrAnalyzing: false,
        hrStatusMsg: '',
        hrErrorMsg: '',

        async initApp() {
            await this.loadScrapeStatus(true);
            this.initSse();
            await Promise.all([
                this.loadLlmStatus(),
                this.loadStations(),
                this.loadSuggestions(),
                this.fetchEmbeddingStats(),
                this.loadWorkflowSummary(),
                this.loadTransitMapData()
            ]);
            await this.runRanker();
            await this.loadLogs();
        },

        async loadWorkflowSummary() {
            try {
                this.workflowSummary = await Api.getWorkflowSummary();
            } catch (e) {}
        },

        async loadTransitMapData() {
            try {
                this.transitMapData = await Api.getTransitMapData(
                    this.filters.centerLocation,
                    this.filters.secondCenterLocation
                );
                this.refreshMap(false);
            } catch (e) {}
        },

        setViewMode(mode) {
            this.viewMode = mode;
            if (mode === 'SPLIT' || mode === 'MAP') {
                setTimeout(() => {
                    this.ensureMapInitialized();
                    this.refreshMap(true);
                }, 40);
            }
        },

        ensureMapInitialized() {
            if (this.mapController) return;
            const canvas = this.$refs && this.$refs.transitMapCanvas;
            if (!canvas || !MapEngine) return;
            this.mapController = MapEngine.createController(canvas, {
                onSelectProperty: (prop) => {
                    this.mapPreviewProperty = prop;
                }
            });
            this.mapController.setLayers(this.mapLayers);
        },

        refreshMap(autoFit) {
            if (this.viewMode !== 'SPLIT' && this.viewMode !== 'MAP') return;
            this.ensureMapInitialized();
            if (!this.mapController) return;
            this.mapController.updateData(this.transitMapData, this.results, this.filters, !!autoFit);
        },

        toggleMapLayer(layerKey) {
            this.mapLayers[layerKey] = !this.mapLayers[layerKey];
            if (this.mapController) {
                this.mapController.setLayers(this.mapLayers);
            }
        },

        mapZoom(factor) {
            if (this.mapController) {
                this.mapController.zoomBy(factor);
            }
        },

        mapResetFit() {
            if (this.mapController) {
                this.mapController.fitBounds();
            }
        },

        focusPropertyOnMap(prop) {
            if (this.viewMode === 'LIST') {
                this.setViewMode('SPLIT');
            }
            this.mapPreviewProperty = prop;
            setTimeout(() => {
                if (this.mapController) {
                    this.mapController.selectListing(prop.listingId);
                }
            }, 60);
        },

        toggleSecondCommuter() {
            this.showSecondCommuter = !this.showSecondCommuter;
            if (!this.showSecondCommuter && this.filters.secondCenterLocation) {
                this.filters.secondCenterLocation = '';
                this.loadTransitMapData();
                this.runRanker();
            }
        },

        onOffersOverModeChange() {
            if (this.offersOverSelectValue === 'AUTO') {
                this.filters.offersOverPremiumPct = null;
            } else {
                const parsed = parseInt(this.offersOverSelectValue, 10);
                this.filters.offersOverPremiumPct = isNaN(parsed) ? null : parsed;
            }
            this.triggerRanker(80);
        },

        setWorkflowTab(tab) {
            this.filters.workflowFilter = tab;
            this.runRanker();
        },

        async toggleShortlist(prop) {
            const current = !!(prop.workflow && prop.workflow.shortlisted);
            try {
                const updated = await Api.updateWorkflow(prop.listingId, { shortlisted: !current });
                prop.workflow = updated;
                if (this.selectedProperty && this.selectedProperty.listingId === prop.listingId) {
                    this.selectedProperty.workflow = updated;
                }
                await this.loadWorkflowSummary();
                if (this.filters.workflowFilter === 'SHORTLISTED' && !updated.shortlisted) {
                    await this.runRanker();
                } else {
                    this.refreshMap(false);
                }
            } catch (e) {
                console.warn('Shortlist update failed', e);
            }
        },

        async toggleHideProperty(prop) {
            const current = !!(prop.workflow && prop.workflow.hidden);
            try {
                const updated = await Api.updateWorkflow(prop.listingId, { hidden: !current });
                prop.workflow = updated;
                if (this.selectedProperty && this.selectedProperty.listingId === prop.listingId && updated.hidden) {
                    this.selectedProperty = null;
                }
                await this.loadWorkflowSummary();
                await this.runRanker();
            } catch (e) {
                console.warn('Hide toggle failed', e);
            }
        },

        isCompared(listingId) {
            return this.compareIds.includes(listingId);
        },

        toggleCompare(prop) {
            const idx = this.compareIds.indexOf(prop.listingId);
            if (idx !== -1) {
                this.compareIds.splice(idx, 1);
            } else {
                if (this.compareIds.length >= 4) {
                    this.compareIds.shift();
                }
                this.compareIds.push(prop.listingId);
                this.compareCache[prop.listingId] = prop;
            }
        },

        clearComparison() {
            this.compareIds = [];
            this.showCompareModal = false;
        },

        comparedProperties() {
            return this.compareIds
                .map(id => this.results.find(r => r.listingId === id) || this.compareCache[id])
                .filter(Boolean);
        },

        viewingStatusBadgeText(status) {
            switch (status) {
                case 'BOOKED': return '📅 Viewing Booked';
                case 'VISITED': return '✅ Visited';
                case 'OFFER_PLACED': return '📝 Offer Placed';
                default: return '';
            }
        },

        async saveModalWorkflow() {
            if (!this.selectedProperty) return;
            this.savingWorkflow = true;
            this.workflowSavedMsg = '';
            try {
                const updated = await Api.updateWorkflow(this.selectedProperty.listingId, {
                    viewingStatus: this.modalViewingStatus,
                    viewingNotes: this.modalViewingNotes
                });
                this.selectedProperty.workflow = updated;
                const match = this.results.find(r => r.listingId === this.selectedProperty.listingId);
                if (match) match.workflow = updated;
                this.compareCache[this.selectedProperty.listingId] = match || this.selectedProperty;
                this.workflowSavedMsg = '✓ Saved viewing status & notes to PostgreSQL';
            } catch (e) {
                this.workflowSavedMsg = '⚠ Failed to save: ' + e.message;
            } finally {
                this.savingWorkflow = false;
            }
        },

        async onHomeReportFileSelected(event) {
            const file = event && event.target && event.target.files && event.target.files[0];
            if (!file || !this.selectedProperty) return;
            const listingId = this.selectedProperty.listingId;
            this.hrAnalyzing = true;
            this.hrStatusMsg = '';
            this.hrErrorMsg = '';
            try {
                const resp = await Api.uploadHomeReportFile(listingId, file);
                await this.applyHomeReportResponse(listingId, resp);
            } catch (e) {
                this.hrErrorMsg = e.message || 'Could not parse Home Report PDF.';
            } finally {
                this.hrAnalyzing = false;
                if (event && event.target) event.target.value = '';
            }
        },

        async analyzePastedHomeReport() {
            if (!this.selectedProperty || !this.hrRawText.trim()) return;
            const listingId = this.selectedProperty.listingId;
            this.hrAnalyzing = true;
            this.hrStatusMsg = '';
            this.hrErrorMsg = '';
            try {
                const resp = await Api.analyzeHomeReportText(listingId, this.hrRawText);
                await this.applyHomeReportResponse(listingId, resp);
                this.hrRawText = '';
            } catch (e) {
                this.hrErrorMsg = e.message || 'Could not analyze Home Report text.';
            } finally {
                this.hrAnalyzing = false;
            }
        },

        async applyHomeReportResponse(listingId, resp) {
            if (resp.summaryCounts) {
                this.workflowSummary = resp.summaryCounts;
            } else {
                await this.loadWorkflowSummary();
            }
            await this.runRanker();
            const refreshed = this.results.find(r => r.listingId === listingId);
            if (refreshed && this.selectedProperty && this.selectedProperty.listingId === listingId) {
                refreshed.rawDescription = this.selectedProperty.rawDescription;
                refreshed.llmResponseLog = this.selectedProperty.llmResponseLog;
                this.selectedProperty = refreshed;
            } else if (this.selectedProperty && resp.workflow) {
                this.selectedProperty.workflow = resp.workflow;
            }
            this.hrStatusMsg = '✓ Home Report extracted & applied to Scottish Cash/LBTT & Score!';
        },

        async fetchEmbeddingStats() {
            try {
                this.embeddingStats = await Api.getEmbeddingStats();
                if (typeof this.embeddingStats.embeddingInProgress === 'boolean') {
                    this.embeddingInProgress = this.embeddingStats.embeddingInProgress;
                }
            } catch (e) {}
        },

        async triggerEmbedAll() {
            if (this.embeddingInProgress) return;
            this.embeddingInProgress = true;
            try {
                const res = await Api.triggerEmbedAll();
                if (!res.ok) {
                    await this.fetchEmbeddingStats();
                    if (!this.embeddingStats.embeddingInProgress) {
                        this.embeddingInProgress = false;
                    }
                    return;
                }
                const pollEmbeddings = async () => {
                    await this.fetchEmbeddingStats();
                    if (this.embeddingStats && this.embeddingStats.embeddingInProgress) {
                        setTimeout(pollEmbeddings, 1500);
                    } else {
                        this.embeddingInProgress = false;
                        if (this.filters.dynamicCriteria.length > 0) {
                            this.runRanker();
                        }
                    }
                };
                setTimeout(pollEmbeddings, 1000);
            } catch (e) {
                this.embeddingInProgress = false;
            }
        },

        async runSemanticSearch() {
            const q = this.semanticQuery.trim();
            if (!q) return;
            this.searchingSemantic = true;
            this.semanticQueryActive = q;

            const existingIdx = this.filters.dynamicCriteria.findIndex(c => c.rule.toLowerCase() === q.toLowerCase());
            if (existingIdx === -1) {
                this.filters.dynamicCriteria.unshift({ rule: q, priority: 'HIGH' });
            }
            this.runRanker();

            try {
                const data = await Api.semanticSearch(q);
                this.semanticResults = data.results || [];
            } catch (e) {
                this.semanticResults = [];
            } finally {
                this.searchingSemantic = false;
            }
        },

        clearSemanticSearch() {
            const q = (this.semanticQueryActive || '').toLowerCase();
            this.semanticResults = null;
            this.semanticQuery = '';
            this.semanticQueryActive = '';
            if (q) {
                const idx = this.filters.dynamicCriteria.findIndex(c => c.rule.toLowerCase() === q);
                if (idx !== -1) {
                    this.filters.dynamicCriteria.splice(idx, 1);
                    this.runRanker();
                }
            }
        },

        scrapeScopeWarning() {
            if (!this.scrapeJob || this.scrapeJob.running || this.totalIndexedCount === 0) return '';
            if (!this.scrapeJob.lastScrapedMaxPrice || this.scrapeJob.lastScrapedMaxPrice <= 0) return '';
            const reasons = [];
            if (this.filters.maxPrice > this.scrapeJob.lastScrapedMaxPrice) {
                reasons.push('Max Price (£' + Number(this.filters.maxPrice).toLocaleString() + ' > scraped £' + Number(this.scrapeJob.lastScrapedMaxPrice).toLocaleString() + ')');
            }
            if (this.filters.radiusMiles > (this.scrapeJob.lastScrapedRadiusMiles || 0) + 0.5) {
                reasons.push('Radius (' + this.filters.radiusMiles + ' mi > scraped ' + this.scrapeJob.lastScrapedRadiusMiles + ' mi)');
            }
            if (this.filters.minBedrooms < (this.scrapeJob.lastScrapedMinBedrooms || 1)) {
                reasons.push('Min Beds (' + this.filters.minBedrooms + ' < scraped ' + this.scrapeJob.lastScrapedMinBedrooms + ')');
            }
            const scrapedTypes = (this.scrapeJob.lastScrapedPropertyTypes || []).map(t => t.toLowerCase());
            if (scrapedTypes.length > 0 && scrapedTypes.length < 5) {
                const missingTypes = (this.filters.propertyTypes || []).filter(t => !scrapedTypes.includes(t.toLowerCase()));
                if (missingTypes.length > 0) {
                    reasons.push('Types (' + missingTypes.join(', ') + ' excluded in last scrape)');
                }
            }
            if (reasons.length === 0) return '';
            return 'Live sliders exceed last portal scrape scope: ' + reasons.join(' • ') + '.';
        },

        scrapeProgressPercent() {
            if (!this.scrapeJob || !this.scrapeJob.running) return 100;
            if (this.scrapeJob.currentImageTotal > 0) {
                return Math.max(6, Math.min(98, Math.round((this.scrapeJob.currentImageIndex / this.scrapeJob.currentImageTotal) * 100)));
            }
            if (this.scrapeJob.currentCorridorMaxPages > 0 && this.scrapeJob.currentPage > 0) {
                return Math.max(8, Math.min(95, Math.round((this.scrapeJob.currentPage / this.scrapeJob.currentCorridorMaxPages) * 100)));
            }
            if (this.scrapeJob.corridorsTotal > 0) {
                return Math.max(8, Math.min(95, Math.round((this.scrapeJob.corridorsCompleted / this.scrapeJob.corridorsTotal) * 100)));
            }
            return 15;
        },

        async loadScrapeStatus(syncScopeIfRunning = false) {
            try {
                const data = await Api.getScrapeStatus();
                this.scrapeJob = State.createDefaultScrapeJob(data);
                this.scraping = !!data.running;
                if (typeof data.totalIndexedCount === 'number') {
                    this.totalIndexedCount = data.totalIndexedCount;
                }
                if (syncScopeIfRunning && data.running && data.jobType === 'SCRAPE_AND_INDEX') {
                    if (data.targetCenter) this.filters.centerLocation = data.targetCenter;
                    if (data.targetRadiusMiles) this.filters.radiusMiles = data.targetRadiusMiles;
                }
                if (data.running) {
                    this.scrapeBannerDismissed = false;
                    this.startFallbackPollingIfNeeded();
                }
            } catch (e) {}
        },

        stopFallbackPolling() {
            if (this.scrapePollTimer) {
                clearInterval(this.scrapePollTimer);
                this.scrapePollTimer = null;
            }
        },

        initSse() {
            if (!window.EventSource || this.scrapeEventSource) {
                if (!window.EventSource) this.startFallbackPollingIfNeeded();
                return;
            }
            try {
                const es = new EventSource('/api/properties/scrape-events');
                es.onopen = () => {
                    this.sseConnected = true;
                    this.stopFallbackPolling();
                };
                es.addEventListener('status', async (e) => {
                    this.sseConnected = true;
                    this.stopFallbackPolling();
                    try {
                        const data = JSON.parse(e.data);
                        const prevNewly = this.scrapeJob ? (this.scrapeJob.newlyIndexedCount || 0) : 0;
                        const prevTotal = this.totalIndexedCount;
                        const wasRunning = this.scrapeJob && this.scrapeJob.running;

                        this.scrapeJob = State.createDefaultScrapeJob(data);
                        this.scraping = !!data.running;
                        if (typeof data.totalIndexedCount === 'number') {
                            this.totalIndexedCount = data.totalIndexedCount;
                        }

                        if ((data.newlyIndexedCount || 0) > prevNewly || data.totalIndexedCount !== prevTotal) {
                            await this.loadSuggestions();
                            await this.triggerRanker(150);
                        }
                        if (wasRunning && !data.running) {
                            this.scrapeStatus = data.statusMessage || '';
                            await this.loadLlmStatus();
                            await this.loadSuggestions();
                            await this.runRanker();
                            if (this.showLogs) await this.loadLogs();
                        }
                    } catch (err) {}
                });
                es.onerror = () => {
                    this.sseConnected = false;
                    if (this.scrapeJob && this.scrapeJob.running) {
                        this.startFallbackPollingIfNeeded();
                    }
                };
                this.scrapeEventSource = es;
            } catch (err) {
                this.sseConnected = false;
                this.startFallbackPollingIfNeeded();
            }
        },

        startFallbackPollingIfNeeded() {
            if (this.sseConnected || this.scrapePollTimer) return;
            this.scrapePollTimer = setInterval(async () => {
                if (this.sseConnected) {
                    this.stopFallbackPolling();
                    return;
                }
                if (this.pollingInFlight) return;
                this.pollingInFlight = true;
                try {
                    const prevTotal = this.totalIndexedCount;
                    const prevNewly = this.scrapeJob ? (this.scrapeJob.newlyIndexedCount || 0) : 0;
                    const data = await Api.getScrapeStatus();
                    this.scrapeJob = State.createDefaultScrapeJob(data);
                    this.scraping = !!data.running;
                    if (typeof data.totalIndexedCount === 'number') {
                        this.totalIndexedCount = data.totalIndexedCount;
                    }

                    if ((data.newlyIndexedCount || 0) > prevNewly || data.totalIndexedCount !== prevTotal) {
                        await this.loadSuggestions();
                        await this.runRanker();
                    }
                    if (this.showLogs) {
                        await this.loadLogs();
                    }

                    if (!data.running) {
                        this.stopFallbackPolling();
                        this.scrapeStatus = data.statusMessage || '';
                        await this.loadLlmStatus();
                        await this.loadSuggestions();
                        await this.runRanker();
                        await this.loadLogs();
                    }
                } catch (e) {
                } finally {
                    this.pollingInFlight = false;
                }
            }, 2000);
        },

        async loadStations() {
            try {
                this.stations = await Api.getStations();
            } catch (e) {
                this.stations = [];
            }
        },

        async loadLlmStatus() {
            try {
                this.llmStatus = await Api.getLlmStatus();
            } catch (e) {
                this.llmStatus = { modeDescription: 'LLM Status Check Failed' };
            }
        },

        async openPropertyModal(prop) {
            this.selectedProperty = prop;
            this.modalViewingStatus = (prop.workflow && prop.workflow.viewingStatus) ? prop.workflow.viewingStatus : 'NONE';
            this.modalViewingNotes = (prop.workflow && prop.workflow.viewingNotes) ? prop.workflow.viewingNotes : '';
            this.workflowSavedMsg = '';
            this.hrStatusMsg = '';
            this.hrErrorMsg = '';
            this.hrRawText = '';
            this.activeModalPhoto = (prop.allImageUrls && prop.allImageUrls.length > 0)
                ? prop.allImageUrls[0]
                : prop.thumbnailUrl;
            if (!prop.rawDescription || !prop.llmResponseLog) {
                try {
                    const detail = await Api.getPropertyDetail(prop.listingId);
                    prop.rawDescription = detail.rawDescription;
                    prop.llmResponseLog = detail.llmResponseLog;
                    if (this.selectedProperty && this.selectedProperty.listingId === prop.listingId) {
                        this.selectedProperty.rawDescription = detail.rawDescription;
                        this.selectedProperty.llmResponseLog = detail.llmResponseLog;
                    }
                } catch (e) {
                    console.warn('Could not fetch full property detail', e);
                }
            }
        },

        async runCommuteResearch(prop) {
            this.commuteReports = {
                ...this.commuteReports,
                [prop.listingId]: {
                    loading: true,
                    report: this.commuteReports[prop.listingId] ? this.commuteReports[prop.listingId].report : null,
                    showThinking: false,
                    showDossier: false,
                    hidden: false
                }
            };
            try {
                const data = await Api.researchCommute(
                    prop.listingId,
                    this.filters.centerLocation,
                    this.filters.transportModes
                );
                this.commuteReports = {
                    ...this.commuteReports,
                    [prop.listingId]: {
                        loading: false,
                        report: data,
                        showThinking: false,
                        showDossier: false,
                        hidden: false
                    }
                };
                if (this.showLogs) {
                    await this.loadLogs();
                }
            } catch (e) {
                this.commuteReports = {
                    ...this.commuteReports,
                    [prop.listingId]: {
                        loading: false,
                        report: {
                            modelUsed: 'Error',
                            targetLocation: this.filters.centerLocation || 'Glasgow Central',
                            llmThinking: '',
                            llmCommuteBreakdown: 'Failed to run LLM commute research: ' + e.message,
                            groundedDossier: ''
                        },
                        showThinking: false,
                        showDossier: false,
                        hidden: false
                    }
                };
            }
        },

        isCommuteLoading(listingId) {
            return !!(this.commuteReports && this.commuteReports[listingId] && this.commuteReports[listingId].loading);
        },

        hasCommuteReport(listingId) {
            return !!(this.commuteReports && this.commuteReports[listingId] && this.commuteReports[listingId].report);
        },

        isCommuteVisible(listingId) {
            return !!(this.commuteReports && this.commuteReports[listingId] && this.commuteReports[listingId].report && !this.commuteReports[listingId].hidden);
        },

        getCommuteReport(listingId) {
            return (this.commuteReports && this.commuteReports[listingId]) ? this.commuteReports[listingId].report : null;
        },

        getCommuteButtonLabel(listingId) {
            if (this.isCommuteLoading(listingId)) return 'Generating ScotRail/OSM + Gemma Research...';
            if (this.hasCommuteReport(listingId)) return 'Refresh LLM Commute Breakdown';
            return 'LLM Commute Breakdown';
        },

        toggleThinking(listingId) {
            if (!this.commuteReports[listingId]) return;
            this.commuteReports[listingId].showThinking = !this.commuteReports[listingId].showThinking;
            this.commuteReports = { ...this.commuteReports };
        },

        toggleDossier(listingId) {
            if (!this.commuteReports[listingId]) return;
            this.commuteReports[listingId].showDossier = !this.commuteReports[listingId].showDossier;
            this.commuteReports = { ...this.commuteReports };
        },

        closeCommuteReport(listingId) {
            if (!this.commuteReports[listingId]) return;
            this.commuteReports[listingId].hidden = true;
            this.commuteReports = { ...this.commuteReports };
        },

        async reanalyzeLlm(onlyFailed = false) {
            if (this.scrapeJob && this.scrapeJob.running) {
                this.scrapeBannerDismissed = false;
                return;
            }
            this.reanalyzing = true;
            this.scrapeBannerDismissed = false;
            try {
                const data = await Api.reanalyzeLlm(onlyFailed);
                this.scrapeJob = State.createDefaultScrapeJob(data);
                this.scraping = !!data.running;
                if (typeof data.totalIndexedCount === 'number') {
                    this.totalIndexedCount = data.totalIndexedCount;
                }
                this.scrapeStatus = data.statusMessage || '';
                if (data.running) {
                    this.startFallbackPollingIfNeeded();
                }
            } finally {
                this.reanalyzing = false;
            }
        },

        async toggleLogs() {
            this.showLogs = !this.showLogs;
            if (this.showLogs) {
                await this.loadLogs();
            }
        },

        async loadLogs() {
            try {
                const data = await Api.getLogs(220);
                this.logFilePath = data.logFilePath;
                this.totalIndexedCount = data.totalIndexedCount;
                this.logLines = data.lines || [];
            } catch (e) {
                this.logLines = ['Failed to load logs: ' + e.message];
            }
        },

        async loadSuggestions() {
            try {
                this.suggestions = await Api.getSuggestions();
            } catch (e) {
                this.suggestions = [];
            }
        },

        availableSuggestions() {
            const active = this.filters.dynamicCriteria.map(c => c.rule.toLowerCase());
            return this.suggestions.filter(s => !active.includes(s.toLowerCase()));
        },

        addCriterion() {
            if (!this.newRuleText.trim()) return;
            this.filters.dynamicCriteria.push({
                rule: this.newRuleText.trim(),
                priority: this.newRulePriority
            });
            this.newRuleText = '';
            this.runRanker();
        },

        addSuggestion(sug) {
            this.filters.dynamicCriteria.push({ rule: sug, priority: 'HIGH' });
            this.runRanker();
        },

        removeCriterion(idx) {
            const removed = this.filters.dynamicCriteria[idx];
            this.filters.dynamicCriteria.splice(idx, 1);
            if (removed && this.semanticQueryActive && removed.rule.toLowerCase() === this.semanticQueryActive.toLowerCase()) {
                this.semanticResults = null;
                this.semanticQuery = '';
                this.semanticQueryActive = '';
            }
            this.runRanker();
        },

        clearAllCriteria() {
            this.filters.dynamicCriteria = [];
            this.semanticResults = null;
            this.semanticQuery = '';
            this.semanticQueryActive = '';
            this.runRanker();
        },

        showAllDbProperties() {
            this.filters.bypassHardFilters = true;
            this.runRanker();
        },

        relaxSidebarFilters() {
            this.filters.bypassHardFilters = false;
            if (!this.scrapeJob || !this.scrapeJob.running) {
                this.filters.radiusMiles = 25.0;
            }
            this.filters.maxDoorToCentralCommuteMins = 60;
            this.filters.maxSecondCommuteMins = 60;
            this.filters.minServicesPerHour = 1;
            this.filters.maxPrice = Math.max(this.filters.maxPrice, 500000);
            this.filters.maxUpfrontCashGbp = 0;
            this.filters.minBedrooms = 1;
            this.filters.requireFreeStationParking = false;
            this.filters.requireStepFreeCategoryA = false;
            this.runRanker();
        },

        // ---- AI chat assistant ----
        toggleChat() {
            this.chatOpen = !this.chatOpen;
            if (this.chatOpen) this.scrollChat();
        },

        toggleChatDetails() {
            this.chatShowDetails = !this.chatShowDetails;
        },

        clearChat() {
            this.chatMessages = [];
            this.chatInput = '';
            this.chatBusy = false;
        },

        scrollChat() {
            this.$nextTick(() => {
                const el = this.$refs.chatBody;
                if (el) el.scrollTop = el.scrollHeight;
            });
        },

        buildChatContext() {
            const f = JSON.parse(JSON.stringify(this.filters));
            f.resultsShown = (this.results || []).length;
            f.totalIndexedProperties = this.totalIndexedCount;
            const top = (this.results || []).slice(0, 10).map((r, idx) => ({
                rank: idx + 1,
                displayRank: '#' + (idx + 1),
                listingId: r.listingId,
                address: r.address,
                type: r.propertyType,
                priceGbp: r.priceGbp,
                bedrooms: r.bedrooms,
                score: r.overallScore,
                doorToTargetMins: r.doorToTargetMins,
                station: r.nearestStationName
            }));
            return { filters: f, topResults: top };
        },

        async sendChat() {
            const text = (this.chatInput || '').trim();
            if (!text || this.chatBusy) return;
            const lower = text.toLowerCase();
            if (text === '/clear' || lower === 'clear' || lower === 'clear chat' || lower === 'clear session' || lower === 'reset chat' || lower === 'reset session') {
                this.clearChat();
                return;
            }
            if (text === '/thinking' || text === '/actions' || lower === 'show thinking' || lower === 'show actions' || lower === 'view actions' || lower === 'toggle actions') {
                this.chatShowDetails = !this.chatShowDetails;
                this.chatMessages.push({
                    role: 'assistant',
                    content: this.chatShowDetails ? '⚡ Enabled detailed thinking and tool actions display.' : '👁️ Hidden detailed thinking and tool actions.'
                });
                this.scrollChat();
                return;
            }
            if (lower === 'hide thinking' || lower === 'hide actions') {
                this.chatShowDetails = false;
                this.chatMessages.push({ role: 'assistant', content: '👁️ Hidden detailed thinking and tool actions.' });
                this.scrollChat();
                return;
            }
            this.chatMessages.push({ role: 'user', content: text });
            this.chatInput = '';
            this.chatBusy = true;
            this.scrollChat();
            try {
                const ctx = this.buildChatContext();
                const data = await Api.chat({
                    messages: this.chatMessages.map(m => ({ role: m.role, content: m.content })),
                    filters: ctx.filters,
                    topResults: ctx.topResults
                });
                const hasClear = (data.actions || []).some(a => a.type === 'clear_session');
                if (hasClear) {
                    this.clearChat();
                    this.chatMessages.push({
                        role: 'assistant',
                        content: data.reply || 'AI session cleared. How can I help you afresh?',
                        tools: data.toolTrace || [],
                        actions: data.actions || [],
                        thinking: data.thinking || '',
                        showDetails: false
                    });
                } else {
                    this.chatMessages.push({
                        role: 'assistant',
                        content: data.reply,
                        tools: data.toolTrace || [],
                        actions: data.actions || [],
                        thinking: data.thinking || '',
                        showDetails: false
                    });
                }
                for (const action of (data.actions || [])) {
                    if (action.type !== 'clear_session') {
                        await this.applyChatAction(action);
                    }
                }
            } catch (e) {
                this.chatMessages.push({ role: 'assistant', content: 'Sorry, the assistant request failed: ' + e.message });
            } finally {
                this.chatBusy = false;
                this.scrollChat();
            }
        },

        async applyChatAction(action) {
            if (!action || !action.type) return;
            if (action.type === 'apply_filters') {
                const allowed = ['maxPrice', 'minBedrooms', 'radiusMiles', 'centerLocation', 'maxDoorToCentralCommuteMins',
                    'minServicesPerHour', 'requireFreeStationParking', 'requireStepFreeCategoryA', 'propertyTypes', 'transportModes'];
                for (const key of allowed) {
                    if (action.filters && action.filters[key] !== undefined) {
                        this.filters[key] = action.filters[key];
                    }
                }
                if (action.clearCriteria) {
                    this.filters.dynamicCriteria = [];
                }
                for (const c of (action.addCriteria || [])) {
                    const exists = this.filters.dynamicCriteria.some(x => x.rule.toLowerCase() === c.rule.toLowerCase());
                    if (!exists) this.filters.dynamicCriteria.unshift({ rule: c.rule, priority: c.priority || 'HIGH' });
                }
                this.filters.bypassHardFilters = false;
                await this.runRanker();
            } else if (action.type === 'open_property') {
                const id = action.listingId;
                let prop = (this.results || []).find(p => String(p.listingId) === String(id));
                if (!prop) {
                    try {
                        prop = await Api.getPropertyDetail(id);
                    } catch (e) {
                        console.warn('Could not load property for modal', e);
                    }
                }
                if (prop) {
                    await this.openPropertyModal(prop);
                }
            } else if (action.type === 'refresh_workflow') {
                await this.loadWorkflowSummary();
                await this.runRanker();
            } else if (action.type === 'fetch_started') {
                this.scrapeBannerDismissed = false;
                await this.loadScrapeStatus(true);
                this.startFallbackPollingIfNeeded();
            } else if (action.type === 'clear_session') {
                this.clearChat();
            }
        },

        resetDefaultCriteria() {
            this.filters.bypassHardFilters = false;
            this.filters.dynamicCriteria = State.createResetCriteria();
            this.runRanker();
        },

        triggerRanker(delayMs = 250) {
            this.filters.bypassHardFilters = false;
            if (this.rankDebounceTimer) {
                clearTimeout(this.rankDebounceTimer);
            }
            this.rankDebounceTimer = setTimeout(() => {
                this.runRanker();
            }, delayMs);
        },

        async runRanker() {
            if (this.rankDebounceTimer) {
                clearTimeout(this.rankDebounceTimer);
                this.rankDebounceTimer = null;
            }
            if (this.rankAbortController) {
                try { this.rankAbortController.abort(); } catch (e) {}
            }
            this.rankAbortController = new AbortController();
            const signal = this.rankAbortController.signal;
            const seq = ++this.rankSequenceId;

            this.ranking = true;
            try {
                const [data] = await Promise.all([
                    Api.rankProperties(this.filters, signal),
                    this.loadTransitMapData()
                ]);
                if (seq === this.rankSequenceId) {
                    this.results = data;
                    for (const p of data) {
                        if (this.compareIds.includes(p.listingId)) {
                            this.compareCache[p.listingId] = p;
                        }
                    }
                    if (this.mapPreviewProperty) {
                        const updatedPreview = data.find(r => r.listingId === this.mapPreviewProperty.listingId);
                        this.mapPreviewProperty = updatedPreview || null;
                    }
                    this.refreshMap(false);
                    const topInfo = this.results.length > 0
                        ? `Top: ${this.results[0].address} (${this.results[0].overallScore}/100)`
                        : 'No matching properties';
                    const now = new Date().toLocaleTimeString();
                    this.rankStatus = `✓ Ranked ${this.results.length} properties at ${now} — ${topInfo}`;
                }
                if (this.showLogs) {
                    await this.loadLogs();
                }
            } catch (e) {
                if (e.name === 'AbortError') {
                    return;
                }
                if (seq === this.rankSequenceId) {
                    this.rankStatus = 'Rank error: ' + e.message;
                }
            } finally {
                if (seq === this.rankSequenceId) {
                    this.ranking = false;
                }
            }
        },

        async syncRightmove() {
            if (this.scrapeJob && this.scrapeJob.running) {
                this.scrapeBannerDismissed = false;
                return;
            }
            this.scraping = true;
            this.scrapeBannerDismissed = false;
            this.scrapeStatus = '';
            try {
                const data = await Api.triggerScrape(this.filters);
                this.scrapeJob = State.createDefaultScrapeJob(data);
                this.scraping = !!data.running;
                if (typeof data.totalIndexedCount === 'number') {
                    this.totalIndexedCount = data.totalIndexedCount;
                }
                this.scrapeStatus = data.statusMessage || '';
                if (data.running) {
                    this.startFallbackPollingIfNeeded();
                } else {
                    await this.loadSuggestions();
                    await this.runRanker();
                    await this.loadLogs();
                }
            } catch (e) {
                this.scraping = false;
                this.scrapeStatus = 'Failed to start background fetch: ' + e.message;
            }
        },

        async clearDatabase() {
            if (this.scrapeJob && this.scrapeJob.running) {
                return;
            }
            if (!confirm(`Clear all ${this.totalIndexedCount} indexed properties from PostgreSQL?`)) {
                return;
            }
            this.clearingDb = true;
            this.scrapeStatus = '';
            try {
                const { ok, data } = await Api.clearDatabase();
                if (!ok) {
                    this.scrapeStatus = data.status || 'Cannot clear database right now.';
                    await this.loadScrapeStatus();
                    return;
                }
                this.totalIndexedCount = 0;
                this.results = [];
                this.mapPreviewProperty = null;
                this.commuteReports = {};
                this.semanticResults = null;
                this.semanticQuery = '';
                this.semanticQueryActive = '';
                this.selectedProperty = null;
                this.embeddingInProgress = false;
                this.embeddingStats = {
                    ...this.embeddingStats,
                    totalProperties: 0,
                    embeddedProperties: 0,
                    totalChunks: 0,
                    embeddingInProgress: false,
                    batchProcessed: 0,
                    batchTotal: 0
                };
                this.scrapeJob = State.createDefaultScrapeJob({
                    targetCenter: this.filters.centerLocation,
                    targetRadiusMiles: this.filters.radiusMiles,
                    currentStep: 'Idle — database cleared',
                    statusMessage: data.status
                });
                this.scrapeStatus = data.status;
                this.rankStatus = 'Database cleared — 0 properties indexed';
                this.refreshMap(false);
                await this.loadSuggestions();
                await this.loadLogs();
            } finally {
                this.clearingDb = false;
            }
        },

        async cancelScrapeJob() {
            if (!confirm('Are you sure you want to stop the running background job?')) {
                return;
            }
            try {
                await Api.cancelJob();
                this.scrapeStatus = 'Stopping background job...';
            } catch (e) {
                console.warn('Failed to stop job', e);
            }
        }
    };
};
