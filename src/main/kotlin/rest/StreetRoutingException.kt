package rest

import org.springframework.http.HttpStatus

/**
 * Exception thrown when there's an error with street routing solving.
 */
class StreetRoutingException : RuntimeException {
    val jobId: String
    val httpStatus: HttpStatus

    constructor(jobId: String, message: String) : super(message) {
        this.jobId = jobId
        this.httpStatus = HttpStatus.NOT_FOUND
    }

    constructor(jobId: String, cause: Throwable) : super("Error solving problem: ${cause.message}", cause) {
        this.jobId = jobId
        this.httpStatus = HttpStatus.INTERNAL_SERVER_ERROR
    }
}

/**
 * Error information returned in API responses.
 */
data class ErrorInfo(
    val jobId: String?,
    val message: String,
    val details: String? = null
)
