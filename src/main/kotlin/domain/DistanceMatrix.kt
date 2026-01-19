package domain

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Wrapper class for the distance matrix to be used as a problem fact.
 * This allows accessing the distance matrix from constraint streams.
 */
class DistanceMatrix @JsonCreator constructor(
    @JsonProperty("distances") val distances: Map<String, Double>
) {
    /**
     * Get distance between two nodes by their IDs.
     * Returns 0.0 if nodes are the same or distance is not found.
     */
    fun getDistance(fromNodeId: String, toNodeId: String): Double {
        if (fromNodeId == toNodeId) return 0.0
        return distances["$fromNodeId->$toNodeId"] ?: 0.0
    }
}
