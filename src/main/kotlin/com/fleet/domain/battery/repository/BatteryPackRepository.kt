package com.fleet.domain.battery.repository

import com.fleet.domain.battery.model.BatteryPack
import com.fleet.domain.battery.model.BatteryPackId
import io.smallrye.mutiny.Uni

/**
 * Battery Pack Repository Interface
 * 
 * Repository pattern - interface in domain, implementation in infrastructure.
 * This maintains clean architecture and dependency inversion.
 * 
 * Event Sourcing Considerations:
 * - save() persists new events to event store
 * - findById() reconstitutes aggregate from event history
 * - Repository handles optimistic concurrency via aggregate version
 */
interface BatteryPackRepository {
    
    /**
     * Save battery pack (persist uncommitted events)
     * 
     * @param batteryPack The aggregate to save
     * @return Saved battery pack with updated version
     * @throws ConcurrencyException if version conflict detected
     */
    fun save(batteryPack: BatteryPack): Uni<BatteryPack>
    
    /**
     * Find battery pack by ID (reconstitute from events)
     * 
     * @param id The battery pack ID
     * @return Battery pack if found, null otherwise
     */
    fun findById(id: BatteryPackId): Uni<BatteryPack?>
    
    /**
     * Check if battery pack exists
     * 
     * @param id The battery pack ID
     * @return true if exists, false otherwise
     */
    fun exists(id: BatteryPackId): Uni<Boolean>
    
    /**
     * Get all battery pack IDs (for admin/reporting)
     * Note: This doesn't load full aggregates, just IDs
     * 
     * @return List of battery pack IDs
     */
    fun findAllIds(): Uni<List<BatteryPackId>>
}

/**
 * Exception thrown when optimistic concurrency conflict is detected
 */
class ConcurrencyException(
    val aggregateId: String,
    val expectedVersion: Long,
    val actualVersion: Long
) : RuntimeException(
    "Concurrency conflict for aggregate $aggregateId: " +
    "expected version $expectedVersion, but actual version is $actualVersion"
)
