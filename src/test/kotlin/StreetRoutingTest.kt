package solver

import ai.timefold.solver.test.api.score.stream.ConstraintVerifier
import domain.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class StreetRoutingConstraintProviderTest {

    private lateinit var constraintVerifier: ConstraintVerifier<StreetRoutingConstraintProvider, StreetRoutingSolution>

    @BeforeEach
    fun setup() {
        constraintVerifier = ConstraintVerifier.build(
            StreetRoutingConstraintProvider(),
            StreetRoutingSolution::class.java,
            Vehicle::class.java,
            StreetSegment::class.java
        )
    }

    private fun createSolution(
        nodes: List<Node>,
        segments: List<StreetSegment>,
        vehicles: List<Vehicle>
    ): StreetRoutingSolution {
        return StreetRoutingSolution(
            nodes = nodes,
            segments = segments,
            vehicles = vehicles,
            distanceMatrix = DistanceMatrix(emptyMap()),
            geometries = emptyMap()
        )
    }

    @Test
    fun `mandatory segment not assigned should penalize`() {
        val node1 = Node("n1", 0.0, 0.0)
        val node2 = Node("n2", 0.0, 0.1)
        val vehicle = Vehicle("v1", node1)
        // vehicle.segments is empty - segment not assigned

        val segment = StreetSegment(
            id = "seg1",
            startNode = node1,
            endNode = node2,
            lengthMeters = 100.0,
            name = "Test Street",
            isMandatory = true
        )
        // Do NOT add segment to vehicle.segments
        // Verify the shadow variable is indeed null
        assert(segment.vehicle == null) { "segment.vehicle should be null before test" }

        val solution = createSolution(
            nodes = listOf(node1, node2),
            segments = listOf(segment),
            vehicles = listOf(vehicle)
        )

        // Manually verify the segment's vehicle is null after creating solution
        // (shadow variables are computed by givenSolution)
        constraintVerifier.verifyThat(StreetRoutingConstraintProvider::mandatorySegmentsMustBeAssigned)
            .givenSolution(solution)
            .penalizes()  // Just verify it penalizes, don't check exact amount
    }

    @Test
    fun `mandatory segment assigned should not penalize`() {
        val node1 = Node("n1", 0.0, 0.0)
        val node2 = Node("n2", 0.0, 0.1)
        val vehicle = Vehicle("v1", node1)

        val segment = StreetSegment(
            id = "seg1",
            startNode = node1,
            endNode = node2,
            lengthMeters = 100.0,
            name = "Test Street",
            isMandatory = true
        )

        // Assign segment to vehicle
        vehicle.segments = mutableListOf(segment)

        val solution = createSolution(
            nodes = listOf(node1, node2),
            segments = listOf(segment),
            vehicles = listOf(vehicle)
        )

        constraintVerifier.verifyThat(StreetRoutingConstraintProvider::mandatorySegmentsMustBeAssigned)
            .givenSolution(solution)
            .penalizesBy(0)
    }

    @Test
    fun `optional segment assigned should reward based on net benefit`() {
        val node1 = Node("n1", 0.0, 0.0)
        val node2 = Node("n2", 0.0, 0.1)
        val vehicle = Vehicle("v1", node1)

        // Segment with value 150 and length 100m
        // Net benefit = value - (lengthMeters / 10) = 150 - 10 = 140
        val segment = StreetSegment(
            id = "seg1",
            startNode = node1,
            endNode = node2,
            lengthMeters = 100.0,
            name = "Optional Street",
            isMandatory = false,
            value = 150
        )

        // Assign segment to vehicle
        vehicle.segments = mutableListOf(segment)

        val solution = createSolution(
            nodes = listOf(node1, node2),
            segments = listOf(segment),
            vehicles = listOf(vehicle)
        )

        constraintVerifier.verifyThat(StreetRoutingConstraintProvider::penalizeUnassignedOptionals)
            .givenSolution(solution)
            .rewardsWith(140)  // 150 - (100/10) = 140
    }

    @Test
    fun `optional segment not assigned should not reward`() {
        val node1 = Node("n1", 0.0, 0.0)
        val node2 = Node("n2", 0.0, 0.1)
        val vehicle = Vehicle("v1", node1)

        val segment = StreetSegment(
            id = "seg1",
            startNode = node1,
            endNode = node2,
            lengthMeters = 100.0,
            name = "Optional Street",
            isMandatory = false,
            value = 150
        )
        // Segment is NOT assigned to vehicle

        val solution = createSolution(
            nodes = listOf(node1, node2),
            segments = listOf(segment),
            vehicles = listOf(vehicle)
        )

        constraintVerifier.verifyThat(StreetRoutingConstraintProvider::penalizeUnassignedOptionals)
            .givenSolution(solution)
            .rewardsWith(0)
    }
}