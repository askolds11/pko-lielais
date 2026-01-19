package benchmark

import ai.timefold.solver.benchmark.api.PlannerBenchmarkFactory
import java.io.File

/**
 * Benchmark runner for street routing optimization using Timefold's built-in benchmark framework.
 *
 * Generates HTML reports with detailed statistics and visualizations.
 */
object BenchmarkRunner {

    private const val defaultBenchmarkConfig = "src/main/resources/benchmarkConfig.xml"

    /**
     * Run the default benchmark suite using the XML configuration.
     * Opens HTML report in browser when complete.
     */
    fun runDefaultBenchmark() {
        val configFile = File(defaultBenchmarkConfig)
        if (!configFile.exists()) {
            throw IllegalStateException("Benchmark config not found: ${configFile.absolutePath}")
        }

        println("Running benchmark from: ${configFile.absolutePath}")
        runBenchmarkFromXmlAndShowReport(configFile)
    }

    /**
     * Run the Timefold benchmark suite and open the report in browser.
     *
     * @param benchmarkConfigFile Path to benchmark config XML
     */
    fun runBenchmarkFromXmlAndShowReport(benchmarkConfigFile: File) {
        val benchmarkFactory = PlannerBenchmarkFactory.createFromXmlFile(benchmarkConfigFile)
        val benchmark = benchmarkFactory.buildPlannerBenchmark()
        benchmark.benchmarkAndShowReportInBrowser()
    }
}
