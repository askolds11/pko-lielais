package solver

import ai.timefold.solver.core.api.score.buildin.hardmediumsoft.HardMediumSoftScore
import ai.timefold.solver.core.api.score.stream.Constraint
import ai.timefold.solver.core.api.score.stream.ConstraintFactory
import ai.timefold.solver.core.api.score.stream.ConstraintProvider
import domain.DistanceMatrix
import domain.StreetSegment
import domain.Vehicle

/**
 * Constraint provider for the street routing problem.
 *
 * Each segment represents a single direction of travel:
 * - Entry node: startNode
 * - Exit node: endNode
 *
 * Two-way streets have two segments (one for each direction).
 * One-way streets have one segment in the allowed direction.
 */
class StreetRoutingConstraintProvider : ConstraintProvider {

    companion object {
        /**
         * Penalty distance (in meters) for U-turns.
         */
        const val U_TURN_PENALTY_METERS = 500.0
    }

    override fun defineConstraints(factory: ConstraintFactory): Array<Constraint> {
        return arrayOf(
            // Hard constraints
            mandatorySegmentsMustBeAssigned(factory),
            vehicleMaxDistance(factory),

            // Medium constraints - incentive to assign optionals
            penalizeUnassignedOptionals(factory),

            // Soft constraints
            minimizeTotalDistance(factory),
//            penalizeLongTravelsBetweenSegments(factory),
            rewardRemainingRange(factory),
            penalizeUnusedVehicles(factory)
        )
    }

    // ==================== HARD CONSTRAINTS ====================

    fun mandatorySegmentsMustBeAssigned(factory: ConstraintFactory): Constraint {
        return factory.forEachIncludingUnassigned(StreetSegment::class.java)
            .filter { it.isMandatory && it.vehicle == null }
            .penalize(HardMediumSoftScore.ONE_HARD) {
                100000
            }
            .asConstraint("Mandatory segment not assigned")
    }

    /**
     * HARD: Vehicle cannot exceed its maximum distance.
     */
    fun vehicleMaxDistance(factory: ConstraintFactory): Constraint {
        return factory.forEach(Vehicle::class.java)
            .filter { it.segments.isNotEmpty() && it.maxDistanceMeters < Double.MAX_VALUE }
            .join(DistanceMatrix::class.java)
            .penalize(HardMediumSoftScore.ONE_HARD) { vehicle, distanceMatrix ->
                val totalDistance = vehicle.getTotalRouteDistance(distanceMatrix)
                val excess = (totalDistance - vehicle.maxDistanceMeters).coerceAtLeast(0.0)
                (excess).toInt().coerceAtLeast(0)
            }
            .asConstraint("Vehicle exceeds max distance")
    }

    // ==================== MEDIUM CONSTRAINTS ====================

    /**
     * MEDIUM: Penalize unassigned optional segments based on their value.
     * This is the incentive to assign optionals - higher value = more penalty for not assigning.
     */
    fun penalizeUnassignedOptionals(factory: ConstraintFactory): Constraint {
        return factory.forEachIncludingUnassigned(StreetSegment::class.java)
            .filter { !it.isMandatory && it.vehicle == null }
            .penalize(HardMediumSoftScore.ONE_MEDIUM) { segment ->
                segment.value.coerceAtLeast(0)
            }
            .asConstraint("Unassigned optional segment")
    }

    // ==================== SOFT CONSTRAINTS ====================

    /**
     * Minimize total distance traveled (including return to depot).
     */
    fun minimizeTotalDistance(factory: ConstraintFactory): Constraint {
        return factory.forEach(Vehicle::class.java)
            .filter { it.segments.isNotEmpty() }
            .join(DistanceMatrix::class.java)
            .penalize(HardMediumSoftScore.ONE_SOFT) { vehicle, distanceMatrix ->
                vehicle.getTotalRouteDistance(distanceMatrix).toInt()
            }
            .asConstraint("Minimize total distance")
    }

    /**
     * Penalize long travels between consecutive segments.
     * Each segment pair is penalized by 1/100 of the travel distance between them.
     * This encourages clustering nearby segments together.
     */
    fun penalizeLongTravelsBetweenSegments(factory: ConstraintFactory): Constraint {
        return factory.forEach(StreetSegment::class.java)
            .filter { it.vehicle != null && it.nextSegment != null }
            .join(DistanceMatrix::class.java)
            .penalize(HardMediumSoftScore.ONE_SOFT) { segment, distanceMatrix ->
                val nextSegment = segment.nextSegment!!
                // Distance from this segment's exit (endNode) to next segment's entry (startNode)
                val travelDistance = distanceMatrix.getDistance(segment.endNode.id, nextSegment.startNode.id)
                // Penalize at 1/100 of the travel distance
                (travelDistance / 100).toInt()
            }
            .asConstraint("Long travel between segments")
    }

    /**
     * Reward vehicles for remaining range when returning to depot.
     * Only applies to vehicles with limited range (maxDistanceMeters < MAX_VALUE).
     * Encourages efficient route planning that leaves some buffer.
     */
    fun rewardRemainingRange(factory: ConstraintFactory): Constraint {
        return factory.forEach(Vehicle::class.java)
            .filter { it.segments.isNotEmpty() && it.maxDistanceMeters < Double.MAX_VALUE }
            .join(DistanceMatrix::class.java)
            .reward(HardMediumSoftScore.ONE_SOFT) { vehicle, distanceMatrix ->
                val totalDistance = vehicle.getTotalRouteDistance(distanceMatrix)
                val remainingRange = (vehicle.maxDistanceMeters - totalDistance).coerceAtLeast(0.0)
                // Reward 1 point per 100 meters of remaining range
                (remainingRange / 100).toInt()
            }
            .asConstraint("Remaining range reward")
    }

    /**
     * Penalize unused vehicles to encourage using all available vehicles.
     */
    fun penalizeUnusedVehicles(factory: ConstraintFactory): Constraint {
        return factory.forEach(Vehicle::class.java)
            .filter { it.segments.isEmpty() }
            .penalize(HardMediumSoftScore.ONE_SOFT) { _ ->
                5000
            }
            .asConstraint("Unused vehicle")
    }
}
