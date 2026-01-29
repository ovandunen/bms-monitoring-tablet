package com.fleet.interfaces.rest

import com.fleet.application.command.GetBatteryStatusQuery
import com.fleet.application.usecase.battery.*
import com.fleet.domain.battery.model.BatteryPackId
import com.fleet.infrastructure.persistence.eventstore.AggregateNotFoundException
import com.fleet.interfaces.rest.dto.*
import com.fleet.interfaces.rest.mapper.DtoMapper
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import kotlinx.coroutines.runBlocking
import org.jboss.logging.Logger
import java.time.Instant

/**
 * Battery REST API Controller
 * 
 * Provides REST endpoints for battery management.
 * This is the main entry point for the web dashboard and external integrations.
 * 
 * Endpoints:
 * - POST   /api/v1/batteries              Create battery
 * - GET    /api/v1/batteries/{id}         Get battery status
 * - POST   /api/v1/batteries/{id}/telemetry  Record telemetry
 * - GET    /api/v1/batteries              List all batteries
 * - GET    /api/v1/batteries/{id}/events  Get event history
 */
@Path("/api/v1/batteries")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class BatteryController(
    private val createBatteryUseCase: CreateBatteryPackUseCase,
    private val recordTelemetryUseCase: RecordTelemetryUseCase,
    private val getBatteryStatusUseCase: GetBatteryStatusUseCase,
    private val listAllBatteriesUseCase: ListAllBatteriesUseCase,
    private val mapper: DtoMapper
) {
    private val logger = Logger.getLogger(BatteryController::class.java)
    
    /**
     * Create a new battery pack
     * 
     * POST /api/v1/batteries
     * 
     * Request body:
     * {
     *   "batteryPackId": "550e8400-e29b-41d4-a716-446655440000",
     *   "manufacturer": "BYD",
     *   "model": "Blade Battery",
     *   "chemistry": "LFP",
     *   "nominalVoltage": 377.6,
     *   "capacity": 60.0,
     *   "cellConfiguration": "114S1P",
     *   "initialStateOfCharge": 80.0
     * }
     */
    @POST
    fun createBattery(request: CreateBatteryRequest): Uni<Response> {
        return Uni.createFrom().item {
            try {
                logger.info("API: Creating battery ${request.batteryPackId}")
                
                val command = mapper.toCommand(request)
                
                val batteryPack = runBlocking {
                    createBatteryUseCase.execute(command)
                }
                
                val response = BatteryCreatedResponse(
                    batteryPackId = batteryPack.id.toString(),
                    message = "Battery pack created successfully"
                )
                
                Response.status(Response.Status.CREATED)
                    .entity(response)
                    .build()
                    
            } catch (e: IllegalStateException) {
                logger.warn("Battery already exists: ${request.batteryPackId}")
                Response.status(Response.Status.CONFLICT)
                    .entity(ErrorResponse(
                        error = "BATTERY_ALREADY_EXISTS",
                        message = e.message ?: "Battery already exists",
                        timestamp = Instant.now().toString()
                    ))
                    .build()
                    
            } catch (e: Exception) {
                logger.error("Failed to create battery", e)
                Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ErrorResponse(
                        error = "INTERNAL_ERROR",
                        message = "Failed to create battery: ${e.message}",
                        timestamp = Instant.now().toString()
                    ))
                    .build()
            }
        }
    }
    
    /**
     * Get battery status
     * 
     * GET /api/v1/batteries/{id}
     * 
     * Returns current battery state reconstructed from events.
     */
    @GET
    @Path("/{id}")
    fun getBattery(@PathParam("id") id: String): Uni<Response> {
        return Uni.createFrom().item {
            try {
                logger.debug("API: Getting battery $id")
                
                val batteryPackId = BatteryPackId.from(id)
                val query = GetBatteryStatusQuery(batteryPackId)
                
                val batteryPack = runBlocking {
                    getBatteryStatusUseCase.execute(query)
                }
                
                if (batteryPack == null) {
                    return@item Response.status(Response.Status.NOT_FOUND)
                        .entity(ErrorResponse(
                            error = "BATTERY_NOT_FOUND",
                            message = "Battery pack not found: $id",
                            timestamp = Instant.now().toString()
                        ))
                        .build()
                }
                
                val response = mapper.toStatusResponse(batteryPack)
                
                Response.ok(response).build()
                
            } catch (e: IllegalArgumentException) {
                Response.status(Response.Status.BAD_REQUEST)
                    .entity(ErrorResponse(
                        error = "INVALID_BATTERY_ID",
                        message = "Invalid battery ID format: $id",
                        timestamp = Instant.now().toString()
                    ))
                    .build()
                    
            } catch (e: Exception) {
                logger.error("Failed to get battery", e)
                Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ErrorResponse(
                        error = "INTERNAL_ERROR",
                        message = "Failed to get battery: ${e.message}",
                        timestamp = Instant.now().toString()
                    ))
                    .build()
            }
        }
    }
    
    /**
     * Record telemetry data
     * 
     * POST /api/v1/batteries/{id}/telemetry
     * 
     * Request body:
     * {
     *   "stateOfCharge": 75.5,
     *   "voltage": 377.2,
     *   "current": -45.3,
     *   "temperatureMin": 28.5,
     *   "temperatureMax": 32.1,
     *   "temperatureAvg": 30.2,
     *   "cellVoltages": [3.29, 3.30, ...]
     * }
     */
    @POST
    @Path("/{id}/telemetry")
    fun recordTelemetry(
        @PathParam("id") id: String,
        request: RecordTelemetryRequest
    ): Uni<Response> {
        return Uni.createFrom().item {
            try {
                logger.debug("API: Recording telemetry for battery $id")
                
                val batteryPackId = BatteryPackId.from(id)
                val command = mapper.toCommand(batteryPackId, request)
                
                runBlocking {
                    recordTelemetryUseCase.execute(command)
                }
                
                val response = TelemetryRecordedResponse(
                    batteryPackId = id,
                    eventsRaised = 1, // At minimum TelemetryRecorded
                    message = "Telemetry recorded successfully"
                )
                
                Response.status(Response.Status.NO_CONTENT).build()
                
            } catch (e: AggregateNotFoundException) {
                Response.status(Response.Status.NOT_FOUND)
                    .entity(ErrorResponse(
                        error = "BATTERY_NOT_FOUND",
                        message = "Battery pack not found: $id",
                        timestamp = Instant.now().toString()
                    ))
                    .build()
                    
            } catch (e: IllegalArgumentException) {
                Response.status(Response.Status.BAD_REQUEST)
                    .entity(ErrorResponse(
                        error = "INVALID_TELEMETRY",
                        message = "Invalid telemetry data: ${e.message}",
                        timestamp = Instant.now().toString()
                    ))
                    .build()
                    
            } catch (e: Exception) {
                logger.error("Failed to record telemetry", e)
                Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ErrorResponse(
                        error = "INTERNAL_ERROR",
                        message = "Failed to record telemetry: ${e.message}",
                        timestamp = Instant.now().toString()
                    ))
                    .build()
            }
        }
    }
    
    /**
     * List all batteries
     * 
     * GET /api/v1/batteries
     * 
     * Returns list of all battery pack IDs in the system.
     */
    @GET
    fun listBatteries(): Uni<Response> {
        return Uni.createFrom().item {
            try {
                logger.debug("API: Listing all batteries")
                
                val ids = runBlocking {
                    listAllBatteriesUseCase.execute()
                }
                
                Response.ok(mapOf(
                    "batteries" to ids,
                    "total" to ids.size
                )).build()
                
            } catch (e: Exception) {
                logger.error("Failed to list batteries", e)
                Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ErrorResponse(
                        error = "INTERNAL_ERROR",
                        message = "Failed to list batteries: ${e.message}",
                        timestamp = Instant.now().toString()
                    ))
                    .build()
            }
        }
    }
    
    /**
     * Get event history for a battery
     * 
     * GET /api/v1/batteries/{id}/events
     * 
     * Returns all events for debugging/audit.
     */
    @GET
    @Path("/{id}/events")
    fun getEvents(@PathParam("id") id: String): Uni<Response> {
        return Uni.createFrom().item {
            try {
                logger.debug("API: Getting events for battery $id")
                
                val batteryPackId = BatteryPackId.from(id)
                val query = GetBatteryStatusQuery(batteryPackId)
                
                val batteryPack = runBlocking {
                    getBatteryStatusUseCase.execute(query)
                }
                
                if (batteryPack == null) {
                    return@item Response.status(Response.Status.NOT_FOUND)
                        .entity(ErrorResponse(
                            error = "BATTERY_NOT_FOUND",
                            message = "Battery pack not found: $id",
                            timestamp = Instant.now().toString()
                        ))
                        .build()
                }
                
                val events = batteryPack.getEventHistory()
                val eventDtos = events.map { mapper.toEventDto(it) }
                
                Response.ok(EventHistoryResponse(
                    batteryPackId = id,
                    events = eventDtos
                )).build()
                
            } catch (e: Exception) {
                logger.error("Failed to get events", e)
                Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ErrorResponse(
                        error = "INTERNAL_ERROR",
                        message = "Failed to get events: ${e.message}",
                        timestamp = Instant.now().toString()
                    ))
                    .build()
            }
        }
    }
}
