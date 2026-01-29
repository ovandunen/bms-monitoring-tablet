package com.fleet.domain.battery.event

import com.fleet.domain.battery.model.*
import com.fleet.domain.shared.BaseDomainEvent
import java.time.Instant
import java.util.UUID

/**
 * Battery Pack Created Event
 * Raised when a new battery pack is introduced to the system
 */
data class BatteryPackCreatedEvent(
    val batteryPackId: BatteryPackId,
    val specifications: BatterySpecifications,
    val initialStateOfCharge: StateOfCharge,
    eventId: UUID = UUID.randomUUID(),
    occurredAt: Instant = Instant.now(),
    correlationId: UUID? = null
) : BaseDomainEvent(
    eventId = eventId,
    aggregateId = batteryPackId.toString(),
    aggregateType = "BatteryPack",
    eventType = "BatteryPackCreated",
    eventVersion = 1,
    occurredAt = occurredAt,
    correlationId = correlationId
) {
    override fun getEventData() = mapOf(
        "batteryPackId" to batteryPackId.value.toString(),
        "manufacturer" to specifications.manufacturer,
        "model" to specifications.model,
        "chemistry" to specifications.chemistry.name,
        "capacity" to specifications.capacity,
        "cellConfiguration" to specifications.cellConfiguration,
        "initialStateOfCharge" to initialStateOfCharge.percentage
    )
}

/**
 * Telemetry Recorded Event
 * Raised every time new telemetry data is received from the BMS
 * This is the most frequent event in the system
 */
data class TelemetryRecordedEvent(
    val batteryPackId: BatteryPackId,
    val reading: TelemetryReading,
    eventId: UUID = UUID.randomUUID(),
    occurredAt: Instant = Instant.now(),
    correlationId: UUID? = null
) : BaseDomainEvent(
    eventId = eventId,
    aggregateId = batteryPackId.toString(),
    aggregateType = "BatteryPack",
    eventType = "TelemetryRecorded",
    eventVersion = 1,
    occurredAt = occurredAt,
    correlationId = correlationId
) {
    override fun getEventData() = mapOf(
        "batteryPackId" to batteryPackId.value.toString(),
        "stateOfCharge" to reading.stateOfCharge.percentage,
        "voltage" to reading.voltage.volts,
        "current" to reading.current.amperes,
        "temperatureMin" to reading.temperatureMin.celsius,
        "temperatureMax" to reading.temperatureMax.celsius,
        "temperatureAvg" to reading.temperatureAvg.celsius,
        "cellVoltageMin" to reading.cellVoltages.min,
        "cellVoltageMax" to reading.cellVoltages.max,
        "cellVoltageDelta" to reading.cellVoltageDelta
    )
}

/**
 * Battery Depleted Event
 * Raised when battery reaches critically low state of charge
 * This is a significant business event that triggers alerts
 */
data class BatteryDepletedEvent(
    val batteryPackId: BatteryPackId,
    val stateOfCharge: StateOfCharge,
    val voltage: Voltage,
    eventId: UUID = UUID.randomUUID(),
    occurredAt: Instant = Instant.now(),
    correlationId: UUID? = null
) : BaseDomainEvent(
    eventId = eventId,
    aggregateId = batteryPackId.toString(),
    aggregateType = "BatteryPack",
    eventType = "BatteryDepleted",
    eventVersion = 1,
    occurredAt = occurredAt,
    correlationId = correlationId
) {
    override fun getEventData() = mapOf(
        "batteryPackId" to batteryPackId.value.toString(),
        "stateOfCharge" to stateOfCharge.percentage,
        "voltage" to voltage.volts
    )
}

/**
 * Critical Temperature Event
 * Raised when battery temperature exceeds safe operating limits
 */
data class CriticalTemperatureEvent(
    val batteryPackId: BatteryPackId,
    val temperature: Temperature,
    val temperatureType: TemperatureType,
    eventId: UUID = UUID.randomUUID(),
    occurredAt: Instant = Instant.now(),
    correlationId: UUID? = null
) : BaseDomainEvent(
    eventId = eventId,
    aggregateId = batteryPackId.toString(),
    aggregateType = "BatteryPack",
    eventType = "CriticalTemperature",
    eventVersion = 1,
    occurredAt = occurredAt,
    correlationId = correlationId
) {
    override fun getEventData() = mapOf(
        "batteryPackId" to batteryPackId.value.toString(),
        "temperature" to temperature.celsius,
        "temperatureType" to temperatureType.name
    )
}

enum class TemperatureType {
    TOO_HIGH,
    TOO_LOW
}

/**
 * Cell Imbalance Detected Event
 * Raised when cell voltage delta exceeds acceptable threshold
 */
data class CellImbalanceDetectedEvent(
    val batteryPackId: BatteryPackId,
    val cellVoltageDelta: Double,
    val cellVoltageMin: Double,
    val cellVoltageMax: Double,
    eventId: UUID = UUID.randomUUID(),
    occurredAt: Instant = Instant.now(),
    correlationId: UUID? = null
) : BaseDomainEvent(
    eventId = eventId,
    aggregateId = batteryPackId.toString(),
    aggregateType = "BatteryPack",
    eventType = "CellImbalanceDetected",
    eventVersion = 1,
    occurredAt = occurredAt,
    correlationId = correlationId
) {
    override fun getEventData() = mapOf(
        "batteryPackId" to batteryPackId.value.toString(),
        "cellVoltageDelta" to cellVoltageDelta,
        "cellVoltageMin" to cellVoltageMin,
        "cellVoltageMax" to cellVoltageMax
    )
}

/**
 * Charging Started Event
 * Raised when battery begins charging
 */
data class ChargingStartedEvent(
    val batteryPackId: BatteryPackId,
    val stateOfCharge: StateOfCharge,
    val chargingSessionId: UUID,
    eventId: UUID = UUID.randomUUID(),
    occurredAt: Instant = Instant.now(),
    correlationId: UUID? = null
) : BaseDomainEvent(
    eventId = eventId,
    aggregateId = batteryPackId.toString(),
    aggregateType = "BatteryPack",
    eventType = "ChargingStarted",
    eventVersion = 1,
    occurredAt = occurredAt,
    correlationId = correlationId
) {
    override fun getEventData() = mapOf(
        "batteryPackId" to batteryPackId.value.toString(),
        "stateOfCharge" to stateOfCharge.percentage,
        "chargingSessionId" to chargingSessionId.toString()
    )
}

/**
 * Charging Completed Event
 * Raised when battery reaches full charge
 */
data class ChargingCompletedEvent(
    val batteryPackId: BatteryPackId,
    val stateOfCharge: StateOfCharge,
    val chargingSessionId: UUID,
    val durationMinutes: Long,
    eventId: UUID = UUID.randomUUID(),
    occurredAt: Instant = Instant.now(),
    correlationId: UUID? = null
) : BaseDomainEvent(
    eventId = eventId,
    aggregateId = batteryPackId.toString(),
    aggregateType = "BatteryPack",
    eventType = "ChargingCompleted",
    eventVersion = 1,
    occurredAt = occurredAt,
    correlationId = correlationId
) {
    override fun getEventData() = mapOf(
        "batteryPackId" to batteryPackId.value.toString(),
        "stateOfCharge" to stateOfCharge.percentage,
        "chargingSessionId" to chargingSessionId.toString(),
        "durationMinutes" to durationMinutes
    )
}

/**
 * Battery Replacement Initiated Event
 * Raised when a battery replacement process begins
 * This event starts the Battery Replacement Saga
 */
data class BatteryReplacementInitiatedEvent(
    val batteryPackId: BatteryPackId,
    val replacementId: UUID,
    val reason: String,
    eventId: UUID = UUID.randomUUID(),
    occurredAt: Instant = Instant.now(),
    correlationId: UUID? = null
) : BaseDomainEvent(
    eventId = eventId,
    aggregateId = batteryPackId.toString(),
    aggregateType = "BatteryPack",
    eventType = "BatteryReplacementInitiated",
    eventVersion = 1,
    occurredAt = occurredAt,
    correlationId = correlationId
) {
    override fun getEventData() = mapOf(
        "batteryPackId" to batteryPackId.value.toString(),
        "replacementId" to replacementId.toString(),
        "reason" to reason
    )
}

/**
 * Battery Decommissioned Event
 * Raised when a battery is removed from service
 */
data class BatteryDecommissionedEvent(
    val batteryPackId: BatteryPackId,
    val replacementId: UUID?,
    val finalStateOfCharge: StateOfCharge,
    val reason: String,
    eventId: UUID = UUID.randomUUID(),
    occurredAt: Instant = Instant.now(),
    correlationId: UUID? = null
) : BaseDomainEvent(
    eventId = eventId,
    aggregateId = batteryPackId.toString(),
    aggregateType = "BatteryPack",
    eventType = "BatteryDecommissioned",
    eventVersion = 1,
    occurredAt = occurredAt,
    correlationId = correlationId
) {
    override fun getEventData() = mapOf(
        "batteryPackId" to batteryPackId.value.toString(),
        "replacementId" to replacementId?.toString(),
        "finalStateOfCharge" to finalStateOfCharge.percentage,
        "reason" to reason
    )
}
