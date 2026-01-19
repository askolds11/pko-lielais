package domain

import ai.timefold.solver.core.api.domain.entity.PlanningEntity
import ai.timefold.solver.core.api.domain.lookup.PlanningId
import ai.timefold.solver.core.api.domain.variable.PlanningListVariable
import com.fasterxml.jackson.annotation.JsonIdentityInfo
import com.fasterxml.jackson.annotation.JsonIdentityReference
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.ObjectIdGenerators
import solver.StreetRoutingConstraintProvider.Companion.U_TURN_PENALTY_METERS

/**
 * A vehicle that traverses street segments.
 * Each vehicle has a starting node and optional maximum range.
 */
@PlanningEntity
@JsonIdentityInfo(
    generator = ObjectIdGenerators.PropertyGenerator::class,
    property = "id"
)
class Vehicle(
    @field:PlanningId
    val id: String = "",

    @JsonIdentityReference(alwaysAsId = true)
    val startingNode: Node = Node("", 0.0, 0.0),

    /** Maximum distance this vehicle can travel (in meters). Use Double.MAX_VALUE for unlimited. */
    val maxDistanceMeters: Double = Double.MAX_VALUE
) {
    /**
     * The list of segments this vehicle will visit, in order.
     * allowsUnassigned = true means optional segments can remain unvisited.
     */
    @PlanningListVariable(allowsUnassignedValues = true)
    var segments: MutableList<StreetSegment> = mutableListOf()

    /**
     * Calculate total route distance: travel distances + segment lengths.
     * - Segment entry: startNode
     * - Segment exit: endNode
     */
    @JsonIgnore
    fun getTotalRouteDistance(distanceMatrix: DistanceMatrix): Double {
        val segments = segments
        if (segments.isEmpty()) return 0.0

        val depot = startingNode
        var totalDistance = 0.0

        // Distance from depot to first segment's entry (startNode)
        totalDistance += distanceMatrix.getDistance(depot.id, segments[0].startNode.id)

        // Add first segment length
        totalDistance += segments[0].lengthMeters

        // For each subsequent segment
        for (i in 1 until segments.size) {
            val prev = segments[i - 1]
            val curr = segments[i]

            // Distance from prev exit (endNode) to curr entry (startNode)
            val travelDist = distanceMatrix.getDistance(prev.endNode.id, curr.startNode.id)

            // Add U-turn penalty if applicable
            if (isUTurn(prev, curr)) {
                totalDistance += U_TURN_PENALTY_METERS
            } else {
                totalDistance += travelDist
            }

            // Add segment length
            totalDistance += curr.lengthMeters
        }

        // Return to depot from last segment
        totalDistance += distanceMatrix.getDistance(segments.last().endNode.id, depot.id)

        return totalDistance
    }

    /**
     * Check if transitioning from prev to curr is a U-turn (same road, opposite direction).
     */
    private fun isUTurn(prev: StreetSegment, curr: StreetSegment): Boolean {
        if (prev.endNode.id != curr.startNode.id) {
            return false
        }
        val prevBaseId = prev.id.removeSuffix("_fwd").removeSuffix("_rev")
        val currBaseId = curr.id.removeSuffix("_fwd").removeSuffix("_rev")
        return prevBaseId == currBaseId
    }

    override fun toString() = "Vehicle($id, segments=${segments.size})"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Vehicle) return false
        return id == other.id
    }

    override fun hashCode() = id.hashCode()
}