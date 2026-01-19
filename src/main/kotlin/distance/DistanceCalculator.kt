package distance

import com.graphhopper.GHRequest
import com.graphhopper.GraphHopper
import domain.Node
import domain.StreetRoutingSolution
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory

class DistanceCalculator private constructor(
    private val graphHopper: GraphHopper?,
    private val parallelism: Int = 8
) {
    private val log = LoggerFactory.getLogger(DistanceCalculator::class.java)

    companion object {
        /**
         * Create a calculator using an existing GraphHopper instance.
         * The calculator will NOT close the GraphHopper instance.
         */
        fun withExistingGraphHopper(hopper: GraphHopper, parallelism: Int = 8): DistanceCalculator {
            return DistanceCalculator(hopper, parallelism = parallelism)
        }
    }

    /**
     * Calculate distance matrix between all nodes using parallel coroutines
     */
    fun calculateDistanceMatrix(nodes: List<Node>): Map<String, Double> {
        val totalPairs = nodes.size * nodes.size
        log.info("Calculating distance matrix for {} nodes ({} pairs) with parallelism={}",
            nodes.size, totalPairs, parallelism)

        val startTime = System.currentTimeMillis()

        val matrix = runBlocking {
            calculateDistanceMatrixAsync(nodes)
        }

        val elapsed = System.currentTimeMillis() - startTime
        log.info("Distance matrix calculation completed in {}ms ({} pairs/sec)",
            elapsed, if (elapsed > 0) totalPairs * 1000 / elapsed else totalPairs)

        return matrix
    }

    private suspend fun calculateDistanceMatrixAsync(nodes: List<Node>): Map<String, Double> {
        val matrix = java.util.concurrent.ConcurrentHashMap<String, Double>()
        val semaphore = Semaphore(parallelism)

        coroutineScope {
            for (from in nodes) {
                for (to in nodes) {
                    launch(Dispatchers.IO) {
                        semaphore.withPermit {
                            val key = StreetRoutingSolution.distanceKey(from.id, to.id)
                            val distance = if (from.id == to.id) {
                                0.0
                            } else {
                                calculateDistance(from, to)
                            }
                            matrix[key] = distance
                        }
                    }
                }
            }
        }

        return matrix
    }

    /**
     * Calculate distance between two nodes
     */
    fun calculateDistance(from: Node, to: Node): Double {
        if (graphHopper == null) {
            return haversineDistance(from, to)
        }

        val request = GHRequest(
            from.latitude, from.longitude,
            to.latitude, to.longitude
        ).apply {
            profile = "car"
        }

        val response = graphHopper.route(request)

        return if (response.hasErrors()) {
            haversineDistance(from, to)
        } else {
            response.best.distance
        }
    }

    /**
     * Haversine distance in meters
     */
    private fun haversineDistance(from: Node, to: Node): Double {
        val earthRadius = 6371000.0

        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val deltaLat = Math.toRadians(to.latitude - from.latitude)
        val deltaLon = Math.toRadians(to.longitude - from.longitude)

        val a = kotlin.math.sin(deltaLat / 2) * kotlin.math.sin(deltaLat / 2) +
                kotlin.math.cos(lat1) * kotlin.math.cos(lat2) *
                kotlin.math.sin(deltaLon / 2) * kotlin.math.sin(deltaLon / 2)

        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))

        return earthRadius * c
    }
}