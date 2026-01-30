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
     * Base version - the version when aggregate was loaded/last saved
     * Used for optimistic concurrency control
     */
    private var baseVersion: Long = 0
    
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
     * Peek at uncommitted events without clearing them.
     * Use this to get events for publishing/saving without removing them.
     */
    fun peekUncommittedEvents(): List<DomainEvent> {
        return uncommittedEvents.toList()
    }
    
    /**
     * Get all uncommitted events and clear the list.
     * Called by repository after persisting events.
     * @deprecated Use peekUncommittedEvents() and markEventsAsCommitted() separately
     */
    @Deprecated("Use peekUncommittedEvents() and markEventsAsCommitted() for safer event handling")
    fun getUncommittedEvents(): List<DomainEvent> {
        return peekUncommittedEvents()
    }
    
    /**
     * Mark events as committed (called by repository after persistence)
     * Only call this after events have been successfully persisted.
     */
    fun markEventsAsCommitted() {
        uncommittedEvents.clear()
        baseVersion = version
    }
    
    /**
     * Get the base version for optimistic concurrency control.
     * This is the version the aggregate had when loaded or last saved.
     */
    fun getBaseVersion(): Long = baseVersion
    
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
        baseVersion = version
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
