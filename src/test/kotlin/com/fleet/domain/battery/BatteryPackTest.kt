package com.fleet.domain.battery

import com.fleet.domain.battery.event.*
import com.fleet.domain.battery.model.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.util.UUID

/**
 * BatteryPack Domain Tests
 * 
 * These are pure unit tests with no dependencies.
 * They test the domain logic in isolation.
 * 
 * This demonstrates the value of DDD - business logic is testable without infrastructure.
 */
class BatteryPackTest {
    
    @Test
    fun `should create battery pack with initial state`() {
        // Given
        val batteryId = BatteryPackId.generate()
        val specs = createTestSpecifications()
        val initialSoc = StateOfCharge.of(80.0)
        
        // When
        val battery = BatteryPack.create(batteryId, specs, initialSoc)
        
        // Then
        assertEquals(1, battery.version)
        assertTrue(battery.hasUncommittedEvents())
        
        val events = battery.getUncommittedEvents()
        assertEquals(1, events.size)
        assertTrue(events[0] is BatteryPackCreatedEvent)
        
        val createdEvent = events[0] as BatteryPackCreatedEvent
        assertEquals(batteryId, createdEvent.batteryPackId)
        assertEquals(initialSoc, createdEvent.initialStateOfCharge)
    }
    
    @Test
    fun `should record telemetry and raise event`() {
        // Given
        val battery = createTestBattery()
        val reading = createNormalTelemetryReading()
        
        // When
        val events = battery.recordTelemetry(reading)
        
        // Then
        assertTrue(events.any { it is TelemetryRecordedEvent })
        assertEquals(2, battery.version) // 1 from creation, 1 from telemetry
    }
    
    @Test
    fun `should raise BatteryDepletedEvent when SOC critically low`() {
        // Given
        val battery = createTestBattery()
        val reading = createCriticalLowSocReading() // SOC < 20%
        
        // When
        val events = battery.recordTelemetry(reading)
        
        // Then
        assertTrue(events.any { it is TelemetryRecordedEvent })
        assertTrue(events.any { it is BatteryDepletedEvent })
        assertEquals(2, events.size)
    }
    
    @Test
    fun `should raise CriticalTemperatureEvent when temperature too high`() {
        // Given
        val battery = createTestBattery()
        val reading = createHighTemperatureReading() // > 55°C
        
        // When
        val events = battery.recordTelemetry(reading)
        
        // Then
        assertTrue(events.any { it is TelemetryRecordedEvent })
        assertTrue(events.any { it is CriticalTemperatureEvent })
    }
    
    @Test
    fun `should raise CellImbalanceDetectedEvent when cells imbalanced`() {
        // Given
        val battery = createTestBattery()
        val reading = createImbalancedCellsReading() // Delta > 50mV
        
        // When
        val events = battery.recordTelemetry(reading)
        
        // Then
        assertTrue(events.any { it is TelemetryRecordedEvent })
        assertTrue(events.any { it is CellImbalanceDetectedEvent })
    }
    
    @Test
    fun `should raise ChargingStartedEvent when charging begins`() {
        // Given
        val battery = createTestBattery()
        
        // Initially discharging
        battery.recordTelemetry(createDischargingReading())
        
        // When - now charging
        val events = battery.recordTelemetry(createChargingReading())
        
        // Then
        assertTrue(events.any { it is ChargingStartedEvent })
    }
    
    @Test
    fun `should decommission battery`() {
        // Given
        val battery = createTestBattery()
        
        // When
        battery.decommission("End of life", UUID.randomUUID())
        
        // Then
        assertTrue(battery.isCurrentlyDecommissioned())
        
        val events = battery.getUncommittedEvents()
        assertTrue(events.any { it is BatteryDecommissionedEvent })
    }
    
    @Test
    fun `should not allow operations on decommissioned battery`() {
        // Given
        val battery = createTestBattery()
        battery.decommission("Test", null)
        battery.markEventsAsCommitted() // Clear events
        
        // When/Then
        assertThrows(IllegalArgumentException::class.java) {
            battery.recordTelemetry(createNormalTelemetryReading())
        }
    }
    
    @Test
    fun `should reconstitute from event history`() {
        // Given
        val batteryId = BatteryPackId.generate()
        val events = createEventHistory(batteryId)
        
        // When
        val battery = BatteryPack.fromHistory(batteryId, events)
        
        // Then
        assertEquals(events.size.toLong(), battery.version)
        assertEquals(75.0, battery.getCurrentStateOfCharge()?.percentage)
        assertFalse(battery.hasUncommittedEvents())
    }
    
    @Test
    fun `should calculate health status correctly`() {
        // Given
        val battery = createTestBattery()
        
        // When - record normal telemetry
        battery.recordTelemetry(createNormalTelemetryReading())
        
        // Then
        assertEquals(BatteryHealthStatus.HEALTHY, battery.getHealthStatus())
        
        // When - record critical telemetry
        battery.recordTelemetry(createCriticalLowSocReading())
        
        // Then
        assertEquals(BatteryHealthStatus.CRITICAL, battery.getHealthStatus())
    }
    
    // Helper methods for test data
    
    private fun createTestBattery(): BatteryPack {
        return BatteryPack.create(
            batteryPackId = BatteryPackId.generate(),
            specifications = createTestSpecifications(),
            initialStateOfCharge = StateOfCharge.of(80.0)
        )
    }
    
    private fun createTestSpecifications(): BatterySpecifications {
        return BatterySpecifications(
            manufacturer = "BYD",
            model = "Blade Battery",
            chemistry = BatteryChemistry.LFP,
            nominalVoltage = Voltage.of(377.6),
            capacity = 60.0,
            cellConfiguration = "114S1P"
        )
    }
    
    private fun createNormalTelemetryReading(): TelemetryReading {
        return TelemetryReading(
            stateOfCharge = StateOfCharge.of(75.0),
            voltage = Voltage.of(377.0),
            current = Current.of(-40.0),
            temperatureMin = Temperature.of(28.0),
            temperatureMax = Temperature.of(32.0),
            temperatureAvg = Temperature.of(30.0),
            cellVoltages = CellVoltages.of(List(114) { 3.30 })
        )
    }
    
    private fun createCriticalLowSocReading(): TelemetryReading {
        return TelemetryReading(
            stateOfCharge = StateOfCharge.of(15.0), // < 20%
            voltage = Voltage.of(350.0),
            current = Current.of(50.0),
            temperatureMin = Temperature.of(28.0),
            temperatureMax = Temperature.of(32.0),
            temperatureAvg = Temperature.of(30.0),
            cellVoltages = CellVoltages.of(List(114) { 3.07 })
        )
    }
    
    private fun createHighTemperatureReading(): TelemetryReading {
        return TelemetryReading(
            stateOfCharge = StateOfCharge.of(75.0),
            voltage = Voltage.of(377.0),
            current = Current.of(150.0),
            temperatureMin = Temperature.of(50.0),
            temperatureMax = Temperature.of(58.0), // > 55°C
            temperatureAvg = Temperature.of(54.0),
            cellVoltages = CellVoltages.of(List(114) { 3.30 })
        )
    }
    
    private fun createImbalancedCellsReading(): TelemetryReading {
        val voltages = MutableList(114) { 3.30 }
        voltages[0] = 3.25  // Min
        voltages[113] = 3.32 // Max (delta = 70mV > 50mV threshold)
        
        return TelemetryReading(
            stateOfCharge = StateOfCharge.of(75.0),
            voltage = Voltage.of(377.0),
            current = Current.of(-40.0),
            temperatureMin = Temperature.of(28.0),
            temperatureMax = Temperature.of(32.0),
            temperatureAvg = Temperature.of(30.0),
            cellVoltages = CellVoltages.of(voltages)
        )
    }
    
    private fun createDischargingReading(): TelemetryReading {
        return createNormalTelemetryReading().copy(
            current = Current.of(50.0) // Positive = discharging
        )
    }
    
    private fun createChargingReading(): TelemetryReading {
        return createNormalTelemetryReading().copy(
            current = Current.of(-50.0) // Negative = charging
        )
    }
    
    private fun createEventHistory(batteryId: BatteryPackId): List<com.fleet.domain.shared.DomainEvent> {
        return listOf(
            BatteryPackCreatedEvent(
                batteryPackId = batteryId,
                specifications = createTestSpecifications(),
                initialStateOfCharge = StateOfCharge.of(80.0)
            ),
            TelemetryRecordedEvent(
                batteryPackId = batteryId,
                reading = createNormalTelemetryReading()
            )
        )
    }
}
