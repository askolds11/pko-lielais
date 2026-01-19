package domain

import com.fasterxml.jackson.annotation.JsonIdentityInfo
import com.fasterxml.jackson.annotation.ObjectIdGenerators

@JsonIdentityInfo(
    generator = ObjectIdGenerators.PropertyGenerator::class,
    property = "id"
)
data class Node(
    val id: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0
) {
    override fun toString() = "Node($id)"
}