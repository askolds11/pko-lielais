package benchmark

import ai.timefold.solver.persistence.common.api.domain.solution.SolutionFileIO
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import domain.StreetRoutingSolution
import java.io.File

/**
 * File IO for StreetRoutingSolution for Timefold benchmarks.
 */
class StreetRoutingSolutionFileIO : SolutionFileIO<StreetRoutingSolution> {

    private val mapper = jacksonObjectMapper()

    override fun getInputFileExtension(): String = "json"

    override fun read(inputSolutionFile: File): StreetRoutingSolution {
        return mapper.readValue(inputSolutionFile)
    }

    override fun write(solution: StreetRoutingSolution, outputSolutionFile: File) {
        mapper.writerWithDefaultPrettyPrinter().writeValue(outputSolutionFile, solution)
    }
}
