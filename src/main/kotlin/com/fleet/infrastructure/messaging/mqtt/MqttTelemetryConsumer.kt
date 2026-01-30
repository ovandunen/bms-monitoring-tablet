package com.fleet.infrastructure.messaging.mqtt

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fleet.application.command.RecordTelemetryCommand
import com.fleet.application.usecase.battery.RecordTelemetryUseCase
import com.fleet.domain.battery.model.BatteryPackId
import io.smallrye.common.annotation.Blocking
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.runBlocking
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.jboss.logging.Logger
import java.util.UUID

/**
 * MQTT Telemetry Consumer
 * 
 * Consumes telemetry messages from Android tablets via MQTT.
 * This is the entry point for IoT data into the DDD backend.
 * 
 * Message flow:
 * 1. Tablet publishes to: fleet/{vehicleId}/bms/telemetry
 * 2. EMQX broker receives and routes
 * 3. This consumer receives message
 * 4. Parse and validate JSON
 * 5. Execute use case (domain logic)
 * 6. Events stored in event store
 * 
 * Frequency: 1-10 Hz per vehicle (130 vehicles = 130-1300 msg/sec)
 */
@ApplicationScoped
class MqttTelemetryConsumer(
    private val recordTelemetryUseCase: RecordTelemetryUseCase,
    private val objectMapper: ObjectMapper
) {
    private val logger = Logger.getLogger(MqttTelemetryConsumer::class.java)
    
    /**
     * Consume telemetry message from MQTT
     * 
     * @Incoming("telemetry") - Channel name from application.properties
     * @Blocking - Process on worker thread (not event loop)
     */
    @Incoming("telemetry")
    @Blocking
    fun consume(payload: ByteArray) {
        try {
            // Parse JSON
            val dto = objectMapper.readValue<TelemetryMessageDto>(payload)
            
            logger.debug("Received telemetry for battery: ${dto.batteryPackId}")
            
            // Idempotency check - prevent duplicate processing
            if (dto.messageId != null && isAlreadyProcessed(dto.messageId)) {
                logger.debug("Skipping duplicate message: ${dto.messageId}")
                return
            }
            
            // Validate
            dto.validate()
            
            // Convert to command
            val command = dto.toCommand()
            
            // Execute use case (suspending function)
            runBlocking {
                recordTelemetryUseCase.execute(command)
            }
            
            // Mark message as processed for idempotency
            if (dto.messageId != null) {
                markAsProcessed(dto.messageId)
            }
            
            logger.trace("Successfully processed telemetry for: ${dto.batteryPackId}")
            
        } catch (e: Exception) {
            logger.error("Failed to process telemetry message", e)
            
            // Send to dead letter queue for later analysis and retry
            try {
                sendToDeadLetterQueue(payload, e)
            } catch (dlqError: Exception) {
                logger.error("CRITICAL: Failed to send message to DLQ - data loss risk!", dlqError)
                // Last resort: log the payload for manual recovery
                logger.error("Failed message payload (base64): ${java.util.Base64.getEncoder().encodeToString(payload)}")
            }
        }
    }
    
    /**
     * Send failed message to dead letter queue
     * In production, this would publish to a separate MQTT topic or message queue
     */
    private fun sendToDeadLetterQueue(payload: ByteArray, error: Exception) {
        try {
            // Create dead letter message with error context
            val dlqMessage = mapOf(
                "originalPayload" to String(payload, Charsets.UTF_8),
                "error" to error.message,
                "stackTrace" to error.stackTraceToString(),
                "timestamp" to java.time.Instant.now().toString(),
                "topic" to "telemetry",
                "attemptCount" to 1
            )
            
            // In production: publish to DLQ topic or save to database
            // For now: log structured error for monitoring systems to pick up
            logger.error("DLQ_MESSAGE: ${objectMapper.writeValueAsString(dlqMessage)}")
            
            // TODO: Implement actual DLQ mechanism
            // Options:
            // 1. Publish to MQTT DLQ topic: fleet/dlq/telemetry
            // 2. Save to database table: dead_letter_queue
            // 3. Send to external queue (RabbitMQ, Kafka, SQS)
            
        } catch (e: Exception) {
            // If DLQ itself fails, log the raw payload
            logger.error("Failed to create DLQ message, raw payload: ${String(payload, Charsets.UTF_8)}", e)
            throw e
        }
    }
    
    /**
     * Check if message has already been processed (idempotency)
     * 
     * In production, this would check Redis or database:
     * - Redis: EXISTS processed:messages:{messageId}
     * - Database: SELECT EXISTS(SELECT 1 FROM processed_messages WHERE message_id = ?)
     */
    private fun isAlreadyProcessed(messageId: String): Boolean {
        // TODO: Implement actual deduplication storage
        // Example Redis implementation:
        // return redis.exists(listOf("processed:messages:$messageId"))
        //     .await().indefinitely()
        //     .toInteger() > 0
        
        // For now, return false (no deduplication)
        // This means duplicate messages will be processed
        logger.warn("Idempotency check not implemented - duplicate processing possible")
        return false
    }
    
    /**
     * Mark message as processed (idempotency)
     * 
     * In production, this would store in Redis or database with TTL:
     * - Redis: SET processed:messages:{messageId} 1 EX 86400 (24 hour TTL)
     * - Database: INSERT INTO processed_messages (message_id, processed_at) VALUES (?, NOW())
     */
    private fun markAsProcessed(messageId: String) {
        // TODO: Implement actual deduplication storage
        // Example Redis implementation:
        // redis.set(listOf(
        //     "processed:messages:$messageId",
        //     "1",
        //     "EX",
        //     "86400"  // 24 hour TTL
        // )).await().indefinitely()
        
        // For now, just log
        logger.debug("Message processed (deduplication not active): $messageId")
    }
}

/**
 * Telemetry Message DTO
 * 
 * Matches JSON structure from Android tablets.
 * Example:
 * {
 *   "messageId": "msg-123e4567-e89b-12d3-a456-426614174000",
 *   "batteryPackId": "550e8400-e29b-41d4-a716-446655440000",
 *   "vehicleId": "vehicle_001",
 *   "timestamp": "2026-01-28T20:15:32.123Z",
 *   "stateOfCharge": 75.5,
 *   "voltage": 377.2,
 *   "current": -45.3,
 *   "temperatureMin": 28.5,
 *   "temperatureMax": 32.1,
 *   "temperatureAvg": 30.2,
 *   "cellVoltages": [3.29, 3.30, ...]
 * }
 */
data class TelemetryMessageDto(
    val messageId: String?,  // Unique message ID for idempotency (optional for backward compatibility)
    val batteryPackId: String,
    val vehicleId: String,
    val timestamp: String,
    val stateOfCharge: Double,
    val voltage: Double,
    val current: Double,
    val temperatureMin: Double,
    val temperatureMax: Double,
    val temperatureAvg: Double,
    val cellVoltageMin: Double,
    val cellVoltageMax: Double,
    val cellVoltageDelta: Double,
    val cellVoltages: List<Double>
) {
    fun validate() {
        require(cellVoltages.size == 114) { 
            "Expected 114 cell voltages, got: ${cellVoltages.size}" 
        }
        require(stateOfCharge in 0.0..100.0) { 
            "SOC out of range: $stateOfCharge" 
        }
        require(voltage in 285.0..420.0) { 
            "Voltage out of range: $voltage" 
        }
    }
    
    fun toCommand(): RecordTelemetryCommand {
        return RecordTelemetryCommand(
            batteryPackId = BatteryPackId.from(batteryPackId),
            stateOfCharge = stateOfCharge,
            voltage = voltage,
            current = current,
            temperatureMin = temperatureMin,
            temperatureMax = temperatureMax,
            temperatureAvg = temperatureAvg,
            cellVoltages = cellVoltages,
            // Use messageId as correlationId if available, otherwise generate new UUID
            correlationId = messageId?.let { UUID.fromString(it) } ?: UUID.randomUUID()
        )
    }
}
