package com.fleet.infrastructure.persistence.eventstore

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fleet.domain.battery.event.*
import com.fleet.domain.shared.DomainEvent
import io.smallrye.mutiny.Uni
import io.vertx.mutiny.pgclient.PgPool
import io.vertx.mutiny.sqlclient.Tuple
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.time.Instant

/**
 * TimescaleDB Event Store Implementation
 * 
 * Stores domain events in TimescaleDB hypertable for optimal performance.
 * Events are serialized as JSON for flexibility.
 * 
 * Features:
 * - Optimistic concurrency control via aggregate version
 * - Event serialization/deserialization
 * - Time-series optimization via hypertable
 * - Fast event replay for aggregate reconstruction
 */
@ApplicationScoped
class TimescaleEventStoreImpl(
    private val pgPool: PgPool,
    private val objectMapper: ObjectMapper
) : EventStore {
    
    private val logger = Logger.getLogger(TimescaleEventStoreImpl::class.java)
    
    override suspend fun saveEvents(
        aggregateId: String,
        aggregateType: String,
        events: List<DomainEvent>,
        expectedVersion: Long
    ): Long {
        if (events.isEmpty()) {
            return expectedVersion
        }
        
        // Check current version for optimistic locking
        val currentVersion = getCurrentVersion(aggregateId)
        
        if (currentVersion != expectedVersion) {
            throw ConcurrencyException(
                aggregateId = aggregateId,
                expectedVersion = expectedVersion,
                actualVersion = currentVersion
            )
        }
        
        // Save each event
        var version = expectedVersion
        events.forEach { event ->
            version++
            saveEvent(aggregateId, aggregateType, event, version)
        }
        
        logger.info("Saved ${events.size} events for aggregate $aggregateId, new version: $version")
        return version
    }
    
    private suspend fun saveEvent(
        aggregateId: String,
        aggregateType: String,
        event: DomainEvent,
        version: Long
    ) {
        val eventDataJson = objectMapper.writeValueAsString(event.getEventData())
        
        val query = """
            INSERT INTO event_store (
                event_id,
                aggregate_id,
                aggregate_type,
                aggregate_version,
                event_type,
                event_version,
                event_data,
                occurred_at,
                correlation_id,
                causation_id
            ) VALUES ($1, $2, $3, $4, $5, $6, $7::jsonb, $8, $9, $10)
        """.trimIndent()
        
        pgPool.preparedQuery(query)
            .execute(
                Tuple.of(
                    event.eventId,
                    aggregateId,
                    aggregateType,
                    version,
                    event.eventType,
                    event.eventVersion,
                    eventDataJson,
                    event.occurredAt,
                    event.correlationId,
                    event.causationId
                )
            )
            .await().indefinitely()
    }
    
    override suspend fun getEvents(
        aggregateId: String,
        fromVersion: Long
    ): List<DomainEvent> {
        val query = """
            SELECT 
                event_id,
                aggregate_id,
                aggregate_type,
                aggregate_version,
                event_type,
                event_version,
                event_data,
                occurred_at,
                correlation_id,
                causation_id
            FROM event_store
            WHERE aggregate_id = $1
              AND aggregate_version > $2
            ORDER BY aggregate_version ASC
        """.trimIndent()
        
        val rows = pgPool.preparedQuery(query)
            .execute(Tuple.of(aggregateId, fromVersion))
            .await().indefinitely()
        
        return rows.map { row ->
            deserializeEvent(
                eventType = row.getString("event_type"),
                eventData = row.getString("event_data"),
                eventId = row.getUUID("event_id"),
                aggregateId = row.getString("aggregate_id"),
                occurredAt = row.getOffsetDateTime("occurred_at").toInstant(),
                correlationId = row.getUUID("correlation_id")
            )
        }
    }
    
    override suspend fun getAllEvents(
        aggregateType: String,
        fromSequence: Long
    ): List<DomainEvent> {
        val query = """
            SELECT 
                event_id,
                aggregate_id,
                aggregate_type,
                event_type,
                event_version,
                event_data,
                occurred_at,
                correlation_id,
                causation_id,
                sequence_number
            FROM event_store
            WHERE aggregate_type = $1
              AND sequence_number > $2
            ORDER BY sequence_number ASC
        """.trimIndent()
        
        val rows = pgPool.preparedQuery(query)
            .execute(Tuple.of(aggregateType, fromSequence))
            .await().indefinitely()
        
        return rows.map { row ->
            deserializeEvent(
                eventType = row.getString("event_type"),
                eventData = row.getString("event_data"),
                eventId = row.getUUID("event_id"),
                aggregateId = row.getString("aggregate_id"),
                occurredAt = row.getOffsetDateTime("occurred_at").toInstant(),
                correlationId = row.getUUID("correlation_id")
            )
        }
    }
    
    override suspend fun getEventCount(aggregateId: String): Long {
        val query = """
            SELECT COUNT(*) as count
            FROM event_store
            WHERE aggregate_id = $1
        """.trimIndent()
        
        val row = pgPool.preparedQuery(query)
            .execute(Tuple.of(aggregateId))
            .await().indefinitely()
            .first()
        
        return row.getLong("count")
    }
    
    override suspend fun getAggregateIds(aggregateType: String): List<String> {
        val query = """
            SELECT DISTINCT aggregate_id
            FROM event_store
            WHERE aggregate_type = $1
            ORDER BY aggregate_id
        """.trimIndent()
        
        val rows = pgPool.preparedQuery(query)
            .execute(Tuple.of(aggregateType))
            .await().indefinitely()
        
        return rows.map { row -> row.getString("aggregate_id") }
    }
    
    private suspend fun getCurrentVersion(aggregateId: String): Long {
        val query = """
            SELECT COALESCE(MAX(aggregate_version), 0) as version
            FROM event_store
            WHERE aggregate_id = $1
        """.trimIndent()
        
        val row = pgPool.preparedQuery(query)
            .execute(Tuple.of(aggregateId))
            .await().indefinitely()
            .first()
        
        return row.getLong("version")
    }
    
    /**
     * Deserialize event from JSON based on event type
     * This method knows how to reconstruct domain events from stored JSON
     */
    private fun deserializeEvent(
        eventType: String,
        eventData: String,
        eventId: java.util.UUID,
        aggregateId: String,
        occurredAt: Instant,
        correlationId: java.util.UUID?
    ): DomainEvent {
        val dataMap: Map<String, Any?> = objectMapper.readValue(eventData)
        
        return when (eventType) {
            "BatteryPackCreated" -> deserializeBatteryPackCreatedEvent(dataMap, eventId, occurredAt, correlationId)
            "TelemetryRecorded" -> deserializeTelemetryRecordedEvent(dataMap, eventId, occurredAt, correlationId)
            "BatteryDepleted" -> deserializeBatteryDepletedEvent(dataMap, eventId, occurredAt, correlationId)
            "CriticalTemperature" -> deserializeCriticalTemperatureEvent(dataMap, eventId, occurredAt, correlationId)
            "CellImbalanceDetected" -> deserializeCellImbalanceDetectedEvent(dataMap, eventId, occurredAt, correlationId)
            "ChargingStarted" -> deserializeChargingStartedEvent(dataMap, eventId, occurredAt, correlationId)
            "ChargingCompleted" -> deserializeChargingCompletedEvent(dataMap, eventId, occurredAt, correlationId)
            "ChargingInterrupted" -> deserializeChargingInterruptedEvent(dataMap, eventId, occurredAt, correlationId)
            "BatteryReplacementInitiated" -> deserializeBatteryReplacementInitiatedEvent(dataMap, eventId, occurredAt, correlationId)
            "BatteryDecommissioned" -> deserializeBatteryDecommissionedEvent(dataMap, eventId, occurredAt, correlationId)
            else -> throw IllegalArgumentException("Unknown event type: $eventType")
        }
    }
    
    // Event deserialization methods (implement based on event data structure)
    // These would be expanded based on actual event schemas
    
    private fun deserializeBatteryPackCreatedEvent(
        data: Map<String, Any?>,
        eventId: java.util.UUID,
        occurredAt: Instant,
        correlationId: java.util.UUID?
    ): BatteryPackCreatedEvent {
        return try {
            BatteryPackCreatedEvent(
                batteryPackId = com.fleet.domain.battery.model.BatteryPackId(
                    java.util.UUID.fromString(
                        data["batteryPackId"] as? String 
                            ?: throw IllegalArgumentException("Missing batteryPackId")
                    )
                ),
                specifications = com.fleet.domain.battery.model.BatterySpecifications(
                    manufacturer = data["manufacturer"] as? String 
                        ?: throw IllegalArgumentException("Missing manufacturer"),
                    model = data["model"] as? String 
                        ?: throw IllegalArgumentException("Missing model"),
                    chemistry = com.fleet.domain.battery.model.BatteryChemistry.valueOf(
                        data["chemistry"] as? String 
                            ?: throw IllegalArgumentException("Missing chemistry")
                    ),
                    nominalVoltage = com.fleet.domain.battery.model.Voltage.of(
                        (data["nominalVoltage"] as? Number)?.toDouble() 
                            ?: throw IllegalArgumentException("Missing nominalVoltage")
                    ),
                    capacity = (data["capacity"] as? Number)?.toDouble() 
                        ?: throw IllegalArgumentException("Missing capacity"),
                    cellConfiguration = data["cellConfiguration"] as? String 
                        ?: throw IllegalArgumentException("Missing cellConfiguration")
                ),
                initialStateOfCharge = com.fleet.domain.battery.model.StateOfCharge.of(
                    (data["initialStateOfCharge"] as? Number)?.toDouble() 
                        ?: throw IllegalArgumentException("Missing initialStateOfCharge")
                ),
                eventId = eventId,
                occurredAt = occurredAt,
                correlationId = correlationId
            )
        } catch (e: Exception) {
            logger.error("Failed to deserialize BatteryPackCreatedEvent, data: $data", e)
            throw EventDeserializationException("Cannot deserialize BatteryPackCreatedEvent", e)
        }
    }
    
    private fun deserializeTelemetryRecordedEvent(
        data: Map<String, Any?>,
        eventId: java.util.UUID,
        occurredAt: Instant,
        correlationId: java.util.UUID?
    ): TelemetryRecordedEvent {
        // Deserialize cell voltages from the stored array
        @Suppress("UNCHECKED_CAST")
        val cellVoltagesList = (data["cellVoltages"] as? List<*>)
            ?.map { (it as Number).toDouble() }
            ?: throw IllegalStateException("Cell voltages missing in event data")
        
        val reading = com.fleet.domain.battery.model.TelemetryReading(
            stateOfCharge = com.fleet.domain.battery.model.StateOfCharge.of(
                (data["stateOfCharge"] as Number).toDouble()
            ),
            voltage = com.fleet.domain.battery.model.Voltage.of(
                (data["voltage"] as Number).toDouble()
            ),
            current = com.fleet.domain.battery.model.Current.of(
                (data["current"] as Number).toDouble()
            ),
            temperatureMin = com.fleet.domain.battery.model.Temperature.of(
                (data["temperatureMin"] as Number).toDouble()
            ),
            temperatureMax = com.fleet.domain.battery.model.Temperature.of(
                (data["temperatureMax"] as Number).toDouble()
            ),
            temperatureAvg = com.fleet.domain.battery.model.Temperature.of(
                (data["temperatureAvg"] as Number).toDouble()
            ),
            cellVoltages = com.fleet.domain.battery.model.CellVoltages.of(cellVoltagesList)
        )
        
        return TelemetryRecordedEvent(
            batteryPackId = com.fleet.domain.battery.model.BatteryPackId(
                java.util.UUID.fromString(data["batteryPackId"] as String)
            ),
            reading = reading,
            eventId = eventId,
            occurredAt = occurredAt,
            correlationId = correlationId
        )
    }
    
    private fun deserializeBatteryDepletedEvent(
        data: Map<String, Any?>,
        eventId: java.util.UUID,
        occurredAt: Instant,
        correlationId: java.util.UUID?
    ): BatteryDepletedEvent {
        return BatteryDepletedEvent(
            batteryPackId = com.fleet.domain.battery.model.BatteryPackId(
                java.util.UUID.fromString(data["batteryPackId"] as String)
            ),
            stateOfCharge = com.fleet.domain.battery.model.StateOfCharge.of(
                (data["stateOfCharge"] as Number).toDouble()
            ),
            voltage = com.fleet.domain.battery.model.Voltage.of(
                (data["voltage"] as Number).toDouble()
            ),
            eventId = eventId,
            occurredAt = occurredAt,
            correlationId = correlationId
        )
    }
    
    private fun deserializeCriticalTemperatureEvent(
        data: Map<String, Any?>,
        eventId: java.util.UUID,
        occurredAt: Instant,
        correlationId: java.util.UUID?
    ): CriticalTemperatureEvent {
        return CriticalTemperatureEvent(
            batteryPackId = com.fleet.domain.battery.model.BatteryPackId(
                java.util.UUID.fromString(data["batteryPackId"] as String)
            ),
            temperature = com.fleet.domain.battery.model.Temperature.of(
                (data["temperature"] as Number).toDouble()
            ),
            temperatureType = TemperatureType.valueOf(data["temperatureType"] as String),
            eventId = eventId,
            occurredAt = occurredAt,
            correlationId = correlationId
        )
    }
    
    private fun deserializeCellImbalanceDetectedEvent(
        data: Map<String, Any?>,
        eventId: java.util.UUID,
        occurredAt: Instant,
        correlationId: java.util.UUID?
    ): CellImbalanceDetectedEvent {
        return CellImbalanceDetectedEvent(
            batteryPackId = com.fleet.domain.battery.model.BatteryPackId(
                java.util.UUID.fromString(data["batteryPackId"] as String)
            ),
            cellVoltageDelta = (data["cellVoltageDelta"] as Number).toDouble(),
            cellVoltageMin = (data["cellVoltageMin"] as Number).toDouble(),
            cellVoltageMax = (data["cellVoltageMax"] as Number).toDouble(),
            eventId = eventId,
            occurredAt = occurredAt,
            correlationId = correlationId
        )
    }
    
    private fun deserializeChargingStartedEvent(
        data: Map<String, Any?>,
        eventId: java.util.UUID,
        occurredAt: Instant,
        correlationId: java.util.UUID?
    ): ChargingStartedEvent {
        return ChargingStartedEvent(
            batteryPackId = com.fleet.domain.battery.model.BatteryPackId(
                java.util.UUID.fromString(data["batteryPackId"] as String)
            ),
            stateOfCharge = com.fleet.domain.battery.model.StateOfCharge.of(
                (data["stateOfCharge"] as Number).toDouble()
            ),
            chargingSessionId = java.util.UUID.fromString(data["chargingSessionId"] as String),
            eventId = eventId,
            occurredAt = occurredAt,
            correlationId = correlationId
        )
    }
    
    private fun deserializeChargingCompletedEvent(
        data: Map<String, Any?>,
        eventId: java.util.UUID,
        occurredAt: Instant,
        correlationId: java.util.UUID?
    ): ChargingCompletedEvent {
        return ChargingCompletedEvent(
            batteryPackId = com.fleet.domain.battery.model.BatteryPackId(
                java.util.UUID.fromString(data["batteryPackId"] as String)
            ),
            stateOfCharge = com.fleet.domain.battery.model.StateOfCharge.of(
                (data["stateOfCharge"] as Number).toDouble()
            ),
            chargingSessionId = java.util.UUID.fromString(data["chargingSessionId"] as String),
            durationMinutes = (data["durationMinutes"] as Number).toLong(),
            eventId = eventId,
            occurredAt = occurredAt,
            correlationId = correlationId
        )
    }
    
    private fun deserializeChargingInterruptedEvent(
        data: Map<String, Any?>,
        eventId: java.util.UUID,
        occurredAt: Instant,
        correlationId: java.util.UUID?
    ): ChargingInterruptedEvent {
        return ChargingInterruptedEvent(
            batteryPackId = com.fleet.domain.battery.model.BatteryPackId(
                java.util.UUID.fromString(data["batteryPackId"] as String)
            ),
            stateOfCharge = com.fleet.domain.battery.model.StateOfCharge.of(
                (data["stateOfCharge"] as Number).toDouble()
            ),
            chargingSessionId = java.util.UUID.fromString(data["chargingSessionId"] as String),
            durationMinutes = (data["durationMinutes"] as Number).toLong(),
            eventId = eventId,
            occurredAt = occurredAt,
            correlationId = correlationId
        )
    }
    
    private fun deserializeBatteryReplacementInitiatedEvent(
        data: Map<String, Any?>,
        eventId: java.util.UUID,
        occurredAt: Instant,
        correlationId: java.util.UUID?
    ): BatteryReplacementInitiatedEvent {
        return BatteryReplacementInitiatedEvent(
            batteryPackId = com.fleet.domain.battery.model.BatteryPackId(
                java.util.UUID.fromString(data["batteryPackId"] as String)
            ),
            replacementId = java.util.UUID.fromString(data["replacementId"] as String),
            reason = data["reason"] as String,
            eventId = eventId,
            occurredAt = occurredAt,
            correlationId = correlationId
        )
    }
    
    private fun deserializeBatteryDecommissionedEvent(
        data: Map<String, Any?>,
        eventId: java.util.UUID,
        occurredAt: Instant,
        correlationId: java.util.UUID?
    ): BatteryDecommissionedEvent {
        return BatteryDecommissionedEvent(
            batteryPackId = com.fleet.domain.battery.model.BatteryPackId(
                java.util.UUID.fromString(data["batteryPackId"] as String)
            ),
            replacementId = data["replacementId"]?.let { java.util.UUID.fromString(it as String) },
            finalStateOfCharge = com.fleet.domain.battery.model.StateOfCharge.of(
                (data["finalStateOfCharge"] as Number).toDouble()
            ),
            reason = data["reason"] as String,
            eventId = eventId,
            occurredAt = occurredAt,
            correlationId = correlationId
        )
    }
}
