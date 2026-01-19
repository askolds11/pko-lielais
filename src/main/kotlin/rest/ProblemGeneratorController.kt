package rest

import benchmark.ProblemGenerator
import domain.StreetRoutingSolution
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.*

@Tag(name = "Problem Generator", description = "Generate test problems for benchmarking")
@RestController
@RequestMapping("/generate")
class ProblemGeneratorController(
    private val osmProblemController: OsmProblemController
) {

    @Operation(summary = "Generate a random street routing problem")
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "Generated problem",
                content = [Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = StreetRoutingSolution::class)
                )]
            )
        ]
    )
    @GetMapping(produces = [MediaType.APPLICATION_JSON_VALUE])
    fun generate(
        @Parameter(description = "Number of mandatory segments")
        @RequestParam(defaultValue = "10") mandatorySegments: Int,

        @Parameter(description = "Number of optional segments")
        @RequestParam(defaultValue = "5") optionalSegments: Int,

        @Parameter(description = "Number of vehicles")
        @RequestParam(defaultValue = "2") vehicles: Int,

        @Parameter(description = "Maximum range per vehicle in meters (0 = unlimited)")
        @RequestParam(defaultValue = "0") maxRange: Double,

        @Parameter(description = "Random seed for reproducibility")
        @RequestParam(required = false) seed: Long?,

        @Parameter(description = "Center latitude (if not provided, uses OSM file center)")
        @RequestParam(required = false) centerLat: Double?,

        @Parameter(description = "Center longitude (if not provided, uses OSM file center)")
        @RequestParam(required = false) centerLon: Double?
    ): StreetRoutingSolution {
        val actualSeed = seed ?: System.currentTimeMillis()
        val actualMaxRange = if (maxRange <= 0) Double.MAX_VALUE else maxRange

        // Get default coordinates from OSM bounds if available
        val osmStatus = osmProblemController.checkOsmSupport()
        val defaultLat = osmStatus.bounds?.centerLat ?: 56.95  // Latvia default
        val defaultLon = osmStatus.bounds?.centerLon ?: 24.1

        return ProblemGenerator.generate(
            mandatorySegments = mandatorySegments,
            optionalSegments = optionalSegments,
            vehicleCount = vehicles,
            maxRangePerVehicle = actualMaxRange,
            seed = actualSeed,
            centerLat = centerLat ?: defaultLat,
            centerLon = centerLon ?: defaultLon
        )
    }

    @Operation(summary = "Generate a problem and immediately start solving it")
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "Job ID of the started solver"
            )
        ]
    )
    @PostMapping(value = ["/solve"], produces = [MediaType.TEXT_PLAIN_VALUE])
    fun generateAndSolve(
        @RequestParam(defaultValue = "10") mandatorySegments: Int,
        @RequestParam(defaultValue = "5") optionalSegments: Int,
        @RequestParam(defaultValue = "2") vehicles: Int,
        @RequestParam(defaultValue = "0") maxRange: Double,
        @RequestParam(required = false) seed: Long?,
        routingController: StreetRoutingController
    ): String {
        val problem = generate(
            mandatorySegments = mandatorySegments,
            optionalSegments = optionalSegments,
            vehicles = vehicles,
            maxRange = maxRange,
            seed = seed,
            centerLat = 52.52,
            centerLon = 13.405
        )
        return routingController.solve(problem)
    }
}
