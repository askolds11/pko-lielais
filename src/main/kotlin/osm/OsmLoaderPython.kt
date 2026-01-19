package osm

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import domain.Node
import domain.StreetSegment
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Loads street network data from OpenStreetMap files using OSMnx/pyrosm (Python).
 *
 * Supports both .osm and .osm.pbf files. For .osm.pbf files, pyrosm is required.
 *
 * OSMnx properly simplifies the graph to only include intersections and dead-ends
 * as nodes, with edges representing full street segments between them.
 *
 * This class calls the Python script `scripts/osm_graph_loader.py` via subprocess.
 * Results are cached to avoid re-running the expensive Python processing on each startup.
 */
class OsmLoaderPython(
    private val pythonPath: String = findPython(),
    private val scriptPath: String = findScript(),
    private val cacheDir: String = "target/osm-cache"
) {
    private val mapper = jacksonObjectMapper()
    private val log = LoggerFactory.getLogger(OsmLoaderPython::class.java)

    companion object {
        /**
         * Find Python executable
         */
        fun findPython(): String {
            val candidates = listOf("python3", "python", "/usr/bin/python3", "/usr/bin/python")
            for (candidate in candidates) {
                try {
                    val process = ProcessBuilder(candidate, "--version")
                        .redirectErrorStream(true)
                        .start()
                    if (process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0) {
                        return candidate
                    }
                } catch (e: Exception) {
                    // Try next candidate
                }
            }
            throw RuntimeException("Python not found. Please install Python 3 and ensure it's in PATH.")
        }

        /**
         * Find the OSM loader Python script
         */
        fun findScript(): String {
            val candidates = listOf(
                "scripts/osm_graph_loader.py",
                "../scripts/osm_graph_loader.py",
                "src/main/resources/scripts/osm_graph_loader.py"
            )
            for (candidate in candidates) {
                val file = File(candidate)
                if (file.exists()) {
                    return file.absolutePath
                }
            }
            // Try to find relative to class location
            val classPath = OsmLoaderPython::class.java.protectionDomain.codeSource?.location?.path
            if (classPath != null) {
                val projectDir = File(classPath).parentFile?.parentFile?.parentFile
                val scriptFile = File(projectDir, "scripts/osm_graph_loader.py")
                if (scriptFile.exists()) {
                    return scriptFile.absolutePath
                }
            }
            throw RuntimeException(
                "Python script not found. Expected at: scripts/osm_graph_loader.py\n" +
                "Make sure you're running from the project root directory."
            )
        }
    }

    /**
     * Generate a cache key based on OSM file and bounding box
     */
    private fun generateCacheKey(osmFile: String, boundingBox: BoundingBox?): String {
        val fileHash = File(osmFile).name.replace(".", "_")
        val bboxHash = boundingBox?.let {
            "_${it.minLat}_${it.minLon}_${it.maxLat}_${it.maxLon}".replace(".", "p").replace("-", "m")
        } ?: ""
        return "graph_cache_$fileHash$bboxHash.json"
    }

    /**
     * Load street segments from an OSM file, using cache if available.
     *
     * @param osmFile Path to OSM file (.osm format, NOT .pbf - convert first)
     * @param boundingBox Optional bounding box filter
     * @param mandatoryFilter Function to determine if a segment is mandatory
     * @param valueCalculator Function to calculate value for optional segments
     * @param useCache Whether to use cached graph data if available (default: true)
     */
    fun loadSegments(
        osmFile: String,
        boundingBox: BoundingBox? = null,
        mandatoryFilter: (SegmentInfo) -> Boolean = { true },
        valueCalculator: (SegmentInfo) -> Int = { 100 },
        useCache: Boolean = true
    ): OsmLoadResult {
        // Check cache first
        if (useCache) {
            val cachedResult = loadFromCache(osmFile, boundingBox)
            if (cachedResult != null) {
                log.info("Loaded graph from cache")
                // Apply filters to cached data
                return applyFilters(cachedResult, mandatoryFilter, valueCalculator)
            }
        }

        // Generate graph using Python
        val rawData = runPythonScript(osmFile, boundingBox)

        // Save to cache for future use
        saveToCache(osmFile, boundingBox, rawData)

        return convertToResult(rawData, mandatoryFilter, valueCalculator)
    }

    /**
     * Load from cache if available and valid
     */
    private fun loadFromCache(osmFile: String, boundingBox: BoundingBox?): RawGraphData? {
        val cacheFile = File(cacheDir, generateCacheKey(osmFile, boundingBox))
        if (!cacheFile.exists()) {
            log.info("Cache miss: ${cacheFile.absolutePath}")
            return null
        }

        // Check if OSM file is newer than cache
        val osmLastModified = File(osmFile).lastModified()
        val cacheLastModified = cacheFile.lastModified()
        if (osmLastModified > cacheLastModified) {
            log.info("Cache stale: OSM file newer than cache")
            return null
        }

        return try {
            log.info("Loading from cache: ${cacheFile.absolutePath}")
            mapper.readValue(cacheFile)
        } catch (e: Exception) {
            log.warn("Failed to load cache: ${e.message}")
            null
        }
    }

    /**
     * Save graph data to cache
     */
    private fun saveToCache(osmFile: String, boundingBox: BoundingBox?, rawData: RawGraphData) {
        try {
            val cacheDirectory = File(cacheDir)
            if (!cacheDirectory.exists()) {
                cacheDirectory.mkdirs()
            }
            val cacheFile = File(cacheDir, generateCacheKey(osmFile, boundingBox))
            mapper.writerWithDefaultPrettyPrinter().writeValue(cacheFile, rawData)
            log.info("Saved graph to cache: ${cacheFile.absolutePath}")
        } catch (e: Exception) {
            log.warn("Failed to save cache: ${e.message}")
        }
    }

    /**
     * Run the Python script to generate graph data
     */
    private fun runPythonScript(osmFile: String, boundingBox: BoundingBox?): RawGraphData {
        // Create temp file for output
        val tempOutput = Files.createTempFile("osm_graph_", ".json")

        try {
            // Build command
            val command = mutableListOf(pythonPath, scriptPath, osmFile, tempOutput.toString())
            if (boundingBox != null) {
                command.add("--bbox=${boundingBox.minLat},${boundingBox.minLon},${boundingBox.maxLat},${boundingBox.maxLon}")
            }

            log.info("Running: ${command.joinToString(" ")}")

            // Execute Python script
            val process = ProcessBuilder(command)
                .redirectErrorStream(false)
                .start()

            // Read stderr for progress messages
            val stderrThread = Thread {
                process.errorStream.bufferedReader().forEachLine { line ->
                    log.info("[Python] $line")
                }
            }
            stderrThread.start()

            // Wait for completion
            val exitCode = process.waitFor()
            stderrThread.join(1000)

            if (exitCode != 0) {
                val stdout = process.inputStream.bufferedReader().readText()
                throw RuntimeException("Python script failed with exit code $exitCode\nOutput: $stdout")
            }

            // Parse output JSON
            return mapper.readValue(tempOutput.toFile())

        } finally {
            Files.deleteIfExists(tempOutput)
        }
    }

    /**
     * Apply mandatory/value filters to cached raw data
     */
    private fun applyFilters(
        rawData: RawGraphData,
        mandatoryFilter: (SegmentInfo) -> Boolean,
        valueCalculator: (SegmentInfo) -> Int
    ): OsmLoadResult {
        return convertToResult(rawData, mandatoryFilter, valueCalculator)
    }

    /**
     * Convert raw Python output to OsmLoadResult with domain objects.
     */
    private fun convertToResult(
        rawData: RawGraphData,
        mandatoryFilter: (SegmentInfo) -> Boolean,
        valueCalculator: (SegmentInfo) -> Int
    ): OsmLoadResult {
        // Create node map from simplified graph nodes
        val nodeMap = rawData.nodes.associate { raw ->
            raw.id to Node(
                id = raw.id,
                latitude = raw.lat,
                longitude = raw.lon
            )
        }

        // Create segments and geometries
        val segments = mutableListOf<StreetSegment>()
        val geometries = mutableMapOf<String, SegmentGeometry>()
        val onewaySegments = mutableMapOf<String, Boolean>()

        for (raw in rawData.segments) {
            val startNode = nodeMap[raw.startNodeId]
                ?: throw IllegalStateException("Unknown start node: ${raw.startNodeId}")
            val endNode = nodeMap[raw.endNodeId]
                ?: throw IllegalStateException("Unknown end node: ${raw.endNodeId}")

            val segmentInfo = SegmentInfo(
                osmWayIds = raw.osmWayIds,
                name = raw.name,
                lengthMeters = raw.lengthMeters,
                startNode = startNode,
                endNode = endNode,
                oneway = raw.oneway
            )

            val isMandatory = mandatoryFilter(segmentInfo)
            val value = if (isMandatory) 0 else valueCalculator(segmentInfo)

            val segment = StreetSegment(
                id = raw.id,
                startNode = startNode,
                endNode = endNode,
                lengthMeters = raw.lengthMeters,
                name = raw.name,
                isMandatory = isMandatory,
                value = value
            )
            segments.add(segment)
            onewaySegments[raw.id] = raw.oneway

            // Create geometry
            val points = raw.geometry.map { coords ->
                Coordinate(latitude = coords[0], longitude = coords[1])
            }
            geometries[raw.id] = SegmentGeometry(
                segmentId = raw.id,
                points = points
            )
        }

        // Convert starting location
        val startingLocation = rawData.startingLocation?.let { raw ->
            nodeMap[raw.id] ?: Node(
                id = raw.id,
                latitude = raw.lat,
                longitude = raw.lon
            )
        }

        // Convert full graph nodes
        val fullGraphNodes = rawData.fullGraphNodes.map { raw ->
            Node(
                id = raw.id,
                latitude = raw.lat,
                longitude = raw.lon
            )
        }

        // Convert full graph edges
        val fullGraphEdges = rawData.fullGraphEdges.map { raw ->
            GraphEdge(
                fromNodeId = raw.u,
                toNodeId = raw.v,
                lengthMeters = raw.length
            )
        }

        return OsmLoadResult(
            nodes = nodeMap.values.toList(),
            segments = segments,
            geometries = geometries,
            startingLocation = startingLocation,
            fullGraphNodes = fullGraphNodes,
            fullGraphEdges = fullGraphEdges,
            onewaySegments = onewaySegments
        )
    }
}

// Raw data classes for JSON parsing (internal for cache serialization)
@JsonIgnoreProperties(ignoreUnknown = true)
internal data class RawGraphData(
    val nodes: List<RawNode>,
    val segments: List<RawSegment>,
    val startingLocation: RawNode? = null,
    val fullGraphNodes: List<RawNode> = emptyList(),
    val fullGraphEdges: List<RawEdge> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class RawNode(
    val id: String = "",
    val lat: Double = 0.0,
    val lon: Double = 0.0
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class RawSegment(
    val id: String = "",
    val startNodeId: String = "",
    val endNodeId: String = "",
    val lengthMeters: Double = 0.0,
    val name: String? = null,
    val osmWayIds: List<Long> = emptyList(),
    val geometry: List<List<Double>> = emptyList(),
    val oneway: Boolean = false
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class RawEdge(
    val u: String = "",
    val v: String = "",
    val length: Double = 0.0
)

