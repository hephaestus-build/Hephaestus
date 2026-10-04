package de.tum.cit.aet.hephaestus.agent.handler;

import de.tum.cit.aet.hephaestus.agent.context.CitedSourceAccess;
import de.tum.cit.aet.hephaestus.agent.context.HistoricalGitEvidence;
import de.tum.cit.aet.hephaestus.agent.context.JobEvidenceFiles;
import de.tum.cit.aet.hephaestus.agent.context.providers.PullRequestContentSource;
import de.tum.cit.aet.hephaestus.agent.context.providers.RepositoryTreeContentSource;
import de.tum.cit.aet.hephaestus.agent.handler.ReviewResultParser.ValidatedObservation;
import de.tum.cit.aet.hephaestus.agent.handler.ReviewResultTargetResolver.Target;
import de.tum.cit.aet.hephaestus.agent.handler.spi.EvidenceQuoteUnverifiedException;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobDeliveryException;
import de.tum.cit.aet.hephaestus.agent.handler.spi.ObservationsRefusedException;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.evidence.ArtifactSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalName;
import de.tum.cit.aet.hephaestus.integration.core.spi.ActorRole;
import de.tum.cit.aet.hephaestus.practices.EvidenceStance;
import de.tum.cit.aet.hephaestus.practices.PracticeJudgment;
import de.tum.cit.aet.hephaestus.practices.PracticePreconditionClause;
import de.tum.cit.aet.hephaestus.practices.PracticeRevisionRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeSignalOptions;
import de.tum.cit.aet.hephaestus.practices.ReviewRuleFingerprint;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.ObservationAnswer;
import de.tum.cit.aet.hephaestus.practices.model.ObservationOrigin;
import de.tum.cit.aet.hephaestus.practices.model.Outcome;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeRevision;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationFingerprint;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.practices.review.AutomatedReviewFence;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public class ReviewOutputService {

    private static final Logger log = LoggerFactory.getLogger(ReviewOutputService.class);

    private final PracticeRevisionRepository practiceRevisionRepository;
    private final ObservationRepository observationRepository;
    private final ReviewResultTargetResolver targetResolver;
    private final ObjectMapper objectMapper;
    private final JobEvidenceFiles evidenceFiles;
    private final CitedSourceAccess citedSourceAccess;
    private final HistoricalGitEvidence historicalGit;
    private final ArtifactSourceCatalogRegistry sourceCatalogs;
    private final AutomatedReviewFence fence;
    private final LinkedIssueRepairAdmissionService repairAdmission;
    private final PracticeSignalOptions signalOptions;

    public ReviewOutputService(
            PracticeRevisionRepository practiceRevisionRepository,
            ObservationRepository observationRepository,
            ReviewResultTargetResolver targetResolver,
            ObjectMapper objectMapper,
            JobEvidenceFiles evidenceFiles,
            ArtifactSourceCatalogRegistry sourceCatalogs,
            HistoricalGitEvidence historicalGit,
            AutomatedReviewFence fence,
            LinkedIssueRepairAdmissionService repairAdmission,
            PracticeSignalOptions signalOptions,
            CitedSourceAccess citedSourceAccess) {
        this.practiceRevisionRepository = practiceRevisionRepository;
        this.observationRepository = observationRepository;
        this.targetResolver = targetResolver;
        this.objectMapper = objectMapper;
        this.evidenceFiles = evidenceFiles;
        this.sourceCatalogs = sourceCatalogs;
        this.historicalGit = historicalGit;
        this.fence = fence;
        this.repairAdmission = repairAdmission;
        this.signalOptions = signalOptions;
        this.citedSourceAccess = citedSourceAccess;
    }

    /** Metadata key for the run's immutable observation origin. */
    public static final String ORIGIN_METADATA_KEY = "observation_origin";

    /**
     * The origin stamped on this job, or {@link de.tum.cit.aet.hephaestus.practices.model.ObservationOrigin#LIVE} for a job with no origin key: every
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

    @Transactional(readOnly = true)
    public void requirePublished(AgentJob job) {
        for (var observation : observationRepository.findByAgentJobId(
                job.getId(), job.getWorkspace().getId())) {
            CitationVerification.requireVerified(job, observation.getEvidence());
        }
    }

    /**
     * The judgment of every practice admitted to this job, by slug: what the submitted answers are parsed and
     * derived against. Every admitted revision is of the current scheme, so each reviewed one carries a judgment.
     */
    @Transactional(readOnly = true)
    public Map<String, PracticeJudgment> judgments(AgentJob job) {
        Map<String, PracticeJudgment> judgments = new HashMap<>();
        admittedRevisions(job, job.getWorkspace().getId()).forEach((slug, revision) -> {
            PracticeJudgment judgment = revision.getJudgment();
            if (judgment == null) {
                throw new JobDeliveryException(
                        "Admitted practice revision has no judgment: slug=" + slug + ", jobId=" + job.getId());
            }
            judgments.put(slug, judgment);
        });
        return Map.copyOf(judgments);
    }

    public PreparedObservations prepare(AgentJob job, List<ValidatedObservation> validObservations) {
        Set<String> practices = new HashSet<>();
        for (ValidatedObservation observation : validObservations) {
            String slug =
                    observation.practiceSlug().trim().toLowerCase(Locale.ROOT).replace('_', '-');
            if (!practices.add(slug)) {
                throw new ObservationsRefusedException(
                        "ambiguous_practice_observations",
                        "A review must submit one final observation per practice; repeated practice: " + slug,
                        objectMapper.createArrayNode());
            }
        }
        JsonNode metadata = job.getMetadata();
        if (metadata == null) {
            throw new JobDeliveryException("Missing job metadata: jobId=" + job.getId());
        }

        Admissible admissible = requireAdmissible(job, metadata);
        CapturedEvidence captured = admissible.evidence();
        Map<String, PracticeRevision> revisionsBySlug = admissible.revisionsBySlug();
        var codeQuotes = verifyCodeQuotes(job, validObservations, captured);
        // A quote that does not verify discredits its own claim, and only EvidenceQuoteUnverifiedException
        // means that. Every other refusal here — an unstaged source, a malformed citation, work attributed
        // to the wrong person — impugns the run, so it stays fatal.
        List<ValidatedObservation> admittedObservations = new ArrayList<>(validObservations.size());
        List<String> withheldObservations = new ArrayList<>();
        var verificationFailures = objectMapper.createArrayNode();
        boolean withheldNegative = false;
        for (int submittedIndex = 0; submittedIndex < validObservations.size(); submittedIndex++) {
            ValidatedObservation observation = validObservations.get(submittedIndex);
            observation.outcome().validate(observation.severity());
            String expectedWarrant =
                    switch (observation.outcome()) {
                        case NOT_APPLICABLE -> "inapplicability";
                        case UNDETERMINED -> "undecidability";
                        case MET, NOT_MET ->
                            observation.evidence() != null
                                            && observation.evidence().hasNonNull("search")
                                    ? "search"
                                    : "";
                    };
            for (String warrant : List.of("search", "inapplicability", "undecidability")) {
                JsonNode evidence = observation.evidence();
                if (!warrant.equals(expectedWarrant) && evidence != null && evidence.hasNonNull(warrant)) {
                    throw new JobDeliveryException("Observation evidence warrant does not match outcome");
                }
            }
            if (observation.outcome() == Outcome.UNDETERMINED) {
                JsonNode evidence = observation.evidence();
                JsonNode warrant = evidence == null ? null : evidence.get("undecidability");
                // An open answer names what would settle it; a rule that decides UNDETERMINED is the warrant itself.
                if (warrant == null
                        || !warrant.isObject()
                        || !warrant.path("openQuestion").isString()
                        || warrant.path("openQuestion").asString().isBlank()
                        || (warrant.has("wouldSettleIt")
                                && (!warrant.path("wouldSettleIt").isString()
                                        || warrant.path("wouldSettleIt")
                                                .asString()
                                                .isBlank()))
                        || (observation.ruleId() == null && !warrant.has("wouldSettleIt"))) {
                    throw new JobDeliveryException("UNDETERMINED requires an open question and what would settle it");
                }
            }
            enforceAnswerSearches(observation, captured, job);
            PracticeRevision revision = revisionsBySlug.get(observation.practiceSlug());
            if (revision == null) {
                throw new JobDeliveryException(
                        "Observation references a practice not admitted to the job: slug=" + observation.practiceSlug()
                                + ", jobId="
                                + job.getId());
            }
            if (fence.withdrawal(revision.getPractice()).isPresent()) {
                // A run prepared before its practice was withdrawn from automated review still returns
                // what the model said about it; the withdrawal, not the run, decides whether that stands.
                withheldObservations.add(observation.practiceSlug() + ": withdrawn from automated review");
                continue;
            }
            enforceAttribution(observation, revision, job);
            try {
                var verifiedEvidence = enforceEvidenceBoundary(observation, revision, captured, job, codeQuotes);
                observation = observation.withEvidence(verifiedEvidence);
                admittedObservations.add(observation);
            } catch (EvidenceQuoteUnverifiedException ex) {
                var failed = verificationFailures
                        .addObject()
                        .put("observationIndex", submittedIndex)
                        .put("citationIndex", ex.citationIndex())
                        .put("status", "REJECTED")
                        .put("reasonCode", "QUOTE_LOCATION_MISMATCH");
                JsonNode submittedEvidence = Objects.requireNonNull(observation.evidence());
                JsonNode citation = Objects.requireNonNull(
                        submittedEvidence.path("citations").get(ex.citationIndex()));
                failed.put("citationSha256", CitationVerification.citationDigest(citation));
                if (citation.path("quote").isString()) {
                    failed.put(
                            "candidateQuoteSha256",
                            CitationVerification.quoteDigest(
                                    citation.path("quote").asString()));
                }
                withheldNegative |= observation.outcome() == Outcome.NOT_MET;
                withheldObservations.add(observation.practiceSlug() + ": " + ex.getMessage());
            }
        }
        if (!withheldObservations.isEmpty()) {
            // Per claim, because a model that cannot quote its own evidence is a defect an otherwise
            // successful delivery would hide.
            log.warn(
                    "Withheld {} of {} observation(s), delivering the rest: jobId={} withheld={}",
                    withheldObservations.size(),
                    validObservations.size(),
                    job.getId(),
                    withheldObservations);
            // Withholding the only fault leaves an all-clear standing over a defect the model did find,
            // which is a different statement to the reader than an incomplete review.
            if (withheldNegative && admittedObservations.stream().noneMatch(o -> o.outcome() == Outcome.NOT_MET)) {
                log.error(
                        "Withheld every negative observation; the remaining claims read as an all-clear: jobId={}",
                        job.getId());
            }
        }
        if (admittedObservations.isEmpty()) {
            throw new ObservationsRefusedException(
                    "no_valid_observations",
                    "No observation survived the evidence check, so there is nothing to deliver: jobId=" + job.getId()
                            + ", withheld="
                            + withheldObservations,
                    verificationFailures);
        }

        return new PreparedObservations(
                job.getId(),
                job.getRetryCount(),
                job.getWorkerId(),
                job.getEvidenceSnapshot() == null
                        ? null
                        : job.getEvidenceSnapshot().deepCopy(),
                metadata.deepCopy(),
                List.copyOf(admittedObservations),
                verificationFailures);
    }

    @Transactional
    public RecordedObservations publish(AgentJob job, PreparedObservations prepared) {
        if (!prepared.jobId.equals(job.getId())
                || prepared.attempt != job.getRetryCount()
                || !Objects.equals(prepared.workerId, job.getWorkerId())
                || !Objects.equals(prepared.snapshot, job.getEvidenceSnapshot())
                || !Objects.equals(prepared.metadata, job.getMetadata())) {
            throw new ObservationAdmissionService.StaleAttemptException();
        }
        Long workspaceId = job.getWorkspace().getId();
        JsonNode metadata = Objects.requireNonNull(job.getMetadata());
        // Asked again under the ownership fence: what was admissible when the submission was verified
        // may have been withdrawn, erased or reassigned since.
        Admissible admissible = requireAdmissible(job, metadata);
        Target target = admissible.target();
        if (target.type().equals(ArtifactKinds.ISSUE)) {
            String admittedSnapshot = metadata.path("review_snapshot_id").asString("");
            UUID currentSnapshot = observationRepository
                    .lockIssueSnapshotForReview(workspaceId, job.getId(), target.id())
                    .orElse(null);
            if (currentSnapshot == null || !currentSnapshot.toString().equals(admittedSnapshot)) {
                throw new JobDeliveryException("Issue changed after this review was submitted: jobId=" + job.getId());
            }
        }
        String signal =
                metadata.path(PracticeCatalogInjector.SIGNAL_METADATA_KEY).asString();
        boolean linkedRepair = !signal.isBlank() && signalOptions.isInternalRepair(SignalName.of(signal));
        if (linkedRepair) {
            repairAdmission.requireCurrentCapture(
                    job, target.id(), metadata.path("linked_issue_revision").asString());
        }
        Map<String, PracticeRevision> revisionsBySlug = admissible.revisionsBySlug();
        List<ValidatedObservation> admittedObservations = prepared.observations;
        for (ValidatedObservation observation : admittedObservations) {
            PracticeRevision revision = revisionsBySlug.get(observation.practiceSlug());
            if (revision == null) throw new JobDeliveryException("Practice is no longer admitted to this job");
            enforceAttribution(observation, revision, job);
            CitationVerification.requireVerified(job, observation.evidence());
        }
        if (metadata instanceof ObjectNode object)
            object.set("citation_verification_failures", prepared.failures.deepCopy());
        ObservationOrigin origin = originOf(metadata);
        // The one person this job resolved. Sound for every observation only because the catalogue injector
        // withheld every practice whose occasion is about somebody else, and enforceAttribution above
        // refuses one that reached here anyway.
        Long aboutUserId = target.aboutUserId();
        ArtifactKind artifactKind = target.type();
        Long artifactId = target.id();
        Map<Long, List<Observation>> previousNegatives = linkedRepair
                ? repairAdmission.currentNegatives(workspaceId, artifactKind, artifactId, aboutUserId)
                : Map.of();
        Set<Long> replacedPractices = new HashSet<>();

        int inserted = 0;
        int discardedDuplicate = 0;
        Instant observedAt = Instant.now();

        List<ValidatedObservation> recordedObservations = new ArrayList<>(admittedObservations.size());

        for (int i = 0; i < admittedObservations.size(); i++) {
            ValidatedObservation observation = admittedObservations.get(i);

            PracticeRevision revision = Objects.requireNonNull(
                    revisionsBySlug.get(observation.practiceSlug()), "Validated practice revision is missing");
            Practice practice = revision.getPractice();

            String occurrenceKey =
                    observation.practiceSlug() + ":" + artifactKind.value() + ":" + artifactId + ":" + job.getId();

            String evidenceJson = null;
            if (observation.evidence() != null) {
                try {
                    evidenceJson = objectMapper.writeValueAsString(observation.evidence());
                } catch (JacksonException e) {
                    throw new JobDeliveryException("Could not serialize validated evidence: jobId=" + job.getId(), e);
                }
            }

            // The location grouping can be shared by different behaviors; occurrence identity addresses this row.
            String recurrenceKey = ObservationFingerprint.compute(
                    observation.practiceSlug(),
                    artifactKind.value(),
                    artifactId,
                    aboutUserId,
                    firstLocationPath(observation.evidence()));
            recordedObservations.add(observation.withKeys(new ObservationKeys(occurrenceKey, recurrenceKey)));

            Long practiceRevisionId = Objects.requireNonNull(revision.getId(), "Practice revision must be persisted");

            String severityName = observation.outcome() == Outcome.NOT_MET && observation.severity() != null
                    ? observation.severity().name()
                    : null;
            String answersJson = null;
            if (observation.answers() != null) {
                try {
                    answersJson = objectMapper.writeValueAsString(observation.answers());
                } catch (JacksonException e) {
                    throw new JobDeliveryException("Could not serialize the answers: jobId=" + job.getId(), e);
                }
            }

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
                    observation.outcome().name(),
                    severityName,
                    evidenceJson,
                    observation.evidenceRationale(),
                    answersJson,
                    observation.ruleId(),
                    recurrenceKey,
                    observedAt,
                    origin.name());

            if (rows == 1) {
                inserted++;
                if (linkedRepair && observation.outcome() == Outcome.MET) {
                    if (previousNegatives.containsKey(practice.getId())) replacedPractices.add(practice.getId());
                }
            } else {
                Observation stored = observationRepository.findByAgentJobId(job.getId(), workspaceId).stream()
                        .filter(row -> row.getOccurrenceKey().equals(occurrenceKey))
                        .findFirst()
                        .orElseThrow(() -> new JobDeliveryException("Recorded observation is unavailable for retry"));
                PracticeRevision storedRevision = stored.getPracticeRevision();
                if (!Objects.equals(stored.getPractice().getId(), practice.getId())
                        || storedRevision == null
                        || !Objects.equals(storedRevision.getId(), practiceRevisionId)
                        || !stored.getArtifactKind().equals(artifactKind)
                        || !Objects.equals(stored.getArtifactId(), artifactId)
                        || !Objects.equals(stored.getAboutUserId(), aboutUserId)
                        || !stored.getSummary().equals(observation.summary())
                        || stored.getOutcome() != observation.outcome()
                        || stored.getSeverity() != observation.severity()
                        || !Objects.equals(
                                stored.getEvidence(), evidenceJson == null ? null : objectMapper.readTree(evidenceJson))
                        || !Objects.equals(stored.getEvidenceRationale(), observation.evidenceRationale())
                        || !Objects.equals(stored.getAnswers(), observation.answers())
                        || !Objects.equals(stored.getRuleId(), observation.ruleId())
                        || !Objects.equals(stored.getRecurrenceKey(), recurrenceKey)
                        || stored.getOrigin() != origin) {
                    throw new JobDeliveryException(
                            "A recorded observation cannot be changed by retry: slug=" + observation.practiceSlug());
                }
                discardedDuplicate++;
            }
        }

        for (Long practiceId : replacedPractices) {
            repairAdmission.retire(
                    workspaceId,
                    target,
                    practiceId,
                    Objects.requireNonNull(previousNegatives.get(practiceId)),
                    observedAt);
        }

        log.info(
                "Recorded this review's observations: inserted={}, duplicate={}, jobId={}",
                inserted,
                discardedDuplicate,
                job.getId());

        return new RecordedObservations(inserted, discardedDuplicate, recordedObservations);
    }

    /** What one admission may record against: the capture, the person and work it names, and its practices. */
    private record Admissible(
            CapturedEvidence evidence, Target target, Map<String, PracticeRevision> revisionsBySlug) {}

    private Admissible requireAdmissible(AgentJob job, JsonNode metadata) {
        if (!citedSourceAccess.permitsReviewResult(job))
            throw new ObservationsRefusedException(
                    "member_ai_declined", "The developer's AI choice no longer permits recording this review result.");
        CapturedEvidence evidence = CapturedEvidence.of(job, objectMapper);
        for (SourceKind kind : evidence.availableSources()) {
            if (!sourceCatalogs.isSourceUsePermitted(
                    evidence.contractVersion(), kind, SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY)) {
                throw new JobDeliveryException(
                        "Evidence source authorization was withdrawn before delivery: source=" + kind
                                + ", jobId="
                                + job.getId());
            }
        }
        Target target = targetResolver.resolve(job, metadata);
        return new Admissible(
                evidence, target, admittedRevisions(job, job.getWorkspace().getId()));
    }

    public static final class PreparedObservations {
        private final UUID jobId;
        private final int attempt;
        private final @Nullable String workerId;
        private final @Nullable JsonNode snapshot;
        private final JsonNode metadata;
        private final List<ValidatedObservation> observations;
        private final JsonNode failures;

        private PreparedObservations(
                UUID jobId,
                int attempt,
                @Nullable String workerId,
                @Nullable JsonNode snapshot,
                JsonNode metadata,
                List<ValidatedObservation> observations,
                JsonNode failures) {
            this.jobId = jobId;
            this.attempt = attempt;
            this.workerId = workerId;
            this.snapshot = snapshot;
            this.metadata = metadata;
            this.observations = observations;
            this.failures = failures;
        }
    }

    private void enforceAttribution(ValidatedObservation observation, PracticeRevision revision, AgentJob job) {
        ActorRole subject = revision.getSubject();
        if (subject == null) {
            throw new JobDeliveryException(
                    "Practice revision has no attribution definition: slug=" + observation.practiceSlug());
        }
        if (PracticeCatalogInjector.subjectNameable(subject, job.getMetadata())) {
            return;
        }
        throw new JobDeliveryException("Observation is about a " + subject
                + " this review cannot name, so it has nobody to be filed against: slug="
                + observation.practiceSlug()
                + ", jobId="
                + job.getId());
    }

    /** The two repository sources a quote of code can name: the checkout, and the change within it. */
    private static boolean isCodeSource(SourceKind kind) {
        return RepositoryTreeContentSource.KIND.equals(kind) || PracticePreconditionClause.DIFF_SOURCE.equals(kind);
    }

    /**
     * Every quote of code, verified in one pass against the attempt's checkout. A quote from the change
     * is a quote from the head (NEW) or the base (OLD) of the pinned range, at a path the change touches.
     */
    private CodeQuotes verifyCodeQuotes(
            AgentJob job, List<ValidatedObservation> observations, CapturedEvidence captured) {
        List<JsonNode> candidates = new ArrayList<>();
        boolean citesChange = false;
        for (ValidatedObservation observation : observations) {
            JsonNode evidence = observation.evidence();
            if (evidence == null) continue;
            for (JsonNode citation : evidence.path("citations")) {
                String kind = citation.path("sourceKind").asString();
                if (RepositoryTreeContentSource.KIND.value().equals(kind)
                        || PracticePreconditionClause.DIFF_SOURCE.value().equals(kind)) {
                    candidates.add(citation);
                    citesChange |=
                            PracticePreconditionClause.DIFF_SOURCE.value().equals(kind);
                }
            }
        }
        if (candidates.isEmpty()) return new CodeQuotes(Map.of(), Set.of(), Map.of());
        var groups = candidates.stream()
                .collect(java.util.stream.Collectors.groupingBy(ReviewOutputService::repositoryRoot));
        var matches = new HashMap<HistoricalGitEvidence.Citation, JobEvidenceFiles.QuoteMatch>();
        var heads = new HashMap<String, String>();
        Set<String> changedPaths = Set.of();
        for (var group : groups.entrySet()) {
            String root = group.getKey();
            var head = captured.requireArtifact(RepositoryTreeContentSource.KIND, root + ".git/HEAD");
            var refs =
                    captured.requireArtifact(RepositoryTreeContentSource.KIND, root + ".git/hephaestus-captured-refs");
            String pinnedHead = root.equals(SandboxLayout.REPO_MOUNT_RELATIVE)
                    ? captured.pinnedHead()
                    : evidenceFiles
                            .inspect(job, root + ".git/HEAD", head.sha256(), reader -> {
                                String identity = new java.io.BufferedReader(reader).readLine();
                                if (identity == null || !identity.matches(CitationVerification.GIT_OBJECT_ID))
                                    throw new JobDeliveryException("Captured repository has no pinned head");
                                return identity;
                            })
                            .orElseThrow(() -> new JobDeliveryException("Captured repository is unavailable"));
            heads.put(root, pinnedHead);
            var requested = group.getValue().stream()
                    .map(citation -> codeCitation(citation, captured, pinnedHead))
                    .toList();
            if (citesChange && root.equals(SandboxLayout.REPO_MOUNT_RELATIVE)) {
                String[] range = captured.reviewRange();
                changedPaths = historicalGit.changedPaths(job, head.sha256(), refs.sha256(), range[0], range[1]);
            }
            matches.putAll(
                    root.equals(SandboxLayout.REPO_MOUNT_RELATIVE)
                            ? historicalGit.verifyAll(job, head.sha256(), refs.sha256(), pinnedHead, requested)
                            : historicalGit.verifyAllAt(
                                    job, root, head.sha256(), refs.sha256(), pinnedHead, requested));
        }
        return new CodeQuotes(Map.copyOf(matches), changedPaths, Map.copyOf(heads));
    }

    private static String repositoryRoot(JsonNode citation) {
        if (PracticePreconditionClause.DIFF_SOURCE
                .value()
                .equals(citation.path("sourceKind").asString())) return SandboxLayout.REPO_MOUNT_RELATIVE;
        String artifact = citation.path("artifactPath").asString();
        if (!artifact.matches("repos/[a-zA-Z0-9_-]+/\\.git/HEAD"))
            throw new JobDeliveryException("A repository citation must name its captured HEAD witness");
        return artifact.substring(0, artifact.length() - ".git/HEAD".length());
    }

    private record CodeQuotes(
            Map<HistoricalGitEvidence.Citation, JobEvidenceFiles.QuoteMatch> matches,
            Set<String> changedPaths,
            Map<String, String> pinnedHeads) {}

    /**
     * A quote of code as a revision, a path and a line range. A checkout citation names the captured
     * {@code .git/HEAD} and may select a revision; a change citation names the pinned change and a side,
     * which selects the revision for it.
     */
    private static HistoricalGitEvidence.Citation codeCitation(
            JsonNode citation, CapturedEvidence captured, String pinnedHead) {
        boolean change = PracticePreconditionClause.DIFF_SOURCE
                .value()
                .equals(citation.path("sourceKind").asString());
        String expectedArtifact =
                change ? PullRequestContentSource.CHANGE_FILE : repositoryRoot(citation) + ".git/HEAD";
        String side = citation.path("side").asString("");
        String revision;
        if (change) {
            if (!citation.path("revision").isMissingNode() || !(side.equals("OLD") || side.equals("NEW"))) {
                throw new JobDeliveryException(
                        "Invalid change citation: name the OLD or NEW side of the pinned change, not a revision");
            }
            String[] range = captured.reviewRange();
            revision = side.equals("OLD") ? range[0] : range[1];
        } else {
            if (!citation.path("side").isMissingNode()) {
                throw new JobDeliveryException("Invalid repository citation: a checkout quote has no side");
            }
            revision = citation.path("revision").asString(pinnedHead);
        }
        String path = citation.path("path").asString();
        String quote = citation.path("quote").asString();
        int start = citation.path("startLine").asInt(-1);
        int end = citation.path("endLine").asInt(start);
        if (!expectedArtifact.equals(citation.path("artifactPath").asString())
                || !revision.matches(CitationVerification.GIT_OBJECT_ID)
                || !citation.path("path").isString()
                || !citation.path("quote").isString()
                || path.isBlank()
                || quote.isBlank()
                || !citation.path("startLine").isIntegralNumber()
                || start < 1
                || end < start
                || (!citation.path("endLine").isMissingNode()
                        && !citation.path("endLine").isIntegralNumber())) {
            throw new JobDeliveryException(
                    "Invalid code citation: name the captured artifact, a relative path and an exact line range");
        }
        CitationVerification.quoteDigest(quote);
        return new HistoricalGitEvidence.Citation(revision, path, quote, start, end);
    }

    private JsonNode enforceEvidenceBoundary(
            ValidatedObservation observation,
            PracticeRevision revision,
            CapturedEvidence captured,
            AgentJob job,
            CodeQuotes codeQuotes) {
        if (revision.getAutomatedReviewPolicy() == null
                || revision.getSignals() == null
                || revision.getEvidenceRequirements() == null) {
            throw new JobDeliveryException("Practice has no evidence requirements: slug=" + observation.practiceSlug()
                    + ", jobId=" + job.getId());
        }
        JsonNode submittedEvidence = observation.evidence();
        JsonNode evidence = submittedEvidence == null ? null : submittedEvidence.deepCopy();
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
        // Citations may use any staged source, not only the practice's evidence requirements.
        // EXHAUSTIVE requirements bound absence claims; byte-exact quotes establish citation validity.
        Set<SourceKind> exhaustive = new HashSet<>();
        revision.getEvidenceRequirements().forEach(need -> {
            if (need.stance() == EvidenceStance.EXHAUSTIVE) {
                exhaustive.add(need.sourceKind());
            }
        });
        enforceRecordedSearch(observation, exhaustive, captured, job);
        enforceStatedInapplicability(observation, captured, job);
        for (int citationIndex = 0; citationIndex < citations.size(); citationIndex++) {
            JsonNode citation = citations.get(citationIndex);
            JsonNode sourceKind = citation.path("sourceKind");
            JsonNode artifactPath = citation.path("artifactPath");
            JsonNode startLine = citation.path("startLine");
            JsonNode endLine = citation.path("endLine");
            JsonNode quote = citation.path("quote");
            if (!citation.isObject()
                    || !sourceKind.isString()
                    || !artifactPath.isString()
                    || !citation.path("path").isString()
                    || !startLine.isIntegralNumber()
                    || !startLine.canConvertToInt()
                    || startLine.asInt() < 1
                    || (!endLine.isMissingNode()
                            && (!endLine.isIntegralNumber()
                                    || !endLine.canConvertToInt()
                                    || endLine.asInt() < startLine.asInt()))
                    || !quote.isString()) {
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
            CapturedEvidence.Artifact artifact = captured.artifact(artifactPath.asString());
            if (!captured.availableSources().contains(kind)
                    || artifact == null
                    || !artifact.kind().equals(kind)) {
                throw new JobDeliveryException("Observation cited unavailable or misattributed evidence source " + kind
                        + ": slug="
                        + observation.practiceSlug()
                        + ", jobId="
                        + job.getId());
            }
            String exactQuote = quote.asString("");
            if (exactQuote.isBlank()) {
                throw new JobDeliveryException(
                        "Observation has an empty evidence quote: slug=" + observation.practiceSlug()
                                + ", jobId="
                                + job.getId());
            }
            String quoteDigest = CitationVerification.quoteDigest(exactQuote);
            if (isCodeSource(kind)
                    || !citation.path("revision").isMissingNode()
                    || !citation.path("side").isMissingNode()) {
                if (!isCodeSource(kind))
                    throw new JobDeliveryException("Only repository citations may select a revision or a side");
                var requested = codeCitation(
                        citation,
                        captured,
                        Objects.requireNonNull(codeQuotes.pinnedHeads().get(repositoryRoot(citation))));
                ((ObjectNode) citation).put("revision", requested.revision());
                if (PracticePreconditionClause.DIFF_SOURCE.equals(kind)
                        && !codeQuotes.changedPaths().contains(requested.path())) {
                    throw new EvidenceQuoteUnverifiedException(
                            "Cited path is not touched by the reviewed change", citationIndex);
                }
                var match = codeQuotes.matches().get(requested);
                if (match == null) throw new JobDeliveryException("Code citation has no prepared verification");
                String blobDigest = match.artifactSha256();
                if (blobDigest == null)
                    throw new EvidenceQuoteUnverifiedException(
                            "Cited path does not exist at the cited revision", citationIndex);
                if (!match.matches())
                    throw new EvidenceQuoteUnverifiedException(
                            "Quote does not match the cited revision and lines", citationIndex);
                citedSourceAccess.bind(job, (ObjectNode) citation, artifact.sha256());
                CitationVerification.record((ObjectNode) citation, job, blobDigest, quoteDigest);
                continue;
            }
            // The path of a serialized source is a label for the reader; the artifact and its lines
            // are what the quote is verified against.
            boolean containsQuote = evidenceFiles
                    .containsUtf8AtLines(
                            job,
                            artifactPath.asString(),
                            artifact.sha256(),
                            exactQuote,
                            startLine.asInt(),
                            endLine.isMissingNode() ? startLine.asInt() : endLine.asInt())
                    .orElseThrow(() -> new JobDeliveryException("Cited evidence artifact is no longer available: path="
                            + artifactPath.asString() + ", jobId=" + job.getId()));
            if (!containsQuote) {
                throw new EvidenceQuoteUnverifiedException(
                        "Evidence quote does not occur in the cited artifact: path=" + artifactPath.asString()
                                + ", jobId=" + job.getId(),
                        citationIndex);
            }
            citedSourceAccess.bind(job, (ObjectNode) citation, artifact.sha256());
            CitationVerification.record((ObjectNode) citation, job, artifact.sha256(), quoteDigest);
        }
        return evidence;
    }

    /** Requires NOT_APPLICABLE claims to identify the subject, exclusion reason, and consulted sources. */
    private void enforceStatedInapplicability(
            ValidatedObservation observation, CapturedEvidence captured, AgentJob job) {
        if (observation.outcome() != Outcome.NOT_APPLICABLE) {
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
            if (!captured.availableSources().contains(sourceKind)) {
                throw new JobDeliveryException(
                        "Stated inapplicability claims a source this run did not stage " + sourceKind
                                + ": slug="
                                + observation.practiceSlug()
                                + ", jobId="
                                + job.getId());
            }
        }
    }

    /**
     * Every answer that rests on absence names where it searched; a source this run did not stage cannot have been
     * searched, whichever answer claims it.
     */
    private void enforceAnswerSearches(ValidatedObservation observation, CapturedEvidence captured, AgentJob job) {
        if (observation.answers() == null) return;
        for (ObservationAnswer answer : observation.answers()) {
            ObservationAnswer.Search search = answer.search();
            if (search == null) continue;
            for (String kind : search.consulted()) {
                SourceKind sourceKind;
                try {
                    sourceKind = new SourceKind(kind);
                } catch (IllegalArgumentException e) {
                    throw new JobDeliveryException(
                            "An answer's search names an invalid source: slug=" + observation.practiceSlug()
                                    + ", jobId=" + job.getId(),
                            e);
                }
                if (!captured.availableSources().contains(sourceKind)) {
                    throw new JobDeliveryException("An answer's search claims a source this run did not stage "
                            + sourceKind + ": slug=" + observation.practiceSlug() + ", jobId=" + job.getId());
                }
            }
        }
    }

    /** Validates a recorded absence search against the staged sources and declared corpus. */
    private void enforceRecordedSearch(
            ValidatedObservation observation, Set<SourceKind> exhaustive, CapturedEvidence captured, AgentJob job) {
        if (observation.evidence() == null || !observation.evidence().hasNonNull("search")) {
            return;
        }
        if (observation.outcome() == Outcome.MET && exhaustive.isEmpty()) {
            throw new JobDeliveryException(
                    "A MET absence claim needs a practice that bounds the corpus it searches, and this one "
                            + "declares no EXHAUSTIVE evidence source: slug="
                            + observation.practiceSlug()
                            + ", jobId="
                            + job.getId());
        }
        JsonNode search = observation.evidence().get("search");
        JsonNode consulted = search.get("consulted");
        if (consulted == null
                || !consulted.isArray()
                || consulted.isEmpty()
                || !search.path("lookedFor").isString()
                || search.path("lookedFor").asString().isBlank()
                || !search.path("boundary").isString()
                || search.path("boundary").asString().isBlank()) {
            throw new JobDeliveryException(
                    "An absence claim must record where it searched: slug=" + observation.practiceSlug()
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
            if (!captured.availableSources().contains(sourceKind)) {
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
                    "An absence claim did not search the sources its practice asserts absence over " + unsearched
                            + ": slug="
                            + observation.practiceSlug()
                            + ", jobId="
                            + job.getId());
        }
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
                    .findByIdAndWorkspaceId(revisionId.asLong(), workspaceId)
                    .orElseThrow(() -> new JobDeliveryException(
                            "Admitted practice revision no longer exists: jobId=" + job.getId()));
            if (!ReviewRuleFingerprint.isCurrentScheme(revision.getReviewRuleFingerprint())) {
                throw new JobDeliveryException(
                        "Admitted practice revision uses an incompatible observation standard: jobId=" + job.getId());
            }
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

    /** Checked against executable review kinds by {@link JobTypeReviewExecutionCatalog} at startup. */
    static final Set<ArtifactKind> ROUTABLE_KINDS = Set.of(
            ArtifactKinds.PULL_REQUEST, ArtifactKinds.ISSUE, ArtifactKinds.CONVERSATION_THREAD, ArtifactKinds.DOCUMENT);

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

    /** @param recorded what this call persisted, each carrying the keys it was stored under. */
    public record RecordedObservations(int inserted, int discardedDuplicate, List<ValidatedObservation> recorded) {}
}
