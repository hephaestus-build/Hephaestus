package de.tum.cit.aet.hephaestus.agent.handler;

import de.tum.cit.aet.hephaestus.agent.context.providers.DocumentContentSource;
import de.tum.cit.aet.hephaestus.agent.conversation.ConversationSourceLiveness;
import de.tum.cit.aet.hephaestus.agent.documentation.DocumentProjection;
import de.tum.cit.aet.hephaestus.agent.handler.PracticeDetectionResultParser.ValidatedObservation;
import de.tum.cit.aet.hephaestus.agent.handler.spi.EvidenceQuoteUnverifiedException;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobDeliveryException;
import de.tum.cit.aet.hephaestus.agent.handler.spi.ObservationsRefusedException;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.ReviewMemberAiPolicy;
import de.tum.cit.aet.hephaestus.agent.runtime.ProvenanceDigest;
import de.tum.cit.aet.hephaestus.evidence.ArtifactSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.evidence.SourceContractVersion;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.integration.core.fabric.ContentAddressedStore;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ActorRole;
import de.tum.cit.aet.hephaestus.integration.scm.ReviewTargetQuery;
import de.tum.cit.aet.hephaestus.practices.EvidenceStance;
import de.tum.cit.aet.hephaestus.practices.PracticeBinding;
import de.tum.cit.aet.hephaestus.practices.PracticeRevisionRepository;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.AssessmentStatus;
import de.tum.cit.aet.hephaestus.practices.model.ObservationOrigin;
import de.tum.cit.aet.hephaestus.practices.model.Outcome;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeRevision;
import de.tum.cit.aet.hephaestus.practices.model.Presence;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationFingerprint;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.practices.observation.PracticeDetectionCompletedEvent;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public class PracticeDetectionDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(PracticeDetectionDeliveryService.class);

    private final ReviewMemberAiPolicy memberAiPolicy;
    private final PracticeRevisionRepository practiceRevisionRepository;
    private final ObservationRepository observationRepository;
    private final ReviewTargetQuery reviewTargets;
    private final ConversationSourceLiveness conversationSourceLiveness;
    private final DocumentProjection documentProjection;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final ContentAddressedStore cas;
    private final ArtifactSourceCatalogRegistry sourceCatalogs;

    public PracticeDetectionDeliveryService(
            PracticeRevisionRepository practiceRevisionRepository,
            ObservationRepository observationRepository,
            ReviewTargetQuery reviewTargets,
            ConversationSourceLiveness conversationSourceLiveness,
            DocumentProjection documentProjection,
            ApplicationEventPublisher eventPublisher,
            ObjectMapper objectMapper,
            ContentAddressedStore cas,
            ArtifactSourceCatalogRegistry sourceCatalogs,
            ReviewMemberAiPolicy memberAiPolicy) {
        this.practiceRevisionRepository = practiceRevisionRepository;
        this.observationRepository = observationRepository;
        this.reviewTargets = reviewTargets;
        this.conversationSourceLiveness = conversationSourceLiveness;
        this.documentProjection = documentProjection;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
        this.cas = cas;
        this.sourceCatalogs = sourceCatalogs;
        this.memberAiPolicy = memberAiPolicy;
    }

    /** Metadata key for the run's immutable observation origin. */
    public static final String ORIGIN_METADATA_KEY = "observation_origin";

    private record Target(ArtifactKind type, Long id, Long aboutUserId) {}

    /**
     * The origin stamped on this job, or {@link ObservationOrigin#LIVE} for a job with no origin key: every
     * such job came from the event-driven path, so LIVE is a fact, not a guess.
     */
    public static ObservationOrigin originOf(@Nullable JsonNode metadata) {
        JsonNode node = metadata == null ? null : metadata.get(ORIGIN_METADATA_KEY);
        if (node == null || !node.isString()) {
            return ObservationOrigin.LIVE;
        }
        try {
            return ObservationOrigin.valueOf(node.asString());
        } catch (IllegalArgumentException unknown) {
            // Reject unknown origins rather than classifying them as unbiased live runs.
            throw new JobDeliveryException("Unknown observation origin in job metadata: " + node.asString(), unknown);
        }
    }

    @Transactional
    public DeliveryResult deliver(AgentJob job, List<ValidatedObservation> validObservations) {
        Long workspaceId = job.getWorkspace().getId();
        JsonNode metadata = job.getMetadata();
        if (metadata == null) {
            throw new JobDeliveryException("Missing job metadata: jobId=" + job.getId());
        }

        if (!memberAiPolicy.allowsResult(job))
            throw new ObservationsRefusedException(
                    "member_ai_declined",
                    "The developer's AI preference no longer permits recording this review result.");
        EvidenceBoundary evidenceBoundary = evidenceBoundary(job);
        for (SourceKind kind : evidenceBoundary.allowedSources()) {
            if (!sourceCatalogs.isSourceUsePermitted(
                    evidenceBoundary.contractVersion(), kind, SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY)) {
                throw new JobDeliveryException(
                        "Evidence source authorization was withdrawn before delivery: source=" + kind
                                + ", jobId="
                                + job.getId());
            }
        }
        Target target = resolveTarget(job, metadata);
        Map<String, PracticeRevision> revisionsBySlug = admittedRevisions(job, workspaceId);
        // A quote that does not verify discredits its own claim, and only EvidenceQuoteUnverifiedException
        // means that. Every other refusal here — an unstaged source, a malformed citation, work attributed
        // to the wrong person — impugns the run, so it stays fatal.
        List<Integer> admittedIndexes = new ArrayList<>(validObservations.size());
        List<ValidatedObservation> admittedObservations = new ArrayList<>(validObservations.size());
        List<String> withheldObservations = new ArrayList<>();
        boolean withheldNegative = false;
        for (int submittedIndex = 0; submittedIndex < validObservations.size(); submittedIndex++) {
            ValidatedObservation observation = validObservations.get(submittedIndex);
            observation
                    .assessmentStatus()
                    .validate(observation.presence(), observation.assessment(), observation.severity());
            String expectedWarrant =
                    switch (observation.assessmentStatus()) {
                        case NOT_APPLICABLE -> "inapplicability";
                        case UNDETERMINED -> "undecidability";
                        case ASSESSED -> observation.presence() == Presence.ABSENT ? "search" : "";
                    };
            for (String warrant : List.of("search", "inapplicability", "undecidability")) {
                JsonNode evidence = observation.evidence();
                if (!warrant.equals(expectedWarrant) && evidence != null && evidence.hasNonNull(warrant)) {
                    throw new JobDeliveryException("Observation evidence warrant does not match status and presence");
                }
            }
            if (observation.assessmentStatus() == AssessmentStatus.UNDETERMINED) {
                JsonNode evidence = observation.evidence();
                JsonNode warrant = evidence == null ? null : evidence.get("undecidability");
                if (warrant == null
                        || !warrant.isObject()
                        || !warrant.path("openQuestion").isString()
                        || warrant.path("openQuestion").asString().isBlank()
                        || !warrant.path("wouldSettleIt").isString()
                        || warrant.path("wouldSettleIt").asString().isBlank()) {
                    throw new JobDeliveryException("UNDETERMINED requires an open question and what would settle it");
                }
            }
            PracticeRevision revision = revisionsBySlug.get(observation.practiceSlug());
            if (revision == null) {
                throw new JobDeliveryException(
                        "Observation references a practice not admitted to the job: slug=" + observation.practiceSlug()
                                + ", jobId="
                                + job.getId());
            }
            var targetAssessment = Practice.declaredTargetAssessment(revision.getCriteria());
            if (observation.assessmentStatus() == AssessmentStatus.ASSESSED
                    && targetAssessment != null
                    && observation.assessment() != targetAssessment) {
                throw new JobDeliveryException(
                        "Observation changes the fixed target assessment for practice " + observation.practiceSlug());
            }

            enforceAttribution(observation, revision, job);
            try {
                enforceEvidenceBoundary(observation, revision, evidenceBoundary, job);
                admittedIndexes.add(submittedIndex);
                admittedObservations.add(observation);
            } catch (EvidenceQuoteUnverifiedException ex) {
                withheldNegative |= observation.outcome() == Outcome.NEGATIVE;
                withheldObservations.add(observation.practiceSlug() + ": " + ex.getMessage());
            }
        }
        if (!withheldObservations.isEmpty()) {
            // Per claim, because a model that cannot quote its own evidence is a defect an otherwise
            // successful delivery would hide.
            log.warn(
                    "Withheld {} of {} observation(s) whose quoted evidence did not verify, delivering the rest: jobId={} withheld={}",
                    withheldObservations.size(),
                    validObservations.size(),
                    job.getId(),
                    withheldObservations);
            // Withholding the only fault leaves an all-clear standing over a defect the model did find,
            // which is a different statement to the reader than an incomplete review.
            if (withheldNegative && admittedObservations.stream().noneMatch(o -> o.outcome() == Outcome.NEGATIVE)) {
                log.error(
                        "Withheld every negative observation; the remaining claims read as an all-clear: jobId={}",
                        job.getId());
            }
        }
        // Only when there was something to admit: a review that found nothing still publishes its zero.
        if (admittedObservations.isEmpty() && !validObservations.isEmpty()) {
            throw new ObservationsRefusedException(
                    "no_valid_observations",
                    "No observation survived the evidence check, so there is nothing to deliver: jobId=" + job.getId()
                            + ", withheld="
                            + withheldObservations);
        }

        ObservationOrigin origin = originOf(metadata);
        // The one person this job resolved. Sound for every observation only because the catalogue injector
        // withheld every practice whose occasion is about somebody else, and enforceAttribution above
        // refuses one that reached here anyway.
        Long aboutUserId = target.aboutUserId();
        ArtifactKind artifactKind = target.type();
        Long artifactId = target.id();

        int inserted = 0;
        int discardedDuplicate = 0;
        boolean hasNegative = false;
        Instant observedAt = Instant.now();

        // Carries the keys each observation was persisted under.
        List<ValidatedObservation> deliveredObservations = new ArrayList<>(admittedObservations.size());

        for (int i = 0; i < admittedObservations.size(); i++) {
            ValidatedObservation observation = admittedObservations.get(i);

            PracticeRevision revision = Objects.requireNonNull(
                    revisionsBySlug.get(observation.practiceSlug()), "Validated practice revision is missing");
            Practice practice = revision.getPractice();

            // Submitted position keeps retry idempotency keys stable when admission outcomes change.
            String occurrenceKey = observation.practiceSlug() + ":"
                    + admittedIndexes.get(i)
                    + ":"
                    + artifactKind.value()
                    + ":"
                    + artifactId
                    + ":"
                    + job.getId();

            String evidenceJson = null;
            if (observation.evidence() != null) {
                try {
                    evidenceJson = objectMapper.writeValueAsString(evidenceForPersistence(observation.evidence()));
                } catch (JacksonException e) {
                    throw new JobDeliveryException("Could not serialize validated evidence: jobId=" + job.getId(), e);
                }
            }

            // Recurrence identity is content-derived and stable across runs (ADR 0021).
            String recurrenceKey = ObservationFingerprint.compute(
                    observation.practiceSlug(),
                    artifactKind.value(),
                    artifactId,
                    aboutUserId,
                    firstLocationPath(observation.evidence()));
            deliveredObservations.add(observation.withKeys(new ObservationKeys(occurrenceKey, recurrenceKey)));

            Long practiceRevisionId = Objects.requireNonNull(revision.getId(), "Practice revision must be persisted");

            // Enforced here because the native insertIfAbsent path bypasses Observation's @PrePersist
            // (ADR-0022): severity is an impact band for a BAD observation only.
            String severityName = observation.outcome() == Outcome.NEGATIVE && observation.severity() != null
                    ? observation.severity().name()
                    : null;

            int rows = observationRepository.insertIfAbsent(
                    UUID.randomUUID(),
                    occurrenceKey,
                    job.getId(),
                    job.getWorkspace().getId(),
                    practice.getId(),
                    practiceRevisionId,
                    artifactKind.value(),
                    artifactId,
                    aboutUserId,
                    observation.summary(),
                    observation.assessmentStatus().name(),
                    observation.presence() == null
                            ? null
                            : observation.presence().name(),
                    observation.assessment() == null
                            ? null
                            : observation.assessment().name(),
                    severityName,
                    evidenceJson,
                    observation.evidenceRationale(),
                    recurrenceKey,
                    observedAt,
                    origin.name());

            if (rows == 1) {
                inserted++;
            } else {
                discardedDuplicate++;
            }
            // Gate on the assessment, not the insert result: a retry's insertIfAbsent returns 0 for an
            // already-persisted observation, yet hasNegative must still reflect it for the delivery gate.
            if (observation.outcome() == Outcome.NEGATIVE) {
                hasNegative = true;
            }
        }

        log.info(
                "Practice reviews delivery: inserted={}, duplicate={}, jobId={}",
                inserted,
                discardedDuplicate,
                job.getId());

        eventPublisher.publishEvent(new PracticeDetectionCompletedEvent(
                job.getId(),
                workspaceId,
                artifactKind,
                artifactId,
                aboutUserId,
                inserted,
                discardedDuplicate,
                hasNegative));

        return new DeliveryResult(inserted, discardedDuplicate, hasNegative, deliveredObservations);
    }

    private void enforceAttribution(ValidatedObservation observation, PracticeRevision revision, AgentJob job) {
        ActorRole subject =
                PracticeBinding.subjectRoleOf(revision.getBindings(), PracticeCatalogInjector.signalOf(job));
        JsonNode metadata = job.getMetadata();
        boolean reviewerRun = metadata != null
                && metadata.path("about_user_id").isNumber()
                && "REVIEWER".equals(metadata.path("subject_role").asString());
        if ((subject == ActorRole.AUTHOR && !reviewerRun) || (subject == ActorRole.REVIEWER && reviewerRun)) {
            return;
        }
        throw new JobDeliveryException("Observation is about a " + subject
                + " this review cannot name, so it has nobody to be filed against: slug="
                + observation.practiceSlug()
                + ", jobId="
                + job.getId());
    }

    private void enforceEvidenceBoundary(
            ValidatedObservation observation, PracticeRevision revision, EvidenceBoundary boundary, AgentJob job) {
        if (revision.getAutomatedReviewPolicy() == null || revision.getBindings() == null) {
            throw new JobDeliveryException("Practice has no evidence requirements: slug=" + observation.practiceSlug()
                    + ", jobId=" + job.getId());
        }
        JsonNode evidence = observation.evidence();
        if (evidence == null) {
            throw new JobDeliveryException(
                    "Observation has no source-bound evidence citation: slug=" + observation.practiceSlug()
                            + ", jobId="
                            + job.getId());
        }
        JsonNode citations = evidence.get("citations");
        if (citations == null || !citations.isArray() || citations.isEmpty()) {
            throw new JobDeliveryException(
                    "Observation has no source-bound evidence citation: slug=" + observation.practiceSlug()
                            + ", jobId="
                            + job.getId());
        }
        // What a practice may CITE is not narrowed to its bindings: every source that applies to the
        // artifact is staged for every review, so a quote from an unbound source is still a quote from
        // bytes that were really there — the fabrication check is the byte-exact quote below, not binding
        // membership. Only EXHAUSTIVE stance (an ABSENCE claim) is the practice's own to make.
        Set<SourceKind> exhaustive = new HashSet<>();
        PracticeBinding.needsFor(revision.getBindings(), PracticeCatalogInjector.signalOf(job))
                .forEach(need -> {
                    if (need.stance() == EvidenceStance.EXHAUSTIVE) {
                        exhaustive.add(need.sourceKind());
                    }
                });
        enforceRecordedSearch(observation, exhaustive, boundary, job);
        enforceStatedInapplicability(observation, boundary, job);
        for (JsonNode citation : citations) {
            JsonNode sourceKind = citation.path("sourceKind");
            JsonNode artifactPath = citation.path("artifactPath");
            JsonNode path = citation.path("path");
            JsonNode side = citation.path("side");
            JsonNode startLine = citation.path("startLine");
            JsonNode endLine = citation.path("endLine");
            JsonNode quote = citation.path("quote");
            JsonNode quoteSha256 = citation.path("quoteSha256");
            boolean redactedSecretCitation =
                    "secret-diff-scanner".equals(evidence.path("detector").asString())
                            && quote.isMissingNode()
                            && quoteSha256.isString()
                            && quoteSha256.asString().matches("[0-9a-f]{64}");
            if (!citation.isObject()
                    || !sourceKind.isString()
                    || !artifactPath.isString()
                    || !path.isString()
                    || ("scm.pull-request.diff".equals(sourceKind.asString())
                            && (!side.isString() || !("OLD".equals(side.asString()) || "NEW".equals(side.asString()))))
                    || (!"scm.pull-request.diff".equals(sourceKind.asString()) && !side.isMissingNode())
                    || !startLine.isIntegralNumber()
                    || startLine.asInt() < 1
                    || (!endLine.isMissingNode()
                            && (!endLine.isIntegralNumber() || endLine.asInt() < startLine.asInt()))
                    || (!quote.isString() && !redactedSecretCitation)) {
                throw new JobDeliveryException(
                        "Observation has an invalid evidence citation: slug=" + observation.practiceSlug()
                                + ", jobId="
                                + job.getId());
            }
            SourceKind kind;
            try {
                kind = new SourceKind(sourceKind.asString());
            } catch (IllegalArgumentException e) {
                throw new JobDeliveryException(
                        "Observation has invalid evidence-source attribution: slug=" + observation.practiceSlug()
                                + ", jobId="
                                + job.getId(),
                        e);
            }
            SourceArtifactRef artifact = boundary.artifacts().get(artifactPath.asString());
            if (!boundary.allowedSources().contains(kind)
                    || artifact == null
                    || !artifact.kind().equals(kind)) {
                throw new JobDeliveryException("Observation cited unavailable or misattributed evidence source " + kind
                        + ": slug="
                        + observation.practiceSlug()
                        + ", jobId="
                        + job.getId());
            }
            String exactQuote = quote.asString("");
            if (!redactedSecretCitation && exactQuote.isBlank()) {
                throw new JobDeliveryException(
                        "Observation has an empty evidence quote: slug=" + observation.practiceSlug()
                                + ", jobId="
                                + job.getId());
            }
            byte[] content = cas.get(artifact.sha256())
                    .orElseThrow(() -> new JobDeliveryException(
                            "Cited evidence artifact is no longer available: path=" + artifactPath.asString()
                                    + ", jobId="
                                    + job.getId()));
            String artifactContent = new String(content, StandardCharsets.UTF_8);
            if (!"scm.pull-request.diff".equals(kind.value()) && !artifactContent.contains(exactQuote)) {
                throw new EvidenceQuoteUnverifiedException(
                        "Evidence quote does not occur in the cited artifact: path=" + artifactPath.asString()
                                + ", jobId="
                                + job.getId());
            }
            if ("scm.pull-request.diff".equals(kind.value())
                    && !(redactedSecretCitation
                            ? diffContainsRedactedCitation(
                                    artifactContent,
                                    path.asString(),
                                    side.asString(),
                                    startLine.asInt(),
                                    quoteSha256.asString())
                            : diffContainsCitation(
                                    artifactContent,
                                    path.asString(),
                                    side.asString(),
                                    startLine.asInt(),
                                    endLine.isMissingNode() ? startLine.asInt() : endLine.asInt(),
                                    exactQuote))) {
                throw new EvidenceQuoteUnverifiedException(
                        "Evidence quote does not match the cited diff location: path=" + path.asString()
                                + ", line="
                                + startLine.asInt()
                                + ", jobId="
                                + job.getId());
            }
        }
    }

    private static boolean diffContainsRedactedCitation(
            String diff, String citedPath, String citedSide, int citedLine, String quoteSha256) {
        String oldPath = null;
        String newPath = null;
        for (String storedLine : diff.split("\n", -1)) {
            String line = storedLine;
            Integer lineNumber = null;
            if (storedLine.startsWith("[L")) {
                int end = storedLine.indexOf("] ");
                if (end <= 2) continue;
                try {
                    lineNumber = Integer.parseInt(storedLine.substring(2, end));
                    line = storedLine.substring(end + 2);
                } catch (NumberFormatException ignored) {
                    return false;
                }
            }
            if (line.startsWith("--- ")) oldPath = parseDiffPath(line.substring(4));
            if (line.startsWith("+++ ")) newPath = parseDiffPath(line.substring(4));
            if (lineNumber == null) continue;
            String lineSide = line.startsWith("-") ? "OLD" : "NEW";
            String linePath = "OLD".equals(lineSide) ? oldPath : newPath;
            if (lineNumber == citedLine && citedSide.equals(lineSide) && citedPath.equals(linePath)) {
                String content = line.startsWith("+") || line.startsWith("-") ? line.substring(1) : line;
                return ProvenanceDigest.sha256Hex(content.strip().getBytes(StandardCharsets.UTF_8))
                        .equals(quoteSha256);
            }
        }
        return false;
    }

    /** Requires NOT_APPLICABLE claims to identify the subject, exclusion reason, and consulted sources. */
    private void enforceStatedInapplicability(
            ValidatedObservation observation, EvidenceBoundary boundary, AgentJob job) {
        if (observation.assessmentStatus() != AssessmentStatus.NOT_APPLICABLE) {
            return;
        }
        JsonNode inapplicability =
                observation.evidence() == null ? null : observation.evidence().get("inapplicability");
        JsonNode consulted = inapplicability == null ? null : inapplicability.get("consulted");
        if (inapplicability == null
                || consulted == null
                || !consulted.isArray()
                || consulted.isEmpty()
                || !inapplicability.path("subject").isString()
                || inapplicability.path("subject").asString().isBlank()
                || !inapplicability.path("ruledOutBy").isString()
                || inapplicability.path("ruledOutBy").asString().isBlank()) {
            throw new JobDeliveryException(
                    "A NOT_APPLICABLE observation must name what the practice looks for and what rules it out "
                            + "here; if it could not be told either way the answer is UNDETERMINED: slug="
                            + observation.practiceSlug()
                            + ", jobId="
                            + job.getId());
        }
        for (JsonNode kind : consulted) {
            if (!kind.isString()) {
                throw new JobDeliveryException(
                        "Stated inapplicability names a non-textual source: slug=" + observation.practiceSlug()
                                + ", jobId="
                                + job.getId());
            }
            SourceKind sourceKind;
            try {
                sourceKind = new SourceKind(kind.asString());
            } catch (IllegalArgumentException e) {
                throw new JobDeliveryException(
                        "Stated inapplicability names an invalid source: slug=" + observation.practiceSlug()
                                + ", jobId="
                                + job.getId(),
                        e);
            }
            if (!boundary.allowedSources().contains(sourceKind)) {
                throw new JobDeliveryException(
                        "Stated inapplicability claims a source this run did not stage " + sourceKind
                                + ": slug="
                                + observation.practiceSlug()
                                + ", jobId="
                                + job.getId());
            }
        }
    }

    /** Requires an exhaustive search for ABSENT claims and a bounded corpus for ABSENT strengths. */
    private void enforceRecordedSearch(
            ValidatedObservation observation, Set<SourceKind> exhaustive, EvidenceBoundary boundary, AgentJob job) {
        if (observation.presence() != Presence.ABSENT) {
            return;
        }
        if (observation.outcome() == Outcome.POSITIVE && exhaustive.isEmpty()) {
            throw new JobDeliveryException(
                    "An ABSENT, BAD observation needs a practice that bounds the corpus it searches, and this one "
                            + "declares no EXHAUSTIVE evidence source: slug="
                            + observation.practiceSlug()
                            + ", jobId="
                            + job.getId());
        }
        JsonNode search =
                observation.evidence() == null ? null : observation.evidence().get("search");
        JsonNode consulted = search == null ? null : search.get("consulted");
        if (search == null
                || consulted == null
                || !consulted.isArray()
                || consulted.isEmpty()
                || !search.path("lookedFor").isString()
                || search.path("lookedFor").asString().isBlank()
                || !search.path("boundary").isString()
                || search.path("boundary").asString().isBlank()) {
            throw new JobDeliveryException(
                    "An ABSENT observation must record where it searched: slug=" + observation.practiceSlug()
                            + ", jobId="
                            + job.getId());
        }
        Set<SourceKind> searched = new HashSet<>();
        for (JsonNode kind : consulted) {
            if (!kind.isString()) {
                throw new JobDeliveryException(
                        "Recorded search names a non-textual source: slug=" + observation.practiceSlug()
                                + ", jobId="
                                + job.getId());
            }
            SourceKind sourceKind;
            try {
                sourceKind = new SourceKind(kind.asString());
            } catch (IllegalArgumentException e) {
                throw new JobDeliveryException(
                        "Recorded search names an invalid source: slug=" + observation.practiceSlug()
                                + ", jobId="
                                + job.getId(),
                        e);
            }
            // Same boundary the citations answer to: a source not staged for this run cannot have been
            // searched or read, so claiming otherwise is fabrication either way.
            if (!boundary.allowedSources().contains(sourceKind)) {
                throw new JobDeliveryException("Recorded search claims a source this run did not stage " + sourceKind
                        + ": slug="
                        + observation.practiceSlug()
                        + ", jobId="
                        + job.getId());
            }
            searched.add(sourceKind);
        }
        if (!searched.containsAll(exhaustive)) {
            Set<SourceKind> unsearched = new HashSet<>(exhaustive);
            unsearched.removeAll(searched);
            throw new JobDeliveryException(
                    "An ABSENT observation did not search the sources its practice asserts absence over " + unsearched
                            + ": slug="
                            + observation.practiceSlug()
                            + ", jobId="
                            + job.getId());
        }
    }

    private static JsonNode evidenceForPersistence(JsonNode evidence) {
        JsonNode persisted = evidence.deepCopy();
        if ("secret-diff-scanner".equals(persisted.path("detector").asString())) {
            for (JsonNode citation : persisted.path("citations")) {
                if (citation instanceof ObjectNode object) {
                    object.remove("quoteSha256");
                }
            }
        }
        return persisted;
    }

    private static boolean diffContainsCitation(
            String diff, String citedPath, String citedSide, int citedStartLine, int citedEndLine, String quote) {
        String oldPath = null;
        String newPath = null;
        Map<Integer, String> citedLines = new HashMap<>();
        for (String storedLine : diff.split("\n", -1)) {
            String line = storedLine;
            Integer annotatedLine = null;
            if (storedLine.startsWith("[L")) {
                int end = storedLine.indexOf("] ");
                if (end > 2) {
                    try {
                        annotatedLine = Integer.parseInt(storedLine.substring(2, end));
                        line = storedLine.substring(end + 2);
                    } catch (NumberFormatException ignored) {
                        return false;
                    }
                }
            }
            if (line.startsWith("--- ")) {
                oldPath = parseDiffPath(line.substring(4));
                continue;
            }
            if (line.startsWith("+++ ")) {
                newPath = parseDiffPath(line.substring(4));
                continue;
            }
            if (annotatedLine != null) {
                String lineSide = line.startsWith("-") ? "OLD" : "NEW";
                String linePath = "OLD".equals(lineSide) ? oldPath : newPath;
                if (citedSide.equals(lineSide) && citedPath.equals(linePath)) {
                    citedLines.put(annotatedLine, line);
                }
            }
        }
        List<String> quoteLines = quote.lines().toList();
        if (quoteLines.size() != citedEndLine - citedStartLine + 1) {
            return false;
        }
        for (int i = 0; i < quoteLines.size(); i++) {
            String diffLine = citedLines.get(citedStartLine + i);
            String quoteLine = quoteLines.get(i);
            if (diffLine == null
                    || !(diffLine.equals(quoteLine) || diffLine.substring(1).equals(quoteLine))) {
                return false;
            }
        }
        return true;
    }

    private static @Nullable String parseDiffPath(String value) {
        String path = value.trim();
        if ("/dev/null".equals(path)) {
            return null;
        }
        if (path.length() >= 2 && path.startsWith("\"") && path.endsWith("\"")) {
            path = path.substring(1, path.length() - 1).replace("\\\"", "\"");
        }
        return path.startsWith("a/") || path.startsWith("b/") ? path.substring(2) : path;
    }

    private EvidenceBoundary evidenceBoundary(AgentJob job) {
        JsonNode manifest = requireEvidenceSnapshot(job).path("manifest");
        SourceContractVersion contractVersion;
        try {
            contractVersion =
                    new SourceContractVersion(manifest.path("contractVersion").asString());
        } catch (IllegalArgumentException e) {
            throw new JobDeliveryException(
                    "Job evidence snapshot has an invalid contract version: jobId=" + job.getId(), e);
        }
        JsonNode sources = manifest.path("sources");
        if (!sources.isArray()) {
            throw new JobDeliveryException("Job evidence snapshot has no source manifest: jobId=" + job.getId());
        }
        Set<SourceKind> available = new HashSet<>();
        Map<String, SourceArtifactRef> artifacts = new HashMap<>();
        for (JsonNode source : sources) {
            if ("AVAILABLE".equals(source.path("state").path("availability").asString())) {
                SourceKind kind = new SourceKind(source.path("kind").asString());
                available.add(kind);
                JsonNode sourceArtifacts = source.path("artifacts");
                if (!sourceArtifacts.isArray()) {
                    throw new JobDeliveryException("Available source has no artifact inventory: jobId=" + job.getId());
                }
                for (JsonNode artifact : sourceArtifacts) {
                    String path = artifact.path("path").asString();
                    String sha256 = artifact.path("sha256").asString();
                    if (path.isBlank() || !sha256.matches("[0-9a-f]{64}")) {
                        throw new JobDeliveryException(
                                "Available source has an invalid artifact: jobId=" + job.getId());
                    }
                    if (artifacts.put(path, new SourceArtifactRef(kind, sha256)) != null) {
                        throw new JobDeliveryException("Evidence artifact belongs to multiple sources: path=" + path);
                    }
                }
            }
        }
        return new EvidenceBoundary(contractVersion, Set.copyOf(available), Map.copyOf(artifacts));
    }

    private Map<String, PracticeRevision> admittedRevisions(AgentJob job, Long workspaceId) {
        JsonNode practices = requireEvidenceSnapshot(job).path("practices");
        if (!practices.isArray() || practices.isEmpty()) {
            throw new JobDeliveryException("Job evidence snapshot has no admitted practices: jobId=" + job.getId());
        }
        Map<String, PracticeRevision> admitted = new HashMap<>();
        for (JsonNode entry : practices) {
            String slug = entry.path("slug").asString();
            JsonNode revisionId = entry.path("revisionId");
            if (slug.isBlank() || !revisionId.isIntegralNumber()) {
                throw new JobDeliveryException("Job evidence snapshot has an invalid practice: jobId=" + job.getId());
            }
            PracticeRevision revision = practiceRevisionRepository
                    .findById(revisionId.asLong())
                    .orElseThrow(() -> new JobDeliveryException(
                            "Admitted practice revision no longer exists: jobId=" + job.getId()));
            Practice practice = revision.getPractice();
            if (!slug.equals(revision.getSlug())
                    || !workspaceId.equals(practice.getWorkspace().getId())) {
                throw new JobDeliveryException(
                        "Admitted practice revision does not match the job: jobId=" + job.getId());
            }
            if (admitted.put(slug, revision) != null) {
                throw new JobDeliveryException("Duplicate admitted practice slug: " + slug + ", jobId=" + job.getId());
            }
        }
        return admitted;
    }

    private static JsonNode requireEvidenceSnapshot(AgentJob job) {
        JsonNode snapshot = job.getEvidenceSnapshot();
        if (snapshot == null || !snapshot.isObject()) {
            throw new JobDeliveryException("Job has no evidence snapshot: jobId=" + job.getId());
        }
        return snapshot;
    }

    private record SourceArtifactRef(SourceKind kind, String sha256) {}

    private record EvidenceBoundary(
            SourceContractVersion contractVersion,
            Set<SourceKind> allowedSources,
            Map<String, SourceArtifactRef> artifacts) {}

    /** Checked against executable review kinds by {@link JobTypeReviewExecutionCatalog} at startup. */
    static final Set<ArtifactKind> ROUTABLE_KINDS = Set.of(
            ArtifactKinds.PULL_REQUEST, ArtifactKinds.ISSUE, ArtifactKinds.CONVERSATION_THREAD, ArtifactKinds.DOCUMENT);

    private Target resolveTarget(AgentJob job, JsonNode metadata) {
        String artifactKind = job.getArtifactKind() != null
                ? job.getArtifactKind().value()
                : metadata.has("artifact_kind") ? metadata.get("artifact_kind").asString() : null;
        if (artifactKind == null) {
            artifactKind = ArtifactKinds.PULL_REQUEST.value();
        }
        if (ArtifactKinds.CONVERSATION_THREAD.value().equals(artifactKind)) {
            JsonNode threadIdNode = metadata.get("slack_thread_id");
            if (threadIdNode == null || threadIdNode.isNull() || !threadIdNode.isNumber()) {
                throw new JobDeliveryException("Missing slack_thread_id in job metadata: jobId=" + job.getId());
            }
            JsonNode aboutUserNode = metadata.get("about_user_id");
            if (aboutUserNode == null || aboutUserNode.isNull() || !aboutUserNode.isNumber()) {
                throw new JobDeliveryException("Missing about_user_id in job metadata: jobId=" + job.getId());
            }
            String channelId = requiredMetadataText(metadata, "slack_channel_id", job);
            String threadTs = requiredMetadataText(metadata, "slack_thread_ts", job);
            long threadId = threadIdNode.asLong();
            long aboutUserId = aboutUserNode.asLong();
            if (!conversationSourceLiveness.isDeliverableThread(
                    job.getWorkspace().getId(), threadId, channelId, threadTs, aboutUserId)) {
                throw new JobDeliveryException(
                        "Conversation target is no longer authorized or does not match the job: jobId=" + job.getId());
            }
            return new Target(ArtifactKinds.CONVERSATION_THREAD, threadId, aboutUserId);
        }
        if (ArtifactKinds.DOCUMENT.value().equals(artifactKind)) {
            // Erasure during execution must prevent admission even when the captured evidence still exists.
            JsonNode documentIdNode = metadata.get(DocumentContentSource.DOCUMENT_ID_METADATA_KEY);
            if (documentIdNode == null || documentIdNode.isNull() || !documentIdNode.isNumber()) {
                throw new JobDeliveryException("Missing " + DocumentContentSource.DOCUMENT_ID_METADATA_KEY
                        + " in job metadata: jobId="
                        + job.getId());
            }
            JsonNode aboutUserNode = metadata.get("about_user_id");
            if (aboutUserNode == null || aboutUserNode.isNull() || !aboutUserNode.isNumber()) {
                throw new JobDeliveryException("Missing about_user_id in job metadata: jobId=" + job.getId());
            }
            long documentId = documentIdNode.asLong();
            boolean live = documentProjection
                    .documentById(job.getWorkspace().getId(), documentId)
                    .filter(document -> !document.deleted())
                    .isPresent();
            if (!live) {
                throw new JobDeliveryException(
                        "Document target is gone: documentId=" + documentId + ", jobId=" + job.getId());
            }
            return new Target(ArtifactKinds.DOCUMENT, documentId, aboutUserNode.asLong());
        }
        if (ArtifactKinds.ISSUE.value().equals(artifactKind)) {
            JsonNode issueIdNode = metadata.get("issue_id");
            if (issueIdNode == null || issueIdNode.isNull() || !issueIdNode.isNumber()) {
                throw new JobDeliveryException("Missing issue_id in job metadata: jobId=" + job.getId());
            }
            Long issueId = issueIdNode.asLong();
            ReviewTargetQuery.Target issue = reviewTargets
                    .findIssue(issueId)
                    .orElseThrow(() ->
                            new JobDeliveryException("Issue not found: issueId=" + issueId + ", jobId=" + job.getId()));
            if (issue.authorId() == null) {
                throw new JobDeliveryException("Issue has no author: issueId=" + issueId + ", jobId=" + job.getId());
            }
            requireMatchingArtifact(issue, metadata, "issue_number", job);
            return new Target(ArtifactKinds.ISSUE, issueId, issue.authorId());
        }
        if (!ArtifactKinds.PULL_REQUEST.value().equals(artifactKind)) {
            throw new JobDeliveryException(
                    "No delivery route for artifact kind: kind=" + artifactKind + ", jobId=" + job.getId());
        }
        JsonNode pullRequestIdNode = metadata.get("pull_request_id");
        if (pullRequestIdNode == null || pullRequestIdNode.isNull() || !pullRequestIdNode.isNumber()) {
            throw new JobDeliveryException("Missing pull_request_id in job metadata: jobId=" + job.getId());
        }
        Long pullRequestId = pullRequestIdNode.asLong();
        ReviewTargetQuery.Target pullRequest = reviewTargets
                .findPullRequest(pullRequestId)
                .orElseThrow(() -> new JobDeliveryException(
                        "Pull request not found: pullRequestId=" + pullRequestId + ", jobId=" + job.getId()));
        if (pullRequest.authorId() == null) {
            throw new JobDeliveryException(
                    "Pull request has no author: pullRequestId=" + pullRequestId + ", jobId=" + job.getId());
        }
        requireMatchingArtifact(pullRequest, metadata, "pr_number", job);
        if ("REVIEWER".equals(metadata.path("subject_role").asString())) {
            long reviewId = metadata.path("review_id").asLong(-1);
            long aboutUserId = metadata.path("about_user_id").asLong(-1);
            boolean matches = reviewTargets.reviewMatchesTarget(reviewId, pullRequestId, aboutUserId);
            if (!matches) {
                throw new JobDeliveryException(
                        "Submitted review no longer matches its PR and reviewer: reviewId=" + reviewId
                                + ", jobId="
                                + job.getId());
            }
            return new Target(ArtifactKinds.PULL_REQUEST, pullRequestId, aboutUserId);
        }
        return new Target(ArtifactKinds.PULL_REQUEST, pullRequestId, pullRequest.authorId());
    }

    private static String requiredMetadataText(JsonNode metadata, String field, AgentJob job) {
        String value = metadata.path(field).asString();
        if (value.isBlank()) {
            throw new JobDeliveryException("Missing " + field + " in job metadata: jobId=" + job.getId());
        }
        return value;
    }

    private static void requireMatchingArtifact(
            ReviewTargetQuery.Target artifact, JsonNode metadata, String numberKey, AgentJob job) {
        if (!PracticeFeedbackDeliveryPolicy.matchesArtifact(artifact, metadata, numberKey)) {
            throw new JobDeliveryException("Artifact metadata does not match the live target: jobId=" + job.getId());
        }
    }

    static @Nullable String firstLocationPath(@Nullable JsonNode evidence) {
        if (evidence == null || evidence.isNull()) {
            return null;
        }
        JsonNode citations = evidence.get("citations");
        if (citations == null || !citations.isArray() || citations.isEmpty()) {
            return null;
        }
        JsonNode first = citations.get(0);
        if (first == null || !first.isObject()) {
            return null;
        }
        JsonNode path = first.get("path");
        return path != null && path.isString() ? path.asString() : null;
    }

    /** @param delivered what this call persisted, each carrying the keys it was stored under. */
    public record DeliveryResult(
            int inserted, int discardedDuplicate, boolean hasNegative, List<ValidatedObservation> delivered) {}
}
