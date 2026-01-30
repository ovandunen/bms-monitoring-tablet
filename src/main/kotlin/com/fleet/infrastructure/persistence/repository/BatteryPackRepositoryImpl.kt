package com.fleet.infrastructure.persistence.repository

import com.fleet.domain.battery.model.BatteryPack
import com.fleet.domain.battery.model.BatteryPackId
import com.fleet.domain.battery.repository.BatteryPackRepository
import com.fleet.infrastructure.persistence.eventstore.AggregateNotFoundException
import com.fleet.infrastructure.persistence.eventstore.EventStore
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger

/**
 * BatteryPack Repository Implementation
 * 
 * Implements repository using event sourcing pattern.
 * - save() persists uncommitted events to event store
 * - findById() reconstitutes aggregate from event history
 * 
 * This is a perfect example of how event sourcing works:
 * 1. Aggregate raises events when business logic executes
 * 2. Repository saves events (source of truth)
 * 3. To load aggregate, replay all events
 */
@ApplicationScoped
class BatteryPackRepositoryImpl(
    private val eventStore: EventStore
) : BatteryPackRepository {
    
    private val logger = Logger.getLogger(BatteryPackRepositoryImpl::class.java)
    
    override fun save(batteryPack: BatteryPack): Uni<BatteryPack> {
        return Uni.createFrom().item {
            // Peek at uncommitted events without clearing them
            val uncommittedEvents = batteryPack.peekUncommittedEvents()
            
            if (uncommittedEvents.isEmpty()) {
                logger.debug("No uncommitted events for battery ${batteryPack.id}")
                return@item batteryPack
            }
            
            logger.info("Saving ${uncommittedEvents.size} events for battery ${batteryPack.id}")
            
            // Use base version for optimistic locking (version when loaded/last saved)
            val expectedVersion = batteryPack.getBaseVersion()
            
            try {
                // Save events to event store
                val newVersion = eventStore.saveEvents(
                    aggregateId = batteryPack.id.toString(),
                    aggregateType = "BatteryPack",
                    events = uncommittedEvents,
                    expectedVersion = expectedVersion
                )
                
                // Only mark events as committed after successful save
                batteryPack.markEventsAsCommitted()
                
                logger.info("Successfully saved events, new version: $newVersion")
                
                batteryPack
            } catch (e: Exception) {
                logger.error("Failed to save events for battery ${batteryPack.id}", e)
                // Events remain uncommitted so they can be retried
                throw e
            }
        }
    }
    
    override fun findById(id: BatteryPackId): Uni<BatteryPack?> {
        return Uni.createFrom().item {
            logger.debug("Loading battery pack: $id")
            
            // Load events from event store
            val events = eventStore.getEvents(
                aggregateId = id.toString(),
                fromVersion = 0
            )
            
            if (events.isEmpty()) {
                logger.debug("No events found for battery pack: $id")
                return@item null
            }
            
            logger.info("Loaded ${events.size} events for battery pack: $id")
            
            // Reconstitute aggregate from events
            val batteryPack = BatteryPack.fromHistory(id, events)
            
            logger.debug("Reconstituted battery pack: $id, version: ${batteryPack.version}")
            
            batteryPack
        }
    }
    
    override fun exists(id: BatteryPackId): Uni<Boolean> {
        return Uni.createFrom().item {
            eventStore.exists(id.toString())
        }
    }
    
    override fun findAllIds(): Uni<List<BatteryPackId>> {
        return Uni.createFrom().item {
            // Use efficient query to get only distinct aggregate IDs
            // Much more performant than loading all events
            val aggregateIds = eventStore.getAggregateIds("BatteryPack")
            
            val ids = aggregateIds.map { BatteryPackId.from(it) }
            
            logger.info("Found ${ids.size} battery packs")
            
            ids
        }
    }
}
