/**
 * FEAT-02: Zero-API-Key Offline Interactive Greater Glasgow Rail, SPT Subway & Property Score Map.
 * Renders equirectangular/cosine-scaled Mercator canvas at 55.86°N with smooth pan, mouse-wheel zoom,
 * hover tooltips, ScotRail corridor lines + 143 stations, 15-stop SPT Subway loop, search radius circle,
 * Partner A & Partner B target pins, and scored 0-100 property pins.
 */
window.GlasgowTransitMap = (function () {
    const REF_LAT_RAD = (55.86 * Math.PI) / 180.0;
    const COS_LAT = Math.cos(REF_LAT_RAD); // ~0.561 at Glasgow latitude
    const MILES_PER_DEG_LAT = 69.05;
    const MILES_PER_DEG_LON = 69.05 * COS_LAT;

    // Approximate River Clyde centerline for immediate geographic orientation across Greater Glasgow
    const RIVER_CLYDE_TRACE = [
        [55.940, -4.640], [55.926, -4.540], [55.902, -4.465], [55.884, -4.405],
        [55.870, -4.355], [55.865, -4.315], [55.856, -4.275], [55.854, -4.250],
        [55.845, -4.225], [55.836, -4.185], [55.829, -4.135], [55.814, -4.095],
        [55.792, -4.045], [55.776, -3.995]
    ];

    function createController(canvas, callbacks) {
        let state = {
            centerLat: 55.8591,
            centerLon: -4.2581,
            pixelsPerMile: 16.0,
            dragging: false,
            dragMoved: false,
            lastMouseX: 0,
            lastMouseY: 0,
            hoverItem: null,
            selectedListingId: null,
            layers: {
                rail: true,
                subway: true,
                schools: false,
                radius: true
            },
            mapData: {
                centerCoords: [55.8591, -4.2581],
                secondCenterCoords: null,
                scotrailStations: [],
                subwayHubs: [],
                schoolAreas: []
            },
            filters: {
                centerLocation: 'Glasgow Central',
                secondCenterLocation: '',
                radiusMiles: 15.0
            },
            properties: []
        };

        let hitTargets = [];

        function project(lat, lon, width, height) {
            const dxMiles = (lon - state.centerLon) * MILES_PER_DEG_LON;
            const dyMiles = (lat - state.centerLat) * MILES_PER_DEG_LAT;
            return {
                x: width * 0.5 + dxMiles * state.pixelsPerMile,
                y: height * 0.5 - dyMiles * state.pixelsPerMile
            };
        }

        function unproject(x, y, width, height) {
            const dxMiles = (x - width * 0.5) / state.pixelsPerMile;
            const dyMiles = (height * 0.5 - y) / state.pixelsPerMile;
            return {
                lat: state.centerLat + dyMiles / MILES_PER_DEG_LAT,
                lon: state.centerLon + dxMiles / MILES_PER_DEG_LON
            };
        }

        function fitBounds() {
            const rect = canvas.getBoundingClientRect();
            const width = Math.max(320, rect.width || 680);
            const height = Math.max(260, rect.height || 420);

            const cCoords = state.mapData.centerCoords || [55.8591, -4.2581];
            const pts = [[cCoords[0], cCoords[1]]];

            if (state.mapData.secondCenterCoords && state.filters.secondCenterLocation) {
                pts.push([state.mapData.secondCenterCoords[0], state.mapData.secondCenterCoords[1]]);
            }
            for (const p of state.properties) {
                if (p.latitude && p.longitude) {
                    pts.push([p.latitude, p.longitude]);
                }
            }

            if (pts.length <= 1) {
                state.centerLat = cCoords[0];
                state.centerLon = cCoords[1];
                const radius = Math.max(3.0, state.filters.radiusMiles || 15.0);
                state.pixelsPerMile = Math.max(5.5, Math.min(48.0, (Math.min(width, height) * 0.42) / radius));
                render();
                return;
            }

            let minLat = 90, maxLat = -90, minLon = 180, maxLon = -180;
            for (const [la, lo] of pts) {
                if (la < minLat) minLat = la;
                if (la > maxLat) maxLat = la;
                if (lo < minLon) minLon = lo;
                if (lo > maxLon) maxLon = lo;
            }
            state.centerLat = (minLat + maxLat) * 0.5;
            state.centerLon = (minLon + maxLon) * 0.5;

            const spanMilesX = Math.max(2.5, (maxLon - minLon) * MILES_PER_DEG_LON * 1.35);
            const spanMilesY = Math.max(2.5, (maxLat - minLat) * MILES_PER_DEG_LAT * 1.35);
            state.pixelsPerMile = Math.max(5.0, Math.min(55.0, Math.min(width / spanMilesX, height / spanMilesY)));
            render();
        }

        function render() {
            if (!canvas) return;
            const rect = canvas.getBoundingClientRect();
            const width = Math.max(300, Math.floor(rect.width || 680));
            const height = Math.max(260, Math.floor(rect.height || 420));
            const dpr = window.devicePixelRatio || 1;
            if (canvas.width !== Math.floor(width * dpr) || canvas.height !== Math.floor(height * dpr)) {
                canvas.width = Math.floor(width * dpr);
                canvas.height = Math.floor(height * dpr);
            }
            const ctx = canvas.getContext('2d');
            ctx.save();
            ctx.scale(dpr, dpr);

            // 1. Dark slate cartographic backdrop
            ctx.fillStyle = '#0f172a';
            ctx.fillRect(0, 0, width, height);

            // Subtle mile grid lines
            const gridStepMiles = state.pixelsPerMile >= 22 ? 1 : (state.pixelsPerMile >= 10 ? 2 : 5);
            const gridPx = gridStepMiles * state.pixelsPerMile;
            ctx.strokeStyle = 'rgba(148, 163, 184, 0.08)';
            ctx.lineWidth = 1;
            for (let x = (width * 0.5) % gridPx; x < width; x += gridPx) {
                ctx.beginPath(); ctx.moveTo(x, 0); ctx.lineTo(x, height); ctx.stroke();
            }
            for (let y = (height * 0.5) % gridPx; y < height; y += gridPx) {
                ctx.beginPath(); ctx.moveTo(0, y); ctx.lineTo(width, y); ctx.stroke();
            }

            // 2. River Clyde geographic orientation ribbon
            ctx.beginPath();
            RIVER_CLYDE_TRACE.forEach(([la, lo], idx) => {
                const pt = project(la, lo, width, height);
                if (idx === 0) ctx.moveTo(pt.x, pt.y);
                else ctx.lineTo(pt.x, pt.y);
            });
            ctx.strokeStyle = 'rgba(56, 189, 248, 0.22)';
            ctx.lineWidth = Math.max(3, Math.min(10, state.pixelsPerMile * 0.32));
            ctx.lineCap = 'round';
            ctx.lineJoin = 'round';
            ctx.stroke();

            hitTargets = [];

            // 3. Search Radius Circle around Partner A Target
            const cCoords = state.mapData.centerCoords || [55.8591, -4.2581];
            const centerPt = project(cCoords[0], cCoords[1], width, height);
            if (state.layers.radius && state.filters.radiusMiles > 0) {
                const radiusPx = state.filters.radiusMiles * state.pixelsPerMile;
                ctx.beginPath();
                ctx.arc(centerPt.x, centerPt.y, radiusPx, 0, Math.PI * 2);
                ctx.fillStyle = 'rgba(99, 102, 241, 0.06)';
                ctx.fill();
                ctx.setLineDash([6, 5]);
                ctx.strokeStyle = 'rgba(129, 140, 248, 0.55)';
                ctx.lineWidth = 1.5;
                ctx.stroke();
                ctx.setLineDash([]);
            }

            // 4. Optional School Catchment / Neighbourhood Hubs Layer
            if (state.layers.schools && state.mapData.schoolAreas) {
                for (const area of state.mapData.schoolAreas) {
                    const pt = project(area.lat, area.lon, width, height);
                    if (pt.x < -60 || pt.x > width + 60 || pt.y < -60 || pt.y > height + 60) continue;
                    const radPx = Math.max(10, (area.radiusMiles || 1.5) * state.pixelsPerMile * 0.55);
                    const isTop = area.attainmentTier === 'TOP_TIER';
                    ctx.beginPath();
                    ctx.arc(pt.x, pt.y, radPx, 0, Math.PI * 2);
                    ctx.fillStyle = isTop ? 'rgba(16, 185, 129, 0.10)' : 'rgba(148, 163, 184, 0.07)';
                    ctx.fill();
                    ctx.strokeStyle = isTop ? 'rgba(52, 211, 153, 0.35)' : 'rgba(148, 163, 184, 0.22)';
                    ctx.lineWidth = 1;
                    ctx.stroke();
                    if (state.pixelsPerMile >= 11) {
                        ctx.fillStyle = isTop ? 'rgba(167, 243, 208, 0.85)' : 'rgba(203, 213, 225, 0.65)';
                        ctx.font = '600 9px ui-sans-serif, system-ui, sans-serif';
                        ctx.textAlign = 'center';
                        ctx.fillText(area.neighbourhoodName.split('&')[0].trim(), pt.x, pt.y + 3);
                    }
                }
            }

            // 5. ScotRail Corridors & Stations (143 stations)
            if (state.layers.rail && state.mapData.scotrailStations) {
                const groups = {};
                for (const st of state.mapData.scotrailStations) {
                    const g = st.corridorGroup || 'OTHER';
                    if (!groups[g]) groups[g] = [];
                    groups[g].push(st);
                }
                const centralPt = project(55.8591, -4.2581, width, height);
                const queenStPt = project(55.8624, -4.2512, width, height);

                // Subtle radial corridor spokes connecting stations along each corridor
                ctx.lineWidth = 1.2;
                ctx.strokeStyle = 'rgba(56, 189, 248, 0.22)';
                Object.values(groups).forEach((list) => {
                    const sorted = list.slice().sort((a, b) => a.fastestMins - b.fastestMins);
                    if (sorted.length === 0) return;
                    const hubPt = sorted[0].queenStPrimary ? queenStPt : centralPt;
                    ctx.beginPath();
                    ctx.moveTo(hubPt.x, hubPt.y);
                    for (const s of sorted) {
                        const p = project(s.lat, s.lon, width, height);
                        ctx.lineTo(p.x, p.y);
                    }
                    ctx.stroke();
                });

                for (const st of state.mapData.scotrailStations) {
                    const pt = project(st.lat, st.lon, width, height);
                    if (pt.x < -20 || pt.x > width + 20 || pt.y < -20 || pt.y > height + 20) continue;
                    const r = st.tphPeak >= 6 ? 3.8 : (st.tphPeak >= 4 ? 3.1 : 2.4);
                    ctx.beginPath();
                    ctx.arc(pt.x, pt.y, r, 0, Math.PI * 2);
                    ctx.fillStyle = st.freePr ? '#38bdf8' : '#94a3b8';
                    ctx.fill();
                    if (st.freePr && state.pixelsPerMile >= 12) {
                        ctx.strokeStyle = '#34d399';
                        ctx.lineWidth = 1;
                        ctx.stroke();
                    }
                    if (state.pixelsPerMile >= 19 && st.tphPeak >= 4) {
                        ctx.fillStyle = 'rgba(226, 232, 240, 0.78)';
                        ctx.font = '500 9px ui-sans-serif, system-ui, sans-serif';
                        ctx.textAlign = 'left';
                        ctx.fillText(st.name, pt.x + 5, pt.y + 3);
                    }
                    hitTargets.push({
                        kind: 'station',
                        x: pt.x,
                        y: pt.y,
                        r: r + 4,
                        title: '🚆 ' + st.name + ' (' + st.tphPeak + '/hr peak)',
                        subtitle: st.fastestMins + 'm fastest • ' + (st.freePr ? ('Free P&R (' + st.prSpaces + ' sp)') : 'No Free P&R') + ' • Cat ' + st.accessCat
                    });
                }
            }

            // 6. SPT Glasgow Subway Circle (15 stations in loop 0..14 -> 0)
            if (state.layers.subway && state.mapData.subwayHubs && state.mapData.subwayHubs.length > 0) {
                const hubs = state.mapData.subwayHubs;
                ctx.beginPath();
                hubs.forEach((h, idx) => {
                    const pt = project(h.lat, h.lon, width, height);
                    if (idx === 0) ctx.moveTo(pt.x, pt.y);
                    else ctx.lineTo(pt.x, pt.y);
                });
                const firstPt = project(hubs[0].lat, hubs[0].lon, width, height);
                ctx.lineTo(firstPt.x, firstPt.y);
                ctx.strokeStyle = 'rgba(249, 115, 22, 0.85)';
                ctx.lineWidth = 2.4;
                ctx.stroke();

                for (const h of hubs) {
                    const pt = project(h.lat, h.lon, width, height);
                    ctx.beginPath();
                    ctx.arc(pt.x, pt.y, 3.6, 0, Math.PI * 2);
                    ctx.fillStyle = '#fb923c';
                    ctx.fill();
                    ctx.strokeStyle = '#0f172a';
                    ctx.lineWidth = 1.2;
                    ctx.stroke();
                    hitTargets.push({
                        kind: 'subway',
                        x: pt.x,
                        y: pt.y,
                        r: 7,
                        title: '🚇 ' + h.name,
                        subtitle: 'SPT Subway Circle (every 4m peak)' + (h.hasParkAndRide ? ' • Subway P&R' : '')
                    });
                }
            }

            // 7. Partner A & Partner B Commute Destination Hubs
            drawHubMarker(ctx, centerPt.x, centerPt.y, '#22d3ee', 'A', state.filters.centerLocation || 'Glasgow Central');
            let secondPt = null;
            if (state.filters.secondCenterLocation && state.mapData.secondCenterCoords) {
                secondPt = project(state.mapData.secondCenterCoords[0], state.mapData.secondCenterCoords[1], width, height);
                drawHubMarker(ctx, secondPt.x, secondPt.y, '#c084fc', 'B', state.filters.secondCenterLocation);
            }

            // 8. Selected Property Commute Vector Lines (to Partner A & Partner B)
            const selectedProp = state.properties.find(p => p.listingId === state.selectedListingId);
            if (selectedProp && selectedProp.latitude && selectedProp.longitude) {
                const sp = project(selectedProp.latitude, selectedProp.longitude, width, height);
                ctx.save();
                ctx.setLineDash([5, 4]);
                ctx.lineWidth = 2.2;
                ctx.strokeStyle = '#34d399';
                ctx.beginPath();
                ctx.moveTo(sp.x, sp.y);
                ctx.lineTo(centerPt.x, centerPt.y);
                ctx.stroke();

                if (secondPt) {
                    ctx.strokeStyle = '#c084fc';
                    ctx.beginPath();
                    ctx.moveTo(sp.x, sp.y);
                    ctx.lineTo(secondPt.x, secondPt.y);
                    ctx.stroke();
                }
                ctx.restore();
            }

            // 9. Ranked Property Pins (draw lower scores first so highest scores sit cleanly on top)
            const orderedProps = state.properties.slice().reverse();
            for (const prop of orderedProps) {
                if (!prop.latitude || !prop.longitude) continue;
                const pt = project(prop.latitude, prop.longitude, width, height);
                if (pt.x < -30 || pt.x > width + 30 || pt.y < -30 || pt.y > height + 30) continue;

                const isSelected = prop.listingId === state.selectedListingId;
                const isShortlisted = !!(prop.workflow && prop.workflow.shortlisted);
                const fill = prop.overallScore >= 85
                    ? '#10b981'
                    : (prop.overallScore >= 70 ? '#6366f1' : '#f59e0b');
                const radius = isSelected ? 12.5 : (isShortlisted ? 10.5 : 9.0);

                if (isSelected || isShortlisted) {
                    ctx.beginPath();
                    ctx.arc(pt.x, pt.y, radius + 4, 0, Math.PI * 2);
                    ctx.fillStyle = isSelected ? 'rgba(52, 211, 153, 0.32)' : 'rgba(251, 191, 36, 0.30)';
                    ctx.fill();
                }

                ctx.beginPath();
                ctx.arc(pt.x, pt.y, radius, 0, Math.PI * 2);
                ctx.fillStyle = fill;
                ctx.fill();
                ctx.lineWidth = isSelected ? 2.5 : (isShortlisted ? 2.2 : 1.5);
                ctx.strokeStyle = isShortlisted ? '#fde047' : '#ffffff';
                ctx.stroke();

                ctx.fillStyle = '#ffffff';
                ctx.font = (isSelected ? '800 10px ' : '700 9px ') + 'ui-sans-serif, system-ui, sans-serif';
                ctx.textAlign = 'center';
                ctx.textBaseline = 'middle';
                ctx.fillText(String(prop.overallScore), pt.x, pt.y + 0.5);

                hitTargets.push({
                    kind: 'property',
                    x: pt.x,
                    y: pt.y,
                    r: radius + 4,
                    prop: prop,
                    title: (isShortlisted ? '⭐ ' : '') + prop.address + ' (' + prop.overallScore + '/100)',
                    subtitle: '£' + prop.priceGbp.toLocaleString() + ' • ' + prop.bedrooms + ' bed • ' + prop.doorToTargetMins + 'm via ' + prop.bestTransitMode
                });
            }

            // 10. Hover Tooltip Overlay
            if (state.hoverItem) {
                drawTooltip(ctx, state.hoverItem, width, height);
            }

            // 11. Scale bar in bottom-left
            drawScaleBar(ctx, width, height, state.pixelsPerMile);

            ctx.restore();
        }

        function drawHubMarker(ctx, x, y, color, letter, label) {
            ctx.beginPath();
            ctx.arc(x, y, 9, 0, Math.PI * 2);
            ctx.fillStyle = color;
            ctx.fill();
            ctx.lineWidth = 2.2;
            ctx.strokeStyle = '#0f172a';
            ctx.stroke();

            ctx.fillStyle = '#0f172a';
            ctx.font = '800 10px ui-sans-serif, system-ui, sans-serif';
            ctx.textAlign = 'center';
            ctx.textBaseline = 'middle';
            ctx.fillText(letter, x, y + 0.5);

            ctx.fillStyle = '#f8fafc';
            ctx.font = '700 10px ui-sans-serif, system-ui, sans-serif';
            ctx.textAlign = 'left';
            ctx.fillText(label, x + 12, y + 3);
        }

        function drawTooltip(ctx, item, width, height) {
            ctx.font = '700 11px ui-sans-serif, system-ui, sans-serif';
            const w1 = ctx.measureText(item.title).width;
            ctx.font = '500 10px ui-sans-serif, system-ui, sans-serif';
            const w2 = ctx.measureText(item.subtitle || '').width;
            const boxW = Math.min(width - 20, Math.max(w1, w2) + 20);
            const boxH = 38;
            let bx = item.x + 12;
            let by = item.y - boxH - 8;
            if (bx + boxW > width - 8) bx = item.x - boxW - 12;
            if (by < 8) by = item.y + 12;

            ctx.fillStyle = 'rgba(2, 6, 23, 0.94)';
            ctx.strokeStyle = 'rgba(148, 163, 184, 0.45)';
            ctx.lineWidth = 1;
            ctx.beginPath();
            ctx.roundRect(bx, by, boxW, boxH, 6);
            ctx.fill();
            ctx.stroke();

            ctx.textBaseline = 'alphabetic';
            ctx.textAlign = 'left';
            ctx.fillStyle = '#f8fafc';
            ctx.font = '700 11px ui-sans-serif, system-ui, sans-serif';
            ctx.fillText(item.title, bx + 9, by + 15);
            ctx.fillStyle = '#94a3b8';
            ctx.font = '500 10px ui-sans-serif, system-ui, sans-serif';
            ctx.fillText(item.subtitle || '', bx + 9, by + 30);
        }

        function drawScaleBar(ctx, width, height, pxPerMile) {
            const miles = pxPerMile >= 25 ? 1 : (pxPerMile >= 12 ? 2 : 5);
            const barW = miles * pxPerMile;
            const x = 14;
            const y = height - 14;
            ctx.strokeStyle = '#cbd5e1';
            ctx.lineWidth = 2;
            ctx.beginPath();
            ctx.moveTo(x, y - 4);
            ctx.lineTo(x, y);
            ctx.lineTo(x + barW, y);
            ctx.lineTo(x + barW, y - 4);
            ctx.stroke();
            ctx.fillStyle = '#cbd5e1';
            ctx.font = '600 9px ui-monospace, monospace';
            ctx.textAlign = 'left';
            ctx.textBaseline = 'alphabetic';
            ctx.fillText(miles + ' mi', x + 4, y - 5);
        }

        function findHit(mx, my) {
            // Prioritize properties first (drawn last -> check from end of hitTargets)
            for (let i = hitTargets.length - 1; i >= 0; i--) {
                const t = hitTargets[i];
                const dx = mx - t.x;
                const dy = my - t.y;
                if (dx * dx + dy * dy <= t.r * t.r) {
                    return t;
                }
            }
            return null;
        }

        // Pointer & wheel interaction bindings
        canvas.addEventListener('mousedown', function (e) {
            state.dragging = true;
            state.dragMoved = false;
            state.lastMouseX = e.clientX;
            state.lastMouseY = e.clientY;
        });

        window.addEventListener('mouseup', function () {
            state.dragging = false;
        });

        canvas.addEventListener('mousemove', function (e) {
            const rect = canvas.getBoundingClientRect();
            const mx = e.clientX - rect.left;
            const my = e.clientY - rect.top;

            if (state.dragging) {
                const dx = e.clientX - state.lastMouseX;
                const dy = e.clientY - state.lastMouseY;
                if (Math.abs(dx) > 2 || Math.abs(dy) > 2) {
                    state.dragMoved = true;
                }
                state.lastMouseX = e.clientX;
                state.lastMouseY = e.clientY;
                state.centerLon -= dx / (state.pixelsPerMile * MILES_PER_DEG_LON);
                state.centerLat += dy / (state.pixelsPerMile * MILES_PER_DEG_LAT);
                render();
                return;
            }

            const hit = findHit(mx, my);
            canvas.style.cursor = hit && hit.kind === 'property' ? 'pointer' : 'grab';
            if (hit !== state.hoverItem) {
                state.hoverItem = hit;
                render();
            }
        });

        canvas.addEventListener('mouseleave', function () {
            if (state.hoverItem) {
                state.hoverItem = null;
                render();
            }
        });

        canvas.addEventListener('click', function (e) {
            if (state.dragMoved) return;
            const rect = canvas.getBoundingClientRect();
            const hit = findHit(e.clientX - rect.left, e.clientY - rect.top);
            if (hit && hit.kind === 'property' && hit.prop) {
                state.selectedListingId = hit.prop.listingId;
                render();
                if (callbacks && typeof callbacks.onSelectProperty === 'function') {
                    callbacks.onSelectProperty(hit.prop);
                }
            }
        });

        canvas.addEventListener('wheel', function (e) {
            e.preventDefault();
            const rect = canvas.getBoundingClientRect();
            const mx = e.clientX - rect.left;
            const my = e.clientY - rect.top;
            const before = unproject(mx, my, rect.width, rect.height);
            const factor = e.deltaY < 0 ? 1.18 : (1.0 / 1.18);
            state.pixelsPerMile = Math.max(4.0, Math.min(85.0, state.pixelsPerMile * factor));
            const after = unproject(mx, my, rect.width, rect.height);
            state.centerLat += (before.lat - after.lat);
            state.centerLon += (before.lon - after.lon);
            render();
        }, { passive: false });

        return {
            updateData: function (mapData, properties, filters, autoFit) {
                if (mapData) state.mapData = mapData;
                if (Array.isArray(properties)) state.properties = properties;
                if (filters) state.filters = filters;
                if (autoFit) {
                    fitBounds();
                } else {
                    render();
                }
            },
            selectListing: function (listingId) {
                state.selectedListingId = listingId;
                render();
            },
            setLayers: function (layers) {
                state.layers = Object.assign({}, state.layers, layers);
                render();
            },
            zoomBy: function (factor) {
                state.pixelsPerMile = Math.max(4.0, Math.min(85.0, state.pixelsPerMile * factor));
                render();
            },
            fitBounds: fitBounds,
            render: render
        };
    }

    return {
        createController: createController
    };
})();
