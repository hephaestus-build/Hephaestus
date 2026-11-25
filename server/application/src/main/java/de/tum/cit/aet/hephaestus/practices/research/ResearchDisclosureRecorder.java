package de.tum.cit.aet.hephaestus.practices.research;

import de.tum.cit.aet.hephaestus.core.auth.spi.ResearchDisclosureAudit;
import de.tum.cit.aet.hephaestus.core.auth.spi.ResearchParticipationQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.ResearchParticipationQuery.GrantStanding;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PrivacyJournal;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PrivacyJournalKind;
import de.tum.cit.aet.hephaestus.core.privacy.spi.ResearchSubjectReference;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.research.ResearchDisclosureSubject.RemovalReason;
import de.tum.cit.aet.hephaestus.practices.research.ResearchDisclosureSubjectRepository.SubjectState;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Every write of the research disclosure aggregate, each in one short transaction. None of them reads
 * practice data or serialises a file, and none spans an HTTP transfer.
 */
@Component
@ConditionalOnServerRole
class ResearchDisclosureRecorder {

    private final PrivacyJournal journal;
    private final ResearchDisclosureRepository disclosures;
    private final ResearchDisclosureSubjectRepository subjects;
    private final ResearchParticipationQuery consent;
    private final ResearchDisclosureAudit audit;

    ResearchDisclosureRecorder(
            ResearchDisclosureRepository disclosures,
            ResearchDisclosureSubjectRepository subjects,
            ResearchParticipationQuery consent,
            ResearchDisclosureAudit audit,
            PrivacyJournal journal) {
        this.journal = journal;
        this.disclosures = disclosures;
        this.subjects = subjects;
        this.consent = consent;
        this.audit = audit;
    }

    record Release(
            UUID datasetId,
            long workspaceId,
            Instant windowFrom,
            Instant windowTo,
            Instant useUntil,
            String contentSha256,
            ResearchDataset.Counts counts) {}

    /**
     * The authorisation cut. The participants' account rows are share-locked first, as every consent
     * write and purge locks them, so a withdrawal either committed before this read and refuses the
     * release, or waits until the receipt has committed and becomes a downstream removal. Each
     * participant's current grant must be the very decision the snapshot read, not merely another yes.
     */
    @Transactional(timeout = 15)
    public ResearchDisclosure authorize(
            Release release, ResearchSnapshot snapshot, Map<Long, String> participantKeys, long adminId, Instant now) {
        if (!consent.currentQuestion().equals(Optional.of(snapshot.question()))) {
            throw changed();
        }
        Map<Long, Long> current = consent.lockCurrentGrants(participantKeys.keySet());
        for (Long account : participantKeys.keySet()) {
            if (!Objects.equals(snapshot.grants().get(account), current.get(account))) {
                throw changed();
            }
        }
        var disclosure = disclosures.save(ResearchDisclosure.builder()
                .id(release.datasetId())
                .workspaceId(release.workspaceId())
                .purpose(ResearchDisclosure.PURPOSE)
                .schemaName(ResearchDataset.SCHEMA)
                .schemaVersion(ResearchDataset.VERSION)
                .windowFrom(release.windowFrom())
                .windowTo(release.windowTo())
                .cutoffAt(snapshot.cutoff())
                .noticeVersion(snapshot.question().noticeVersion())
                .researchOrganization(snapshot.question().organization())
                .createdByAccountId(adminId)
                .createdAt(now)
                .contentSha256(release.contentSha256())
                .participantCount(release.counts().participants())
                .resultCount(release.counts().results())
                .problemCount(release.counts().problems())
                .feedbackCount(release.counts().feedback())
                .feedbackExcludedCount(release.counts().feedbackExcludedIncompleteBasis())
                .useUntil(release.useUntil())
                .build());
        List<ResearchDisclosureSubject> rows = new ArrayList<>();
        participantKeys.forEach((account, key) -> rows.add(new ResearchDisclosureSubject(
                release.datasetId(),
                release.workspaceId(),
                account,
                key,
                Objects.requireNonNull(current.get(account)))));
        subjects.saveAll(rows);
        subjects.flush();
        boolean audited = audit.recordRelease(
                adminId,
                release.workspaceId(),
                release.datasetId(),
                ResearchDataset.VERSION,
                new ResearchDisclosureAudit.Counts(
                        release.counts().participants(),
                        release.counts().results(),
                        release.counts().feedback()));
        if (!audited) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "The release could not be audited. Nothing was released.");
        }
        journal.record(
                release.datasetId(),
                PrivacyJournalKind.RESEARCH_DATASET_RELEASED,
                Map.of(
                        "datasetId",
                        release.datasetId(),
                        "workspaceId",
                        release.workspaceId(),
                        "receiptDigest",
                        release.contentSha256(),
                        "releasedAt",
                        now,
                        "useUntil",
                        release.useUntil(),
                        "subjectLinks",
                        participantKeys.entrySet().stream()
                                .map(entry -> new ResearchSubjectReference(
                                        entry.getKey(),
                                        entry.getValue(),
                                        Objects.requireNonNull(current.get(entry.getKey()))))
                                .toList()));
        return disclosure;
    }

    record Obligation(String participantKey, RemovalReason reason, Instant requiredAt) {}

    record OpenDisclosure(
            UUID datasetId,
            long workspaceId,
            Instant createdAt,
            Instant useUntil,
            boolean useExpired,
            List<Obligation> removals) {}

    /**
     * Compares every still-linked participant's original grant with the ledger today, records any new
     * obligation, and lists every open disclosure, including those with no removal owed. An obligation,
     * once recorded, stays: a later regrant does not cancel a removal already owed for data released under
     * the earlier grant.
     *
     * <p>Account erasure and receipts change the same rows without this transaction's locks. So the rows are read
     * as values, each new reason is written only while the row still links the grant that was checked, and the
     * manifest reports the rows as stored afterwards: an erasure or receipt that committed meanwhile stays.
     */
    @Transactional(timeout = 30)
    public List<OpenDisclosure> detectRemovals(Instant now) {
        List<ResearchDisclosure> open = disclosures.findOpen();
        if (open.isEmpty()) return List.of();
        List<UUID> ids = open.stream().map(ResearchDisclosure::getId).toList();
        Map<UUID, List<SubjectState>> checked =
                subjects.stateOf(ids).stream().collect(Collectors.groupingBy(SubjectState::disclosureId));
        for (ResearchDisclosure disclosure : open) {
            List<SubjectState> rows = checked.getOrDefault(disclosure.getId(), List.of());
            // One ledger read per disclosure: an account appears at most once in each, under one grant.
            Map<Long, Long> linked = new HashMap<>();
            for (var row : rows) {
                Long account = row.accountId();
                Long grant = row.grantDecisionId();
                if (row.removalReason() == null && account != null && grant != null) {
                    linked.put(account, grant);
                }
            }
            Map<Long, GrantStanding> standings = consent.grantStandings(linked);
            for (var row : rows) {
                Long account = row.accountId();
                Long grant = row.grantDecisionId();
                if (row.removalReason() != null || account == null || grant == null) continue;
                RemovalReason reason = reason(standings.get(account));
                if (reason != null
                        && subjects.requireRemoval(row.workspaceId(), row.id(), account, grant, reason.name(), now)
                                == 1) {
                    journal.record(
                            row.id(),
                            PrivacyJournalKind.RESEARCH_REMOVAL_OWED,
                            Map.of(
                                    "datasetId",
                                    row.disclosureId(),
                                    "rowKey",
                                    row.participantKey(),
                                    "reason",
                                    reason.name()));
                }
            }
        }
        Map<UUID, List<SubjectState>> stored =
                subjects.stateOf(ids).stream().collect(Collectors.groupingBy(SubjectState::disclosureId));
        List<OpenDisclosure> manifest = new ArrayList<>();
        for (ResearchDisclosure disclosure : open) {
            List<Obligation> owed = new ArrayList<>();
            for (var row : stored.getOrDefault(disclosure.getId(), List.of())) {
                if (row.isRemovalOutstanding()) {
                    owed.add(new Obligation(
                            row.participantKey(),
                            Objects.requireNonNull(row.removalReason()),
                            Objects.requireNonNull(row.removalRequiredAt())));
                }
            }
            boolean expired = disclosure.isUseExpired(now);
            manifest.add(new OpenDisclosure(
                    disclosure.getId(),
                    disclosure.getWorkspaceId(),
                    disclosure.getCreatedAt(),
                    disclosure.getUseUntil(),
                    expired,
                    List.copyOf(owed)));
        }
        return List.copyOf(manifest);
    }

    /**
     * The operator's attestation that these participants were removed from the retained copy. It writes only the
     * receipt and the dropped link, all of them or none, so the first reason and its date stay as recorded.
     */
    @Transactional(timeout = 15)
    public int recordRemoved(UUID datasetId, Collection<String> participantKeys, long adminId, Instant now) {
        ResearchDisclosure disclosure = openForUpdate(datasetId);
        Set<String> requested = Set.copyOf(participantKeys);
        Map<String, SubjectState> rows = subjects.stateOf(List.of(datasetId)).stream()
                .collect(Collectors.toMap(SubjectState::participantKey, row -> row));
        for (String key : requested) {
            SubjectState row = rows.get(key);
            if (row == null || !row.isRemovalOutstanding()) {
                throw outstandingOnly();
            }
            if (now.isBefore(Objects.requireNonNull(row.removalRequiredAt()))) {
                throw new IllegalArgumentException("Removal precedes the obligation");
            }
        }
        int recorded =
                subjects.recordRemoved(disclosure.getWorkspaceId(), datasetId, requested.toArray(String[]::new), now);
        if (recorded != requested.size()) {
            throw outstandingOnly();
        }
        requireAudited(audit.recordRemoval(adminId, disclosure.getWorkspaceId(), datasetId, requested.size(), null));
        journal.record(
                PrivacyJournalKind.RESEARCH_REMOVAL_RECORDED,
                Map.of(
                        "datasetId",
                        datasetId,
                        "rowKeys",
                        requested.stream().sorted().toList(),
                        "removedAt",
                        now));
        return requested.size();
    }

    /**
     * The operator's attestation that the whole retained copy was deleted or anonymised. Only now is the
     * protected mapping deleted; the header stays as a receipt without subject identity.
     */
    @Transactional(timeout = 15)
    public void close(UUID datasetId, ResearchDisclosure.Closure closure, long adminId, Instant now) {
        ResearchDisclosure disclosure = openForUpdate(datasetId);
        disclosure.close(closure, now);
        int removed = subjects.deleteForDisclosure(disclosure.getWorkspaceId(), datasetId);
        requireAudited(audit.recordRemoval(adminId, disclosure.getWorkspaceId(), datasetId, removed, closure.name()));
        if (removed > 0) {
            journal.record(
                    PrivacyJournalKind.RESEARCH_MAPPING_ERASED,
                    Map.of("datasetId", datasetId, "closure", closure, "closedAt", now));
        }
    }

    private ResearchDisclosure openForUpdate(UUID datasetId) {
        ResearchDisclosure disclosure = disclosures
                .findByIdForUpdate(datasetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "The dataset was not found."));
        if (disclosure.isClosed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The dataset is already closed.");
        }
        return disclosure;
    }

    private static ResponseStatusException outstandingOnly() {
        return new ResponseStatusException(
                HttpStatus.CONFLICT, "Only participants with an outstanding removal can be marked removed.");
    }

    private static void requireAudited(boolean audited) {
        if (!audited) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "The receipt could not be audited. Nothing was recorded.");
        }
    }

    static @Nullable RemovalReason reason(@Nullable GrantStanding standing) {
        if (standing == null) return null;
        return switch (standing) {
            case CURRENT, ACCOUNT_INACTIVE -> null;
            case WITHDRAWN -> RemovalReason.CONSENT_WITHDRAWN;
            case SUPERSEDED -> RemovalReason.CONSENT_SUPERSEDED;
            case QUESTION_CHANGED -> RemovalReason.QUESTION_CHANGED;
            case ACCOUNT_ERASED -> RemovalReason.ACCOUNT_ERASED;
        };
    }

    private static ResponseStatusException changed() {
        return new ResponseStatusException(
                HttpStatus.CONFLICT, "Research consent changed while the dataset was built. Nothing was released.");
    }
}
