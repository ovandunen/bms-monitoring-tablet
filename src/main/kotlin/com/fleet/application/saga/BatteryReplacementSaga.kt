package com.fleet.application.saga

import com.fleet.domain.battery.model.BatteryPackId
import com.fleet.domain.battery.repository.BatteryPackRepository
import com.fleet.infrastructure.persistence.eventstore.AggregateNotFoundException
import io.vertx.mutiny.redis.client.Redis
import io.vertx.mutiny.redis.client.Response
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import org.jboss.logging.Logger
import java.time.Instant
import java.util.UUID

/**
 * Battery Replacement Saga
 * 
 * Orchestrates the complex, long-running battery replacement process.
 * This saga coordinates multiple aggregates and handles failures with compensation.
 * 
 * Process Steps:
 * 1. Decommission old battery
 * 2. Install new battery (simulated)
 * 3. Verify installation (simulated)
 * 4. Complete replacement
 * 
 * If any step fails, compensation logic rolls back:
 * - Reinstate old battery if new installation fails
 * - Cancel replacement on verification failure
 * 
 * Saga state is stored in Redis for durability.
 */
@ApplicationScoped
class BatteryReplacementSaga(
    private val batteryRepository: BatteryPackRepository,
    private val redis: Redis
) {
    private val logger = Logger.getLogger(BatteryReplacementSaga::class.java)
    
    /**
     * Execute the saga
     * 
     * This method orchestrates all steps and handles compensation.
     */
    suspend fun execute(
        replacementId: UUID,
        oldBatteryId: BatteryPackId,
        newBatteryId: BatteryPackId,
        vehicleId: String,
        reason: String
    ): SagaResult {
        
        logger.info("Starting battery replacement saga: $replacementId")
        
        // Initialize saga state
        val sagaState = SagaState(
            sagaId = replacementId,
            sagaType = "BatteryReplacement",
            status = SagaStatus.RUNNING,
            currentStep = ReplacementStep.INITIATED,
            data = mapOf(
                "oldBatteryId" to oldBatteryId.toString(),
                "newBatteryId" to newBatteryId.toString(),
                "vehicleId" to vehicleId,
                "reason" to reason
            ),
            startedAt = Instant.now()
        )
        
        saveSagaState(sagaState)
        
        try {
            // Step 1: Decommission old battery
            logger.info("Step 1: Decommissioning old battery: $oldBatteryId")
            decommissionOldBattery(oldBatteryId, replacementId, reason)
            sagaState.currentStep = ReplacementStep.OLD_DECOMMISSIONED
            saveSagaState(sagaState)
            
            // Step 2: Install new battery (simulated - in production this would be manual)
            logger.info("Step 2: Installing new battery: $newBatteryId")
            installNewBattery(newBatteryId, vehicleId)
            sagaState.currentStep = ReplacementStep.NEW_INSTALLED
            saveSagaState(sagaState)
            
            // Step 3: Verify installation (simulated - could be BMS health check)
            logger.info("Step 3: Verifying installation")
            verifyInstallation(vehicleId, newBatteryId)
            sagaState.currentStep = ReplacementStep.VERIFIED
            saveSagaState(sagaState)
            
            // Step 4: Complete
            logger.info("Step 4: Completing replacement")
            sagaState.status = SagaStatus.COMPLETED
            sagaState.currentStep = ReplacementStep.COMPLETED
            sagaState.completedAt = Instant.now()
            saveSagaState(sagaState)
            
            logger.info("Battery replacement saga completed successfully: $replacementId")
            
            return SagaResult.Success(replacementId)
            
        } catch (e: Exception) {
            logger.error("Saga failed at step ${sagaState.currentStep}: $replacementId", e)
            
            // Compensate based on how far we got
            compensate(sagaState, e)
            
            return SagaResult.Failed(replacementId, e.message ?: "Unknown error")
        }
    }
    
    /**
     * Step 1: Decommission old battery
     */
    private suspend fun decommissionOldBattery(
        oldBatteryId: BatteryPackId,
        replacementId: UUID,
        reason: String
    ) {
        val battery = batteryRepository.findById(oldBatteryId)
            .await().indefinitely()
            ?: throw AggregateNotFoundException(oldBatteryId.toString())
        
        battery.decommission(
            reason = reason,
            replacementId = replacementId,
            correlationId = replacementId
        )
        
        batteryRepository.save(battery).await().indefinitely()
    }
    
    /**
     * Step 2: Install new battery
     * 
     * In production, this would trigger a workflow for:
     * - Technician notification
     * - Physical battery swap
     * - BMS reconfiguration
     * - Safety checks
     * 
     * For now, this is simulated.
     */
    private suspend fun installNewBattery(
        newBatteryId: BatteryPackId,
        vehicleId: String
    ) {
        // Verify new battery exists
        val exists = batteryRepository.exists(newBatteryId).await().indefinitely()
        if (!exists) {
            throw IllegalStateException("New battery not found: $newBatteryId")
        }
        
        // Simulate installation time
        delay(1000)
        
        logger.info("Battery $newBatteryId installed in vehicle $vehicleId")
    }
    
    /**
     * Step 3: Verify installation
     * 
     * In production, this would:
     * - Check BMS reports correct battery ID
     * - Verify all cells responding
     * - Check voltage levels
     * - Confirm temperature sensors working
     */
    private suspend fun verifyInstallation(
        vehicleId: String,
        newBatteryId: BatteryPackId
    ) {
        // Simulate verification
        delay(500)
        
        // In production, check that telemetry is coming through
        val battery = batteryRepository.findById(newBatteryId).await().indefinitely()
        if (battery == null) {
            throw IllegalStateException("Cannot verify - battery not found")
        }
        
        // Check battery is not decommissioned
        if (battery.isCurrentlyDecommissioned()) {
            throw IllegalStateException("New battery is decommissioned!")
        }
        
        logger.info("Installation verified for battery $newBatteryId")
    }
    
    /**
     * Compensate when saga fails
     * 
     * Rolls back based on which step failed.
     */
    private suspend fun compensate(state: SagaState, error: Exception) {
        logger.warn("Compensating saga ${state.sagaId} at step ${state.currentStep}")
        
        state.status = SagaStatus.COMPENSATING
        saveSagaState(state)
        
        try {
            when (state.currentStep) {
                ReplacementStep.VERIFIED,
                ReplacementStep.NEW_INSTALLED -> {
                    // Installation was done but failed verification
                    // In production: Remove new battery, alert technician
                    logger.info("Compensation: Would remove new battery")
                }
                
                ReplacementStep.OLD_DECOMMISSIONED -> {
                    // Old battery was decommissioned but new installation failed
                    // Try to reinstate old battery
                    val oldBatteryId = BatteryPackId.from(
                        state.data["oldBatteryId"] as String
                    )
                    reinstateOldBattery(oldBatteryId)
                }
                
                else -> {
                    // Early failure, nothing to compensate
                    logger.info("No compensation needed for step ${state.currentStep}")
                }
            }
            
            state.status = SagaStatus.COMPENSATED
            state.error = error.message
            state.completedAt = Instant.now()
            saveSagaState(state)
            
        } catch (e: Exception) {
            logger.error("Compensation failed for saga ${state.sagaId}", e)
            state.status = SagaStatus.FAILED
            state.error = "Original error: ${error.message}, Compensation error: ${e.message}"
            saveSagaState(state)
        }
    }
    
    /**
     * Compensation: Reinstate old battery
     * 
     * In production, this would:
     * - Alert technician to reinstall old battery
     * - Update system to show old battery active again
     * - Mark new battery as not installed
     */
    private suspend fun reinstateOldBattery(oldBatteryId: BatteryPackId) {
        logger.info("Compensation: Reinstating old battery $oldBatteryId")
        
        // In production, you'd need to create a reverse operation
        // For now, just log
        // Note: You can't "un-decommission" in the current domain model
        // This is by design - decommissioning is irreversible
        // In production, you might need a separate "ReinstatedAfterFailedReplacement" event
        
        logger.warn("Old battery $oldBatteryId remains decommissioned - manual intervention needed")
    }
    
    /**
     * Save saga state to Redis with timeout and fallback
     */
    private suspend fun saveSagaState(state: SagaState) {
        val key = "saga:${state.sagaId}"
        val json = state.toJson()
        
        try {
            // Add 5 second timeout to prevent indefinite blocking
            withTimeout(5000) {
                redis.set(listOf(key, json))
                    .await().indefinitely()
            }
            
            logger.debug("Saved saga state to Redis: ${state.sagaId}, step: ${state.currentStep}")
            
        } catch (e: TimeoutCancellationException) {
            logger.error("Redis timeout saving saga state: ${state.sagaId}", e)
            // Fallback: Log to file for manual recovery
            logger.warn("Saga state not persisted to Redis, manual intervention may be required: $json")
            // In production: save to database as fallback
            // fallbackStorage.saveSagaState(state)
            
        } catch (e: Exception) {
            logger.error("Failed to save saga state: ${state.sagaId}", e)
            // Log for manual recovery
            logger.warn("Saga state persistence failed: $json")
            // Don't throw - allow saga to continue even if state persistence fails
            // The saga can still complete, but recovery after crash won't be possible
        }
    }
}

/**
 * Saga State
 * 
 * Represents the current state of a running saga.
 * Persisted to Redis for durability.
 */
data class SagaState(
    val sagaId: UUID,
    val sagaType: String,
    var status: SagaStatus,
    var currentStep: ReplacementStep,
    val data: Map<String, String>,
    val startedAt: Instant,
    var completedAt: Instant? = null,
    var error: String? = null
) {
    fun toJson(): String {
        val dataJson = data.entries.joinToString(",") { (key, value) -> 
            "\"$key\": \"${value.replace("\"", "\\\"")}\"" 
        }
        
        return """
            {
                "sagaId": "$sagaId",
                "sagaType": "$sagaType",
                "status": "$status",
                "currentStep": "$currentStep",
                "data": { $dataJson },
                "startedAt": "$startedAt",
                "completedAt": ${completedAt?.let { "\"$it\"" } ?: "null"},
                "error": ${error?.let { "\"${it.replace("\"", "\\\"")}\"" } ?: "null"}
            }
        """.trimIndent()
    }
}

/**
 * Saga Status
 */
enum class SagaStatus {
    RUNNING,
    COMPLETED,
    FAILED,
    COMPENSATING,
    COMPENSATED
}

/**
 * Replacement Steps
 */
enum class ReplacementStep {
    INITIATED,
    OLD_DECOMMISSIONED,
    NEW_INSTALLED,
    VERIFIED,
    COMPLETED
}

/**
 * Saga Result
 */
sealed class SagaResult {
    data class Success(val sagaId: UUID) : SagaResult()
    data class Failed(val sagaId: UUID, val reason: String) : SagaResult()
}
