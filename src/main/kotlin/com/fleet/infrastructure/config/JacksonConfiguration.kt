package com.fleet.infrastructure.config

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.kotlinModule
import io.quarkus.jackson.ObjectMapperCustomizer
import jakarta.enterprise.context.ApplicationScoped

/**
 * Jackson ObjectMapper Configuration
 * 
 * Configures JSON serialization/deserialization for:
 * - REST API requests/responses
 * - Event store JSON storage
 * - MQTT message payloads
 * 
 * Important configurations:
 * - Kotlin module for data classes
 * - Java 8 time support (Instant, LocalDateTime, etc.)
 * - Pretty printing in dev mode
 * - Fail on unknown properties disabled (API evolution)
 */
@ApplicationScoped
class JacksonConfiguration : ObjectMapperCustomizer {
    
    override fun customize(objectMapper: ObjectMapper) {
        // Kotlin support
        objectMapper.registerModule(kotlinModule())
        
        // Java 8 date/time support
        objectMapper.registerModule(JavaTimeModule())
        
        // Write dates as ISO-8601 strings, not timestamps
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        
        // Don't fail on unknown properties (allows API evolution)
        objectMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        
        // Include non-null values only
        objectMapper.setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        
        // Pretty print in development
        objectMapper.enable(SerializationFeature.INDENT_OUTPUT)
        
        // Don't fail on empty beans
        objectMapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
    }
}
