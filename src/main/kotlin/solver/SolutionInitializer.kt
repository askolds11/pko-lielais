package solver

import domain.StreetRoutingSolution
import domain.StreetSegment

/**
 * Utility to initialize a solution by assigning segments to vehicles.
 */
object SolutionInitializer {

    /**
     * Assigns all segments to vehicles in a round-robin fashion.
     * Segments are sorted: mandatory first, then by length (longest first).
     * This ensures all mandatory segments are assigned from the start.
     */
    fun initializeSolution(solution: StreetRoutingSolution) {
        val vehicles = solution.vehicles
        val segments = solution.segments

        if (vehicles.isEmpty() || segments.isEmpty()) return

        // Clear any existing assignments
        vehicles.forEach { it.segments.clear() }

        // Sort segments: mandatory first, then by length (longest first)
        val sortedSegments = segments.sortedWith(
            compareByDescending<StreetSegment> { it.isMandatory }
                .thenByDescending { it.lengthMeters }
        )

        // Assign segments to vehicles round-robin
        sortedSegments.forEachIndexed { index, segment ->
            val vehicle = vehicles[index % vehicles.size]
            vehicle.segments.add(segment)
        }
    }
}
