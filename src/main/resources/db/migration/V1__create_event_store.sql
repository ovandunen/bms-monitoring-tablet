-- =====================================================
-- V1: Create Event Store Schema with TimescaleDB
-- =====================================================
-- This migration creates the event sourcing infrastructure
-- using TimescaleDB for optimal time-series performance

-- Enable TimescaleDB extension
CREATE EXTENSION IF NOT EXISTS timescaledb;

-- =====================================================
-- Event Store Table (Hypertable)
-- =====================================================
-- Stores all domain events for event sourcing
-- This is the source of truth for all aggregate state
CREATE TABLE event_store (
    -- Event identification
    event_id UUID PRIMARY KEY,
    sequence_number BIGSERIAL NOT NULL,
    
    -- Aggregate identification
    aggregate_id TEXT NOT NULL,
    aggregate_type TEXT NOT NULL,
    aggregate_version BIGINT NOT NULL,
    
    -- Event metadata
    event_type TEXT NOT NULL,
    event_version INT NOT NULL DEFAULT 1,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    
    -- Event data (JSON for flexibility)
    event_data JSONB NOT NULL,
    
    -- Causality tracking
    correlation_id UUID,
    causation_id UUID,
    
    -- Metadata
    user_id TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    
    -- Constraints
    CONSTRAINT unique_aggregate_version UNIQUE (aggregate_id, aggregate_version)
);

-- Convert to TimescaleDB hypertable (partitioned by time)
SELECT create_hypertable(
    'event_store',
    'occurred_at',
    chunk_time_interval => INTERVAL '1 day',
    if_not_exists => TRUE
);

-- Create indexes for event store queries
CREATE INDEX idx_event_store_aggregate 
    ON event_store (aggregate_id, aggregate_version DESC);

CREATE INDEX idx_event_store_type 
    ON event_store (aggregate_type, occurred_at DESC);

CREATE INDEX idx_event_store_event_type 
    ON event_store (event_type, occurred_at DESC);

CREATE INDEX idx_event_store_correlation 
    ON event_store (correlation_id) WHERE correlation_id IS NOT NULL;

CREATE INDEX idx_event_store_occurred_at 
    ON event_store (occurred_at DESC);

-- GIN index for JSONB queries
CREATE INDEX idx_event_store_event_data 
    ON event_store USING GIN (event_data);

-- =====================================================
-- Event Store Snapshots (Optional Optimization)
-- =====================================================
-- Stores aggregate snapshots to avoid replaying all events
CREATE TABLE aggregate_snapshots (
    aggregate_id TEXT NOT NULL,
    aggregate_type TEXT NOT NULL,
    aggregate_version BIGINT NOT NULL,
    snapshot_data JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    
    PRIMARY KEY (aggregate_id, aggregate_type)
);

CREATE INDEX idx_snapshots_version 
    ON aggregate_snapshots (aggregate_id, aggregate_version DESC);

-- =====================================================
-- Event Processing Checkpoint (for projections)
-- =====================================================
-- Tracks last processed event for each projection/handler
CREATE TABLE event_processing_checkpoint (
    handler_name TEXT PRIMARY KEY,
    last_processed_sequence BIGINT NOT NULL,
    last_processed_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- =====================================================
-- Saga State Table
-- =====================================================
-- Stores saga execution state for battery replacement saga
CREATE TABLE saga_state (
    saga_id UUID PRIMARY KEY,
    saga_type TEXT NOT NULL,
    current_step TEXT NOT NULL,
    saga_data JSONB NOT NULL,
    status TEXT NOT NULL, -- RUNNING, COMPLETED, FAILED, COMPENSATING, COMPENSATED
    started_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMPTZ,
    error_message TEXT
);

CREATE INDEX idx_saga_state_type_status 
    ON saga_state (saga_type, status);

CREATE INDEX idx_saga_state_updated 
    ON saga_state (updated_at DESC);

-- =====================================================
-- Idempotency Table
-- =====================================================
-- Prevents duplicate processing of commands/events
CREATE TABLE idempotency_keys (
    idempotency_key TEXT PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    result JSONB,
    expires_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_idempotency_expires 
    ON idempotency_keys (expires_at);

-- Auto-cleanup expired keys
CREATE OR REPLACE FUNCTION cleanup_expired_idempotency_keys()
RETURNS void AS $$
BEGIN
    DELETE FROM idempotency_keys WHERE expires_at < NOW();
END;
$$ LANGUAGE plpgsql;

-- =====================================================
-- Battery Pack Projection (Current State)
-- =====================================================
-- Denormalized view of current battery state
-- Built from events, not source of truth
CREATE TABLE battery_pack_projection (
    battery_pack_id UUID PRIMARY KEY,
    
    -- Specifications
    manufacturer TEXT,
    model TEXT,
    chemistry TEXT,
    capacity_kwh DECIMAL(10, 2),
    cell_configuration TEXT,
    
    -- Current telemetry
    current_soc DECIMAL(5, 2),
    current_voltage DECIMAL(6, 2),
    current_current DECIMAL(6, 2),
    current_temperature DECIMAL(5, 2),
    cell_voltage_min DECIMAL(4, 3),
    cell_voltage_max DECIMAL(4, 3),
    cell_voltage_delta DECIMAL(4, 3),
    
    -- Status
    health_status TEXT,
    is_decommissioned BOOLEAN DEFAULT FALSE,
    is_charging BOOLEAN DEFAULT FALSE,
    
    -- Timestamps
    created_at TIMESTAMPTZ NOT NULL,
    last_telemetry_at TIMESTAMPTZ,
    decommissioned_at TIMESTAMPTZ,
    
    -- Version (for optimistic locking)
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_battery_projection_status 
    ON battery_pack_projection (health_status, is_decommissioned);

CREATE INDEX idx_battery_projection_last_telemetry 
    ON battery_pack_projection (last_telemetry_at DESC);

-- =====================================================
-- Vehicle Projection
-- =====================================================
CREATE TABLE vehicle_projection (
    vehicle_id TEXT PRIMARY KEY,
    registration TEXT,
    model TEXT,
    battery_pack_id UUID REFERENCES battery_pack_projection(battery_pack_id),
    status TEXT,
    last_seen TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_vehicle_status 
    ON vehicle_projection (status, last_seen DESC);

-- =====================================================
-- Alert Projection
-- =====================================================
CREATE TABLE alert_projection (
    alert_id UUID PRIMARY KEY,
    battery_pack_id UUID REFERENCES battery_pack_projection(battery_pack_id),
    vehicle_id TEXT REFERENCES vehicle_projection(vehicle_id),
    alert_type TEXT NOT NULL,
    severity TEXT NOT NULL,
    message TEXT NOT NULL,
    metadata JSONB,
    is_resolved BOOLEAN DEFAULT FALSE,
    triggered_at TIMESTAMPTZ NOT NULL,
    resolved_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_alert_battery 
    ON alert_projection (battery_pack_id, triggered_at DESC);

CREATE INDEX idx_alert_unresolved 
    ON alert_projection (is_resolved, triggered_at DESC) 
    WHERE is_resolved = FALSE;

CREATE INDEX idx_alert_severity 
    ON alert_projection (severity, triggered_at DESC);

-- =====================================================
-- Telemetry History (for analytics)
-- =====================================================
-- Stores telemetry readings for historical analysis
-- Separate from event store for query optimization
CREATE TABLE telemetry_history (
    id BIGSERIAL,
    battery_pack_id UUID NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    
    soc DECIMAL(5, 2),
    voltage DECIMAL(6, 2),
    current DECIMAL(6, 2),
    temperature_min DECIMAL(5, 2),
    temperature_max DECIMAL(5, 2),
    temperature_avg DECIMAL(5, 2),
    cell_voltage_min DECIMAL(4, 3),
    cell_voltage_max DECIMAL(4, 3),
    cell_voltage_delta DECIMAL(4, 3),
    
    PRIMARY KEY (battery_pack_id, recorded_at)
);

-- Convert to hypertable for time-series optimization
SELECT create_hypertable(
    'telemetry_history',
    'recorded_at',
    chunk_time_interval => INTERVAL '7 days',
    if_not_exists => TRUE
);

-- Add retention policy (keep 90 days)
SELECT add_retention_policy(
    'telemetry_history',
    INTERVAL '90 days',
    if_not_exists => TRUE
);

-- Create continuous aggregate for hourly averages
CREATE MATERIALIZED VIEW telemetry_hourly
WITH (timescaledb.continuous) AS
SELECT
    battery_pack_id,
    time_bucket('1 hour', recorded_at) AS hour,
    AVG(soc) AS avg_soc,
    AVG(voltage) AS avg_voltage,
    AVG(current) AS avg_current,
    AVG(temperature_avg) AS avg_temperature,
    MIN(soc) AS min_soc,
    MAX(soc) AS max_soc
FROM telemetry_history
GROUP BY battery_pack_id, hour
WITH NO DATA;

-- Refresh policy for continuous aggregate
SELECT add_continuous_aggregate_policy(
    'telemetry_hourly',
    start_offset => INTERVAL '1 day',
    end_offset => INTERVAL '1 hour',
    schedule_interval => INTERVAL '1 hour',
    if_not_exists => TRUE
);

-- Create index on continuous aggregate
CREATE INDEX idx_telemetry_hourly_battery 
    ON telemetry_hourly (battery_pack_id, hour DESC);

-- =====================================================
-- Helper Functions
-- =====================================================

-- Function to get latest events for an aggregate
CREATE OR REPLACE FUNCTION get_aggregate_events(
    p_aggregate_id TEXT,
    p_from_version BIGINT DEFAULT 0
)
RETURNS TABLE (
    event_id UUID,
    aggregate_version BIGINT,
    event_type TEXT,
    event_data JSONB,
    occurred_at TIMESTAMPTZ
) AS $$
BEGIN
    RETURN QUERY
    SELECT 
        e.event_id,
        e.aggregate_version,
        e.event_type,
        e.event_data,
        e.occurred_at
    FROM event_store e
    WHERE e.aggregate_id = p_aggregate_id
      AND e.aggregate_version > p_from_version
    ORDER BY e.aggregate_version ASC;
END;
$$ LANGUAGE plpgsql;

-- Function to get event count for aggregate
CREATE OR REPLACE FUNCTION get_aggregate_event_count(
    p_aggregate_id TEXT
)
RETURNS BIGINT AS $$
    SELECT COUNT(*) 
    FROM event_store 
    WHERE aggregate_id = p_aggregate_id;
$$ LANGUAGE sql;

-- =====================================================
-- Seed Data (Optional)
-- =====================================================

-- Insert initial processing checkpoint
INSERT INTO event_processing_checkpoint (handler_name, last_processed_sequence, last_processed_at)
VALUES 
    ('BatteryProjectionHandler', 0, NOW()),
    ('AlertProjectionHandler', 0, NOW()),
    ('TelemetryHistoryHandler', 0, NOW())
ON CONFLICT (handler_name) DO NOTHING;

-- =====================================================
-- Grants (for application user)
-- =====================================================
GRANT ALL ON ALL TABLES IN SCHEMA public TO fleetuser;
GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO fleetuser;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO fleetuser;

-- =====================================================
-- Comments for documentation
-- =====================================================
COMMENT ON TABLE event_store IS 'Event sourcing event store - source of truth for all aggregate state';
COMMENT ON TABLE aggregate_snapshots IS 'Aggregate snapshots for performance optimization';
COMMENT ON TABLE saga_state IS 'Saga execution state for long-running processes';
COMMENT ON TABLE battery_pack_projection IS 'Denormalized current state of battery packs';
COMMENT ON TABLE telemetry_history IS 'Historical telemetry data for analytics';
COMMENT ON MATERIALIZED VIEW telemetry_hourly IS 'Continuous aggregate of hourly telemetry averages';
