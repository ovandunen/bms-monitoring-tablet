package com.fleet.interfaces.rest.mapper

import com.fleet.application.command.CreateBatteryPackCommand
import com.fleet.application.command.RecordTelemetryCommand
import com.fleet.domain.battery.model.*
import com.fleet.interfaces.rest.dto.*
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

/**
 * DTO Mapper
 * 
 * Maps between REST DTOs and domain objects/commands.
 * This keeps the API layer separate from the domain layer.
 */
@ApplicationScoped
class DtoMapper {
    
    /**
     * Map CreateBatteryRequest to Command
     */
    fun toCommand(request: CreateBatteryRequest): CreateBatteryPackCommand {
        return CreateBatteryPackCommand(
            batteryPackId = BatteryPackId.from(request.batteryPackId),
            manufacturer = request.manufacturer,
            model = request.model,
            chemistry = BatteryChemistry.valueOf(request.chemistry.uppercase()),
            nominalVoltage = request.nominalVoltage,
            capacity = request.capacity,
            cellConfiguration = request.cellConfiguration,
            initialStateOfCharge = request.initialStateOfCharge,
            correlationId = UUID.randomUUID()
        )
    }
    
    /**
     * Map RecordTelemetryRequest to Command
     */
    fun toCommand(
        batteryPackId: BatteryPackId,
        request: RecordTelemetryRequest
    ): RecordTelemetryCommand {
        return RecordTelemetryCommand(
            batteryPackId = batteryPackId,
            stateOfCharge = request.stateOfCharge,
            voltage = request.voltage,
            current = request.current,
            temperatureMin = request.temperatureMin,
            temperatureMax = request.temperatureMax,
            temperatureAvg = request.temperatureAvg,
            cellVoltages = request.cellVoltages,
            correlationId = UUID.randomUUID()
        )
    }
    
    /**
     * Map BatteryPack domain object to Response DTO
     */
    fun toStatusResponse(batteryPack: BatteryPack): BatteryStatusResponse {
        val specs = batteryPack.getSpecifications()
        
        return BatteryStatusResponse(
            batteryPackId = batteryPack.id.toString(),
            manufacturer = specs?.manufacturer ?: "Unknown",
            model = specs?.model ?: "Unknown",
            chemistry = specs?.chemistry?.name ?: "Unknown",
            capacity = specs?.capacity ?: 0.0,
            currentStateOfCharge = batteryPack.getCurrentStateOfCharge()?.percentage,
            currentVoltage = batteryPack.getCurrentVoltage()?.volts,
            currentTemperature = batteryPack.getCurrentTemperature()?.celsius,
            healthStatus = batteryPack.getHealthStatus().name,
            isDecommissioned = batteryPack.isCurrentlyDecommissioned(),
            version = batteryPack.version
        )
    }
    
    /**
     * Map DomainEvent to EventDto
     */
    fun toEventDto(event: com.fleet.domain.shared.DomainEvent): EventDto {
        return EventDto(
            eventId = event.eventId.toString(),
            eventType = event.eventType,
            eventVersion = event.eventVersion,
            aggregateVersion = 0, // Would need to track this
            occurredAt = event.occurredAt.toString(),
            data = event.getEventData()
        )
    }
}
