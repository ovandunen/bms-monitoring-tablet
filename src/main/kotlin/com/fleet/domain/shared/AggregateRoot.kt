package com.fleet.domain.shared

import java.util.UUID

/**
 * Base class for all Aggregate Roots in the system.
 * 
 * Aggregates are consistency boundaries - all business rules are enforced here.
 * Events are the source of truth (Event Sourcing pattern).
 * 
 * Key principles:
 * - Aggregates are reconstituted from events
 * - All state changes produce events
 * - Events are applied to rebuild state
 * - Optimistic concurrency via version
 */
abstract class AggregateRoot<ID : Any> {
    
    abstract val id: ID
    
    /**
     * Aggregate version for optimistic locking
     * Incremented with each event
     */
    var version: Long = 0
        protected set
    
    /**
     * Uncommitted events that have been raised but not yet persisted
     */
    private val uncommittedEvents = mutableListOf<DomainEvent>()
    
    /**
     * All events that constitute this aggregate (for event sourcing)
     */
    private val eventHistory = mutableListOf<DomainEvent>()
    
    /**
     * Raise a new domain event.
     * The event is added to uncommitted events and applied to aggregate state.
     */
    protected fun raiseEvent(event: DomainEvent) {
        // Apply the event to change aggregate state
        applyEvent(event)
        
        // Track as uncommitted
        uncommittedEvents.add(event)
        
        // Increment version
        version++
    }
    
    /**
     * Apply an event to the aggregate state.
     * This method handles the event and updates the aggregate's state accordingly.
     * Must be implemented by concrete aggregates.
     */
    protected abstract fun applyEvent(event: DomainEvent)
    
    /**
     * Get all uncommitted events and clear the list.
     * Called by repository after persisting events.
     */
    fun getUncommittedEvents(): List<DomainEvent> {
        val events = uncommittedEvents.toList()
        uncommittedEvents.clear()
        return events
    }
    
    /**
     * Mark events as committed (called by repository after persistence)
     */
    fun markEventsAsCommitted() {
        uncommittedEvents.clear()
    }
    
    /**
     * Reconstitute aggregate from event history (Event Sourcing)
     * Called when loading aggregate from event store
     */
    fun loadFromHistory(events: List<DomainEvent>) {
        events.forEach { event ->
            applyEvent(event)
            eventHistory.add(event)
            version++
        }
    }
    
    /**
     * Get complete event history
     */
    fun getEventHistory(): List<DomainEvent> = eventHistory.toList()
    
    /**
     * Check if aggregate has uncommitted changes
     */
    fun hasUncommittedEvents(): Boolean = uncommittedEvents.isNotEmpty()
}

/**
 * Marker interface for Value Objects
 * Value Objects are immutable and compared by value, not identity
 */
interface ValueObject

/**
 * Marker interface for Entities (non-root entities within an aggregate)
 * Entities have identity and lifecycle
 */
interface Entity<ID : Any> {
    val id: ID
}
