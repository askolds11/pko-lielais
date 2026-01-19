#!/usr/bin/env python3
"""
OSM Graph Loader using OSMnx/pyrosm for proper graph simplification.

This script loads an OSM file (.osm or .osm.pbf) and extracts a simplified street network
where edges represent segments between intersections (not every OSM node).

Usage:
    python osm_graph_loader.py <osm_file> <output_json> [--bbox=minLat,minLon,maxLat,maxLon]

Output JSON format:
{
    "nodes": [{"id": "node_123", "lat": 56.123, "lon": 24.456}, ...],
    "segments": [
        {
            "id": "seg_123_456",
            "startNodeId": "node_123",
            "endNodeId": "node_456",
            "lengthMeters": 150.5,
            "name": "Main Street",
            "osmWayIds": [12345, 12346],
            "geometry": [[lat, lon], [lat, lon], ...]
        },
        ...
    ],
    "startingLocation": {"id": "node_xxx", "lat": 56.xxx, "lon": 24.xxx},
    "fullGraphNodes": [...]  // All nodes from original graph for distance calculations
}

Requirements:
    pip install osmnx networkx pyrosm
"""

# Riga, Latvia bounding box (default)
RIGA_BBOX = (56.88, 23.95, 57.05, 24.30)  # minLat, minLon, maxLat, maxLon

import argparse
import json
import sys
from pathlib import Path

try:
    import osmnx as ox
    import networkx as nx
except ImportError:
    print("Error: osmnx is required. Install with: pip install osmnx", file=sys.stderr)
    sys.exit(1)

# Check for pyrosm (needed for .pbf files)
try:
    from pyrosm import OSM
    PYROSM_AVAILABLE = True
except ImportError:
    PYROSM_AVAILABLE = False


def load_osm_graph(osm_file: str, bbox: tuple = None) -> tuple:
    """
    Load OSM file and return both simplified and full graphs.

    OSMnx automatically simplifies the graph to only include intersections
    and dead-ends as nodes, consolidating intermediate nodes into edge geometry.

    Supports both .osm and .osm.pbf files.

    Returns:
        tuple: (simplified_graph, full_graph) - simplified for segments, full for distance matrix
    """
    # Use Riga bbox as default if none provided
    if bbox is None:
        bbox = RIGA_BBOX
        print(f"[Python] Using default Riga bounding box: {bbox}", file=sys.stderr)

    is_pbf = osm_file.endswith('.pbf')

    if is_pbf:
        if not PYROSM_AVAILABLE:
            print("Warning: pyrosm not available, trying alternative method...", file=sys.stderr)
            # Try using osmnx with graph_from_polygon if we have a bbox
            if bbox:
                G = load_graph_from_bbox(bbox)
                return G, G  # Return same graph for both
            else:
                raise ValueError(
                    "PBF files require pyrosm. Install with: pip install pyrosm\n"
                    "Or provide a --bbox to download from Overpass API."
                )

        # Use pyrosm to load PBF file - returns both simplified and full graphs
        G_simplified, G_full = load_graph_from_pbf(osm_file, bbox)
    else:
        # Load from OSM XML file
        G_full = ox.graph_from_xml(osm_file, simplify=False, bidirectional=True)
        G_simplified = ox.graph_from_xml(osm_file, simplify=True, bidirectional=True)

        # Apply bounding box filter if provided
        if bbox:
            min_lat, min_lon, max_lat, max_lon = bbox
            G_full = ox.truncate.truncate_graph_bbox(G_full, max_lat, min_lat, max_lon, min_lon)
            G_simplified = ox.truncate.truncate_graph_bbox(G_simplified, max_lat, min_lat, max_lon, min_lon)

    return G_simplified, G_full


def load_graph_from_pbf(pbf_file: str, bbox: tuple = None) -> tuple:
    """
    Load graph from PBF file using pyrosm.
    Build the NetworkX graph directly without using ox.graph_from_gdfs.

    Returns:
        tuple: (simplified_graph, full_graph)
    """
    print(f"[Python] Loading PBF file with pyrosm: {pbf_file}", file=sys.stderr)

    # Initialize pyrosm OSM object with bounding box
    if bbox:
        min_lat, min_lon, max_lat, max_lon = bbox
        print(f"[Python] Using bounding box: {bbox}", file=sys.stderr)
        osm = OSM(pbf_file, bounding_box=[min_lon, min_lat, max_lon, max_lat])
    else:
        osm = OSM(pbf_file)

    # Get the driving network
    nodes_gdf, edges_gdf = osm.get_network(network_type="driving", nodes=True)

    if edges_gdf is None or len(edges_gdf) == 0:
        raise ValueError("No edges found in the PBF file for the given area")

    print(f"[Python] Pyrosm loaded: {len(nodes_gdf)} nodes, {len(edges_gdf)} edges", file=sys.stderr)

    # Build NetworkX MultiDiGraph directly (this is the full graph)
    G_full = nx.MultiDiGraph()

    # Add nodes with x, y attributes (required by osmnx for simplification)
    for _, row in nodes_gdf.iterrows():
        node_id = row['id']
        x = row['lon'] if 'lon' in row else row.geometry.x
        y = row['lat'] if 'lat' in row else row.geometry.y
        G_full.add_node(node_id, x=x, y=y)

    print(f"[Python] Added {G_full.number_of_nodes()} nodes to full graph", file=sys.stderr)

    # Add edges with geometry and attributes
    for _, row in edges_gdf.iterrows():
        u = row['u']
        v = row['v']

        # Skip if nodes don't exist
        if u not in G_full.nodes or v not in G_full.nodes:
            continue

        edge_data = {
            'osmid': row.get('id', row.get('osmid', 0)),
            'length': row.get('length', 0.0),
            'name': row.get('name'),
            'highway': row.get('highway'),
            'oneway': row.get('oneway', False),
        }

        # Add geometry if available
        if row.geometry is not None:
            edge_data['geometry'] = row.geometry

        G_full.add_edge(u, v, **edge_data)

    print(f"[Python] Added {G_full.number_of_edges()} edges to full graph", file=sys.stderr)

    # Set graph CRS attribute (needed for some osmnx functions)
    G_full.graph['crs'] = 'EPSG:4326'

    # Create simplified graph by copying and simplifying
    try:
        G_simplified = ox.simplify_graph(G_full.copy())
        print(f"[Python] Simplified graph: {G_simplified.number_of_nodes()} nodes, {G_simplified.number_of_edges()} edges", file=sys.stderr)
    except Exception as e:
        print(f"[Python] Warning: Could not simplify graph: {e}", file=sys.stderr)
        print(f"[Python] Using unsimplified graph for both", file=sys.stderr)
        G_simplified = G_full

    return G_simplified, G_full


def load_graph_from_bbox(bbox: tuple) -> nx.MultiDiGraph:
    """
    Fallback: Download graph from Overpass API using bounding box.
    """
    min_lat, min_lon, max_lat, max_lon = bbox
    print(f"Downloading from Overpass API for bbox: {bbox}", file=sys.stderr)

    G = ox.graph_from_bbox(
        north=max_lat, south=min_lat, east=max_lon, west=min_lon,
        network_type='drive',
        simplify=True
    )

    return G


def graph_to_json(G_simplified: nx.MultiDiGraph, G_full: nx.MultiDiGraph = None) -> dict:
    """
    Convert NetworkX graphs to JSON format for Kotlin consumption.

    Args:
        G_simplified: Simplified graph (segments between intersections)
        G_full: Full graph (all nodes for distance matrix), if None uses G_simplified
    """
    if G_full is None:
        G_full = G_simplified

    nodes = []
    segments = []

    # Process nodes from simplified graph (intersection nodes)
    for node_id, data in G_simplified.nodes(data=True):
        nodes.append({
            "id": f"node_{node_id}",
            "lat": data.get('y', 0.0),
            "lon": data.get('x', 0.0)
        })

    # Process edges from simplified graph
    seen_edges = set()
    for u, v, key, data in G_simplified.edges(keys=True, data=True):
        # Create unique edge identifier (handle bidirectional edges)
        edge_id = tuple(sorted([u, v])) + (key,)
        if edge_id in seen_edges:
            continue
        seen_edges.add(edge_id)

        # Get geometry
        geometry = []
        if 'geometry' in data:
            # OSMnx stores geometry as shapely LineString
            coords = list(data['geometry'].coords)
            geometry = [[lat, lon] for lon, lat in coords]
        else:
            # No intermediate geometry, just start and end points
            start_node = G_simplified.nodes[u]
            end_node = G_simplified.nodes[v]
            geometry = [
                [start_node['y'], start_node['x']],
                [end_node['y'], end_node['x']]
            ]

        # Get OSM way IDs (may be a list or single value)
        osm_ids = data.get('osmid', [])
        if not isinstance(osm_ids, list):
            osm_ids = [osm_ids]

        # Get street name
        name = data.get('name')
        if isinstance(name, list):
            name = name[0] if name else None

        # Get length
        length = data.get('length', 0.0)

        # Check if one-way
        oneway = data.get('oneway', False)
        # Handle various oneway values from OSM
        # After simplification, oneway can be a list if multiple ways were merged
        if isinstance(oneway, list):
            # If any segment is one-way, consider the whole merged segment as one-way
            oneway = any(
                (isinstance(o, bool) and o) or
                (isinstance(o, str) and o.lower() in ('yes', 'true', '1', '-1'))
                for o in oneway
            )
        elif isinstance(oneway, str):
            oneway = oneway.lower() in ('yes', 'true', '1', '-1')
        elif not isinstance(oneway, bool):
            oneway = False

        segments.append({
            "id": f"seg_{u}_{v}_{key}",
            "startNodeId": f"node_{u}",
            "endNodeId": f"node_{v}",
            "lengthMeters": length,
            "name": name,
            "osmWayIds": osm_ids,
            "geometry": geometry,
            "oneway": oneway
        })

    # Find a central starting location (closest to centroid of all nodes)
    if nodes:
        avg_lat = sum(n['lat'] for n in nodes) / len(nodes)
        avg_lon = sum(n['lon'] for n in nodes) / len(nodes)

        # Find the node closest to the centroid
        starting_node = min(nodes, key=lambda n:
            (n['lat'] - avg_lat)**2 + (n['lon'] - avg_lon)**2
        )
        starting_location = {
            "id": starting_node['id'],
            "lat": starting_node['lat'],
            "lon": starting_node['lon']
        }
    else:
        starting_location = None

    # Process all nodes from the full graph (for distance matrix calculations)
    full_graph_nodes = []
    for node_id, data in G_full.nodes(data=True):
        full_graph_nodes.append({
            "id": f"node_{node_id}",
            "lat": data.get('y', 0.0),
            "lon": data.get('x', 0.0)
        })

    # Also include full graph edges for distance calculations
    full_graph_edges = []
    for u, v, key, data in G_full.edges(keys=True, data=True):
        full_graph_edges.append({
            "u": f"node_{u}",
            "v": f"node_{v}",
            "length": data.get('length', 0.0)
        })

    return {
        "nodes": nodes,
        "segments": segments,
        "startingLocation": starting_location,
        "fullGraphNodes": full_graph_nodes,
        "fullGraphEdges": full_graph_edges
    }


def main():
    parser = argparse.ArgumentParser(
        description="Load OSM file and extract simplified street graph"
    )
    parser.add_argument("osm_file", help="Path to OSM file (.osm or .osm.pbf format)")
    parser.add_argument("output_json", help="Path for output JSON file")
    parser.add_argument(
        "--bbox",
        help=f"Bounding box: minLat,minLon,maxLat,maxLon (default: Riga {RIGA_BBOX})",
        default=None
    )

    args = parser.parse_args()

    # Parse bounding box
    bbox = None
    if args.bbox:
        try:
            bbox = tuple(map(float, args.bbox.split(',')))
            if len(bbox) != 4:
                raise ValueError("Bounding box must have 4 values")
        except ValueError as e:
            print(f"Error parsing bbox: {e}", file=sys.stderr)
            sys.exit(1)

    # Check input file
    if not Path(args.osm_file).exists():
        print(f"Error: File not found: {args.osm_file}", file=sys.stderr)
        sys.exit(1)

    print(f"[Python] Loading OSM graph from: {args.osm_file}", file=sys.stderr)

    try:
        G_simplified, G_full = load_osm_graph(args.osm_file, bbox)
        print(f"[Python] Simplified graph: {G_simplified.number_of_nodes()} nodes, {G_simplified.number_of_edges()} edges", file=sys.stderr)
        print(f"[Python] Full graph: {G_full.number_of_nodes()} nodes, {G_full.number_of_edges()} edges", file=sys.stderr)

        # Convert to JSON (use simplified for segments, full for distance matrix)
        result = graph_to_json(G_simplified, G_full)
        print(f"[Python] Exported: {len(result['nodes'])} intersection nodes, {len(result['segments'])} segments", file=sys.stderr)
        print(f"[Python] Full graph: {len(result['fullGraphNodes'])} nodes, {len(result['fullGraphEdges'])} edges for distance matrix", file=sys.stderr)
        if result['startingLocation']:
            print(f"[Python] Starting location: {result['startingLocation']['id']} at ({result['startingLocation']['lat']}, {result['startingLocation']['lon']})", file=sys.stderr)

        # Write output
        with open(args.output_json, 'w') as f:
            json.dump(result, f, indent=2)

        print(f"[Python] Output written to: {args.output_json}", file=sys.stderr)

    except Exception as e:
        print(f"[Python] Error: {e}", file=sys.stderr)
        import traceback
        traceback.print_exc()
        sys.exit(1)


if __name__ == "__main__":
    main()
