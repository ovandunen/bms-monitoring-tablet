package com.fleet.interfaces.rest.dto

import com.fleet.domain.battery.model.*

/**
 * REST API DTOs
 * 
 * These DTOs define the REST API contract.
 * They are separate from domain model to allow API evolution without breaking domain.
 */

/**
 * Request: Create Battery Pack
 */
data class CreateBatteryRequest(
    val batteryPackId: String,
    val manufacturer: String,
    val model: String,
    val chemistry: String,
    val nominalVoltage: Double,
    val capacity: Double,
    val cellConfiguration: String,
    val initialStateOfCharge: Double
)

/**
 * Request: Record Telemetry
 */
data class RecordTelemetryRequest(
    val stateOfCharge: Double,
    val voltage: Double,
    val current: Double,
    val temperatureMin: Double,
    val temperatureMax: Double,
    val temperatureAvg: Double,
    val cellVoltages: List<Double>
)

/**
 * Response: Battery Status
 */
data class BatteryStatusResponse(
    val batteryPackId: String,
    val manufacturer: String,
    val model: String,
    val chemistry: String,
    val capacity: Double,
    val currentStateOfCharge: Double?,
    val currentVoltage: Double?,
    val currentTemperature: Double?,
    val healthStatus: String,
    val isDecommissioned: Boolean,
    val version: Long
)

/**
 * Response: Battery Created
 */
data class BatteryCreatedResponse(
    val batteryPackId: String,
    val message: String
)

/**
 * Response: Telemetry Recorded
 */
data class TelemetryRecordedResponse(
    val batteryPackId: String,
    val eventsRaised: Int,
    val message: String
)

/**
 * Response: Event History
 */
data class EventHistoryResponse(
    val batteryPackId: String,
    val events: List<EventDto>
)

/**
 * Event DTO
 */
data class EventDto(
    val eventId: String,
    val eventType: String,
    val eventVersion: Int,
    val aggregateVersion: Long,
    val occurredAt: String,
    val data: Map<String, Any?>
)

/**
 * Response: Battery List
 */
data class BatteryListResponse(
    val batteries: List<BatteryStatusResponse>,
    val total: Int
)

/**
 * Error Response
 */
data class ErrorResponse(
    val error: String,
    val message: String,
    val timestamp: String
)

/**
 * Success Response
 */
data class SuccessResponse(
    val success: Boolean,
    val message: String
)
