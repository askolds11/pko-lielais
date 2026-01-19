package benchmark

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIf
import java.io.File

/**
 * Benchmark test for the street routing solver.
 *
 * Runs benchmarks on problems stored in benchmark-data/ folder:
 * - problem_small.json
 * - problem_medium.json
 * - problem_large.json
 *
 * To add/update benchmark problems:
 * 1. Generate and solve a problem in the web UI (http://localhost:8080)
 * 2. Click "Download Problem JSON" button
 * 3. Save the file to benchmark-data/ folder with appropriate name
 *
 * Run benchmark:
 *   ./gradlew test --tests "benchmark.BenchmarkTest"
 */
class BenchmarkTest {

    companion object {
        private val benchmarkDataDir = File("benchmark-data")
        private val requiredFiles = listOf(
            "problem_small.json",
//            "problem_medium.json",
//            "problem_large.json"
        )

        @JvmStatic
        fun hasBenchmarkProblems(): Boolean {
            return requiredFiles.all { File(benchmarkDataDir, it).exists() }
        }
    }

    @Test
    @EnabledIf("hasBenchmarkProblems")
    fun runBenchmark() {
        // Check which files exist
        println("\n" + "=".repeat(60))
        println("BENCHMARK DATA CHECK")
        println("=".repeat(60))

        for (filename in requiredFiles) {
            val file = File(benchmarkDataDir, filename)
            val status = if (file.exists()) "✓ Found (${file.length()} bytes)" else "✗ Missing"
            println("  $filename: $status")
        }
        println()

        // Run the benchmark
        println("Starting Timefold benchmark...")
        println("This will run all problems and generate an HTML report.")
        println()

        BenchmarkRunner.runDefaultBenchmark()

        // Verify report was generated
        val reportDir = File("target/benchmark-report")
        assertTrue(reportDir.exists(), "Benchmark report directory should exist")

        println("\nBenchmark complete!")
        println("Report location: ${reportDir.absolutePath}")
    }

    @Test
    fun verifyBenchmarkData() {
        println("\n" + "=".repeat(60))
        println("BENCHMARK DATA INFO")
        println("=".repeat(60))

        if (!benchmarkDataDir.exists()) {
            println("benchmark-data/ folder does not exist!")
            println("Create it and add problem JSON files.")
            return
        }

        val jsonFiles = benchmarkDataDir.listFiles()?.filter { it.extension == "json" } ?: emptyList()

        if (jsonFiles.isEmpty()) {
            println("No JSON files found in benchmark-data/")
            println("\nTo add benchmark problems:")
            println("  1. Run the web application: ./gradlew bootRun")
            println("  2. Open http://localhost:8080")
            println("  3. Generate a problem with 'Generate & Solve'")
            println("  4. Click 'Download Problem JSON'")
            println("  5. Save as benchmark-data/problem_small.json (or medium/large)")
            return
        }

        println("Found ${jsonFiles.size} problem file(s):\n")

        for (file in jsonFiles.sortedBy { it.name }) {
            try {
                val problem = ProblemGenerator.loadProblem(file)
                val mandatoryCount = problem.segments.count { it.isMandatory }
                val optionalCount = problem.segments.count { !it.isMandatory }
                println("  ${file.name}:")
                println("    - Segments: ${problem.segments.size} total ($mandatoryCount mandatory, $optionalCount optional)")
                println("    - Vehicles: ${problem.vehicles.size}")
                println("    - Nodes: ${problem.nodes.size}")
                println()
            } catch (e: Exception) {
                println("  ${file.name}: ERROR - ${e.message}")
            }
        }

        // Check for required files
        println("Required files status:")
        for (filename in requiredFiles) {
            val exists = File(benchmarkDataDir, filename).exists()
            println("  $filename: ${if (exists) "✓" else "✗ MISSING"}")
        }
    }
}
