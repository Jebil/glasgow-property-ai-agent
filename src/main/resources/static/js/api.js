/**
 * Typed HTTP client helpers for /api/properties endpoints (REF-06 & Phase 3).
 */
window.PropertyAgentApi = (function () {
    async function fetchJson(url, options) {
        const res = await fetch(url, options);
        if (!res.ok) {
            const errData = await res.json().catch(function () { return {}; });
            const msg = errData.message || errData.status || errData.error || ('HTTP ' + res.status);
            const err = new Error(msg);
            err.status = res.status;
            err.payload = errData;
            throw err;
        }
        return res.json();
    }

    return {
        getScrapeStatus: function () {
            return fetchJson('/api/properties/scrape-status');
        },
        getStations: function () {
            return fetchJson('/api/properties/stations');
        },
        getTransitMapData: function (center, secondCenter) {
            const params = new URLSearchParams();
            if (center) params.set('center', center);
            if (secondCenter) params.set('secondCenter', secondCenter);
            const qs = params.toString();
            return fetchJson('/api/properties/transit-map-data' + (qs ? '?' + qs : ''));
        },
        getWorkflowSummary: function () {
            return fetchJson('/api/properties/workflow-summary');
        },
        updateWorkflow: function (listingId, payload) {
            return fetchJson('/api/properties/' + encodeURIComponent(listingId) + '/workflow', {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            });
        },
        uploadHomeReportFile: function (listingId, file) {
            const formData = new FormData();
            formData.append('file', file);
            return fetchJson('/api/properties/' + encodeURIComponent(listingId) + '/home-report', {
                method: 'POST',
                body: formData
            });
        },
        analyzeHomeReportText: function (listingId, rawText) {
            return fetchJson('/api/properties/' + encodeURIComponent(listingId) + '/home-report-text', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ rawText: rawText })
            });
        },
        getLlmStatus: function () {
            return fetchJson('/api/properties/llm-status');
        },
        getSuggestions: function () {
            return fetchJson('/api/properties/suggestions');
        },
        getLogs: function (lines) {
            return fetchJson('/api/properties/logs?lines=' + (lines || 220));
        },
        getEmbeddingStats: function () {
            return fetchJson('/api/properties/embedding-stats');
        },
        triggerEmbedAll: function () {
            return fetch('/api/properties/embed-all', { method: 'POST' });
        },
        semanticSearch: function (query) {
            return fetchJson('/api/properties/semantic-search?q=' + encodeURIComponent(query));
        },
        getPropertyDetail: function (listingId) {
            return fetchJson('/api/properties/' + encodeURIComponent(listingId));
        },
        researchCommute: function (listingId, targetLocation, transportModes) {
            return fetchJson('/api/properties/' + encodeURIComponent(listingId) + '/commute-research', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    targetLocation: targetLocation,
                    transportModes: transportModes
                })
            });
        },
        rankProperties: function (filters, abortSignal) {
            return fetchJson('/api/properties/rank', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(filters),
                signal: abortSignal
            });
        },
        triggerScrape: async function (filters) {
            const res = await fetch('/api/properties/scrape', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(filters)
            });
            return res.json();
        },
        reanalyzeLlm: async function (onlyFailed) {
            const url = '/api/properties/reanalyze-llm?limit=0&onlyFailed=' + (onlyFailed ? 'true' : 'false');
            const res = await fetch(url, { method: 'POST' });
            return res.json();
        },
        clearDatabase: async function () {
            const res = await fetch('/api/properties/clear?confirm=true', { method: 'DELETE' });
            const data = await res.json();
            return { ok: res.ok, data: data };
        },
        cancelJob: function () {
            return fetch('/api/properties/cancel-job', { method: 'POST' });
        },
        chat: function (payload) {
            return fetchJson('/api/chat', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            });
        }
    };
})();
