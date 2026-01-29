package com.fleet.infrastructure.persistence.eventstore

import com.fleet.domain.shared.DomainEvent
import io.smallrye.mutiny.Uni

/**
 * Event Store Interface
 * 
 * Responsible for persisting and retrieving domain events.
 * This is the foundation of event sourcing - events are the source of truth.
 * 
 * Implementation uses TimescaleDB for optimal time-series performance.
 */
interface EventStore {
    
    /**
     * Save events for an aggregate
     * 
     * @param aggregateId The aggregate identifier
     * @param aggregateType Type of aggregate (e.g., "BatteryPack")
     * @param events List of events to save
     * @param expectedVersion Expected version for optimistic concurrency
     * @return The new version number after saving
     * @throws ConcurrencyException if version mismatch detected
     */
    suspend fun saveEvents(
        aggregateId: String,
        aggregateType: String,
        events: List<DomainEvent>,
        expectedVersion: Long
    ): Long
    
    /**
     * Get all events for an aggregate
     * 
     * @param aggregateId The aggregate identifier
     * @param fromVersion Start from this version (default: 0 = all events)
     * @return List of events in order
     */
    suspend fun getEvents(
        aggregateId: String,
        fromVersion: Long = 0
    ): List<DomainEvent>
    
    /**
     * Get all events for an aggregate type
     * Useful for rebuilding projections
     * 
     * @param aggregateType Type of aggregate
     * @param fromSequence Start from this sequence number
     * @return List of events across all aggregates of this type
     */
    suspend fun getAllEvents(
        aggregateType: String,
        fromSequence: Long = 0
    ): List<DomainEvent>
    
    /**
     * Get event count for an aggregate
     * Useful for determining if aggregate exists
     * 
     * @param aggregateId The aggregate identifier
     * @return Number of events
     */
    suspend fun getEventCount(aggregateId: String): Long
    
    /**
     * Check if aggregate exists
     * 
     * @param aggregateId The aggregate identifier
     * @return true if aggregate has events
     */
    suspend fun exists(aggregateId: String): Boolean = getEventCount(aggregateId) > 0
}

/**
 * Exception thrown when optimistic concurrency conflict detected
 */
class ConcurrencyException(
    val aggregateId: String,
    val expectedVersion: Long,
    val actualVersion: Long
) : RuntimeException(
    "Concurrency conflict for aggregate $aggregateId: " +
    "expected version $expectedVersion, but actual version is $actualVersion"
)

/**
 * Exception thrown when aggregate not found
 */
class AggregateNotFoundException(
    val aggregateId: String
) : RuntimeException("Aggregate not found: $aggregateId")
