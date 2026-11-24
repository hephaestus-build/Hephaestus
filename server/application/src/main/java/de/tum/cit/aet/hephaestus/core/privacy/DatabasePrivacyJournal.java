package de.tum.cit.aet.hephaestus.core.privacy;

import de.tum.cit.aet.hephaestus.core.TransactionCallbacks;
import de.tum.cit.aet.hephaestus.core.privacy.spi.NativeId;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentityResolver;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PrivacyJournal;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PrivacyJournalKind;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
class DatabasePrivacyJournal implements PrivacyJournal {

    private static final Logger log = LoggerFactory.getLogger(DatabasePrivacyJournal.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Optional<PersonIdentityResolver> resolver;
    private final PrivacyJournalShipping shipping;

    @Override
    public void record(PrivacyJournalKind kind, Map<String, Object> refs) {
        record(UUID.randomUUID(), kind, refs);
    }

    @Override
    public void record(UUID entryId, PrivacyJournalKind kind, Map<String, Object> refs) {
        record(entryId, kind, refs, true);
    }

    @Override
    public void recordDeferred(UUID entryId, PrivacyJournalKind kind, Map<String, Object> refs) {
        record(entryId, kind, refs, false);
    }

    private void record(UUID entryId, PrivacyJournalKind kind, Map<String, Object> refs, boolean replyWaits) {
        var recoveryRefs = recoveryReferences(kind, refs);
        PrivacyJournalReferences.validate(kind, recoveryRefs);
        var targetIds = PrivacyJournalReferences.logTargetIds(recoveryRefs);
        String recoveryReferences = mapper.writeValueAsString(recoveryRefs);
        var requestScope = PrivacyJournalShipping.ERASURE_REQUEST;
        @Nullable UUID erasureRequestId = requestScope.isBound() ? requestScope.get() : null;
        jdbc.update("""
                INSERT INTO privacy_journal(entry_id, kind, refs, erasure_request_id, requires_shipping)
                VALUES (?, ?, CAST(? AS jsonb), ?, ?)
                """, entryId, kind.name(), recoveryReferences, erasureRequestId, shipping.enabled());
        TransactionCallbacks.afterCommit(() -> log.atInfo()
                .addKeyValue("event.name", "privacy.journal.recorded")
                .addKeyValue("privacy.journal.kind", kind)
                .addKeyValue("privacy.journal.entry.id", entryId)
                .addKeyValue("privacy.journal.target.ids", targetIds)
                .log("Recorded a privacy fact"));
        if (replyWaits && shipping.enabled())
            TransactionCallbacks.afterCommit(() -> PrivacyJournalReply.committed(entryId));
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void confirmStored(UUID entryId) {
        shipping.confirm(entryId);
    }

    @Override
    public void confirmPending(PrivacyJournalKind kind, Map<String, Object> subject) {
        if (!PrivacyJournalReply.isBound()) return;
        var pending = jdbc.queryForList(
                """
                SELECT entry_id FROM privacy_journal
                WHERE shipped_at IS NULL AND (requires_shipping OR ?) AND kind = ? AND refs @> CAST(? AS jsonb)
                ORDER BY id
                """,
                UUID.class,
                shipping.enabled(),
                kind.name(),
                mapper.writeValueAsString(recoveryReferences(kind, subject)));
        if (!pending.isEmpty()) {
            TransactionCallbacks.afterCommit(() -> {
                for (var id : pending) PrivacyJournalReply.committed(Objects.requireNonNull(id));
            });
        }
    }

    @Override
    public boolean confirmIfRecorded(@Nullable UUID receipt, PrivacyJournalKind kind, Map<String, Object> subject) {
        var recorded = jdbc.query(
                """
                SELECT entry_id, shipped_at IS NULL AND (requires_shipping OR ?) AS pending
                FROM privacy_journal
                WHERE entry_id = COALESCE(?, entry_id) AND kind = ? AND refs @> CAST(? AS jsonb)
                ORDER BY id
                """,
                (row, index) ->
                        new ConfirmedFact(Objects.requireNonNull(row.getObject(1, UUID.class)), row.getBoolean(2)),
                shipping.enabled(),
                receipt,
                kind.name(),
                mapper.writeValueAsString(recoveryReferences(kind, subject)));
        for (var fact : recorded) {
            if (fact.pending()) TransactionCallbacks.afterCommit(() -> PrivacyJournalReply.committed(fact.id()));
        }
        return !recorded.isEmpty();
    }

    private record ConfirmedFact(UUID id, boolean pending) {}

    private Map<String, Object> recoveryReferences(PrivacyJournalKind kind, Map<String, Object> refs) {
        Map<String, Object> recovery = new HashMap<>(refs);
        recovery.replaceAll((name, value) -> switch (value) {
            case NativeId id -> id.value();
            case Enum<?> state -> state.name();
            case null, default -> value;
        });
        if (kind == PrivacyJournalKind.PERSON_ERASED && recovery.get("identities") instanceof List<?> identities) {
            List<PersonIdentityResolver.Provider> providers = identities.isEmpty()
                    ? List.of()
                    : resolver.orElseThrow(
                                    () -> new IllegalStateException("Erasure identities require the server role"))
                            .providers();
            recovery.put(
                    "identities",
                    identities.stream()
                            .map(identity -> identity instanceof PersonIdentity nativeIdentity
                                    ? recoveryIdentity(nativeIdentity, providers)
                                    : identity)
                            .toList());
            recovery.putIfAbsent("externalCopiesRemoved", false);
        }
        return recovery;
    }

    private static PersonDataRecovery.NativeIdentity recoveryIdentity(
            PersonIdentity identity, List<PersonIdentityResolver.Provider> providers) {
        return providers.stream()
                .filter(provider -> provider.id() == identity.providerId())
                .findFirst()
                .map(provider -> new PersonDataRecovery.NativeIdentity(
                        provider.type(), provider.serverUrl(), identity.subject(), identity.teamId()))
                .orElseThrow(() -> new IllegalStateException("Recovery provider is missing"));
    }
}
