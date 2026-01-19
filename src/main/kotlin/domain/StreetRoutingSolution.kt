package domain

import ai.timefold.solver.core.api.domain.solution.*
import ai.timefold.solver.core.api.domain.valuerange.ValueRangeProvider
import ai.timefold.solver.core.api.score.buildin.hardmediumsoft.HardMediumSoftScore

/**
 * The planning solution for the street routing problem.
 *
 * Problem: Given mandatory and optional street segments (edges),
 * assign them to vehicles such that:
 * - All mandatory segments are visited by exactly one vehicle
 * - Optional segments are visited when beneficial (value vs travel cost)
 * - Each vehicle respects its maximum range constraint
 * - Total travel distance is minimized
 */
@PlanningSolution
class StreetRoutingSolution(
    @field:ProblemFactCollectionProperty
    val nodes: List<Node>,

    /**
     * All segments (edges) that can be visited.
     * This is both the entity collection AND the value range for vehicles.
     */
    @field:PlanningEntityCollectionProperty
    @field:ValueRangeProvider
    val segments: List<StreetSegment>,

    @field:PlanningEntityCollectionProperty
    val vehicles: List<Vehicle>,

    /** Pre-computed distance matrix between all node pairs */
    @field:ProblemFactProperty
    val distanceMatrix: DistanceMatrix,

    /**
     * Road geometries for visualization - maps segment ID to list of [lat, lon] coordinates.
     * This is not used by the solver, only for visualization.
     */
    val geometries: Map<String, List<List<Double>>> = emptyMap()
) {
    @PlanningScore
    var score: HardMediumSoftScore? = null

    companion object {
        fun distanceKey(fromId: String, toId: String) = "$fromId->$toId"
    }
}