package com.fleet.domain.battery.model

import com.fleet.domain.shared.ValueObject
import java.util.UUID

/**
 * Battery Pack Identity (Value Object)
 * Strongly typed ID prevents mixing IDs of different aggregate types
 */
data class BatteryPackId(val value: UUID) : ValueObject {
    companion object {
        fun generate() = BatteryPackId(UUID.randomUUID())
        fun from(value: String) = BatteryPackId(UUID.fromString(value))
    }
    
    override fun toString() = value.toString()
}

/**
 * State of Charge - represents battery charge level
 * Enforces business invariants: must be 0-100%
 */
data class StateOfCharge private constructor(val percentage: Double) : ValueObject {
    
    init {
        require(percentage in 0.0..100.0) { 
            "State of Charge must be between 0% and 100%, got: $percentage%" 
        }
    }
    
    fun isCriticallyLow() = percentage < LOW_THRESHOLD
    fun isLow() = percentage < MEDIUM_THRESHOLD
    fun isFullyCharged() = percentage >= FULL_THRESHOLD
    fun isSufficientForOperation() = percentage >= OPERATIONAL_THRESHOLD
    
    fun canAcceptCharge() = percentage < FULL_THRESHOLD
    
    operator fun compareTo(other: StateOfCharge): Int = percentage.compareTo(other.percentage)
    
    companion object {
        private const val LOW_THRESHOLD = 20.0
        private const val MEDIUM_THRESHOLD = 50.0
        private const val FULL_THRESHOLD = 99.0
        private const val OPERATIONAL_THRESHOLD = 30.0
        
        fun of(percentage: Double) = StateOfCharge(percentage)
        fun empty() = StateOfCharge(0.0)
        fun full() = StateOfCharge(100.0)
    }
}

/**
 * Pack Voltage - represents total battery pack voltage
 * Enforces valid range for 114S LFP configuration (370-380V nominal)
 */
data class Voltage private constructor(val volts: Double) : ValueObject {
    
    init {
        require(volts in MIN_VOLTAGE..MAX_VOLTAGE) { 
            "Voltage must be between $MIN_VOLTAGE and $MAX_VOLTAGE volts, got: $volts" 
        }
    }
    
    fun isInNormalRange() = volts in NORMAL_MIN..NORMAL_MAX
    fun isLow() = volts < NORMAL_MIN
    fun isHigh() = volts > NORMAL_MAX
    fun isCriticallyLow() = volts < CRITICAL_MIN
    fun isCriticallyHigh() = volts > CRITICAL_MAX
    
    operator fun compareTo(other: Voltage): Int = volts.compareTo(other.volts)
    
    companion object {
        private const val MIN_VOLTAGE = 300.0  // Absolute minimum (114S × 2.5V)
        private const val MAX_VOLTAGE = 420.0  // Absolute maximum (114S × 3.65V)
        private const val NORMAL_MIN = 370.0   // Normal minimum
        private const val NORMAL_MAX = 385.0   // Normal maximum
        private const val CRITICAL_MIN = 350.0
        private const val CRITICAL_MAX = 400.0
        
        fun of(volts: Double) = Voltage(volts)
    }
}

/**
 * Pack Current - positive for discharge, negative for charge
 */
data class Current private constructor(val amperes: Double) : ValueObject {
    
    init {
        require(amperes.let { it in -MAX_CHARGE_CURRENT..MAX_DISCHARGE_CURRENT }) {
            "Current out of range: $amperes A"
        }
    }
    
    fun isCharging() = amperes < 0
    fun isDischarging() = amperes > 0
    fun isIdle() = amperes in -1.0..1.0
    fun isHighDischargeCurrent() = amperes > HIGH_DISCHARGE_THRESHOLD
    fun isHighChargeCurrent() = amperes < -HIGH_CHARGE_THRESHOLD
    
    companion object {
        private const val MAX_DISCHARGE_CURRENT = 210.0  // 210A peak
        private const val MAX_CHARGE_CURRENT = 210.0
        private const val HIGH_DISCHARGE_THRESHOLD = 150.0
        private const val HIGH_CHARGE_THRESHOLD = 100.0
        
        fun of(amperes: Double) = Current(amperes)
        fun charging(amperes: Double) = Current(-Math.abs(amperes))
        fun discharging(amperes: Double) = Current(Math.abs(amperes))
    }
}

/**
 * Temperature - represents battery temperature
 */
data class Temperature private constructor(val celsius: Double) : ValueObject {
    
    init {
        require(celsius in MIN_TEMP..MAX_TEMP) {
            "Temperature must be between $MIN_TEMP and $MAX_TEMP°C, got: $celsius°C"
        }
    }
    
    fun isInNormalRange() = celsius in NORMAL_MIN..NORMAL_MAX
    fun isSafeForCharging() = celsius in CHARGE_MIN..CHARGE_MAX
    fun isSafeForDischarge() = celsius in DISCHARGE_MIN..DISCHARGE_MAX
    fun exceedsOperatingLimit() = celsius > CRITICAL_MAX || celsius < CRITICAL_MIN
    fun isCriticallyHigh() = celsius > CRITICAL_MAX
    fun isCriticallyLow() = celsius < CRITICAL_MIN
    
    operator fun compareTo(other: Temperature): Int = celsius.compareTo(other.celsius)
    
    companion object {
        private const val MIN_TEMP = -20.0
        private const val MAX_TEMP = 80.0
        private const val NORMAL_MIN = 15.0
        private const val NORMAL_MAX = 40.0
        private const val CHARGE_MIN = 0.0
        private const val CHARGE_MAX = 45.0
        private const val DISCHARGE_MIN = -10.0
        private const val DISCHARGE_MAX = 60.0
        private const val CRITICAL_MIN = -5.0
        private const val CRITICAL_MAX = 55.0
        
        fun of(celsius: Double) = Temperature(celsius)
    }
}

/**
 * Cell Voltages - collection of individual cell voltages in the pack
 * For 114S configuration
 */
data class CellVoltages private constructor(val voltages: List<Double>) : ValueObject {
    
    init {
        require(voltages.size == CELL_COUNT) {
            "Must have exactly $CELL_COUNT cell voltages, got: ${voltages.size}"
        }
        voltages.forEach { voltage ->
            require(voltage in MIN_CELL_VOLTAGE..MAX_CELL_VOLTAGE) {
                "Cell voltage out of range: $voltage V"
            }
        }
    }
    
    val min: Double get() = voltages.minOrNull() ?: 0.0
    val max: Double get() = voltages.maxOrNull() ?: 0.0
    val average: Double get() = voltages.average()
    val delta: Double get() = max - min
    
    fun isBalanced() = delta <= BALANCED_THRESHOLD
    fun requiresBalancing() = delta > BALANCING_THRESHOLD
    fun hasCriticalImbalance() = delta > CRITICAL_IMBALANCE_THRESHOLD
    
    companion object {
        const val CELL_COUNT = 114
        private const val MIN_CELL_VOLTAGE = 2.5
        private const val MAX_CELL_VOLTAGE = 3.65
        private const val BALANCED_THRESHOLD = 0.020  // 20mV
        private const val BALANCING_THRESHOLD = 0.030  // 30mV
        private const val CRITICAL_IMBALANCE_THRESHOLD = 0.050  // 50mV
        
        fun of(voltages: List<Double>): CellVoltages {
            require(voltages.size == CELL_COUNT) {
                "Must provide exactly $CELL_COUNT cell voltages"
            }
            return CellVoltages(voltages)
        }
    }
}

/**
 * Telemetry Reading - encapsulates a complete battery measurement
 * This is a value object that represents all measurements at a point in time
 */
data class TelemetryReading(
    val stateOfCharge: StateOfCharge,
    val voltage: Voltage,
    val current: Current,
    val temperatureMin: Temperature,
    val temperatureMax: Temperature,
    val temperatureAvg: Temperature,
    val cellVoltages: CellVoltages
) : ValueObject {
    
    val cellVoltageDelta: Double get() = cellVoltages.delta
    
    fun hasAnomalies(): Boolean {
        return voltage.isCriticallyLow() ||
               voltage.isCriticallyHigh() ||
               temperatureMax.exceedsOperatingLimit() ||
               cellVoltages.hasCriticalImbalance()
    }
    
    fun requiresImmediateAttention(): Boolean {
        return stateOfCharge.isCriticallyLow() ||
               temperatureMax.isCriticallyHigh() ||
               cellVoltages.hasCriticalImbalance()
    }
}

/**
 * Battery Specifications - immutable specifications of the battery pack
 */
data class BatterySpecifications(
    val manufacturer: String,
    val model: String,
    val chemistry: BatteryChemistry,
    val nominalVoltage: Voltage,
    val capacity: Double,  // kWh
    val cellConfiguration: String  // e.g., "114S1P"
) : ValueObject

enum class BatteryChemistry {
    LFP,  // Lithium Iron Phosphate (LiFePO4)
    NMC,  // Lithium Nickel Manganese Cobalt Oxide
    LTO   // Lithium Titanate
}
