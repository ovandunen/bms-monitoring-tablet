# Fleet DDD System - Quick Start Guide

## 🚀 Get Running in 10 Minutes

### Prerequisites
- Java 17+
- Docker & Docker Compose
- Your favorite IDE (IntelliJ IDEA recommended)

---

## Step 1: Start Infrastructure (2 minutes)

```bash
cd fleet-ddd-system
docker-compose up -d
```

**Verify services are running:**
```bash
docker-compose ps

# Should see:
# - fleet-timescaledb (port 5432)
# - fleet-emqx (ports 1883, 18083)
# - fleet-redis (port 6379)
# - fleet-prometheus (port 9090)
# - fleet-grafana (port 3000)
```

**Access dashboards:**
- EMQX Dashboard: http://localhost:18083 (admin/public)
- Grafana: http://localhost:3000 (admin/admin)
- Prometheus: http://localhost:9090
- pgAdmin: http://localhost:5050 (admin@fleet.local/admin)

---

## Step 2: Build Project (1 minute)

```bash
./gradlew build
```

---

## Step 3: Run Database Migrations (30 seconds)

```bash
./gradlew flywayMigrate
```

This creates:
- Event store (event_store hypertable)
- Aggregate snapshots table
- Saga state table
- Projections (battery_pack_projection, telemetry_history)
- Continuous aggregates (telemetry_hourly)

**Verify migration:**
```bash
docker exec -it fleet-timescaledb psql -U fleetuser -d fleetdb -c "\dt"
```

---

## Step 4: Start Application (1 minute)

```bash
./gradlew quarkusDev
```

**Application will start on port 8080**

**Verify health:**
```bash
curl http://localhost:8080/health

# Should return:
# {
#   "status": "UP",
#   "checks": [...]
# }
```

---

## Step 5: Test the System (5 minutes)

### Create a Battery Pack

```bash
curl -X POST http://localhost:8080/api/v1/batteries \
  -H "Content-Type: application/json" \
  -d '{
    "batteryPackId": "550e8400-e29b-41d4-a716-446655440000",
    "manufacturer": "BYD",
    "model": "Blade Battery",
    "chemistry": "LFP",
    "nominalVoltage": 377.6,
    "capacity": 60.0,
    "cellConfiguration": "114S1P",
    "initialStateOfCharge": 80.0
  }'

# Response: 201 Created
```

### Record Telemetry

```bash
curl -X POST http://localhost:8080/api/v1/batteries/550e8400-e29b-41d4-a716-446655440000/telemetry \
  -H "Content-Type: application/json" \
  -d '{
    "stateOfCharge": 75.5,
    "voltage": 377.2,
    "current": -45.3,
    "temperatureMin": 28.5,
    "temperatureMax": 32.1,
    "temperatureAvg": 30.2,
    "cellVoltages": [
      3.29, 3.30, 3.31, 3.29, 3.30, 3.31, 3.29, 3.30, 3.31, 3.29,
      3.30, 3.31, 3.29, 3.30, 3.31, 3.29, 3.30, 3.31, 3.29, 3.30,
      3.31, 3.29, 3.30, 3.31, 3.29, 3.30, 3.31, 3.29, 3.30, 3.31,
      3.29, 3.30, 3.31, 3.29, 3.30, 3.31, 3.29, 3.30, 3.31, 3.29,
      3.30, 3.31, 3.29, 3.30, 3.31, 3.29, 3.30, 3.31, 3.29, 3.30,
      3.31, 3.29, 3.30, 3.31, 3.29, 3.30, 3.31, 3.29, 3.30, 3.31,
      3.29, 3.30, 3.31, 3.29, 3.30, 3.31, 3.29, 3.30, 3.31, 3.29,
      3.30, 3.31, 3.29, 3.30, 3.31, 3.29, 3.30, 3.31, 3.29, 3.30,
      3.31, 3.29, 3.30, 3.31, 3.29, 3.30, 3.31, 3.29, 3.30, 3.31,
      3.29, 3.30, 3.31, 3.29, 3.30, 3.31, 3.29, 3.30, 3.31, 3.29,
      3.30, 3.31, 3.29, 3.30, 3.31, 3.29, 3.30, 3.31, 3.29, 3.30,
      3.31, 3.29, 3.30, 3.31
    ]
  }'

# Response: 204 No Content
```

### Get Battery Status

```bash
curl http://localhost:8080/api/v1/batteries/550e8400-e29b-41d4-a716-446655440000

# Response: Battery details with current state
```

### View Event History (Event Sourcing)

```bash
curl http://localhost:8080/api/v1/batteries/550e8400-e29b-41d4-a716-446655440000/events

# Response: All events for this battery
# [
#   {"eventType": "BatteryPackCreated", ...},
#   {"eventType": "TelemetryRecorded", ...}
# ]
```

---

## Verify Event Sourcing

### Check events were persisted:
```bash
docker exec -it fleet-timescaledb psql -U fleetuser -d fleetdb

SELECT 
    event_type, 
    aggregate_version, 
    occurred_at 
FROM event_store 
WHERE aggregate_id = '550e8400-e29b-41d4-a716-446655440000'
ORDER BY aggregate_version;

# Should see:
# event_type            | aggregate_version | occurred_at
# ----------------------+-------------------+-------------------------
# BatteryPackCreated    |                 1 | 2026-01-26 20:15:32.123
# TelemetryRecorded     |                 2 | 2026-01-26 20:16:45.456
```

---

## Test MQTT Integration

### Publish telemetry via MQTT:

```bash
# Install mosquitto client
sudo apt install mosquitto-clients  # Linux
brew install mosquitto  # Mac

# Publish message
mosquitto_pub -h localhost -p 1883 \
  -u backend -P backend123 \
  -t "fleet/vehicle_001/bms/telemetry" \
  -m '{
    "batteryPackId": "550e8400-e29b-41d4-a716-446655440000",
    "stateOfCharge": 70.0,
    "voltage": 375.5,
    "current": -40.0,
    "temperatureMin": 29.0,
    "temperatureMax": 33.0,
    "temperatureAvg": 31.0,
    "cellVoltages": [3.29, 3.30, ...]
  }'
```

**Check application logs** - should see:
```
INFO [com.fle.inf.mes.mq.MqttTelemetryConsumer] Processed telemetry for battery: 550e8400...
```

---

## Test Saga (Battery Replacement)

```bash
curl -X POST http://localhost:8080/api/v1/batteries/battery-replacement \
  -H "Content-Type: application/json" \
  -d '{
    "oldBatteryId": "550e8400-e29b-41d4-a716-446655440000",
    "newBatteryId": "660e8400-e29b-41d4-a716-446655440000",
    "vehicleId": "vehicle_001",
    "reason": "End of life"
  }'

# Response: 202 Accepted (saga running)
```

**Check saga state:**
```bash
docker exec -it fleet-timescaledb psql -U fleetuser -d fleetdb

SELECT 
    saga_id, 
    saga_type, 
    current_step, 
    status 
FROM saga_state 
ORDER BY started_at DESC 
LIMIT 1;
```

---

## Monitoring

### Prometheus Metrics
```bash
curl http://localhost:8080/metrics

# Shows:
# - JVM metrics
# - HTTP request metrics
# - Database connection pool
# - Custom business metrics
```

### View in Grafana

1. Open http://localhost:3000
2. Login: admin/admin
3. Navigate to Dashboards
4. Import dashboard (example dashboards in monitoring/)

---

## Common Issues

### Issue: Database connection failed

**Solution:**
```bash
docker-compose restart timescaledb
# Wait 10 seconds
./gradlew quarkusDev
```

### Issue: MQTT connection refused

**Solution:**
```bash
docker-compose restart emqx
# Verify EMQX is running
docker logs fleet-emqx
```

### Issue: Port already in use

**Solution:**
```bash
# Find process using port
lsof -i :8080
# Kill it or change quarkus.http.port in application.properties
```

---

## Development Workflow

### 1. Make changes to domain model
```kotlin
// src/main/kotlin/com/fleet/domain/battery/model/BatteryPack.kt
// Add new method or business rule
```

### 2. Quarkus auto-reloads
```
Press 's' to restart
Press 'r' to resume testing
Press 'h' for help
```

### 3. Test immediately
```bash
curl ...
```

### 4. Check logs
```bash
# Application logs in terminal
# Or check container logs
docker logs -f fleet-timescaledb
```

---

## Next Steps

1. **Implement remaining use cases** (see IMPLEMENTATION_GUIDE.md)
2. **Add comprehensive tests** (see test examples)
3. **Create frontend dashboard** (React + WebSockets)
4. **Deploy to production** (Kubernetes configs in deployment/)

---

## Useful Commands

### View all events globally:
```sql
SELECT * FROM event_store ORDER BY sequence_number DESC LIMIT 10;
```

### View telemetry history:
```sql
SELECT * FROM telemetry_history 
WHERE battery_pack_id = '550e8400-e29b-41d4-a716-446655440000'
ORDER BY recorded_at DESC 
LIMIT 10;
```

### View hourly aggregates:
```sql
SELECT * FROM telemetry_hourly 
WHERE battery_pack_id = '550e8400-e29b-41d4-a716-446655440000'
ORDER BY hour DESC 
LIMIT 24;
```

### Rebuild projection from events:
```sql
-- Clear projection
DELETE FROM battery_pack_projection;

-- Replay all battery events (would be done by event handler)
-- This demonstrates event sourcing principle
```

---

## Support

**Documentation:**
- README.md - Architecture overview
- IMPLEMENTATION_GUIDE.md - Complete file structure
- API documentation: http://localhost:8080/q/swagger-ui

**Logs:**
- Application: Console output
- TimescaleDB: `docker logs fleet-timescaledb`
- EMQX: `docker logs fleet-emqx`
- Redis: `docker logs fleet-redis`

---

**You're now running a production-grade DDD system with Event Sourcing!** 🎉
