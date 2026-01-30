package com.fleet.domain.battery.model

import com.fleet.domain.battery.event.*
import com.fleet.domain.shared.AggregateRoot
import com.fleet.domain.shared.DomainEvent
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * BatteryPack Aggregate Root
 * 
 * This is an Event-Sourced aggregate - its complete state is rebuilt from events.
 * All state changes must produce events.
 * Events are the source of truth.
 * 
 * Business Rules Enforced:
 * - Battery specifications are immutable after creation
 * - Telemetry readings must be valid and complete
 * - Alerts are raised for critical conditions
 * - Charging state is managed consistently
 * - Decommissioning is irreversible
 * 
 * Invariants Protected:
 * - Battery cannot be used after decommissioning
 * - State of charge always 0-100%
 * - Voltage within safe operating range
 * - Temperature within safe limits
 */
class BatteryPack private constructor(
    override val id: BatteryPackId,
    private var specifications: BatterySpecifications? = null,
    private var currentStateOfCharge: StateOfCharge? = null,
    private var currentVoltage: Voltage? = null,
    private var currentCurrent: Current? = null,
    private var currentTemperature: Temperature? = null,
    private var currentCellVoltages: CellVoltages? = null,
    private var isDecommissioned: Boolean = false,
    private var chargingSessionId: UUID? = null,
    private var chargingStartedAt: Instant? = null
) : AggregateRoot<BatteryPackId>() {
    
    companion object {
        /**
         * Factory method: Create a new battery pack
         * This is the only way to instantiate a new aggregate
         */
        fun create(
            batteryPackId: BatteryPackId,
            specifications: BatterySpecifications,
            initialStateOfCharge: StateOfCharge,
            correlationId: UUID? = null
        ): BatteryPack {
            val battery = BatteryPack(id = batteryPackId)
            
            // Raise creation event
            battery.raiseEvent(
                BatteryPackCreatedEvent(
                    batteryPackId = batteryPackId,
                    specifications = specifications,
                    initialStateOfCharge = initialStateOfCharge,
                    correlationId = correlationId
                )
            )
            
            return battery
        }
        
        /**
         * Reconstitute aggregate from event history (Event Sourcing)
         */
        fun fromHistory(batteryPackId: BatteryPackId, events: List<DomainEvent>): BatteryPack {
            val battery = BatteryPack(id = batteryPackId)
            battery.loadFromHistory(events)
            return battery
        }
    }
    
    /**
     * Record new telemetry reading from BMS
     * This is the most frequent operation - called every few seconds/minutes
     */
    fun recordTelemetry(
        reading: TelemetryReading,
        correlationId: UUID? = null
    ): List<DomainEvent> {
        requireNotDecommissioned()
        
        val events = mutableListOf<DomainEvent>()
        
        // Always record telemetry
        events.add(
            TelemetryRecordedEvent(
                batteryPackId = id,
                reading = reading,
                correlationId = correlationId
            )
        )
        
        // Check for critical conditions and raise appropriate events
        
        // Critical battery depletion
        if (reading.stateOfCharge.isCriticallyLow()) {
            events.add(
                BatteryDepletedEvent(
                    batteryPackId = id,
                    stateOfCharge = reading.stateOfCharge,
                    voltage = reading.voltage,
                    correlationId = correlationId
                )
            )
        }
        
        // Critical temperature
        if (reading.temperatureMax.isCriticallyHigh()) {
            events.add(
                CriticalTemperatureEvent(
                    batteryPackId = id,
                    temperature = reading.temperatureMax,
                    temperatureType = TemperatureType.TOO_HIGH,
                    correlationId = correlationId
                )
            )
        }
        
        if (reading.temperatureMin.isCriticallyLow()) {
            events.add(
                CriticalTemperatureEvent(
                    batteryPackId = id,
                    temperature = reading.temperatureMin,
                    temperatureType = TemperatureType.TOO_LOW,
                    correlationId = correlationId
                )
            )
        }
        
        // Cell imbalance
        if (reading.cellVoltages.hasCriticalImbalance()) {
            events.add(
                CellImbalanceDetectedEvent(
                    batteryPackId = id,
                    cellVoltageDelta = reading.cellVoltageDelta,
                    cellVoltageMin = reading.cellVoltages.min,
                    cellVoltageMax = reading.cellVoltages.max,
                    correlationId = correlationId
                )
            )
        }
        
        // Detect charging state transitions
        // Store previous state BEFORE applying the new telemetry event
        val previousCurrent = currentCurrent
        val wasCharging = previousCurrent?.isCharging() == true
        val isNowCharging = reading.current.isCharging()
        
        if (!wasCharging && isNowCharging) {
            // Charging just started
            events.add(
                ChargingStartedEvent(
                    batteryPackId = id,
                    stateOfCharge = reading.stateOfCharge,
                    chargingSessionId = UUID.randomUUID(),
                    correlationId = correlationId
                )
            )
        } else if (wasCharging && !isNowCharging) {
            // Charging stopped (either completed or interrupted)
            val duration = chargingStartedAt?.let { 
                Duration.between(it, Instant.now()).toMinutes() 
            } ?: 0
            
            if (reading.stateOfCharge.isFullyCharged()) {
                // Charging completed successfully
                events.add(
                    ChargingCompletedEvent(
                        batteryPackId = id,
                        stateOfCharge = reading.stateOfCharge,
                        chargingSessionId = chargingSessionId ?: UUID.randomUUID(),
                        durationMinutes = duration,
                        correlationId = correlationId
                    )
                )
            } else {
                // Charging interrupted before full charge
                events.add(
                    ChargingInterruptedEvent(
                        batteryPackId = id,
                        stateOfCharge = reading.stateOfCharge,
                        chargingSessionId = chargingSessionId ?: UUID.randomUUID(),
                        durationMinutes = duration,
                        correlationId = correlationId
                    )
                )
            }
        }
        
        // Raise all events
        events.forEach { raiseEvent(it) }
        
        return events
    }
    
    /**
     * Initiate battery replacement process
     * This starts the Battery Replacement Saga
     */
    fun initiateReplacement(
        replacementId: UUID,
        reason: String,
        correlationId: UUID? = null
    ) {
        requireNotDecommissioned()
        
        raiseEvent(
            BatteryReplacementInitiatedEvent(
                batteryPackId = id,
                replacementId = replacementId,
                reason = reason,
                correlationId = correlationId
            )
        )
    }
    
    /**
     * Decommission this battery (remove from service)
     * This is an irreversible operation
     */
    fun decommission(
        reason: String,
        replacementId: UUID? = null,
        correlationId: UUID? = null
    ) {
        requireNotDecommissioned()
        
        raiseEvent(
            BatteryDecommissionedEvent(
                batteryPackId = id,
                replacementId = replacementId,
                finalStateOfCharge = currentStateOfCharge ?: StateOfCharge.empty(),
                reason = reason,
                correlationId = correlationId
            )
        )
    }
    
    /**
     * Check if battery can accept charge
     * Business logic: safe temperature, not full, not decommissioned
     */
    fun canAcceptCharge(): Boolean {
        if (isDecommissioned) return false
        
        val soc = currentStateOfCharge ?: return false
        val temp = currentTemperature ?: return true  // Allow if no temp data
        
        return soc.canAcceptCharge() && temp.isSafeForCharging()
    }
    
    /**
     * Check if battery requires immediate attention
     */
    fun requiresImmediateAttention(): Boolean {
        val soc = currentStateOfCharge ?: return false
        val temp = currentTemperature ?: return false
        val cells = currentCellVoltages ?: return false
        
        return soc.isCriticallyLow() ||
               temp.isCriticallyHigh() ||
               cells.hasCriticalImbalance()
    }
    
    /**
     * Get current battery health status
     */
    fun getHealthStatus(): BatteryHealthStatus {
        if (isDecommissioned) return BatteryHealthStatus.DECOMMISSIONED
        
        val soc = currentStateOfCharge ?: return BatteryHealthStatus.UNKNOWN
        val temp = currentTemperature ?: return BatteryHealthStatus.UNKNOWN
        val cells = currentCellVoltages ?: return BatteryHealthStatus.UNKNOWN
        
        return when {
            requiresImmediateAttention() -> BatteryHealthStatus.CRITICAL
            !temp.isInNormalRange() || !cells.isBalanced() -> BatteryHealthStatus.WARNING
            soc.isSufficientForOperation() -> BatteryHealthStatus.HEALTHY
            else -> BatteryHealthStatus.LOW_CHARGE
        }
    }
    
    /**
     * Apply event to aggregate state (Event Sourcing)
     * This method rebuilds the aggregate from its event history
     */
    override fun applyEvent(event: DomainEvent) {
        when (event) {
            is BatteryPackCreatedEvent -> apply(event)
            is TelemetryRecordedEvent -> apply(event)
            is BatteryDepletedEvent -> apply(event)
            is CriticalTemperatureEvent -> apply(event)
            is CellImbalanceDetectedEvent -> apply(event)
            is ChargingStartedEvent -> apply(event)
            is ChargingCompletedEvent -> apply(event)
            is ChargingInterruptedEvent -> apply(event)
            is BatteryReplacementInitiatedEvent -> apply(event)
            is BatteryDecommissionedEvent -> apply(event)
            else -> {
                // Unknown event - log but don't fail
                // This allows for forward compatibility
            }
        }
    }
    
    private fun apply(event: BatteryPackCreatedEvent) {
        this.specifications = event.specifications
        this.currentStateOfCharge = event.initialStateOfCharge
    }
    
    private fun apply(event: TelemetryRecordedEvent) {
        this.currentStateOfCharge = event.reading.stateOfCharge
        this.currentVoltage = event.reading.voltage
        this.currentCurrent = event.reading.current
        this.currentTemperature = event.reading.temperatureAvg
        this.currentCellVoltages = event.reading.cellVoltages
    }
    
    private fun apply(event: BatteryDepletedEvent) {
        // Event captured, no state change needed beyond telemetry
    }
    
    private fun apply(event: CriticalTemperatureEvent) {
        // Event captured, no state change needed beyond telemetry
    }
    
    private fun apply(event: CellImbalanceDetectedEvent) {
        // Event captured, no state change needed beyond telemetry
    }
    
    private fun apply(event: ChargingStartedEvent) {
        this.chargingSessionId = event.chargingSessionId
        this.chargingStartedAt = event.occurredAt
    }
    
    private fun apply(event: ChargingCompletedEvent) {
        this.chargingSessionId = null
        this.chargingStartedAt = null
    }
    
    private fun apply(event: ChargingInterruptedEvent) {
        this.chargingSessionId = null
        this.chargingStartedAt = null
    }
    
    private fun apply(event: BatteryReplacementInitiatedEvent) {
        // Saga will handle the replacement process
    }
    
    private fun apply(event: BatteryDecommissionedEvent) {
        this.isDecommissioned = true
    }
    
    private fun requireNotDecommissioned() {
        require(!isDecommissioned) {
            "Cannot perform operation on decommissioned battery: ${id}"
        }
    }
    
    // Getters for current state (for queries)
    fun getCurrentStateOfCharge(): StateOfCharge? = currentStateOfCharge
    fun getCurrentVoltage(): Voltage? = currentVoltage
    fun getCurrentTemperature(): Temperature? = currentTemperature
    fun getSpecifications(): BatterySpecifications? = specifications
    fun isCurrentlyDecommissioned(): Boolean = isDecommissioned
}

/**
 * Battery Health Status enumeration
 */
enum class BatteryHealthStatus {
    HEALTHY,
    LOW_CHARGE,
    WARNING,
    CRITICAL,
    DECOMMISSIONED,
    UNKNOWN
}
