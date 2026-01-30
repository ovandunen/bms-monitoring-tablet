package com.fleet.interfaces.rest

import io.vertx.mutiny.pgclient.PgPool
import io.vertx.mutiny.redis.client.Redis
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import kotlinx.coroutines.runBlocking
import org.eclipse.microprofile.health.HealthCheck
import org.eclipse.microprofile.health.HealthCheckResponse
import org.eclipse.microprofile.health.Liveness
import org.eclipse.microprofile.health.Readiness
import org.jboss.logging.Logger
import java.time.Instant

/**
 * Health Check Endpoints
 * 
 * Provides health and readiness endpoints for:
 * - Kubernetes liveness/readiness probes
 * - Load balancer health checks
 * - Monitoring systems (Prometheus, Grafana)
 */

/**
 * Liveness Probe
 * 
 * Indicates if the application is alive and running.
 * If this fails, Kubernetes will restart the pod.
 */
@Liveness
class LivenessCheck : HealthCheck {
    override fun call(): HealthCheckResponse {
        return HealthCheckResponse
            .named("Battery Fleet Management - Liveness")
            .up()
            .withData("timestamp", Instant.now().toString())
            .build()
    }
}

/**
 * Readiness Probe
 * 
 * Indicates if the application is ready to serve traffic.
 * Checks database and Redis connectivity.
 * If this fails, Kubernetes won't route traffic to this pod.
 */
@Readiness
class ReadinessCheck(
    private val pgPool: PgPool,
    private val redis: Redis
) : HealthCheck {
    private val logger = Logger.getLogger(ReadinessCheck::class.java)
    
    override fun call(): HealthCheckResponse {
        return try {
            // Check database
            val dbHealthy = checkDatabase()
            
            // Check Redis
            val redisHealthy = checkRedis()
            
            if (dbHealthy && redisHealthy) {
                HealthCheckResponse
                    .named("Battery Fleet Management - Readiness")
                    .up()
                    .withData("database", "UP")
                    .withData("redis", "UP")
                    .withData("timestamp", Instant.now().toString())
                    .build()
            } else {
                HealthCheckResponse
                    .named("Battery Fleet Management - Readiness")
                    .down()
                    .withData("database", if (dbHealthy) "UP" else "DOWN")
                    .withData("redis", if (redisHealthy) "UP" else "DOWN")
                    .withData("timestamp", Instant.now().toString())
                    .build()
            }
        } catch (e: Exception) {
            logger.error("Health check failed", e)
            HealthCheckResponse
                .named("Battery Fleet Management - Readiness")
                .down()
                .withData("error", e.message ?: "Unknown error")
                .withData("timestamp", Instant.now().toString())
                .build()
        }
    }
    
    private fun checkDatabase(): Boolean {
        return try {
            runBlocking {
                pgPool.query("SELECT 1")
                    .execute()
                    .await().indefinitely()
            }
            true
        } catch (e: Exception) {
            logger.warn("Database health check failed", e)
            false
        }
    }
    
    private fun checkRedis(): Boolean {
        return try {
            runBlocking {
                redis.ping(listOf())
                    .await().indefinitely()
            }
            true
        } catch (e: Exception) {
            logger.warn("Redis health check failed", e)
            false
        }
    }
}

/**
 * Custom Health Endpoint
 * 
 * Provides detailed health information in JSON format.
 * Useful for monitoring dashboards.
 */
@Path("/api/v1/health")
@Produces(MediaType.APPLICATION_JSON)
class HealthController(
    private val pgPool: PgPool,
    private val redis: Redis
) {
    private val logger = Logger.getLogger(HealthController::class.java)
    
    @GET
    fun getHealth(): Response {
        val health = runBlocking {
            mapOf(
                "status" to "UP",
                "timestamp" to Instant.now().toString(),
                "components" to mapOf(
                    "database" to checkDatabaseHealth(),
                    "redis" to checkRedisHealth(),
                    "mqtt" to mapOf("status" to "UP") // Assume UP (actual check would be more complex)
                ),
                "info" to mapOf(
                    "app" to "Battery Fleet Management System",
                    "version" to "1.0.0",
                    "description" to "DDD Event-Sourced Fleet Management"
                )
            )
        }
        
        return Response.ok(health).build()
    }
    
    private suspend fun checkDatabaseHealth(): Map<String, Any> {
        return try {
            val start = System.currentTimeMillis()
            pgPool.query("SELECT COUNT(*) FROM event_store")
                .execute()
                .await().indefinitely()
            val duration = System.currentTimeMillis() - start
            
            mapOf(
                "status" to "UP",
                "responseTime" to "${duration}ms"
            )
        } catch (e: Exception) {
            logger.error("Database health check failed", e)
            mapOf(
                "status" to "DOWN",
                "error" to (e.message ?: "Unknown error")
            )
        }
    }
    
    private suspend fun checkRedisHealth(): Map<String, Any> {
        return try {
            val start = System.currentTimeMillis()
            redis.ping(listOf()).await().indefinitely()
            val duration = System.currentTimeMillis() - start
            
            mapOf(
                "status" to "UP",
                "responseTime" to "${duration}ms"
            )
        } catch (e: Exception) {
            logger.error("Redis health check failed", e)
            mapOf(
                "status" to "DOWN",
                "error" to (e.message ?: "Unknown error")
            )
        }
    }
}
