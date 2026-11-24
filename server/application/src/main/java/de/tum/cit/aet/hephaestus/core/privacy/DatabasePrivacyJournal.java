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
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
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

    @Override
    public void record(PrivacyJournalKind kind, Map<String, Object> refs) {
        record(UUID.randomUUID(), kind, refs);
    }

    @Override
    public void record(UUID entryId, PrivacyJournalKind kind, Map<String, Object> refs) {
        var recoveryRefs = recoveryReferences(kind, refs);
        PrivacyJournalReferences.validate(kind, recoveryRefs);
        var targetIds = PrivacyJournalReferences.logTargetIds(recoveryRefs);
        String recoveryReferences = mapper.writeValueAsString(recoveryRefs);
        jdbc.update(
                "INSERT INTO privacy_journal(entry_id, kind, refs) VALUES (?, ?, CAST(? AS jsonb))",
                entryId,
                kind.name(),
                recoveryReferences);
        TransactionCallbacks.afterCommit(() -> log.atInfo()
                .addKeyValue("event.name", "privacy.journal.recorded")
                .addKeyValue("privacy.journal.kind", kind)
                .addKeyValue("privacy.journal.entry.id", entryId)
                .addKeyValue("privacy.journal.target.ids", targetIds)
                .log("Recorded a privacy fact"));
    }

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
