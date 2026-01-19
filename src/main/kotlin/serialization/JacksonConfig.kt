package serialization

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.kotlinModule

object JacksonConfig {

    fun createObjectMapper(): ObjectMapper {
        return ObjectMapper().apply {
            // Register all available modules (includes Timefold, Kotlin, JavaTime, etc.)
            findAndRegisterModules()

            // Explicitly register Kotlin module with desired configuration
            registerModule(kotlinModule())

            // Configuration
            configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            configure(SerializationFeature.INDENT_OUTPUT, true)
            configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        }
    }
}