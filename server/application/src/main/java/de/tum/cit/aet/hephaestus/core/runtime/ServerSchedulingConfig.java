package de.tum.cit.aet.hephaestus.core.runtime;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Activates Spring {@link EnableScheduling @EnableScheduling} only on the server-role pod.
 *
 * <p>This single switch silences every {@code @Scheduled} method on the {@code webhook-server}
 * container (which sets {@code hephaestus.runtime.server.enabled=false}) — preventing double-run
 * pathologies for sync schedulers (GitHub/GitLab data sync, historical backfill), agent zombie
 * sweepers, mentor in-flight reaper, contributor cache eviction, rate-limit eviction, and the
 * GitLab webhook health check.
 *
 * <p>{@code matchIfMissing=true} preserves ADR 0005's DX invariant: zero env vars → full monolith
 * boots with scheduling enabled.
 * Build-only profiles introspect the HTTP contract or train the class archive and must not start background work.
 *
 * <p>Worker-side sandbox maintenance is registered independently by
 * {@code SandboxMaintenanceConfiguration}. Worker-only deployments do not enable this server-wide
 * scheduler; the stalled-write watchdog has its own thread so Docker cleanup cannot delay it.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!specs & !cds-training")
@ConditionalOnProperty(name = RuntimeRole.SERVER_PROPERTY, havingValue = "true", matchIfMissing = true)
@EnableScheduling
public class ServerSchedulingConfig {}
