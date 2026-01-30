package com.fleet.interfaces.rest.exception

import com.fleet.infrastructure.persistence.eventstore.AggregateNotFoundException
import com.fleet.infrastructure.persistence.eventstore.ConcurrencyException
import com.fleet.interfaces.rest.dto.ErrorResponse
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import org.jboss.logging.Logger
import java.time.Instant

/**
 * Global Exception Handler
 * 
 * Maps domain/infrastructure exceptions to HTTP responses.
 * Provides consistent error responses across the API.
 */

/**
 * Handle aggregate not found exceptions
 */
@Provider
class AggregateNotFoundExceptionMapper : ExceptionMapper<AggregateNotFoundException> {
    private val logger = Logger.getLogger(AggregateNotFoundExceptionMapper::class.java)
    
    override fun toResponse(exception: AggregateNotFoundException): Response {
        logger.warn("Aggregate not found: ${exception.aggregateId}")
        
        return Response
            .status(Response.Status.NOT_FOUND)
            .entity(ErrorResponse(
                error = "AGGREGATE_NOT_FOUND",
                message = exception.message ?: "Aggregate not found",
                timestamp = Instant.now().toString()
            ))
            .build()
    }
}

/**
 * Handle concurrency exceptions (optimistic locking failures)
 */
@Provider
class ConcurrencyExceptionMapper : ExceptionMapper<ConcurrencyException> {
    private val logger = Logger.getLogger(ConcurrencyExceptionMapper::class.java)
    
    override fun toResponse(exception: ConcurrencyException): Response {
        logger.warn("Concurrency conflict: ${exception.message}")
        
        return Response
            .status(Response.Status.CONFLICT)
            .entity(ErrorResponse(
                error = "CONCURRENCY_CONFLICT",
                message = "The resource has been modified by another request. Please retry.",
                timestamp = Instant.now().toString()
            ))
            .build()
    }
}

/**
 * Handle illegal argument exceptions (validation failures)
 */
@Provider
class IllegalArgumentExceptionMapper : ExceptionMapper<IllegalArgumentException> {
    private val logger = Logger.getLogger(IllegalArgumentExceptionMapper::class.java)
    
    override fun toResponse(exception: IllegalArgumentException): Response {
        logger.warn("Validation error: ${exception.message}")
        
        return Response
            .status(Response.Status.BAD_REQUEST)
            .entity(ErrorResponse(
                error = "VALIDATION_ERROR",
                message = exception.message ?: "Invalid request",
                timestamp = Instant.now().toString()
            ))
            .build()
    }
}

/**
 * Handle illegal state exceptions (business rule violations)
 */
@Provider
class IllegalStateExceptionMapper : ExceptionMapper<IllegalStateException> {
    private val logger = Logger.getLogger(IllegalStateExceptionMapper::class.java)
    
    override fun toResponse(exception: IllegalStateException): Response {
        logger.warn("Business rule violation: ${exception.message}")
        
        return Response
            .status(Response.Status.CONFLICT)
            .entity(ErrorResponse(
                error = "BUSINESS_RULE_VIOLATION",
                message = exception.message ?: "Operation not allowed",
                timestamp = Instant.now().toString()
            ))
            .build()
    }
}

/**
 * Handle all other uncaught exceptions
 */
@Provider
class GenericExceptionMapper : ExceptionMapper<Exception> {
    private val logger = Logger.getLogger(GenericExceptionMapper::class.java)
    
    override fun toResponse(exception: Exception): Response {
        logger.error("Unhandled exception", exception)
        
        return Response
            .status(Response.Status.INTERNAL_SERVER_ERROR)
            .entity(ErrorResponse(
                error = "INTERNAL_ERROR",
                message = "An unexpected error occurred. Please contact support.",
                timestamp = Instant.now().toString()
            ))
            .build()
    }
}
