package osm

import domain.Node
import domain.StreetSegment

/**
 * Bounding box for geographic filtering.
 */
data class BoundingBox(
    val minLat: Double,
    val minLon: Double,
    val maxLat: Double,
    val maxLon: Double
)

/**
 * Information about a street segment for filtering purposes.
 */
data class SegmentInfo(
    val osmWayIds: List<Long>,
    val name: String?,
    val lengthMeters: Double,
    val startNode: Node,
    val endNode: Node,
    val oneway: Boolean = false
)

/**
 * A coordinate point (latitude, longitude).
 */
data class Coordinate(
    val latitude: Double,
    val longitude: Double
)

/**
 * Geometry of a street segment as a list of coordinates.
 */
data class SegmentGeometry(
    val segmentId: String,
    val points: List<Coordinate>
)

/**
 * An edge in the full graph (for distance calculations).
 */
data class GraphEdge(
    val fromNodeId: String,
    val toNodeId: String,
    val lengthMeters: Double
)

/**
 * Result of loading OSM data.
 */
data class OsmLoadResult(
    val nodes: List<Node>,
    val segments: List<StreetSegment>,
    val geometries: Map<String, SegmentGeometry>,
    val startingLocation: Node? = null,
    val fullGraphNodes: List<Node> = emptyList(),
    val fullGraphEdges: List<GraphEdge> = emptyList(),
    val onewaySegments: Map<String, Boolean> = emptyMap()
) {
    /**
     * Check if a segment is one-way.
     */
    fun isOneway(segmentId: String): Boolean = onewaySegments[segmentId] ?: false
}
