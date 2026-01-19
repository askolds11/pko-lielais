package rest

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.context.request.WebRequest

/**
 * Global exception handler for REST API errors.
 */
@ControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    @ExceptionHandler(StreetRoutingException::class)
    fun handleStreetRoutingException(
        ex: StreetRoutingException,
        request: WebRequest
    ): ResponseEntity<ErrorInfo> {
        log.error("Street routing error for job {}: {}", ex.jobId, ex.message)
        val errorInfo = ErrorInfo(
            jobId = ex.jobId,
            message = ex.message ?: "Unknown error",
            details = ex.cause?.message
        )
        return ResponseEntity.status(ex.httpStatus).body(errorInfo)
    }

    @ExceptionHandler(Exception::class)
    fun handleGenericException(
        ex: Exception,
        request: WebRequest
    ): ResponseEntity<ErrorInfo> {
        log.error("Unexpected error: {}", ex.message, ex)
        val errorInfo = ErrorInfo(
            jobId = null,
            message = ex.message ?: "Internal server error",
            details = ex.javaClass.simpleName
        )
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorInfo)
    }
}
