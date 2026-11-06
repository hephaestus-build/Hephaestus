package de.tum.cit.aet.hephaestus.core.privacy;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentityResolver;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonScope;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

/** Reapplies operator-held decisions before lifecycle workers, scheduled tasks and broker consumers start. */
@Component
@Profile("erasure-recovery")
@ConditionalOnServerRole
final class PersonDataRecovery implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(PersonDataRecovery.class);
    private final PersonDataService service;
    private final PersonIdentityResolver resolver;
    private final ObjectMapper mapper;
    private final Environment environment;
    private final JdbcTemplate jdbc;
    private final String manifestPath;
    private boolean running;

    PersonDataRecovery(
            PersonDataService service,
            PersonIdentityResolver resolver,
            ObjectMapper mapper,
            Environment environment,
            JdbcTemplate jdbc,
            PersonDataRecoveryProperties properties) {
        this.service = service;
        this.resolver = resolver;
        this.mapper = mapper;
        this.environment = environment;
        this.jdbc = jdbc;
        this.manifestPath = properties.recoveryManifest();
    }

    record Manifest(
            int version,
            Instant restorePoint,
            boolean noLaterErasures,
            @Nullable Long administratorAccountId,
            List<Person> people) {}

    record Person(@Nullable Long accountId, List<NativeIdentity> identities, boolean externalCopiesRemoved) {}

    record NativeIdentity(
            String providerType,
            String serverUrl,
            String subject,
            @Nullable String teamId) {}

    @Override
    @SuppressWarnings("PMD.PreserveStackTrace") // Parser errors can quote identities from the private manifest.
    public void start() {
        try {
            if (!RuntimeRole.enabled(environment).equals(List.of(RuntimeRole.SERVER)) || manifestPath.isBlank()) {
                throw new IllegalStateException();
            }
            Path path = Path.of(manifestPath);
            if (Files.size(path) > 16 * 1024 * 1024) throw new IllegalStateException();
            Manifest manifest = mapper.readerFor(Manifest.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(Files.readAllBytes(path));
            Objects.requireNonNull(manifest.restorePoint());
            if (manifest.version() != 1
                    || manifest.restorePoint().isAfter(Instant.now())
                    || manifest.noLaterErasures() != manifest.people().isEmpty()) {
                throw new IllegalStateException();
            }
            // Resolve and validate the whole input before the first erasure changes a store.
            List<List<PersonIdentity>> identities = manifest.people().stream()
                    .map(person ->
                            person.identities().stream().map(this::resolve).toList())
                    .toList();
            for (int index = 0; index < manifest.people().size(); index++) {
                Person person = manifest.people().get(index);
                if ((person.accountId() != null && person.accountId() <= 0)
                        || (person.accountId() == null && identities.get(index).isEmpty())) {
                    throw new IllegalStateException();
                }
            }
            if (!manifest.people().isEmpty()) {
                long administrator = Objects.requireNonNull(manifest.administratorAccountId());
                if (administrator <= 0) throw new IllegalStateException();
                for (int index = 0; index < manifest.people().size(); index++) {
                    reapplyErasure(
                            administrator,
                            manifest.people().get(index).accountId(),
                            identities.get(index),
                            manifest.people().get(index).externalCopiesRemoved());
                }
            }
            running = true;
            log.atInfo()
                    .addKeyValue("event.name", "person_data.recovery.completed")
                    .log("Erasure recovery completed");
        } catch (IOException | RuntimeException failure) {
            // Parser and provider exceptions can quote identifying input. Never attach their cause.
            log.atError()
                    .addKeyValue("event.name", "person_data.recovery.failed")
                    .log("Erasure recovery blocked startup");
            throw new IllegalStateException(
                    "Erasure recovery is incomplete. Keep all other roles stopped and check the private manifest and erasure receipts.");
        }
    }

    /** Recovery uses the same frozen requests and receipts, including an interrupted request restored from WAL. */
    private void reapplyErasure(
            long administratorId,
            @Nullable Long accountId,
            List<PersonIdentity> identities,
            boolean externalCopiesRemoved) {
        PersonScope person = resolver.resolve(accountId, identities);
        List<UUID> interrupted = jdbc.query(
                """
                SELECT id FROM person_data_request
                WHERE state IN ('ERASING','FAILED') AND scope_json IS NOT NULL
                  AND ((scope_json::jsonb)->>'accountId'=?
                    OR EXISTS (SELECT 1 FROM jsonb_array_elements((scope_json::jsonb)->'identities') stored
                      JOIN jsonb_array_elements(CAST(? AS jsonb)) chosen ON stored=chosen))
                ORDER BY created_at
                """,
                (rs, row) -> Objects.requireNonNull(rs.getObject(1, UUID.class)),
                person.accountId() == null ? null : person.accountId().toString(),
                mapper.writeValueAsString(person.identities()));
        for (UUID id : interrupted) {
            service.requestErasure(id, administratorId, externalCopiesRemoved);
            service.run(id);
            requireRecoveryComplete(id);
        }
        UUID id = service.preview(administratorId, accountId, identities)
                .request()
                .getId();
        service.requestErasure(id, administratorId, externalCopiesRemoved);
        service.run(id);
        requireRecoveryComplete(id);
    }

    private void requireRecoveryComplete(UUID id) {
        var state = service.get(id).request().getState();
        if (state != PersonDataRequest.State.COMPLETE) {
            throw new IllegalStateException("Erasure recovery has an unfinished request");
        }
    }

    private PersonIdentity resolve(NativeIdentity identity) {
        boolean nativeSubject = switch (identity.providerType()) {
            case "GITHUB", "GITLAB" ->
                identity.teamId() == null && identity.subject().matches("[1-9][0-9]*");
            case "SLACK" ->
                identity.subject().matches("[UW][A-Z0-9]+")
                        && identity.teamId() != null
                        && identity.teamId().matches("T[A-Z0-9]+");
            case "OUTLINE" -> UUID.fromString(identity.subject()).toString().equals(identity.subject());
            default -> false;
        };
        if (!nativeSubject) throw new IllegalArgumentException();
        var providers = resolver.providers().stream()
                .filter(provider -> provider.type().equals(identity.providerType())
                        && provider.serverUrl().equals(identity.serverUrl()))
                .toList();
        if (providers.size() != 1) throw new IllegalArgumentException();
        return new PersonIdentity(providers.getFirst().id(), identity.subject(), identity.teamId());
    }

    @Override
    public int getPhase() {
        return Integer.MIN_VALUE;
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
