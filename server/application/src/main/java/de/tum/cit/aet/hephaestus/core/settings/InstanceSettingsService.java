package de.tum.cit.aet.hephaestus.core.settings;

import de.tum.cit.aet.hephaestus.core.EntityTagPrecondition;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEvent;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventLogger;
import de.tum.cit.aet.hephaestus.core.exception.PreconditionFailedException;
import de.tum.cit.aet.hephaestus.core.exception.PreconditionRequiredException;
import de.tum.cit.aet.hephaestus.core.security.SecurityUtils;
import de.tum.cit.aet.hephaestus.core.settings.spi.SilentModeQuery;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Not gated on the runtime role: the server serves the admin API and consults {@link SilentModeQuery} on every
 * outbound delivery. {@link AuthEventLogger} is {@code @ConditionalOnServerRole}, so a context without the server role
 * has none, and it is injected as an {@link java.util.Optional}.
 */
@Service
@WorkspaceAgnostic("Singleton instance-wide settings row — no tenant dimension exists")
public class InstanceSettingsService implements SilentModeQuery {

    private static final Logger log = LoggerFactory.getLogger(InstanceSettingsService.class);

    private final InstanceSettingsRepository repository;
    private final Optional<AuthEventLogger> authEventLogger;
    private final ObjectMapper objectMapper;

    InstanceSettingsService(
            InstanceSettingsRepository repository,
            Optional<AuthEventLogger> authEventLogger,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.authEventLogger = authEventLogger;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public InstanceSettings get() {
        return repository.findById(InstanceSettings.SINGLETON_ID).orElseGet(InstanceSettings::failSafeDefault);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isSilentModeEngaged() {
        return repository.readSilentModeEngaged();
    }

    @Transactional
    public InstanceSettings updateSilentMode(
            boolean engaged,
            @Nullable String reason,
            @Nullable Long actor,
            @Nullable EntityTagPrecondition precondition) {
        repository.insertFailSafeSingletonIfMissing();
        String effectiveReason = engaged && reason != null && !reason.isBlank() ? reason.trim() : null;
        Instant changedAt = Instant.now();
        InstanceSettings saved =
                engaged ? engage(effectiveReason, actor, changedAt) : release(actor, changedAt, precondition);
        log.warn(
                "Instance silent mode {}: actor={}, reason={}",
                engaged ? "ENGAGED — all outbound delivery suppressed" : "RELEASED — outbound delivery resumed",
                actor,
                effectiveReason);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("engaged", engaged);
        if (effectiveReason != null) {
            details.put("reason", effectiveReason);
        }
        authEventLogger.ifPresent(
                logger -> logger.event(AuthEvent.EventType.SILENT_MODE_CHANGED, AuthEvent.Result.SUCCESS)
                        .actingAccount(SecurityUtils.getCurrentAccountId().orElse(null))
                        .details(objectMapper.writeValueAsString(details))
                        .record());
        return saved;
    }

    private InstanceSettings engage(@Nullable String reason, @Nullable Long actor, Instant changedAt) {
        if (repository.engageSilentMode(reason, changedAt, actor) != 1) {
            throw new IllegalStateException("We could not turn on silent mode. Try again.");
        }
        return currentSettings();
    }

    private InstanceSettings release(
            @Nullable Long actor, Instant changedAt, @Nullable EntityTagPrecondition precondition) {
        if (precondition == null) throw new PreconditionRequiredException();
        InstanceSettings settings = currentSettings();
        EntityTagPrecondition.requireExact(precondition, Long.toString(settings.getVersion()));
        settings.setSilentModeEngaged(false);
        settings.setSilentModeReason(null);
        settings.setSilentModeChangedAt(changedAt);
        settings.setSilentModeChangedByAccountId(actor);
        try {
            return repository.saveAndFlush(settings);
        } catch (ObjectOptimisticLockingFailureException exception) {
            throw new PreconditionFailedException(exception);
        }
    }

    private InstanceSettings currentSettings() {
        return repository
                .findById(InstanceSettings.SINGLETON_ID)
                .orElseThrow(() -> new IllegalStateException("Failed to initialize instance_settings singleton"));
    }
}
