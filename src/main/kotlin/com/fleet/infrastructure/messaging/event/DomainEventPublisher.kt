package com.fleet.infrastructure.messaging.event

import com.fasterxml.jackson.databind.ObjectMapper
import com.fleet.domain.shared.DomainEvent
import io.smallrye.reactive.messaging.mqtt.MqttMessage
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Emitter
import org.jboss.logging.Logger

/**
 * Domain Event Publisher
 * 
 * Publishes domain events to:
 * 1. Internal event bus (for projections, sagas)
 * 2. External integration via MQTT (for dashboards, analytics)
 * 
 * This is the bridge between domain layer and infrastructure.
 */
@ApplicationScoped
class DomainEventPublisher(
    @Channel("domain-events")
    private val emitter: Emitter<ByteArray>,
    private val objectMapper: ObjectMapper
) {
    private val logger = Logger.getLogger(DomainEventPublisher::class.java)
    
    /**
     * Publish domain event to integration channels
     * 
     * Events are published as JSON over MQTT for:
     * - Dashboard real-time updates
     * - Alert notifications
     * - Analytics systems
     * - Third-party integrations
     */
    fun publish(event: DomainEvent) {
        try {
            // Create integration event DTO
            val integrationEvent = IntegrationEventDto(
                eventId = event.eventId.toString(),
                eventType = event.eventType,
                aggregateId = event.aggregateId,
                aggregateType = event.aggregateType,
                occurredAt = event.occurredAt.toString(),
                data = event.getEventData()
            )
            
            // Serialize to JSON
            val json = objectMapper.writeValueAsBytes(integrationEvent)
            
            // Publish to MQTT
            emitter.send(json)
            
            logger.debug("Published event: ${event.eventType} for aggregate: ${event.aggregateId}")
            
        } catch (e: Exception) {
            logger.error("Failed to publish event: ${event.eventType}", e)
            // Don't throw - publishing to external systems should not fail domain operations
        }
    }
    
    /**
     * Publish multiple events (batch)
     */
    fun publishAll(events: List<DomainEvent>) {
        events.forEach { publish(it) }
    }
}

/**
 * Integration Event DTO
 * 
 * Simplified event representation for external systems.
 * Domain events are rich objects, but external systems need simple JSON.
 */
data class IntegrationEventDto(
    val eventId: String,
    val eventType: String,
    val aggregateId: String,
    val aggregateType: String,
    val occurredAt: String,
    val data: Map<String, Any?>
)
