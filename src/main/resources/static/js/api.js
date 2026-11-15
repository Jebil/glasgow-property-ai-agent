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
        },
        chatStream: async function (payload, handlers) {
            const h = handlers || {};
            let donePayload = null;
            try {
                const res = await fetch('/api/chat/stream', {
                    method: 'POST',
                    headers: {
                        'Content-Type': 'application/json',
                        'Accept': 'text/event-stream'
                    },
                    body: JSON.stringify(payload)
                });
                if (!res.ok || !res.body) {
                    return this.chat(payload);
                }
                const reader = res.body.getReader();
                const decoder = new TextDecoder('utf-8');
                let buffer = '';

                const dispatchEvent = function (eventName, rawData) {
                    if (!rawData) return;
                    let parsed;
                    try {
                        parsed = JSON.parse(rawData);
                    } catch (e) {
                        return;
                    }
                    if (eventName === 'status' && h.onStatus) {
                        h.onStatus(parsed.message || '');
                    } else if (eventName === 'thinking' && h.onThinking) {
                        h.onThinking(parsed.thinking || '');
                    } else if (eventName === 'tool' && h.onTool) {
                        h.onTool(parsed.tool || '');
                    } else if (eventName === 'done') {
                        donePayload = parsed;
                        if (h.onDone) h.onDone(parsed);
                    } else if (eventName === 'error') {
                        const err = new Error(parsed.message || 'Chat stream failed');
                        err.isStreamError = true;
                        throw err;
                    }
                };

                while (true) {
                    const { value, done } = await reader.read();
                    if (done) break;
                    buffer += decoder.decode(value, { stream: true });
                    let sepIdx;
                    while ((sepIdx = buffer.indexOf('\n\n')) !== -1) {
                        const block = buffer.slice(0, sepIdx);
                        buffer = buffer.slice(sepIdx + 2);
                        let eventName = 'message';
                        const dataLines = [];
                        for (const line of block.split(/\r?\n/)) {
                            if (line.startsWith('event:')) {
                                eventName = line.slice(6).trim();
                            } else if (line.startsWith('data:')) {
                                dataLines.push(line.slice(5).trim());
                            }
                        }
                        if (dataLines.length > 0) {
                            dispatchEvent(eventName, dataLines.join('\n'));
                        }
                    }
                }
                if (donePayload) {
                    return donePayload;
                }
            } catch (e) {
                if (e && e.isStreamError) {
                    throw e;
                }
            }
            return this.chat(payload);
        }
    };
})();
