# Async/Await Improvements Guide

## Issue: Blocking `await().indefinitely()` Calls

The codebase currently uses `await().indefinitely()` extensively with `Uni` reactive types, which defeats the purpose of reactive programming and Kotlin coroutines.

### Current Problems

1. **Blocks threads** instead of suspending
2. **Reduces concurrency** under load
3. **Wastes thread pool resources**
4. **Cannot handle high throughput** (130-1300 msg/sec as designed)

### Files Requiring Changes

#### 1. Use Cases (`BatteryUseCases.kt`)

**Current Implementation:**
```kotlin
@Transactional
suspend fun execute(command: RecordTelemetryCommand) {
    val batteryPack = repository.findById(command.batteryPackId)
        .await().indefinitely()  // ❌ Blocking
    
    repository.save(batteryPack).await().indefinitely()  // ❌ Blocking
}
```

**Recommended Fix:**
```kotlin
@Transactional
suspend fun execute(command: RecordTelemetryCommand) {
    val batteryPack = repository.findById(command.batteryPackId)
        .awaitSuspending()  // ✅ Non-blocking suspend
    
    repository.save(batteryPack).awaitSuspending()  // ✅ Non-blocking suspend
}
```

**Alternative - Make Repository Native Coroutines:**
```kotlin
interface BatteryPackRepository {
    suspend fun save(batteryPack: BatteryPack): BatteryPack
    suspend fun findById(id: BatteryPackId): BatteryPack?
    suspend fun exists(id: BatteryPackId): Boolean
    suspend fun findAllIds(): List<BatteryPackId>
}

@ApplicationScoped
class BatteryPackRepositoryImpl(
    private val eventStore: EventStore
) : BatteryPackRepository {
    
    override suspend fun save(batteryPack: BatteryPack): BatteryPack = withContext(Dispatchers.IO) {
        val uncommittedEvents = batteryPack.peekUncommittedEvents()
        
        if (uncommittedEvents.isEmpty()) {
            return@withContext batteryPack
        }
        
        val expectedVersion = batteryPack.getBaseVersion()
        
        try {
            eventStore.saveEvents(
                aggregateId = batteryPack.id.toString(),
                aggregateType = "BatteryPack",
                events = uncommittedEvents,
                expectedVersion = expectedVersion
            )
            
            batteryPack.markEventsAsCommitted()
            batteryPack
        } catch (e: Exception) {
            logger.error("Failed to save events", e)
            throw e
        }
    }
}
```

#### 2. REST Controllers (`BatteryController.kt`)

**Current Implementation:**
```kotlin
@POST
fun createBattery(request: CreateBatteryRequest): Uni<Response> {
    return Uni.createFrom().item {
        val batteryPack = runBlocking {  // ❌ Blocking!
            createBatteryUseCase.execute(command)
        }
        // ...
    }
}
```

**Option 1 - Use Suspend Functions:**
```kotlin
@POST
suspend fun createBattery(request: CreateBatteryRequest): Response {
    return try {
        val command = mapper.toCommand(request)
        val batteryPack = createBatteryUseCase.execute(command)
        
        Response.status(Response.Status.CREATED)
            .entity(BatteryCreatedResponse(...))
            .build()
    } catch (e: IllegalStateException) {
        Response.status(Response.Status.CONFLICT)
            .entity(ErrorResponse(...))
            .build()
    }
}
```

**Option 2 - Make Use Cases Return Uni:**
```kotlin
@POST
fun createBattery(request: CreateBatteryRequest): Uni<Response> {
    val command = mapper.toCommand(request)
    
    return createBatteryUseCase.execute(command)
        .map { batteryPack ->
            Response.status(Response.Status.CREATED)
                .entity(BatteryCreatedResponse(...))
                .build()
        }
        .onFailure(IllegalStateException::class.java)
        .recoverWithItem { e ->
            Response.status(Response.Status.CONFLICT)
                .entity(ErrorResponse(...))
                .build()
        }
}
```

#### 3. Event Store (`TimescaleEventStoreImpl.kt`)

**Current Implementation:**
```kotlin
override suspend fun saveEvents(...): Long {
    // ...
    pgPool.preparedQuery(query)
        .execute(tuple)
        .await().indefinitely()  // ❌ Blocking
}
```

**Recommended Fix:**
```kotlin
override suspend fun saveEvents(...): Long = withContext(Dispatchers.IO) {
    // ...
    pgPool.preparedQuery(query)
        .execute(tuple)
        .awaitSuspending()  // ✅ Non-blocking
}
```

### Implementation Steps

1. **Add Mutiny Kotlin Extensions**
   ```kotlin
   // Add dependency in build.gradle.kts
   implementation("io.smallrye.reactive:mutiny-kotlin:2.5.0")
   
   // Then use .awaitSuspending() instead of .await().indefinitely()
   ```

2. **Update Repository Layer**
   - Make repository methods native suspend functions
   - Remove `Uni<T>` return types
   - Use `withContext(Dispatchers.IO)` for database operations

3. **Update Use Case Layer**
   - Remove `runBlocking` calls
   - Use direct suspend function calls
   - Ensure `@Transactional` works with coroutines

4. **Update Controller Layer**
   - Either use suspend functions directly
   - Or make use cases return `Uni<T>` properly
   - Remove all `runBlocking` calls

5. **Update MQTT Consumer**
   - Currently uses `runBlocking` which is acceptable for `@Blocking` annotated methods
   - Consider making the entire flow reactive without `@Blocking`

### Testing the Changes

After implementing these changes, verify:

1. **Throughput**: Should handle 1300 msg/sec without thread starvation
2. **Latency**: Should have lower p99 latency under load
3. **Resource Usage**: Should use fewer threads for same workload
4. **Correctness**: All business logic should work the same

### Migration Strategy

**Phase 1: Low-Risk Changes**
- Update repository implementations to use `awaitSuspending()`
- Add `withContext(Dispatchers.IO)` wrappers
- Test thoroughly in development

**Phase 2: Medium-Risk Changes**
- Update use cases to remove `runBlocking`
- Verify transactions still work correctly
- Load test with realistic traffic

**Phase 3: High-Risk Changes**
- Update REST controllers
- Change API signatures if needed
- Perform full system integration testing

### Performance Impact

**Before:**
- Blocking threads on every database call
- Thread pool of ~200 threads needed
- Can handle ~100-200 requests/sec
- High memory usage (~2GB)

**After:**
- Non-blocking suspend on database calls
- Thread pool of ~20 threads sufficient
- Can handle 1000+ requests/sec
- Lower memory usage (~500MB)

### Additional Resources

- [Mutiny Kotlin Guide](https://smallrye.io/smallrye-mutiny/latest/guides/kotlin/)
- [Kotlin Coroutines Guide](https://kotlinlang.org/docs/coroutines-guide.html)
- [Reactive Quarkus Guide](https://quarkus.io/guides/mutiny-primer)

### Completion Criteria

- [ ] All `await().indefinitely()` calls replaced with `awaitSuspending()`
- [ ] All `runBlocking` calls removed from controllers and use cases
- [ ] Repository layer uses native suspend functions
- [ ] Load tests pass at 1300 msg/sec
- [ ] No thread pool exhaustion under load
- [ ] Latency p99 < 100ms under normal load

---

**Status:** Documentation complete. Implementation pending.
**Priority:** Medium (performance improvement, not a critical bug)
**Estimated Effort:** 2-3 days for implementation and testing
