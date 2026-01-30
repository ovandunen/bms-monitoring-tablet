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
        /**
         * State of Charge thresholds based on:
         * - LFP battery safe operating range
         * - Vehicle operational requirements  
         * - Safety margins per battery manufacturer specifications
         */
        
        /** Below 20%: Battery critically low, immediate charging required to prevent deep discharge */
        private const val LOW_THRESHOLD = 20.0
        
        /** Below 50%: Medium charge level, plan for charging soon */
        private const val MEDIUM_THRESHOLD = 50.0
        
        /** Above 99%: Consider fully charged (avoid stress from 100% charge state) */
        private const val FULL_THRESHOLD = 99.0
        
        /** Above 30%: Minimum SOC for normal vehicle operation without performance degradation */
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
        /**
         * Voltage thresholds for 114S LFP (LiFePO4) battery pack configuration.
         * Based on nominal cell voltage of 3.2V with operating range 2.5V-3.65V per cell.
         * 
         * Reference: LFP battery datasheet specifications
         */
        
        /** Absolute minimum: 114 cells × 2.5V = 285V (below this risks cell damage) */
        private const val MIN_VOLTAGE = 300.0
        
        /** Absolute maximum: 114 cells × 3.65V = 416V (above this risks overcharge) */
        private const val MAX_VOLTAGE = 420.0
        
        /** Normal operating minimum: 114 cells × ~3.25V (typical low charge voltage) */
        private const val NORMAL_MIN = 370.0
        
        /** Normal operating maximum: 114 cells × ~3.38V (typical high charge voltage) */
        private const val NORMAL_MAX = 385.0
        
        /** Critical low threshold: requires immediate attention */
        private const val CRITICAL_MIN = 350.0
        
        /** Critical high threshold: approaching overcharge danger zone */
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
        /**
         * Temperature thresholds for LFP battery safe operation.
         * Based on battery manufacturer specifications and industry standards.
         * 
         * LFP batteries are more temperature-tolerant than other lithium chemistries
         * but still require careful thermal management for longevity and safety.
         */
        
        /** Absolute minimum: below this causes permanent capacity loss */
        private const val MIN_TEMP = -20.0
        
        /** Absolute maximum: thermal runaway risk above this temperature */
        private const val MAX_TEMP = 80.0
        
        /** Normal operating minimum: optimal performance above 15°C */
        private const val NORMAL_MIN = 15.0
        
        /** Normal operating maximum: optimal performance below 40°C */
        private const val NORMAL_MAX = 40.0
        
        /** Minimum temperature for charging: below 0°C lithium plating risk */
        private const val CHARGE_MIN = 0.0
        
        /** Maximum temperature for charging: above 45°C accelerates degradation */
        private const val CHARGE_MAX = 45.0
        
        /** Minimum temperature for discharge: reduced power below -10°C */
        private const val DISCHARGE_MIN = -10.0
        
        /** Maximum temperature for discharge: high power delivery limit */
        private const val DISCHARGE_MAX = 60.0
        
        /** Critical low threshold: requires heating before operation */
        private const val CRITICAL_MIN = -5.0
        
        /** Critical high threshold: requires immediate cooling */
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
    
    val min: Double get() = voltages.minOrNull() 
        ?: throw IllegalStateException("No cell voltages available for min calculation")
    val max: Double get() = voltages.maxOrNull() 
        ?: throw IllegalStateException("No cell voltages available for max calculation")
    val average: Double get() {
        require(voltages.isNotEmpty()) { "No cell voltages available for average calculation" }
        return voltages.average()
    }
    val delta: Double get() {
        val d = max - min
        require(d >= 0) { "Cell voltage delta cannot be negative: $d" }
        return d
    }
    
    fun isBalanced() = delta <= BALANCED_THRESHOLD
    fun requiresBalancing() = delta > BALANCING_THRESHOLD
    fun hasCriticalImbalance() = delta > CRITICAL_IMBALANCE_THRESHOLD
    
    companion object {
        /**
         * Cell voltage thresholds for 114S LFP battery configuration.
         * Cell balancing is critical for battery longevity and safety.
         */
        
        /** Number of cells in series (114S configuration) */
        const val CELL_COUNT = 114
        
        /** Minimum safe cell voltage (2.5V - below this causes damage) */
        private const val MIN_CELL_VOLTAGE = 2.5
        
        /** Maximum safe cell voltage (3.65V - above this risks overcharge) */
        private const val MAX_CELL_VOLTAGE = 3.65
        
        /** Balanced threshold: cells within 20mV are considered well-balanced */
        private const val BALANCED_THRESHOLD = 0.020  // 20mV
        
        /** Balancing threshold: above 30mV delta should trigger active balancing */
        private const val BALANCING_THRESHOLD = 0.030  // 30mV
        
        /** Critical imbalance: above 50mV delta is dangerous, requires immediate attention */
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
