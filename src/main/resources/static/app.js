// Vehicle colors for visualization
const VEHICLE_COLORS = [
    '#e41a1c', '#377eb8', '#4daf4a', '#984ea3',
    '#ff7f00', '#ffff33', '#a65628', '#f781bf',
    '#66c2a5', '#fc8d62', '#8da0cb', '#e78ac3'
];

// Global state
let map;
let currentJobId = null;
let pollingInterval = null;
let segmentLayers = [];
let markerLayers = [];
let osmSupported = false;
let mapBounds = null;

// Collapsible section toggle
function toggleCollapsible(header) {
    header.classList.toggle('collapsed');
    const content = header.nextElementSibling;
    if (content.classList.contains('collapsible-content')) {
        content.classList.toggle('collapsed');
        if (!content.classList.contains('collapsed')) {
            // Expanding - set max-height based on content
            content.style.maxHeight = content.scrollHeight + 'px';
        }
    }
}

// Open fullscreen breakdown page
function openFullscreenBreakdown() {
    if (currentJobId) {
        window.open(`/breakdown.html?jobId=${currentJobId}`, '_blank');
    } else {
        alert('No solution loaded. Generate or load a solution first.');
    }
}

// Initialize the application
document.addEventListener('DOMContentLoaded', () => {
    initMap();
    setupEventListeners();
    checkOsmSupport();
    initCollapsibleSections();
});

// Initialize collapsible sections with proper max-height
function initCollapsibleSections() {
    document.querySelectorAll('.collapsible-content').forEach(content => {
        if (!content.classList.contains('collapsed')) {
            // Set initial max-height
            content.style.maxHeight = content.scrollHeight + 'px';
            // Observe for content changes
            const observer = new MutationObserver(() => {
                if (!content.classList.contains('collapsed')) {
                    content.style.maxHeight = content.scrollHeight + 'px';
                }
            });
            observer.observe(content, { childList: true, subtree: true, characterData: true });
        }
    });
}

function initMap() {
    // Initialize Leaflet map with a default view (will be updated when OSM status is loaded)
    map = L.map('map').setView([56.95, 24.1], 10);

    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
        attribution: '© OpenStreetMap contributors'
    }).addTo(map);
}

function setMapToOsmBounds(bounds) {
    if (bounds && bounds.minLat && bounds.maxLat && bounds.minLon && bounds.maxLon) {
        // Fit map to the OSM file bounds
        map.fitBounds([
            [bounds.minLat, bounds.minLon],
            [bounds.maxLat, bounds.maxLon]
        ]);
        mapBounds = bounds;
    }
}

function setupEventListeners() {
    document.getElementById('loadBtn').addEventListener('click', loadSolution);
    document.getElementById('stopBtn').addEventListener('click', stopSolving);
    document.getElementById('osmGenerateBtn').addEventListener('click', generateFromOsm);
    document.getElementById('downloadBtn').addEventListener('click', downloadProblem);
}

async function downloadProblem() {
    if (!currentJobId) {
        alert('No solution loaded. Generate or load a solution first.');
        return;
    }

    try {
        const response = await fetch(`/routing/${currentJobId}/download`);
        if (!response.ok) {
            throw new Error(`HTTP ${response.status}`);
        }

        const blob = await response.blob();
        const url = window.URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `problem_${currentJobId}.json`;
        document.body.appendChild(a);
        a.click();
        window.URL.revokeObjectURL(url);
        document.body.removeChild(a);
    } catch (error) {
        alert('Error downloading problem: ' + error.message);
        console.error('Download error:', error);
    }
}


async function checkOsmSupport() {
    const statusDiv = document.getElementById('osmStatus');
    const btn = document.getElementById('osmGenerateBtn');

    try {
        const response = await fetch('/osm/status');
        const data = await response.json();

        if (data.available) {
            statusDiv.className = 'osm-status available';
            statusDiv.textContent = '✓ ' + data.message;
            osmSupported = true;
            btn.disabled = false;

            // Set map to OSM file bounds if available
            if (data.bounds) {
                setMapToOsmBounds(data.bounds);
            }
        } else {
            statusDiv.className = 'osm-status unavailable';
            statusDiv.textContent = '✗ ' + data.message;
            osmSupported = false;
            btn.disabled = true;
        }
    } catch (error) {
        statusDiv.className = 'osm-status unavailable';
        statusDiv.textContent = '✗ Could not check OSM support';
        osmSupported = false;
        btn.disabled = true;
    }
}

async function generateFromOsm() {
    const mandatoryCount = parseInt(document.getElementById('osmMandatory').value) || 20;
    const optionalCount = parseInt(document.getElementById('osmOptional').value) || 10;
    const vehicleCount = parseInt(document.getElementById('osmVehicles').value) || 2;
    const minRange = parseInt(document.getElementById('osmMinRange').value) || 0;
    const maxRange = parseInt(document.getElementById('osmMaxRange').value) || 0;

    try {
        updateStatus('Generating from OSM...', 'solving');

        const params = new URLSearchParams({
            mandatoryCount: mandatoryCount,
            optionalCount: optionalCount,
            vehicles: vehicleCount,
            minRange: minRange,
            maxRange: maxRange
        });

        const response = await fetch('/osm/solve?' + params.toString(), {
            method: 'POST'
        });

        if (!response.ok) {
            const errorText = await response.text();
            throw new Error(errorText || `HTTP ${response.status}`);
        }

        currentJobId = await response.text();
        document.getElementById('jobId').value = currentJobId;
        document.getElementById('downloadBtn').disabled = false;

        // Start polling for updates
        startPolling();

    } catch (error) {
        updateStatus('Error: ' + error.message, 'error');
        console.error('OSM generation error:', error);
    }
}


async function loadSolution() {
    const jobId = document.getElementById('jobId').value.trim();
    if (!jobId) {
        alert('Please enter a job ID');
        return;
    }

    currentJobId = jobId;
    document.getElementById('downloadBtn').disabled = false;
    await fetchAndDisplaySolution();
}


function startPolling() {
    document.getElementById('stopBtn').style.display = 'block';

    // Poll every 2 seconds
    pollingInterval = setInterval(async () => {
        await fetchAndDisplaySolution();
    }, 2000);

    // Also fetch immediately
    fetchAndDisplaySolution();
}

function stopPolling() {
    if (pollingInterval) {
        clearInterval(pollingInterval);
        pollingInterval = null;
    }
    document.getElementById('stopBtn').style.display = 'none';
}

async function stopSolving() {
    if (!currentJobId) return;

    try {
        await fetch(`/routing/${currentJobId}/stop`, { method: 'POST' });
        stopPolling();
        await fetchAndDisplaySolution();
    } catch (error) {
        console.error('Stop error:', error);
    }
}

async function fetchAndDisplaySolution() {
    if (!currentJobId) return;

    try {
        // Fetch solution
        const solutionResponse = await fetch(`/routing/${currentJobId}`);
        if (!solutionResponse.ok) {
            throw new Error(`HTTP ${solutionResponse.status}`);
        }
        const data = await solutionResponse.json();

        console.log('Received data:', data);
        console.log('Solution object:', data.solution);

        // Update status
        const status = data.solverStatus;
        if (status === 'SOLVING_ACTIVE') {
            updateStatus('Solving...', 'solving');
        } else if (status === 'NOT_SOLVING') {
            updateStatus('Solved', 'solved');
            stopPolling();
        } else {
            updateStatus(status, 'idle');
        }

        // Update score display
        if (data.score) {
            updateScoreDisplay(data.score);
        }

        // Display solution on map - the solution is in data.solution
        if (data.solution) {
            displaySolution(data.solution);
        } else {
            console.warn('No solution in response data');
        }

        // Fetch and display score breakdown
        await fetchAndDisplayBreakdown();

    } catch (error) {
        updateStatus('Error: ' + error.message, 'error');
        console.error('Fetch error:', error);
        stopPolling();
    }
}

async function fetchAndDisplayBreakdown() {
    if (!currentJobId) return;

    try {
        const response = await fetch(`/routing/breakdown/${currentJobId}`);
        if (!response.ok) return;

        const breakdown = await response.json();
        displayBreakdown(breakdown);

    } catch (error) {
        console.error('Breakdown fetch error:', error);
    }
}

function updateStatus(text, type) {
    const statusEl = document.querySelector('#status .status-badge');
    statusEl.textContent = text;
    statusEl.className = 'status-badge ' + type;
}

function updateScoreDisplay(score) {
    // Parse score string like "0hard/444medium/-573soft"
    const parts = score.toString().split('/');

    if (parts.length >= 3) {
        document.getElementById('hardScore').textContent = parts[0].replace('hard', '');
        document.getElementById('mediumScore').textContent = parts[1].replace('medium', '');
        document.getElementById('softScore').textContent = parts[2].replace('soft', '');
    } else if (score.hardScore !== undefined) {
        document.getElementById('hardScore').textContent = score.hardScore;
        document.getElementById('mediumScore').textContent = score.mediumScore;
        document.getElementById('softScore').textContent = score.softScore;
    }
}

function displaySolution(solution) {
    // Clear existing layers
    segmentLayers.forEach(layer => map.removeLayer(layer));
    markerLayers.forEach(layer => map.removeLayer(layer));
    segmentLayers = [];
    markerLayers = [];

    if (!solution) {
        console.log('No solution to display');
        return;
    }

    if (!solution.segments || !Array.isArray(solution.segments)) {
        console.log('No segments in solution:', solution);
        return;
    }

    console.log('Displaying solution with', solution.segments.length, 'segments');

    // Build node lookup map (nodes might be referenced by ID)
    const nodeMap = {};
    if (solution.nodes && Array.isArray(solution.nodes)) {
        solution.nodes.forEach(node => {
            if (node && node.id) {
                nodeMap[node.id] = node;
            }
        });
    }
    console.log('Built node map with', Object.keys(nodeMap).length, 'nodes');

    // Helper to get node object (handles both full object and ID reference)
    function getNode(nodeRef) {
        if (!nodeRef) return null;
        // If it's already a full object with coordinates
        if (typeof nodeRef === 'object' && nodeRef.latitude !== undefined && nodeRef.longitude !== undefined) {
            return nodeRef;
        }
        // If it's an ID reference (string), look it up
        if (typeof nodeRef === 'string') {
            return nodeMap[nodeRef] || null;
        }
        // If it's an object with just an id
        if (typeof nodeRef === 'object' && nodeRef.id) {
            return nodeMap[nodeRef.id] || null;
        }
        return null;
    }

    const bounds = [];
    const vehicleSegments = {};

    // Group segments by vehicle
    if (solution.vehicles && Array.isArray(solution.vehicles)) {
        solution.vehicles.forEach((vehicle, idx) => {
            if (vehicle && vehicle.id) {
                vehicleSegments[vehicle.id] = {
                    color: VEHICLE_COLORS[idx % VEHICLE_COLORS.length],
                    segments: vehicle.segments || [],
                    index: idx
                };
            }
        });
    }

    // Draw all segments
    solution.segments.forEach(segment => {
        if (!segment) return;

        // Try to get the actual road geometry, fallback to straight line
        let latlngs;
        if (solution.geometries && solution.geometries[segment.id]) {
            // Use the actual road geometry
            latlngs = solution.geometries[segment.id];
        } else {
            // Fallback to straight line between nodes
            const startNode = getNode(segment.startNode);
            const endNode = getNode(segment.endNode);

            if (!startNode || !endNode) {
                console.warn('Skipping segment - missing nodes:', segment.id, 'startNode:', segment.startNode, 'endNode:', segment.endNode);
                return;
            }

            if (startNode.latitude === undefined || startNode.longitude === undefined ||
                endNode.latitude === undefined || endNode.longitude === undefined) {
                console.warn('Skipping segment - invalid coordinates:', segment.id, startNode, endNode);
                return;
            }

            latlngs = [
                [startNode.latitude, startNode.longitude],
                [endNode.latitude, endNode.longitude]
            ];
        }

        // Add all points to bounds
        latlngs.forEach(point => bounds.push(point));

        // Determine color and style
        let color = '#999';
        let weight = 3;
        let opacity = 0.5;
        let dashArray = null;
        let isAssigned = false;

        // Check which vehicle this segment is assigned to
        const vehicleId = segment.vehicle?.id || segment.vehicle;
        if (vehicleId && vehicleSegments[vehicleId]) {
            color = vehicleSegments[vehicleId].color;
            opacity = 0.8;
            weight = segment.isMandatory ? 5 : 4;
            dashArray = segment.isMandatory ? null : '5, 10';
            isAssigned = true;
        } else {
            // Unassigned segment - show as gray dashed
            color = segment.isMandatory ? '#c0392b' : '#7f8c8d';  // Red-ish for unassigned mandatory, gray for optional
            opacity = 0.6;
            weight = 3;
            dashArray = '3, 6';
        }

        const polyline = L.polyline(latlngs, {
            color: color,
            weight: weight,
            opacity: opacity,
            dashArray: dashArray
        });

        // Add popup with segment info
        const statusBadge = isAssigned
            ? `<span class="badge assigned">Assigned to ${vehicleId}</span>`
            : `<span class="badge unassigned">Unassigned</span>`;

        const popupContent = `
            <div class="segment-popup">
                <h4>${segment.name || segment.id}</h4>
                <div class="info-row">
                    <span>Type:</span>
                    <span class="badge ${segment.isMandatory ? 'mandatory' : 'optional'}">
                        ${segment.isMandatory ? 'Mandatory' : 'Optional'}
                    </span>
                </div>
                <div class="info-row">
                    <span>Status:</span>
                    ${statusBadge}
                </div>
                <div class="info-row">
                    <span>Length:</span>
                    <span>${Math.round(segment.lengthMeters || 0)}m</span>
                </div>
                ${!segment.isMandatory ? `
                <div class="info-row">
                    <span>Value:</span>
                    <span>${segment.value || 0}</span>
                </div>
                ` : ''}
            </div>
        `;

        polyline.bindPopup(popupContent);
        polyline.addTo(map);
        segmentLayers.push(polyline);
    });

    console.log('Drew', segmentLayers.length, 'segments on map');

    // Draw order markers for each vehicle's route
    Object.entries(vehicleSegments).forEach(([vehicleId, vehicleData]) => {
        if (!vehicleData.segments || vehicleData.segments.length === 0) return;

        vehicleData.segments.forEach((segment, order) => {
            // Find the segment data - handle both ID reference and object
            const segmentId = typeof segment === 'string' ? segment : (segment?.id || null);
            if (!segmentId) return;

            const segmentData = solution.segments.find(s => s.id === segmentId);
            if (!segmentData) return;

            const startNode = getNode(segmentData.startNode);
            const endNode = getNode(segmentData.endNode);
            if (!startNode || !endNode) return;
            if (startNode.latitude === undefined || endNode.latitude === undefined) return;

            // Calculate midpoint
            let midLat = (startNode.latitude + endNode.latitude) / 2;
            let midLng = (startNode.longitude + endNode.longitude) / 2;

            // Offset markers for reverse direction segments to avoid overlap
            // Check if this is a reverse segment (ends with _rev)
            const isReverse = segmentId.endsWith('_rev');
            if (isReverse) {
                // Calculate perpendicular offset
                const dLat = endNode.latitude - startNode.latitude;
                const dLng = endNode.longitude - startNode.longitude;
                const len = Math.sqrt(dLat * dLat + dLng * dLng);
                if (len > 0) {
                    // Offset perpendicular to the line (about 0.0001 degrees ≈ 10m)
                    const offset = 0.00015;
                    midLat += (-dLng / len) * offset;
                    midLng += (dLat / len) * offset;
                }
            }

            // Different infill for mandatory vs optional
            const isMandatory = segmentData.isMandatory;
            const backgroundColor = isMandatory ? vehicleData.color : 'white';
            const textColor = isMandatory ? 'white' : vehicleData.color;
            const borderWidth = isMandatory ? '2px' : '3px';

            // Create order marker with different style based on mandatory/optional
            const icon = L.divIcon({
                className: 'order-marker-container',
                html: `<div class="order-marker" style="
                    background-color: ${backgroundColor}; 
                    border: ${borderWidth} solid ${vehicleData.color}; 
                    color: ${textColor};
                    font-weight: bold;
                ">${order + 1}</div>`,
                iconSize: [24, 24],
                iconAnchor: [12, 12]
            });

            const marker = L.marker([midLat, midLng], { icon: icon });
            marker.addTo(map);
            markerLayers.push(marker);
        });
    });

    // Add markers for unvisited segments
    solution.segments.forEach(segment => {
        if (!segment) return;

        // Skip if assigned to a vehicle
        const vehicleId = segment.vehicle?.id || segment.vehicle;
        if (vehicleId) return;

        const startNode = getNode(segment.startNode);
        const endNode = getNode(segment.endNode);
        if (!startNode || !endNode) return;
        if (startNode.latitude === undefined || endNode.latitude === undefined) return;

        // Calculate midpoint
        const midLat = (startNode.latitude + endNode.latitude) / 2;
        const midLng = (startNode.longitude + endNode.longitude) / 2;

        // Different marker for mandatory vs optional unvisited
        const isMandatory = segment.isMandatory;
        const markerText = isMandatory ? '!' : '?';
        const bgColor = isMandatory ? '#c0392b' : '#7f8c8d';
        const title = isMandatory ? 'Unassigned Mandatory' : 'Unvisited Optional';

        const icon = L.divIcon({
            className: 'unvisited-marker-container',
            html: `<div class="unvisited-marker" style="
                background-color: ${bgColor}; 
                border: 2px solid white; 
                color: white;
                font-weight: bold;
                font-size: 14px;
            " title="${title}">${markerText}</div>`,
            iconSize: [20, 20],
            iconAnchor: [10, 10]
        });

        const marker = L.marker([midLat, midLng], { icon: icon });
        marker.bindPopup(`<b>${segment.name || segment.id}</b><br>${title}<br>Length: ${Math.round(segment.lengthMeters || 0)}m`);
        marker.addTo(map);
        markerLayers.push(marker);
    });

    // Add depot markers
    if (solution.vehicles && Array.isArray(solution.vehicles)) {
        solution.vehicles.forEach((vehicle, idx) => {
            if (!vehicle) return;

            const startingNode = getNode(vehicle.startingNode);
            if (!startingNode || startingNode.latitude === undefined || startingNode.longitude === undefined) return;

            const depotIcon = L.divIcon({
                className: 'depot-marker',
                html: `<div style="background: ${VEHICLE_COLORS[idx % VEHICLE_COLORS.length]}; 
                                  width: 30px; height: 30px; border-radius: 50%; 
                                  border: 3px solid white; box-shadow: 0 2px 5px rgba(0,0,0,0.3);
                                  display: flex; align-items: center; justify-content: center;
                                  color: white; font-weight: bold;">🏠</div>`,
                iconSize: [30, 30],
                iconAnchor: [15, 15]
            });

            const marker = L.marker(
                [startingNode.latitude, startingNode.longitude],
                { icon: depotIcon }
            );
            marker.bindPopup(`<b>${vehicle.id}</b><br>Starting Location`);
            marker.addTo(map);
            markerLayers.push(marker);
        });
    }

    // Fit map to bounds
    if (bounds.length > 0) {
        map.fitBounds(bounds, { padding: [50, 50] });
    }

    // Update legend
    if (solution.vehicles) {
        updateLegend(solution.vehicles);
    }
}

function updateLegend(vehicles) {
    const legend = document.getElementById('legend');
    const legendItems = document.getElementById('legendItems');

    legendItems.innerHTML = '';

    vehicles.forEach((vehicle, idx) => {
        const color = VEHICLE_COLORS[idx % VEHICLE_COLORS.length];
        const item = document.createElement('div');
        item.className = 'legend-item';
        item.innerHTML = `
            <div class="legend-line" style="background: ${color};"></div>
            <span>${vehicle.id}</span>
        `;
        legendItems.appendChild(item);
    });

    legend.style.display = 'block';
}

function displayBreakdown(breakdown) {
    const container = document.getElementById('vehicleBreakdowns');

    if (!breakdown || !breakdown.vehicleBreakdowns) {
        container.innerHTML = '<p style="color: #666; font-style: italic;">No breakdown data available</p>';
        return;
    }

    let html = '';

    // Render vehicle-specific breakdowns
    breakdown.vehicleBreakdowns.forEach((vehicle, idx) => {
        const color = VEHICLE_COLORS[idx % VEHICLE_COLORS.length];
        const rangeDisplay = vehicle.maxRange < 1e15
            ? `Range: ${Math.round(vehicle.maxRange).toLocaleString()}m`
            : 'Range: Unlimited';

        html += `
            <div class="vehicle-breakdown">
                <div class="vehicle-header">
                    <div class="vehicle-color" style="background: ${color};"></div>
                    <span>${vehicle.vehicleId}</span>
                    <span style="margin-left: auto; font-size: 0.85em; opacity: 0.9;">
                        ${vehicle.segmentCount} segments · ${Math.round(vehicle.totalDistance).toLocaleString()}m
                    </span>
                </div>
                <div class="constraint-list">
                    <div style="font-size: 0.8em; color: #666; margin-bottom: 8px; padding-bottom: 8px; border-bottom: 1px solid #eee;">
                        ${rangeDisplay}
                    </div>
                    <div class="score-summary" style="display: flex; gap: 10px; margin-bottom: 10px; padding-bottom: 10px; border-bottom: 1px solid #eee;">
                        <div style="flex: 1; text-align: center; padding: 5px; background: #ffebee; border-radius: 4px;">
                            <div style="font-size: 0.7em; color: #666;">HARD</div>
                            <div style="font-weight: bold; color: #e74c3c;">${vehicle.hardScore}</div>
                        </div>
                        <div style="flex: 1; text-align: center; padding: 5px; background: #fff3e0; border-radius: 4px;">
                            <div style="font-size: 0.7em; color: #666;">MEDIUM</div>
                            <div style="font-weight: bold; color: #f39c12;">${vehicle.mediumScore}</div>
                        </div>
                        <div style="flex: 1; text-align: center; padding: 5px; background: #e3f2fd; border-radius: 4px;">
                            <div style="font-size: 0.7em; color: #666;">SOFT</div>
                            <div style="font-weight: bold; color: #3498db;">${vehicle.softScore}</div>
                        </div>
                    </div>
        `;

        // Vehicle-level constraints
        if (vehicle.vehicleConstraints && vehicle.vehicleConstraints.length > 0) {
            html += '<div style="font-size: 0.8em; color: #666; margin-bottom: 5px;">Vehicle Constraints:</div>';
            vehicle.vehicleConstraints.forEach(constraint => {
                html += `
                    <div class="constraint-item">
                        <span class="constraint-name">${constraint.name}</span>
                        <span class="constraint-score ${constraint.scoreType}">${constraint.score}</span>
                    </div>
                `;
            });
        }

        // Segment-level constraints
        if (vehicle.segmentConstraints && vehicle.segmentConstraints.length > 0) {
            html += '<div style="font-size: 0.8em; color: #666; margin: 10px 0 5px 0;">Segment Constraints:</div>';
            vehicle.segmentConstraints.forEach(constraint => {
                html += `
                    <div class="constraint-item">
                        <span class="constraint-name">
                            ${constraint.name}
                            <br><small style="color:#888">${constraint.segmentName || constraint.segmentId}</small>
                        </span>
                        <span class="constraint-score ${constraint.scoreType}">${constraint.score}</span>
                    </div>
                `;
            });
        }

        if ((!vehicle.vehicleConstraints || vehicle.vehicleConstraints.length === 0) &&
            (!vehicle.segmentConstraints || vehicle.segmentConstraints.length === 0)) {
            html += '<div style="color: #27ae60; font-style: italic;">✓ No constraint violations</div>';
        }

        html += '</div></div>';
    });

    // Render unassigned segment constraints
    if (breakdown.unassignedSegmentConstraints && breakdown.unassignedSegmentConstraints.length > 0) {
        html += `
            <div class="vehicle-breakdown">
                <div class="vehicle-header" style="background: #e74c3c;">
                    <span>⚠️ Unassigned Segments</span>
                </div>
                <div class="constraint-list">
        `;

        breakdown.unassignedSegmentConstraints.forEach(constraint => {
            html += `
                <div class="constraint-item">
                    <span class="constraint-name">
                        ${constraint.name}
                        <br><small style="color:#888">${constraint.segmentName || constraint.segmentId}</small>
                    </span>
                    <span class="constraint-score ${constraint.scoreType}">${constraint.score}</span>
                </div>
            `;
        });

        html += '</div></div>';
    }

    container.innerHTML = html || '<p style="color: #666; font-style: italic;">No constraint data available</p>';
}

