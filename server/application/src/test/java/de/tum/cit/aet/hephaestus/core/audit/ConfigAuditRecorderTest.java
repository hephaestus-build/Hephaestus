package de.tum.cit.aet.hephaestus.core.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditAction;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditActorKind;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntityType;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntry;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditSnapshot;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditUnavailableException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Tag("unit")
class ConfigAuditRecorderTest {

    private final ConfigAuditEventRepository repository = mock(ConfigAuditEventRepository.class);
    private final ConfigAuditRecorder recorder =
            new ConfigAuditRecorder(repository, Clock.fixed(Instant.parse("2026-07-16T10:15:30Z"), ZoneOffset.UTC));

    record Snap(@Nullable Integer cooldownMinutes) implements ConfigAuditSnapshot {}

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void refusesToRecordOutsideATransaction() {
        // Without this, a producer that forgot @Transactional would commit its change and silently
        // leave no audit row — the failure this port exists to prevent.
        assertThatThrownBy(() -> recorder.record(entry(new Snap(30), new Snap(10))))
                .isInstanceOf(ConfigAuditUnavailableException.class)
                .hasMessageContaining("inside the transaction");
        verify(repository, never()).save(any());
    }

    @Test
    void refusesToRecordInsideAReadOnlyTransaction() {
        // readOnly satisfies MANDATORY but never flushes, so the INSERT would silently not happen.
        inTransaction(true);
        assertThatThrownBy(() -> recorder.record(entry(new Snap(30), new Snap(10))))
                .isInstanceOf(ConfigAuditUnavailableException.class)
                .hasMessageContaining("read-only");
        verify(repository, never()).save(any());
    }

    @Test
    void suppressesNoOpUpdates() {
        inTransaction(false);
        recorder.record(entry(new Snap(30), new Snap(30)));
        verify(repository, never()).save(any());
    }

    @Test
    void recordsAnUpdateWithItsChangedKeysAndBothSnapshots() {
        inTransaction(false);
        recorder.record(entry(new Snap(30), new Snap(10)));

        ConfigAuditEvent saved = captureSaved();
        assertThat(saved.getAction()).isEqualTo(ConfigAuditAction.UPDATED);
        assertThat(saved.changedKeyList()).containsExactly("cooldownMinutes");
        assertThat(saved.getOldValue()).contains("30");
        assertThat(saved.getNewValue()).contains("10");
        assertThat(saved.getOccurredAt()).isEqualTo(Instant.parse("2026-07-16T10:15:30Z"));
    }

    @Test
    void createIsRecordedEvenThoughItHasNoPriorState() {
        inTransaction(false);
        recorder.record(ConfigAuditEntry.created(ConfigAuditEntityType.AGENT_CONFIG, 7L, 1L, new Snap(30)));

        ConfigAuditEvent saved = captureSaved();
        assertThat(saved.getAction()).isEqualTo(ConfigAuditAction.CREATED);
        assertThat(saved.getOldValue()).isNull();
        assertThat(saved.changedKeyList()).containsExactly("cooldownMinutes");
    }

    @Test
    void anUnauthenticatedCallerIsRecordedAsSystemNotAsAnAbsentActor() {
        // Seeders and schedulers reach producers with no security context. Recording that as a bare
        // null id would be indistinguishable from an actor whose account was erased.
        inTransaction(false);
        recorder.record(entry(new Snap(30), new Snap(10)));

        ConfigAuditEvent saved = captureSaved();
        assertThat(saved.getActorKind()).isEqualTo(ConfigAuditActorKind.SYSTEM);
        assertThat(saved.getActorAccountId()).isNull();
    }

    @Test
    void aSignedInCallerIsRecordedAsUser() {
        inTransaction(false);
        authenticate("42");
        recorder.record(entry(new Snap(30), new Snap(10)));

        ConfigAuditEvent saved = captureSaved();
        assertThat(saved.getActorKind()).isEqualTo(ConfigAuditActorKind.USER);
        assertThat(saved.getActorAccountId()).isEqualTo(42L);
        assertThat(saved.getActingAccountId()).isNull();
    }

    @Test
    void anAuthenticatedCallerWhoseSubjectCannotBeResolvedIsStillAUserNotTheSystem() {
        // Filing a signed-in human as SYSTEM would be the exact confusion actor_kind exists to prevent,
        // so the kind follows authentication and only the id goes unresolved.
        inTransaction(false);
        authenticate("not-an-account-id");
        recorder.record(entry(new Snap(30), new Snap(10)));

        ConfigAuditEvent saved = captureSaved();
        assertThat(saved.getActorKind()).isEqualTo(ConfigAuditActorKind.USER);
        assertThat(saved.getActorAccountId()).isNull();
    }

    private ConfigAuditEvent captureSaved() {
        ArgumentCaptor<ConfigAuditEvent> captor = ArgumentCaptor.forClass(ConfigAuditEvent.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    private static ConfigAuditEntry entry(ConfigAuditSnapshot before, ConfigAuditSnapshot after) {
        return ConfigAuditEntry.updated(ConfigAuditEntityType.PRACTICE_REVIEW_SETTINGS, 1L, 1L, before, after);
    }

    private static void inTransaction(boolean readOnly) {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(readOnly);
    }

    private static void authenticate(String subject) {
        Jwt.Builder jwt =
                Jwt.withTokenValue("t").header("alg", "none").subject(subject).claim("sub", subject);
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt.build(), List.of()));
    }
}
