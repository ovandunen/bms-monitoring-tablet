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
            
            // Validate
            dto.validate()
            
            // Convert to command
            val command = dto.toCommand()
            
            // Execute use case (suspending function)
            runBlocking {
                recordTelemetryUseCase.execute(command)
            }
            
            logger.trace("Successfully processed telemetry for: ${dto.batteryPackId}")
            
        } catch (e: Exception) {
            logger.error("Failed to process telemetry message", e)
            // In production, send to dead letter queue
            // For now, just log and continue
        }
    }
}

/**
 * Telemetry Message DTO
 * 
 * Matches JSON structure from Android tablets.
 * Example:
 * {
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
            correlationId = UUID.randomUUID()
        )
    }
}
