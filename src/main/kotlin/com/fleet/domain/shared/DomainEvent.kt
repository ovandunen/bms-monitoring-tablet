package com.fleet.domain.shared

import java.time.Instant
import java.util.UUID

/**
 * Base interface for all domain events.
 * Events are immutable facts about things that have happened in the domain.
 * 
 * Following event sourcing principles:
 * - Events are immutable
 * - Events are versioned
 * - Events include metadata (who, when, correlation)
 */
interface DomainEvent {
    val eventId: UUID
    val aggregateId: String
    val aggregateType: String
    val eventType: String
    val eventVersion: Int
    val occurredAt: Instant
    val correlationId: UUID?
    val causationId: UUID?
    
    /**
 * Get event data as map for serialization
     */
    fun getEventData(): Map<String, Any?>
}

/**
 * Base implementation providing common event metadata
 */
abstract class BaseDomainEvent(
    override val eventId: UUID = UUID.randomUUID(),
    override val aggregateId: String,
    override val aggregateType: String,
    override val eventType: String,
    override val eventVersion: Int = 1,
    override val occurredAt: Instant = Instant.now(),
    override val correlationId: UUID? = null,
    override val causationId: UUID? = null
) : DomainEvent
