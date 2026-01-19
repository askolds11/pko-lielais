package rest

import com.graphhopper.GraphHopper
import com.graphhopper.config.Profile
import com.graphhopper.util.CustomModel
import distance.DistanceCalculator
import domain.*
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.Resource
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.*
import osm.BoundingBox
import osm.OsmLoaderPython
import java.io.File
import kotlin.random.Random

@Tag(name = "OSM Problem Generator", description = "Generate problems from OpenStreetMap data")
@RestController
@RequestMapping("/osm")
class OsmProblemController(
    private val routingController: StreetRoutingController
) {
    private val log = LoggerFactory.getLogger(OsmProblemController::class.java)

    @Value($$"${app.osm.file:classpath:map.osm.pbf}")
    private lateinit var osmFileResource: Resource

    @Value($$"${app.graphhopper.cache-dir:cache/graphhopper}")
    private lateinit var graphHopperCacheDir: String

    @Value($$"${app.osm.graph-cache-file:cache/osm-graph-cache.json}")
    private lateinit var osmGraphCacheFile: String

    @Value($$"${app.distance.parallelism:8}")
    private var distanceParallelism: Int = 8

    private var osmFilePath: File? = null
    private var graphHopper: GraphHopper? = null

    @PostConstruct
    fun init() {
        // Extract the OSM file from resources to a fixed location in cache
        try {
            if (osmFileResource.exists()) {
                // Use a fixed file path in the cache directory
                val cacheDir = File(graphHopperCacheDir).parentFile ?: File("cache")
                if (!cacheDir.exists()) cacheDir.mkdirs()

                val originalFilename = osmFileResource.filename ?: "map.osm.pbf"
                val extractedFile = File(cacheDir, originalFilename)

                // Only extract if not already present or if resource is newer
                if (!extractedFile.exists() || extractedFile.length() == 0L) {
                    log.info("Extracting OSM file to: ${extractedFile.absolutePath}")
                    osmFileResource.inputStream.use { input ->
                        extractedFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                } else {
                    log.info("Using cached OSM file: ${extractedFile.absolutePath}")
                }

                osmFilePath = extractedFile
                log.info("OSM file ready: ${extractedFile.absolutePath}, size: ${extractedFile.length()} bytes")

                // Initialize GraphHopper for road-based distance calculation
                initGraphHopper(extractedFile)
            } else {
                log.warn("OSM file not found in resources. OSM generation will not be available.")
            }
        } catch (e: Exception) {
            log.error("Failed to load OSM file from resources: ${e.message}", e)
        }
    }

    private fun initGraphHopper(osmFileArg: File) {
        log.info("Starting GraphHopper initialization...")
        log.info("OSM file: ${osmFileArg.absolutePath}")
        log.info("OSM file exists: ${osmFileArg.exists()}")
        log.info("OSM file size: ${osmFileArg.length()} bytes")
        log.info("OSM file readable: ${osmFileArg.canRead()}")

        if (!osmFileArg.exists() || !osmFileArg.canRead()) {
            log.error("OSM file does not exist or is not readable!")
            return
        }

        if (osmFileArg.length() == 0L) {
            log.error("OSM file is empty!")
            return
        }

        try {
            // Ensure cache directory exists with proper permissions (persistent across restarts)
            val cacheDir = File(graphHopperCacheDir)
            if (cacheDir.exists()) {
                log.info("GraphHopper cache directory exists: ${cacheDir.absolutePath}")
            } else {
                val created = cacheDir.mkdirs()
                log.info("Created GraphHopper cache directory: ${cacheDir.absolutePath}, success: $created")
            }

            log.info("Creating GraphHopper instance...")
            val hopper = GraphHopper()
            hopper.osmFile = osmFileArg.absolutePath
            hopper.graphHopperLocation = cacheDir.absolutePath

            // GraphHopper 11.0+ requires custom weighting with distance_influence for shortest path
            // High distance_influence (e.g., 10000) makes it behave like shortest path
            val customModel = CustomModel()
            customModel.distanceInfluence = 10000.0  // High value = prioritize distance (shortest path)
            // Set initial speed unconditionally (required by GraphHopper 11.0)
            customModel.addToSpeed(com.graphhopper.json.Statement.If("true", com.graphhopper.json.Statement.Op.LIMIT, "100"))

            val profile = Profile("car")
                .setCustomModel(customModel)
            hopper.setProfiles(profile)

            log.info("Calling GraphHopper importOrLoad() - this may take a while for first run...")
            hopper.importOrLoad()

            graphHopper = hopper
            log.info("GraphHopper initialized successfully!")
        } catch (e: Exception) {
            log.error("Failed to initialize GraphHopper", e)
            log.error("Exception type: ${e.javaClass.name}")
            log.error("Exception message: ${e.message}")
            e.cause?.let { cause ->
                log.error("Caused by: ${cause.javaClass.name}: ${cause.message}")
            }
            graphHopper = null
        }
    }

    @PreDestroy
    fun cleanup() {
        graphHopper?.close()
    }

    @Operation(summary = "Generate a street routing problem from the bundled OSM file")
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "Generated problem",
                content = [Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = StreetRoutingSolution::class)
                )]
            ),
            ApiResponse(
                responseCode = "400",
                description = "Invalid parameters or OSM file not available"
            )
        ]
    )
    @PostMapping(
        value = ["/generate"],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    fun generateFromOsm(
        @Parameter(description = "Number of mandatory segments to select")
        @RequestParam(defaultValue = "20") mandatoryCount: Int,

        @Parameter(description = "Number of optional segments to select")
        @RequestParam(defaultValue = "10") optionalCount: Int,

        @Parameter(description = "Number of vehicles")
        @RequestParam(defaultValue = "2") vehicles: Int,

        @Parameter(description = "Minimum range per vehicle in meters (0 = unlimited)")
        @RequestParam(defaultValue = "0") minRange: Double,

        @Parameter(description = "Maximum range per vehicle in meters (0 = unlimited). If different from minRange, a random value is chosen for each vehicle.")
        @RequestParam(defaultValue = "0") maxRange: Double,


        @Parameter(description = "Bounding box filter: minLat,minLon,maxLat,maxLon")
        @RequestParam(required = false) bbox: String?,

        @Parameter(description = "Random seed for reproducibility")
        @RequestParam(required = false) seed: Long?
    ): StreetRoutingSolution {
        val file = osmFilePath
            ?: throw IllegalStateException("OSM file not available. Please add map.osm.pbf to resources.")

        val actualSeed = seed ?: System.currentTimeMillis()
        val random = Random(actualSeed)

        // Parse bounding box if provided
        val boundingBox = bbox?.let { parseBoundingBox(it) }

        // Load segments from OSM file
        log.info("Loading OSM data from bundled file")
        val loader = OsmLoaderPython(cacheDir = osmGraphCacheFile.substringBeforeLast("/"))

        val result = loader.loadSegments(
            osmFile = file.absolutePath,
            boundingBox = boundingBox,
            mandatoryFilter = { true },  // We'll select mandatory/optional ourselves
            valueCalculator = { info ->
                // Calculate value based on length and name
                val baseValue = (info.lengthMeters / 10).toInt().coerceIn(10, 500)
                if (info.name != null) baseValue * 2 else baseValue
            }
        )

        if (result.segments.isEmpty()) {
            throw IllegalArgumentException("No segments found in OSM file. Check the bounding box.")
        }

        log.info("Loaded ${result.segments.size} segments from OSM file")

        // Select segments for the problem
        val totalNeeded = mandatoryCount + optionalCount
        val availableSegments = result.segments.shuffled(random)

        if (availableSegments.size < totalNeeded) {
            log.warn("Requested $totalNeeded segments but only ${availableSegments.size} available")
        }

        val selectedSegments = availableSegments.take(totalNeeded.coerceAtMost(availableSegments.size))
        val actualMandatoryCount = mandatoryCount.coerceAtMost(selectedSegments.size)

        // Create segments - for two-way roads, create both directions
        // Each selected segment counts as 1 towards the count (but creates 2 actual segments for two-way)
        val segments = mutableListOf<StreetSegment>()
        val geometriesMap = mutableMapOf<String, List<List<Double>>>()

        selectedSegments.forEachIndexed { index, segment ->
            val isMandatory = index < actualMandatoryCount
            val value = if (isMandatory) 0 else random.nextInt(50, 300)
            val isOneway = result.isOneway(segment.id)

            // Forward direction (A -> B)
            val forwardId = "${segment.id}_fwd"
            segments.add(StreetSegment(
                id = forwardId,
                startNode = segment.startNode,
                endNode = segment.endNode,
                lengthMeters = segment.lengthMeters,
                name = segment.name,
                isMandatory = isMandatory,
                value = value
            ))

            // Store forward geometry
            val geometry = result.geometries[segment.id]
            if (geometry != null) {
                geometriesMap[forwardId] = geometry.points.map { listOf(it.latitude, it.longitude) }
            } else {
                geometriesMap[forwardId] = listOf(
                    listOf(segment.startNode.latitude, segment.startNode.longitude),
                    listOf(segment.endNode.latitude, segment.endNode.longitude)
                )
            }

            // Reverse direction (B -> A) - only for two-way streets
            if (!isOneway) {
                val reverseId = "${segment.id}_rev"
                segments.add(StreetSegment(
                    id = reverseId,
                    startNode = segment.endNode,  // Swapped
                    endNode = segment.startNode,  // Swapped
                    lengthMeters = segment.lengthMeters,
                    name = segment.name,
                    isMandatory = isMandatory,
                    value = value
                ))

                // Store reverse geometry (reversed points)
                if (geometry != null) {
                    geometriesMap[reverseId] = geometry.points.reversed().map { listOf(it.latitude, it.longitude) }
                } else {
                    geometriesMap[reverseId] = listOf(
                        listOf(segment.endNode.latitude, segment.endNode.longitude),
                        listOf(segment.startNode.latitude, segment.startNode.longitude)
                    )
                }
            }
        }

        // Collect all unique nodes from selected segments
        val nodeSet = mutableSetOf<Node>()
        segments.forEach { segment ->
            nodeSet.add(segment.startNode)
            nodeSet.add(segment.endNode)
        }
        val nodes = nodeSet.toList()

        // Use starting location from OSM result (single depot for all vehicles)
        // Fall back to central node if not available
        val depot = result.startingLocation ?: findCentralNode(nodes)

        // Make sure depot is in the nodes list
        val nodesWithDepot = if (nodes.any { it.id == depot.id }) {
            nodes
        } else {
            nodes + depot
        }

        // Create vehicles - all start from the same depot
        val vehicleList = (1..vehicles).map { i ->
            // Generate random range within the interval, or unlimited if both are 0
            val vehicleRange = when {
                minRange <= 0 && maxRange <= 0 -> Double.MAX_VALUE
                minRange <= 0 -> random.nextDouble() * maxRange
                maxRange <= 0 -> minRange + random.nextDouble() * minRange // Use minRange as base if maxRange is 0
                minRange >= maxRange -> minRange
                else -> minRange + random.nextDouble() * (maxRange - minRange)
            }
            Vehicle(
                id = "vehicle_$i",
                startingNode = depot,
                maxDistanceMeters = vehicleRange
            )
        }

        // Build distance matrix using GraphHopper (road-based) - require it, no silent fallback
        if (graphHopper == null) {
            throw IllegalStateException(
                "GraphHopper not initialized. Road-based distance calculation is required. " +
                "Check the server logs for GraphHopper initialization errors."
            )
        }
        log.info("Using GraphHopper for road-based distance calculation with ${nodesWithDepot.size} nodes, parallelism=$distanceParallelism")
        val distanceCalculator = DistanceCalculator.withExistingGraphHopper(graphHopper!!, distanceParallelism)
        val distanceMatrixMap = distanceCalculator.calculateDistanceMatrix(nodesWithDepot)
        val distanceMatrix = DistanceMatrix(distanceMatrixMap)

        log.info("Created problem with ${segments.count { it.isMandatory }} mandatory and ${segments.count { !it.isMandatory }} optional segments (${segments.size} total directions)")
        log.info("Starting location (depot) for all vehicles: ${depot.id} at (${depot.latitude}, ${depot.longitude})")

        // Cache the bounds from loaded nodes for future status calls
        if (nodesWithDepot.isNotEmpty() && cachedBounds == null) {
            val minLat = nodesWithDepot.minOf { it.latitude }
            val maxLat = nodesWithDepot.maxOf { it.latitude }
            val minLon = nodesWithDepot.minOf { it.longitude }
            val maxLon = nodesWithDepot.maxOf { it.longitude }
            cachedBounds = MapBounds(
                minLat = minLat,
                maxLat = maxLat,
                minLon = minLon,
                maxLon = maxLon,
                centerLat = (minLat + maxLat) / 2,
                centerLon = (minLon + maxLon) / 2
            )
            log.info("Cached OSM bounds from problem generation: $cachedBounds")
        }

        return StreetRoutingSolution(
            nodes = nodesWithDepot,
            segments = segments,
            vehicles = vehicleList,
            distanceMatrix = distanceMatrix,
            geometries = geometriesMap
        )
    }

    @Operation(summary = "Generate a problem from the bundled OSM file and immediately start solving")
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "Job ID of the started solver"
            )
        ]
    )
    @PostMapping(
        value = ["/solve"],
        produces = [MediaType.TEXT_PLAIN_VALUE]
    )
    fun generateAndSolve(
        @RequestParam(defaultValue = "20") mandatoryCount: Int,
        @RequestParam(defaultValue = "10") optionalCount: Int,
        @RequestParam(defaultValue = "2") vehicles: Int,
        @RequestParam(defaultValue = "0") minRange: Double,
        @RequestParam(defaultValue = "0") maxRange: Double,
        @RequestParam(required = false) bbox: String?,
        @RequestParam(required = false) seed: Long?
    ): String {
        val problem = generateFromOsm(mandatoryCount, optionalCount, vehicles, minRange, maxRange, bbox, seed)
        return routingController.solve(problem)
    }

    @Operation(summary = "Check if OSM support is available and get map bounds")
    @GetMapping("/status")
    fun checkOsmSupport(): OsmSupportStatus {
        val pythonAvailable = try {
            OsmLoaderPython.findPython()
            OsmLoaderPython.findScript()
            true
        } catch (e: Exception) {
            log.warn("Python/OSMnx not available: ${e.message}")
            false
        }

        val fileAvailable = osmFilePath?.exists() == true

        // Use default Riga bounds - loading the entire file for bounds is too slow
        // The actual bounds will be computed when generating problems
        val bounds = if (pythonAvailable && fileAvailable) {
            // Return cached bounds if available, otherwise use Riga defaults
            cachedBounds ?: MapBounds(
                minLat = 56.88,
                maxLat = 57.05,
                minLon = 23.95,
                maxLon = 24.30,
                centerLat = 56.965,
                centerLon = 24.125
            )
        } else null

        return OsmSupportStatus(
            available = pythonAvailable && fileAvailable,
            pythonAvailable = pythonAvailable,
            fileAvailable = fileAvailable,
            message = when {
                !pythonAvailable -> "Python/OSMnx not available"
                !fileAvailable -> "OSM file (map.osm.pbf) not found in resources"
                else -> "OSM support is available"
            },
            bounds = bounds
        )
    }

    // Cache for bounding box
    private var cachedBounds: MapBounds? = null

    private fun parseBoundingBox(bbox: String): BoundingBox {
        val parts = bbox.split(",").map { it.trim().toDouble() }
        if (parts.size != 4) {
            throw IllegalArgumentException("Bounding box must have 4 values: minLat,minLon,maxLat,maxLon")
        }
        return BoundingBox(parts[0], parts[1], parts[2], parts[3])
    }

    private fun findCentralNode(nodes: List<Node>): Node {
        if (nodes.isEmpty()) throw IllegalArgumentException("No nodes available")
        if (nodes.size == 1) return nodes.first()

        // Calculate centroid
        val avgLat = nodes.map { it.latitude }.average()
        val avgLon = nodes.map { it.longitude }.average()

        // Find node closest to centroid
        return nodes.minByOrNull { node ->
            val dLat = node.latitude - avgLat
            val dLon = node.longitude - avgLon
            dLat * dLat + dLon * dLon
        }!!
    }
}

data class OsmSupportStatus(
    val available: Boolean,
    val pythonAvailable: Boolean,
    val fileAvailable: Boolean,
    val message: String,
    val bounds: MapBounds? = null
)

data class MapBounds(
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double,
    val centerLat: Double,
    val centerLon: Double
)
