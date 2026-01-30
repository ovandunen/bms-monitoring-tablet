package com.fleet.application.usecase.battery

import com.fleet.application.command.*
import com.fleet.domain.battery.model.BatteryPack
import com.fleet.domain.battery.model.StateOfCharge
import com.fleet.domain.battery.repository.BatteryPackRepository
import com.fleet.infrastructure.messaging.event.DomainEventPublisher
import com.fleet.infrastructure.persistence.eventstore.AggregateNotFoundException
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import org.jboss.logging.Logger

/**
 * Create Battery Pack Use Case
 * 
 * Creates a new battery pack in the system.
 * This is typically done during vehicle onboarding or when installing a new battery.
 * 
 * Process:
 * 1. Create aggregate using factory method
 * 2. Save to event store (BatteryPackCreatedEvent persisted)
 * 3. Publish domain event for projections/integrations
 */
@ApplicationScoped
class CreateBatteryPackUseCase(
    private val repository: BatteryPackRepository,
    private val eventPublisher: DomainEventPublisher
) {
    private val logger = Logger.getLogger(CreateBatteryPackUseCase::class.java)
    
    @Transactional
    suspend fun execute(command: CreateBatteryPackCommand): BatteryPack {
        logger.info("Creating battery pack: ${command.batteryPackId}")
        
        // Check if already exists
        val exists = repository.exists(command.batteryPackId).await().indefinitely()
        if (exists) {
            throw IllegalStateException("Battery pack already exists: ${command.batteryPackId}")
        }
        
        // Create aggregate (raises BatteryPackCreatedEvent)
        val batteryPack = BatteryPack.create(
            batteryPackId = command.batteryPackId,
            specifications = command.toSpecifications(),
            initialStateOfCharge = StateOfCharge.of(command.initialStateOfCharge),
            correlationId = command.correlationId
        )
        
        // Get events before saving (peek without clearing)
        val events = batteryPack.peekUncommittedEvents()
        
        // Save to event store
        repository.save(batteryPack).await().indefinitely()
        
        // Publish events for projections (only after successful save)
        events.forEach { event ->
            eventPublisher.publish(event)
        }
        
        logger.info("Successfully created battery pack: ${command.batteryPackId}")
        
        return batteryPack
    }
}

/**
 * Record Telemetry Use Case
 * 
 * Records new telemetry data from BMS.
 * This is the MOST FREQUENT operation in the system (1-10 Hz per vehicle).
 * 
 * Process:
 * 1. Load aggregate from event store
 * 2. Execute business logic (may raise multiple events)
 * 3. Save events to event store
 * 4. Publish events for projections/alerts
 * 
 * Business Rules Enforced:
 * - Battery must not be decommissioned
 * - Telemetry reading must be valid
 * - Critical conditions trigger alert events
 */
@ApplicationScoped
class RecordTelemetryUseCase(
    private val repository: BatteryPackRepository,
    private val eventPublisher: DomainEventPublisher
) {
    private val logger = Logger.getLogger(RecordTelemetryUseCase::class.java)
    
    @Transactional
    suspend fun execute(command: RecordTelemetryCommand) {
        logger.debug("Recording telemetry for battery: ${command.batteryPackId}")
        
        // Load aggregate
        val batteryPack = repository.findById(command.batteryPackId)
            .await().indefinitely()
            ?: throw AggregateNotFoundException(command.batteryPackId.toString())
        
        // Execute domain logic (business rules enforced here)
        // This returns the events that were raised
        val eventsRaised = batteryPack.recordTelemetry(
            reading = command.toTelemetryReading(),
            correlationId = command.correlationId
        )
        
        // Peek at all uncommitted events before saving
        val uncommittedEvents = batteryPack.peekUncommittedEvents()
        
        // Save events
        repository.save(batteryPack).await().indefinitely()
        
        // Publish for projections and alerts (only after successful save)
        uncommittedEvents.forEach { event ->
            eventPublisher.publish(event)
        }
        
        if (eventsRaised.size > 1) {
            // More than just TelemetryRecorded means alerts were raised
            logger.info("Telemetry recorded with ${eventsRaised.size} events (includes alerts)")
        }
    }
}

/**
 * Initiate Battery Replacement Use Case
 * 
 * Starts the battery replacement saga.
 * This is a complex, long-running process that involves:
 * 1. Decommissioning old battery
 * 2. Installing new battery
 * 3. Verifying installation
 * 4. Commissioning new battery
 * 
 * The saga handles failure and compensation.
 */
@ApplicationScoped
class InitiateBatteryReplacementUseCase(
    private val repository: BatteryPackRepository,
    private val eventPublisher: DomainEventPublisher
) {
    private val logger = Logger.getLogger(InitiateBatteryReplacementUseCase::class.java)
    
    @Transactional
    suspend fun execute(command: InitiateBatteryReplacementCommand) {
        logger.info("Initiating battery replacement: ${command.replacementId}")
        
        // Load old battery
        val oldBattery = repository.findById(command.oldBatteryPackId)
            .await().indefinitely()
            ?: throw AggregateNotFoundException(command.oldBatteryPackId.toString())
        
        // Verify new battery exists
        val newBatteryExists = repository.exists(command.newBatteryPackId)
            .await().indefinitely()
        if (!newBatteryExists) {
            throw IllegalStateException("New battery pack not found: ${command.newBatteryPackId}")
        }
        
        // Initiate replacement on old battery (raises event)
        oldBattery.initiateReplacement(
            replacementId = command.replacementId,
            reason = command.reason,
            correlationId = command.correlationId
        )
        
        // Get events before saving
        val events = oldBattery.peekUncommittedEvents()
        
        // Save event
        repository.save(oldBattery).await().indefinitely()
        
        // Publish event (triggers saga) - only after successful save
        events.forEach { event ->
            eventPublisher.publish(event)
        }
        
        logger.info("Battery replacement initiated, saga will continue: ${command.replacementId}")
    }
}

/**
 * Get Battery Status Use Case
 * 
 * Retrieves current battery status by reconstituting from events.
 * This demonstrates how event sourcing works - current state is derived from events.
 */
@ApplicationScoped
class GetBatteryStatusUseCase(
    private val repository: BatteryPackRepository
) {
    private val logger = Logger.getLogger(GetBatteryStatusUseCase::class.java)
    
    suspend fun execute(query: GetBatteryStatusQuery): BatteryPack? {
        logger.debug("Getting battery status: ${query.batteryPackId}")
        
        return repository.findById(query.batteryPackId)
            .await().indefinitely()
    }
}

/**
 * List All Batteries Use Case
 * 
 * Returns list of all battery pack IDs in the system.
 * Useful for admin/monitoring dashboards.
 */
@ApplicationScoped
class ListAllBatteriesUseCase(
    private val repository: BatteryPackRepository
) {
    private val logger = Logger.getLogger(ListAllBatteriesUseCase::class.java)
    
    suspend fun execute(): List<String> {
        logger.debug("Listing all batteries")
        
        val ids = repository.findAllIds().await().indefinitely()
        
        return ids.map { it.toString() }
    }
}
