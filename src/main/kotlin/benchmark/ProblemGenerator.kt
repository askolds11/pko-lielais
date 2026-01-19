package benchmark

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import domain.*
import java.io.File
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Generates test problems for benchmarking.
 */
object ProblemGenerator {

    private val mapper = jacksonObjectMapper()

    /**
     * Generate a random street routing problem.
     *
     * @param mandatorySegments Number of mandatory segments
     * @param optionalSegments Number of optional segments
     * @param vehicleCount Number of vehicles
     * @param maxRangePerVehicle Maximum range per vehicle (meters)
     * @param seed Random seed for reproducibility
     * @param centerLat Center latitude for generated network
     * @param centerLon Center longitude for generated network
     * @param areaSize Size of the area in degrees (roughly)
     */
    fun generate(
        mandatorySegments: Int,
        optionalSegments: Int,
        vehicleCount: Int = 2,
        maxRangePerVehicle: Double = Double.MAX_VALUE,
        seed: Long = System.currentTimeMillis(),
        centerLat: Double = 56.95,  // Latvia default
        centerLon: Double = 24.1,   // Latvia default
        areaSize: Double = 0.05
    ): StreetRoutingSolution {
        val random = Random(seed)

        val totalSegments = mandatorySegments + optionalSegments
        // Generate enough nodes (roughly 1.5 nodes per segment for a connected network)
        val nodeCount = (totalSegments * 1.5).toInt().coerceAtLeast(totalSegments + 1)

        // Generate nodes in a random pattern
        val nodes = generateNodes(nodeCount, centerLat, centerLon, areaSize, random)
        val nodeList = nodes.values.toList()

        // Create depot as the first node
        val depot = nodeList.first()

        // Generate segments connecting random pairs of nodes
        // Each road counts as 1 but creates 2 segments (forward and reverse)
        val segments = mutableListOf<StreetSegment>()
        val geometries = mutableMapOf<String, List<List<Double>>>()
        val usedPairs = mutableSetOf<Pair<String, String>>()

        // Generate mandatory segments (each road = 2 segments)
        repeat(mandatorySegments) { i ->
            val (fwdSegment, revSegment, fwdGeom, revGeom) = generateTwoWaySegment(
                baseId = "mandatory_$i",
                nodes = nodeList,
                usedPairs = usedPairs,
                isMandatory = true,
                value = 0,
                random = random
            )
            segments.add(fwdSegment)
            segments.add(revSegment)
            geometries[fwdSegment.id] = fwdGeom
            geometries[revSegment.id] = revGeom
        }

        // Generate optional segments (each road = 2 segments)
        repeat(optionalSegments) { i ->
            val value = random.nextInt(50, 300) // Random value between 50 and 300
            val (fwdSegment, revSegment, fwdGeom, revGeom) = generateTwoWaySegment(
                baseId = "optional_$i",
                nodes = nodeList,
                usedPairs = usedPairs,
                isMandatory = false,
                value = value,
                random = random
            )
            segments.add(fwdSegment)
            segments.add(revSegment)
            geometries[fwdSegment.id] = fwdGeom
            geometries[revSegment.id] = revGeom
        }

        // Create vehicles
        val vehicles = (1..vehicleCount).map { i ->
            Vehicle(
                id = "vehicle_$i",
                startingNode = depot,
                maxDistanceMeters = maxRangePerVehicle
            )
        }

        // Create distance matrix (simplified: Haversine distance)
        val distanceMatrixMap = createDistanceMatrix(nodeList)
        val distanceMatrix = DistanceMatrix(distanceMatrixMap)

        return StreetRoutingSolution(
            nodes = nodeList,
            segments = segments,
            vehicles = vehicles,
            distanceMatrix = distanceMatrix,
            geometries = geometries
        )
    }

    /**
     * Result of generating a two-way road segment.
     */
    data class TwoWaySegmentResult(
        val forwardSegment: StreetSegment,
        val reverseSegment: StreetSegment,
        val forwardGeometry: List<List<Double>>,
        val reverseGeometry: List<List<Double>>
    )

    /**
     * Generate a two-way road segment (forward and reverse direction).
     */
    private fun generateTwoWaySegment(
        baseId: String,
        nodes: List<Node>,
        usedPairs: MutableSet<Pair<String, String>>,
        isMandatory: Boolean,
        value: Int,
        random: Random
    ): TwoWaySegmentResult {
        var startNode: Node
        var endNode: Node
        var pair: Pair<String, String>

        // Find an unused pair of nodes
        do {
            startNode = nodes[random.nextInt(nodes.size)]
            endNode = nodes[random.nextInt(nodes.size)]
            pair = if (startNode.id < endNode.id) {
                startNode.id to endNode.id
            } else {
                endNode.id to startNode.id
            }
        } while (startNode == endNode || pair in usedPairs)

        usedPairs.add(pair)

        // Calculate length based on distance
        val length = haversineDistance(
            startNode.latitude, startNode.longitude,
            endNode.latitude, endNode.longitude
        )

        // Generate a street name
        val streetNames = listOf(
            "Main", "Oak", "Maple", "Cedar", "Pine", "Elm", "Park", "Lake",
            "River", "Hill", "Valley", "Forest", "Mountain", "Ocean", "Beach"
        )
        val streetTypes = listOf("Street", "Avenue", "Road", "Lane", "Drive", "Way", "Boulevard")
        val name = "${streetNames[random.nextInt(streetNames.size)]} ${streetTypes[random.nextInt(streetTypes.size)]}"

        // Forward segment (A -> B)
        val forwardSegment = StreetSegment(
            id = "${baseId}_fwd",
            startNode = startNode,
            endNode = endNode,
            lengthMeters = length,
            name = name,
            isMandatory = isMandatory,
            value = value
        )

        // Reverse segment (B -> A)
        val reverseSegment = StreetSegment(
            id = "${baseId}_rev",
            startNode = endNode,
            endNode = startNode,
            lengthMeters = length,
            name = name,
            isMandatory = isMandatory,
            value = value
        )

        // Geometries
        val forwardGeometry = listOf(
            listOf(startNode.latitude, startNode.longitude),
            listOf(endNode.latitude, endNode.longitude)
        )
        val reverseGeometry = listOf(
            listOf(endNode.latitude, endNode.longitude),
            listOf(startNode.latitude, startNode.longitude)
        )

        return TwoWaySegmentResult(forwardSegment, reverseSegment, forwardGeometry, reverseGeometry)
    }

    private fun generateNodes(
        count: Int,
        centerLat: Double,
        centerLon: Double,
        areaSize: Double,
        random: Random
    ): Map<String, Node> {
        val nodes = mutableMapOf<String, Node>()

        // First node is the depot at center
        nodes["depot"] = Node("depot", centerLat, centerLon)

        // Generate remaining nodes in a roughly circular pattern with some randomness
        for (i in 1 until count) {
            val angle = random.nextDouble() * 2 * Math.PI
            val distance = random.nextDouble() * areaSize

            val lat = centerLat + distance * cos(angle)
            val lon = centerLon + distance * sin(angle) / cos(Math.toRadians(centerLat))

            val id = "node_$i"
            nodes[id] = Node(id, lat, lon)
        }

        return nodes
    }

    private fun createDistanceMatrix(nodes: List<Node>): Map<String, Double> {
        val matrix = mutableMapOf<String, Double>()

        for (from in nodes) {
            for (to in nodes) {
                if (from != to) {
                    val key = "${from.id}->${to.id}"
                    val distance = haversineDistance(
                        from.latitude, from.longitude,
                        to.latitude, to.longitude
                    )
                    matrix[key] = distance
                }
            }
        }

        return matrix
    }

    private fun haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0 // Earth's radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return R * c
    }

    /**
     * Load a problem from a JSON file.
     */
    fun loadProblem(file: File): StreetRoutingSolution {
        return mapper.readValue(file)
    }
}
