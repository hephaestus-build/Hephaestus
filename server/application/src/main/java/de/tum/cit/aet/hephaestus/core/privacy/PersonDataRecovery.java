package de.tum.cit.aet.hephaestus.core.privacy;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentityResolver;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonScope;
import de.tum.cit.aet.hephaestus.core.privacy.spi.RestoredCollectionErasures;
import de.tum.cit.aet.hephaestus.core.privacy.spi.RestoredDisconnects;
import de.tum.cit.aet.hephaestus.core.privacy.spi.RestoredSlackChannelErasures;
import de.tum.cit.aet.hephaestus.core.privacy.spi.RestoredSlackMessageChanges;
import de.tum.cit.aet.hephaestus.core.privacy.spi.RestoredWorkspacePurges;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnBackgroundWork;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

/**
 * Reapplies the erasures that a database restore undid, from the operator-held manifest: disconnects, then workspace
 * purges, then erased documentation collections, deleted conversation messages, erased Slack channels and Slack
 * messages changed in Slack, then people. It runs only in a server with background work off, so no scheduled task,
 * claimer or inbox subscriber writes while it skips the purge's quiescence guards; the {@code erasure-recovery} profile
 * turns background work off, and startup stops if anything turns it on again.
 */
@Component
@Profile("erasure-recovery")
@ConditionalOnServerRole
final class PersonDataRecovery implements SmartLifecycle {
    private static final int MAX_MANIFEST_BYTES = 16 * 1024 * 1024;

    enum FailureReason {
        RUNTIME_ROLE,
        BACKGROUND_WORK,
        MANIFEST_PATH,
        MANIFEST_READ,
        MANIFEST_VERSION,
        MANIFEST_INVALID,
        IDENTITY_INVALID,
        ERASURE_INCOMPLETE
    }

    private static final Logger log = LoggerFactory.getLogger(PersonDataRecovery.class);
    private final PersonDataService service;
    private final PersonIdentityResolver resolver;
    private final ObjectMapper mapper;
    private final Environment environment;
    private final PersonDataRequestRepository requests;
    private final RestoredWorkspacePurges purges;
    private final RestoredDisconnects disconnects;
    private final RestoredOwnerContent content;
    private final RestoredCollectionErasures collections;
    private final RestoredSlackMessageChanges slackMessages;
    private final RestoredSlackChannelErasures slackChannels;
    private final String manifestPath;
    private boolean running;

    PersonDataRecovery(
            PersonDataService service,
            PersonIdentityResolver resolver,
            ObjectMapper mapper,
            Environment environment,
            PersonDataRequestRepository requests,
            RestoredWorkspacePurges purges,
            RestoredDisconnects disconnects,
            RestoredOwnerContent content,
            RestoredCollectionErasures collections,
            RestoredSlackMessageChanges slackMessages,
            RestoredSlackChannelErasures slackChannels,
            PersonDataRecoveryProperties properties) {
        this.service = service;
        this.resolver = resolver;
        this.mapper = mapper;
        this.environment = environment;
        this.requests = requests;
        this.purges = purges;
        this.disconnects = disconnects;
        this.content = content;
        this.collections = collections;
        this.slackMessages = slackMessages;
        this.slackChannels = slackChannels;
        this.manifestPath = properties.recoveryManifest();
    }

    /**
     * Version 5 adds exact personal goal deletions. An earlier manifest cannot say that there were none.
     */
    record Manifest(
            int version,
            Instant restorePoint,
            boolean noLaterErasures,
            @Nullable Long administratorAccountId,
            List<Person> people,
            List<WorkspacePurge> workspacePurges,
            List<Disconnect> disconnects,
            List<CollectionErasure> collectionErasures,
            List<MessageDeletion> messageDeletions,
            List<UUID> recordDeletions,
            List<SlackMessageChange> slackMessageChanges,
            List<SlackChannelErasure> slackChannelErasures) {}

    record WorkspacePurge(long workspaceId) {}

    record Disconnect(long workspaceId, long connectionId) {}

    /** A documentation collection that an admin erased from the workspace's mirror. */
    record CollectionErasure(long workspaceId, long connectionId, String collectionId) {}

    /** A conversation message that its developer deleted. */
    record MessageDeletion(long workspaceId, UUID entryId) {}

    /** A Slack channel that an admin revoked or that Slack deleted. */
    record SlackChannelErasure(
            long workspaceId,
            String channelId,
            @Nullable Instant erasedAt,
            @Nullable Instant copiesErasedThrough) {}

    /** A stored Slack message that its author edited or deleted in Slack. */
    record SlackMessageChange(long workspaceId, long messageId, Change change) {
        enum Change {
            EDITED,
            DELETED
        }
    }

    record Person(@Nullable Long accountId, List<NativeIdentity> identities, boolean externalCopiesRemoved) {}

    record NativeIdentity(
            String providerType,
            String serverUrl,
            String subject,
            @Nullable String teamId) {}

    @Override
    @SuppressWarnings("PMD.PreserveStackTrace") // Parser errors can quote identities from the private manifest.
    public void start() {
        FailureReason reason = FailureReason.RUNTIME_ROLE;
        try {
            if (!RuntimeRole.enabled(environment).equals(List.of(RuntimeRole.SERVER)))
                throw new IllegalStateException();
            reason = FailureReason.BACKGROUND_WORK;
            if (!"false".equals(environment.getProperty(ConditionalOnBackgroundWork.PROPERTY)))
                throw new IllegalStateException();
            reason = FailureReason.MANIFEST_PATH;
            if (manifestPath.isBlank()) throw new IllegalStateException();
            reason = FailureReason.MANIFEST_READ;
            Path path = Path.of(manifestPath);
            long size = Files.size(path);
            reason = FailureReason.MANIFEST_INVALID;
            if (size > MAX_MANIFEST_BYTES) throw new IllegalStateException();
            Manifest manifest = mapper.readerFor(Manifest.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(Files.readAllBytes(path));
            reason = FailureReason.MANIFEST_VERSION;
            if (manifest.version() != 5) throw new IllegalStateException();
            reason = FailureReason.MANIFEST_INVALID;
            Objects.requireNonNull(manifest.restorePoint());
            Objects.requireNonNull(manifest.people());
            Objects.requireNonNull(manifest.workspacePurges());
            Objects.requireNonNull(manifest.disconnects());
            Objects.requireNonNull(manifest.collectionErasures());
            Objects.requireNonNull(manifest.messageDeletions());
            var recordDeletions = Set.copyOf(manifest.recordDeletions());
            Objects.requireNonNull(manifest.slackMessageChanges());
            Objects.requireNonNull(manifest.slackChannelErasures());
            boolean none = manifest.people().isEmpty()
                    && manifest.workspacePurges().isEmpty()
                    && manifest.disconnects().isEmpty()
                    && manifest.collectionErasures().isEmpty()
                    && manifest.messageDeletions().isEmpty()
                    && recordDeletions.isEmpty()
                    && manifest.slackMessageChanges().isEmpty()
                    && manifest.slackChannelErasures().isEmpty();
            Instant now = Instant.now();
            if (manifest.restorePoint().isAfter(now)
                    || manifest.noLaterErasures() != none
                    || manifest.workspacePurges().stream().anyMatch(purge -> purge.workspaceId() <= 0)
                    || manifest.disconnects().stream()
                            .anyMatch(disconnect -> disconnect.workspaceId() <= 0 || disconnect.connectionId() <= 0)
                    || manifest.collectionErasures().stream()
                            .anyMatch(collection -> collection.workspaceId() <= 0
                                    || collection.connectionId() <= 0
                                    || collection.collectionId() == null
                                    || collection.collectionId().isBlank())
                    || manifest.messageDeletions().stream()
                            .anyMatch(message -> message.workspaceId() <= 0 || message.entryId() == null)
                    || manifest.slackChannelErasures().stream()
                            .anyMatch(channel -> channel.workspaceId() <= 0
                                    || (channel.erasedAt() != null
                                            && channel.erasedAt().isAfter(now))
                                    || (channel.copiesErasedThrough() != null
                                            && channel.copiesErasedThrough().isAfter(now))
                                    || channel.channelId() == null
                                    || channel.channelId().isBlank())
                    || manifest.slackMessageChanges().stream()
                            .anyMatch(message -> message.workspaceId() <= 0
                                    || message.messageId() <= 0
                                    || message.change() == null)) {
                throw new IllegalStateException();
            }
            // Validate every native identity before the first erasure changes a store.
            reason = FailureReason.IDENTITY_INVALID;
            List<List<PersonIdentity>> identities = manifest.people().stream()
                    .map(person ->
                            person.identities().stream().map(this::resolve).toList())
                    .toList();
            reason = FailureReason.MANIFEST_INVALID;
            for (int index = 0; index < manifest.people().size(); index++) {
                Person person = manifest.people().get(index);
                if ((person.accountId() != null && person.accountId() <= 0)
                        || (person.accountId() == null && identities.get(index).isEmpty())) {
                    throw new IllegalStateException();
                }
            }
            Long administrator = manifest.administratorAccountId();
            if (!manifest.people().isEmpty() && (administrator == null || administrator <= 0))
                throw new IllegalStateException();
            reason = FailureReason.ERASURE_INCOMPLETE;
            // Each step is idempotent: one whose target the restore lacks, or that is erased already, does nothing.
            for (Disconnect disconnect : manifest.disconnects())
                disconnects.disconnectAgain(disconnect.workspaceId(), disconnect.connectionId());
            for (WorkspacePurge purge : manifest.workspacePurges()) purges.purgeAgain(purge.workspaceId());
            for (CollectionErasure collection : manifest.collectionErasures())
                collections.eraseAgain(collection.workspaceId(), collection.connectionId(), collection.collectionId());
            content.deleteAgain(manifest.messageDeletions(), recordDeletions);
            for (SlackChannelErasure channel : manifest.slackChannelErasures()) {
                Instant erasedAt = Objects.requireNonNullElse(channel.erasedAt(), manifest.restorePoint());
                slackChannels.eraseAgain(
                        channel.workspaceId(),
                        channel.channelId(),
                        erasedAt,
                        Objects.requireNonNullElse(channel.copiesErasedThrough(), erasedAt));
            }
            for (SlackMessageChange message : manifest.slackMessageChanges())
                slackMessages.eraseAgain(
                        message.workspaceId(),
                        message.messageId(),
                        message.change() == SlackMessageChange.Change.DELETED);
            if (!manifest.people().isEmpty()) {
                for (int index = 0; index < manifest.people().size(); index++) {
                    reapplyErasure(
                            Objects.requireNonNull(administrator),
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
                    .addKeyValue("person_data.recovery.reason", reason)
                    .log("Erasure recovery blocked startup: {}", reason);
            throw new IllegalStateException("Erasure recovery is incomplete (" + reason
                    + "). Keep all other roles stopped and check the private manifest and erasure receipts.");
        }
    }

    /** Recovery uses the same frozen requests and receipts, including an interrupted request restored from WAL. */
    private void reapplyErasure(
            long administratorId,
            @Nullable Long accountId,
            List<PersonIdentity> identities,
            boolean externalCopiesRemoved) {
        PersonScope person = resolver.resolve(accountId, identities);
        List<UUID> interrupted =
                requests.findInterruptedForPerson(person.accountId(), mapper.writeValueAsString(person.identities()));
        for (UUID id : interrupted) {
            service.requestErasure(id, administratorId, externalCopiesRemoved);
            service.runRecovery(id);
            requireRecoveryComplete(id);
        }
        UUID id = service.preview(administratorId, accountId, identities)
                .request()
                .getId();
        service.requestErasure(id, administratorId, externalCopiesRemoved);
        service.runRecovery(id);
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
            // An OIDC subject is an opaque string that its issuer chose; it has no workspace key.
            case "OIDC" ->
                identity.teamId() == null
                        && !identity.subject().isBlank()
                        && identity.subject().length() <= 255;
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
