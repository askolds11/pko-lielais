package domain

import ai.timefold.solver.core.api.domain.entity.PlanningEntity
import ai.timefold.solver.core.api.domain.lookup.PlanningId
import ai.timefold.solver.core.api.domain.variable.InverseRelationShadowVariable
import ai.timefold.solver.core.api.domain.variable.NextElementShadowVariable
import ai.timefold.solver.core.api.domain.variable.PreviousElementShadowVariable
import com.fasterxml.jackson.annotation.JsonIdentityInfo
import com.fasterxml.jackson.annotation.JsonIdentityReference
import com.fasterxml.jackson.annotation.ObjectIdGenerators

/**
 * A street segment (edge) that vehicles may traverse.
 * - Mandatory segments MUST be visited by some vehicle
 * - Optional segments MAY be visited if profitable (based on weight/value)
 *
 * Each segment can be traversed in either direction. The optimal direction
 * is computed dynamically based on the route context.
 */
@PlanningEntity
@JsonIdentityInfo(
    generator = ObjectIdGenerators.PropertyGenerator::class,
    property = "id"
)
class StreetSegment(
    @field:PlanningId
    val id: String = "",

    @JsonIdentityReference(alwaysAsId = true)
    val startNode: Node = Node("", 0.0, 0.0),

    @JsonIdentityReference(alwaysAsId = true)
    val endNode: Node = Node("", 0.0, 0.0),

    /** Length/weight of this edge in meters */
    val lengthMeters: Double = 0.0,

    val name: String? = null,

    /** If true, this segment MUST be visited by some vehicle */
    val isMandatory: Boolean = true,

    /**
     * Value/priority of visiting this segment (higher = more valuable).
     * Used to make optional segments "lucrative" to visit.
     * For optional segments, this represents the benefit of visiting.
     */
    val value: Int = 1
) {
    // Shadow variables for list variable chaining
    @PreviousElementShadowVariable(sourceVariableName = "segments")
    @JsonIdentityReference(alwaysAsId = true)
    var previousSegment: StreetSegment? = null

    @NextElementShadowVariable(sourceVariableName = "segments")
    @JsonIdentityReference(alwaysAsId = true)
    var nextSegment: StreetSegment? = null

    @InverseRelationShadowVariable(sourceVariableName = "segments")
    @JsonIdentityReference(alwaysAsId = true)
    var vehicle: Vehicle? = null

    override fun toString() = "Segment($id: ${name ?: "unnamed"}, mandatory=$isMandatory)"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StreetSegment) return false
        return id == other.id
    }

    override fun hashCode() = id.hashCode()
}