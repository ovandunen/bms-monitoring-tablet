package com.fleet.application.command

import com.fleet.domain.battery.model.*
import java.util.UUID

/**
 * Create Battery Pack Command
 * 
 * Command to create a new battery pack in the system.
 * This is typically called during vehicle onboarding.
 */
data class CreateBatteryPackCommand(
    val batteryPackId: BatteryPackId,
    val manufacturer: String,
    val model: String,
    val chemistry: BatteryChemistry,
    val nominalVoltage: Double,
    val capacity: Double,
    val cellConfiguration: String,
    val initialStateOfCharge: Double,
    val correlationId: UUID? = null
) {
    init {
        require(manufacturer.isNotBlank()) { "Manufacturer cannot be blank" }
        require(model.isNotBlank()) { "Model cannot be blank" }
        require(capacity > 0) { "Capacity must be positive" }
        require(initialStateOfCharge in 0.0..100.0) { "Initial SOC must be 0-100%" }
    }
    
    fun toSpecifications(): BatterySpecifications {
        return BatterySpecifications(
            manufacturer = manufacturer,
            model = model,
            chemistry = chemistry,
            nominalVoltage = Voltage.of(nominalVoltage),
            capacity = capacity,
            cellConfiguration = cellConfiguration
        )
    }
}

/**
 * Record Telemetry Command
 * 
 * Command to record new telemetry data from BMS.
 * This is the most frequent command in the system (1-10 Hz per vehicle).
 */
data class RecordTelemetryCommand(
    val batteryPackId: BatteryPackId,
    val stateOfCharge: Double,
    val voltage: Double,
    val current: Double,
    val temperatureMin: Double,
    val temperatureMax: Double,
    val temperatureAvg: Double,
    val cellVoltages: List<Double>,
    val correlationId: UUID? = null
) {
    init {
        require(cellVoltages.size == 114) { 
            "Must provide exactly 114 cell voltages, got: ${cellVoltages.size}" 
        }
    }
    
    fun toTelemetryReading(): TelemetryReading {
        return TelemetryReading(
            stateOfCharge = StateOfCharge.of(stateOfCharge),
            voltage = Voltage.of(voltage),
            current = Current.of(current),
            temperatureMin = Temperature.of(temperatureMin),
            temperatureMax = Temperature.of(temperatureMax),
            temperatureAvg = Temperature.of(temperatureAvg),
            cellVoltages = CellVoltages.of(cellVoltages)
        )
    }
}

/**
 * Initiate Battery Replacement Command
 * 
 * Command to start the battery replacement saga.
 * This is a complex, long-running process that spans multiple aggregates.
 */
data class InitiateBatteryReplacementCommand(
    val oldBatteryPackId: BatteryPackId,
    val newBatteryPackId: BatteryPackId,
    val vehicleId: String,
    val reason: String,
    val replacementId: UUID = UUID.randomUUID(),
    val correlationId: UUID? = null
) {
    init {
        require(reason.isNotBlank()) { "Reason cannot be blank" }
        require(oldBatteryPackId != newBatteryPackId) { 
            "Old and new battery must be different" 
        }
    }
}

/**
 * Query Battery Status Command
 * 
 * Not really a command, but a query parameter object.
 * Used for reading current battery status.
 */
data class GetBatteryStatusQuery(
    val batteryPackId: BatteryPackId
)

/**
 * Get Battery Event History Query
 * 
 * Query to retrieve event history for a battery pack.
 * Useful for debugging and audit trail.
 */
data class GetBatteryEventsQuery(
    val batteryPackId: BatteryPackId,
    val fromVersion: Long = 0,
    val maxEvents: Int? = null
)
