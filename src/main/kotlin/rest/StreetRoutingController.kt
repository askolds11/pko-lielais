package rest

import ai.timefold.solver.core.api.score.analysis.ScoreAnalysis
import ai.timefold.solver.core.api.score.buildin.hardmediumsoft.HardMediumSoftScore
import ai.timefold.solver.core.api.solver.SolutionManager
import ai.timefold.solver.core.api.solver.SolverManager
import ai.timefold.solver.core.api.solver.SolverStatus
import domain.StreetRoutingSolution
import domain.StreetSegment
import domain.Vehicle
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import solver.SolutionInitializer
import java.util.*
import java.util.concurrent.ConcurrentHashMap

@Tag(name = "Street Routing", description = "Service to optimize street routing problems")
@RestController
@RequestMapping("/routing")
class StreetRoutingController(
    private val solverManager: SolverManager<StreetRoutingSolution, String>,
    private val solutionManager: SolutionManager<StreetRoutingSolution, HardMediumSoftScore>
) {
    private val log = LoggerFactory.getLogger(StreetRoutingController::class.java)
    private val jobIdToJob = ConcurrentHashMap<String, Job>()

    @Operation(summary = "List the job IDs of all submitted street routing problems.")
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200", description = "List of all job IDs.",
                content = [Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(type = "array", implementation = String::class)
                )]
            )
        ]
    )
    @GetMapping
    fun list(): Collection<String> = jobIdToJob.keys

    @Operation(summary = "Submit a street routing problem to start solving as soon as CPU resources are available.")
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "202",
                description = "The job ID. Use that ID to get the solution with the other methods.",
                content = [Content(
                    mediaType = MediaType.TEXT_PLAIN_VALUE,
                    schema = Schema(implementation = String::class)
                )]
            )
        ]
    )
    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.TEXT_PLAIN_VALUE])
    fun solve(@RequestBody problem: StreetRoutingSolution): String {
        val jobId = UUID.randomUUID().toString()

        // Initialize solution by assigning all segments to vehicles
        SolutionInitializer.initializeSolution(problem)

        jobIdToJob[jobId] = Job.ofSolution(problem)
        @Suppress("DEPRECATION")
        solverManager.solveBuilder()
            .withProblemId(jobId)
            .withProblemFinder { jobIdToJob[it]!!.solution }
            .withBestSolutionEventConsumer { event ->
                val solution = event.solution()
                // Log new best solution
                val assignedCount = solution.segments.count { it.vehicle != null }
                val unassignedMandatory = solution.segments.count { it.isMandatory && it.vehicle == null }
                val unassignedOptional = solution.segments.count { !it.isMandatory && it.vehicle == null }
                log.info(
                    "New best solution for job {}: score={}, assigned={}/{} segments, unassigned: {} mandatory, {} optional",
                    jobId,
                    solution.score,
                    assignedCount,
                    solution.segments.size,
                    unassignedMandatory,
                    unassignedOptional
                )
                // Update stored solution
                jobIdToJob[jobId] = Job.ofSolution(solution)
            }
            .withExceptionHandler { id, exception ->
                jobIdToJob[id] = Job.ofException(exception)
                log.error("Failed solving jobId ({}).", id, exception)
            }
            .run()

        return jobId
    }

    @Operation(
        summary = "Get the solution and score for a given job ID. This is the best solution so far, as it might still be running or not even started."
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200", description = "The best solution of the street routing problem so far.",
                content = [Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = StreetRoutingSolution::class)
                )]
            ),
            ApiResponse(
                responseCode = "404", description = "No problem found.",
                content = [Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorInfo::class)
                )]
            ),
            ApiResponse(
                responseCode = "500", description = "Exception during solving.",
                content = [Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorInfo::class)
                )]
            )
        ]
    )
    @GetMapping(value = ["/{jobId}"], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getSolution(
        @Parameter(description = "The job ID returned by the POST method.") @PathVariable("jobId") jobId: String
    ): StreetRoutingSolutionResponse {
        val solution = getSolutionAndCheckForExceptions(jobId)
        val solverStatus = solverManager.getSolverStatus(jobId)
        return StreetRoutingSolutionResponse(
            solution = solution,
            solverStatus = solverStatus,
            score = solution.score
        )
    }

    @Operation(summary = "Get the solver status for a given job ID.")
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200", description = "The solver status.",
                content = [Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = SolverStatus::class)
                )]
            ),
            ApiResponse(
                responseCode = "404", description = "No problem found.",
                content = [Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorInfo::class)
                )]
            )
        ]
    )
    @GetMapping(value = ["/{jobId}/status"], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getStatus(
        @Parameter(description = "The job ID returned by the POST method.") @PathVariable("jobId") jobId: String
    ): StatusResponse {
        getSolutionAndCheckForExceptions(jobId) // Verify job exists
        val solverStatus = solverManager.getSolverStatus(jobId)
        return StatusResponse(jobId = jobId, status = solverStatus)
    }

    @Operation(summary = "Get the score analysis for a given job ID.")
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200", description = "The score analysis of the best solution so far.",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE)]
            ),
            ApiResponse(
                responseCode = "404", description = "No problem found.",
                content = [Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorInfo::class)
                )]
            ),
            ApiResponse(
                responseCode = "500", description = "Exception during solving.",
                content = [Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorInfo::class)
                )]
            )
        ]
    )
    @GetMapping(value = ["/score/{jobId}"], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun analyze(
        @Parameter(description = "The job ID returned by the POST method.") @PathVariable("jobId") jobId: String
    ): ScoreAnalysis<HardMediumSoftScore> {
        val solution = getSolutionAndCheckForExceptions(jobId)
        return solutionManager.analyze(solution)
    }

    @Operation(summary = "Get the score indictments for a given job ID.")
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200", description = "The score indictments of the best solution so far.",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE)]
            ),
            ApiResponse(
                responseCode = "404", description = "No problem found.",
                content = [Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorInfo::class)
                )]
            ),
            ApiResponse(
                responseCode = "500", description = "Exception during solving.",
                content = [Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorInfo::class)
                )]
            )
        ]
    )
    @GetMapping(value = ["/indictments/{jobId}"], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun indictments(
        @Parameter(description = "The job ID returned by the POST method.") @PathVariable("jobId") jobId: String
    ): List<SimpleIndictmentObject> {
        val solution = getSolutionAndCheckForExceptions(jobId)
        return solutionManager.explain(solution).indictmentMap.entries.map { (key, indictment) ->
            SimpleIndictmentObject(
                indictedObject = key,
                score = indictment.score,
                constraintMatchCount = indictment.constraintMatchCount,
                constraintMatches = indictment.constraintMatchSet.map { match ->
                    ConstraintMatchInfo(
                        constraintName = match.constraintRef.constraintName,
                        score = match.score.toString()
                    )
                }
            )
        }
    }

    @Operation(summary = "Stop solving a given job ID.")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Solving stopped."),
            ApiResponse(
                responseCode = "404", description = "No problem found.",
                content = [Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorInfo::class)
                )]
            )
        ]
    )
    @PostMapping(value = ["/{jobId}/stop"])
    fun stopSolving(
        @Parameter(description = "The job ID returned by the POST method.") @PathVariable("jobId") jobId: String
    ): StatusResponse {
        getSolutionAndCheckForExceptions(jobId) // Verify job exists
        solverManager.terminateEarly(jobId)
        val status = solverManager.getSolverStatus(jobId)
        return StatusResponse(jobId = jobId, status = status)
    }

    @Operation(summary = "Delete a job and its solution.")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Job deleted."),
            ApiResponse(
                responseCode = "404", description = "No problem found.",
                content = [Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorInfo::class)
                )]
            )
        ]
    )
    @DeleteMapping(value = ["/{jobId}"])
    fun deleteJob(
        @Parameter(description = "The job ID returned by the POST method.") @PathVariable("jobId") jobId: String
    ) {
        if (!jobIdToJob.containsKey(jobId)) {
            throw StreetRoutingException(jobId, "No problem found.")
        }
        solverManager.terminateEarly(jobId)
        jobIdToJob.remove(jobId)
    }

    @Operation(summary = "Download problem as JSON file for benchmarks.")
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200", description = "Problem JSON file.",
                content = [Content(mediaType = MediaType.APPLICATION_OCTET_STREAM_VALUE)]
            ),
            ApiResponse(
                responseCode = "404", description = "No problem found.",
                content = [Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorInfo::class)
                )]
            )
        ]
    )
    @GetMapping(value = ["/{jobId}/download"], produces = [MediaType.APPLICATION_OCTET_STREAM_VALUE])
    fun downloadProblem(
        @Parameter(description = "The job ID returned by the POST method.") @PathVariable("jobId") jobId: String
    ): ResponseEntity<ByteArray> {
        val solution = getSolutionAndCheckForExceptions(jobId)

        // Create a clean copy without solution state (for benchmarking)
        // Clear vehicle segment assignments and score
        val cleanSolution = StreetRoutingSolution(
            nodes = solution.nodes,
            segments = solution.segments.map { segment ->
                // Create a copy of segment without shadow variable state
                StreetSegment(
                    id = segment.id,
                    startNode = segment.startNode,
                    endNode = segment.endNode,
                    lengthMeters = segment.lengthMeters,
                    name = segment.name,
                    isMandatory = segment.isMandatory,
                    value = segment.value
                )
            },
            vehicles = solution.vehicles.map { vehicle ->
                // Create a copy of vehicle without assigned segments
                Vehicle(
                    id = vehicle.id,
                    startingNode = vehicle.startingNode,
                    maxDistanceMeters = vehicle.maxDistanceMeters
                )
                // segments list is empty by default
            },
            distanceMatrix = solution.distanceMatrix,
            geometries = solution.geometries
        )
        // score is null by default

        val mapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
            .enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT)
        val json = mapper.writeValueAsBytes(cleanSolution)

        return ResponseEntity.ok()
            .header("Content-Disposition", "attachment; filename=\"problem_${jobId}.json\"")
            .header("Content-Type", "application/json")
            .body(json)
    }

    @Operation(summary = "Get score breakdown by vehicle for a given job ID.")
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200", description = "Score breakdown by vehicle.",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE)]
            ),
            ApiResponse(
                responseCode = "404", description = "No problem found.",
                content = [Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorInfo::class)
                )]
            )
        ]
    )
    @GetMapping(value = ["/breakdown/{jobId}"], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun breakdown(
        @Parameter(description = "The job ID returned by the POST method.") @PathVariable("jobId") jobId: String
    ): ScoreBreakdownResponse {
        val solution = getSolutionAndCheckForExceptions(jobId)
        val explanation = solutionManager.explain(solution)

        // Build breakdown by vehicle
        val vehicleBreakdowns = solution.vehicles.map { vehicle ->
            // Get constraints for this vehicle (vehicle-level constraints)
            val vehicleIndictment = explanation.indictmentMap[vehicle]
            val vehicleOnlyScore = vehicleIndictment?.score ?: HardMediumSoftScore.ZERO

            val vehicleConstraints = vehicleIndictment?.constraintMatchSet?.map { match ->
                ConstraintBreakdown(
                    name = match.constraintRef.constraintName,
                    score = match.score.toString(),
                    scoreType = getScoreType(match.score)
                )
            } ?: emptyList()

            // Get constraints for segments assigned to this vehicle
            val segmentConstraints = vehicle.segments.flatMap { segment ->
                explanation.indictmentMap[segment]?.constraintMatchSet?.map { match ->
                    ConstraintBreakdown(
                        name = match.constraintRef.constraintName,
                        score = match.score.toString(),
                        scoreType = getScoreType(match.score),
                        segmentId = segment.id,
                        segmentName = segment.name
                    )
                } ?: emptyList()
            }

            // Sum up segment scores for this vehicle
            val segmentScoreSum = vehicle.segments.fold(HardMediumSoftScore.ZERO) { acc, segment ->
                val segmentScore = explanation.indictmentMap[segment]?.score ?: HardMediumSoftScore.ZERO
                acc.add(segmentScore)
            }

            // Total vehicle score = vehicle constraints + segment constraints
            val totalVehicleScore = vehicleOnlyScore.add(segmentScoreSum)

            VehicleScoreBreakdown(
                vehicleId = vehicle.id,
                totalScore = totalVehicleScore.toString(),
                hardScore = parseScoreComponent(totalVehicleScore, "hard"),
                mediumScore = parseScoreComponent(totalVehicleScore, "medium"),
                softScore = parseScoreComponent(totalVehicleScore, "soft"),
                segmentCount = vehicle.segments.size,
                totalDistance = vehicle.segments.sumOf { it.lengthMeters },
                maxRange = vehicle.maxDistanceMeters,
                vehicleConstraints = vehicleConstraints,
                segmentConstraints = segmentConstraints
            )
        }

        // Get unassigned segment constraints
        val unassignedConstraints = solution.segments
            .filter { it.vehicle == null }
            .flatMap { segment ->
                explanation.indictmentMap[segment]?.constraintMatchSet?.map { match ->
                    ConstraintBreakdown(
                        name = match.constraintRef.constraintName,
                        score = match.score.toString(),
                        scoreType = getScoreType(match.score),
                        segmentId = segment.id,
                        segmentName = segment.name
                    )
                } ?: emptyList()
            }

        return ScoreBreakdownResponse(
            totalScore = solution.score?.toString() ?: "N/A",
            vehicleBreakdowns = vehicleBreakdowns,
            unassignedSegmentConstraints = unassignedConstraints
        )
    }

    private fun getScoreType(score: HardMediumSoftScore): String {
        val hard = parseScoreComponent(score, "hard")
        val medium = parseScoreComponent(score, "medium")
        val soft = parseScoreComponent(score, "soft")
        return when {
            hard != 0 -> "hard"
            medium != 0 -> "medium"
            soft != 0 -> "soft"
            else -> "none"
        }
    }

    /**
     * Parse a score component from a HardMediumSoftScore without using deprecated methods.
     * Score format is like "0hard/444medium/-573soft"
     */
    private fun parseScoreComponent(score: HardMediumSoftScore, component: String): Int {
        val scoreStr = score.toString()
        return when (component) {
            "hard" -> scoreStr.substringBefore("hard").toIntOrNull() ?: 0
            "medium" -> scoreStr.substringAfter("/").substringBefore("medium").toIntOrNull() ?: 0
            "soft" -> scoreStr.substringAfterLast("/").substringBefore("soft").toIntOrNull() ?: 0
            else -> 0
        }
    }

    private fun getSolutionAndCheckForExceptions(jobId: String): StreetRoutingSolution {
        val job = jobIdToJob[jobId] ?: throw StreetRoutingException(jobId, "No problem found.")
        if (job.exception != null) {
            throw StreetRoutingException(jobId, job.exception)
        }
        return job.solution!!
    }

    private data class Job(
        val solution: StreetRoutingSolution?,
        val exception: Throwable?
    ) {
        companion object {
            fun ofSolution(solution: StreetRoutingSolution) = Job(solution, null)
            fun ofException(exception: Throwable) = Job(null, exception)
        }
    }
}

// Response DTOs
data class StreetRoutingSolutionResponse(
    val solution: StreetRoutingSolution,
    val solverStatus: SolverStatus,
    val score: HardMediumSoftScore?
)

data class StatusResponse(
    val jobId: String,
    val status: SolverStatus
)

data class SimpleIndictmentObject(
    val indictedObject: Any,
    val score: HardMediumSoftScore,
    val constraintMatchCount: Int,
    val constraintMatches: List<ConstraintMatchInfo>
)

data class ConstraintMatchInfo(
    val constraintName: String,
    val score: String
)

data class ScoreBreakdownResponse(
    val totalScore: String,
    val vehicleBreakdowns: List<VehicleScoreBreakdown>,
    val unassignedSegmentConstraints: List<ConstraintBreakdown>
)

data class VehicleScoreBreakdown(
    val vehicleId: String,
    val totalScore: String,
    val hardScore: Int,
    val mediumScore: Int,
    val softScore: Int,
    val segmentCount: Int,
    val totalDistance: Double,
    val maxRange: Double,
    val vehicleConstraints: List<ConstraintBreakdown>,
    val segmentConstraints: List<ConstraintBreakdown>
)

data class ConstraintBreakdown(
    val name: String,
    val score: String,
    val scoreType: String,
    val segmentId: String? = null,
    val segmentName: String? = null
)

