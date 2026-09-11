package de.tum.cit.aet.hephaestus.agent.handler;

import static de.tum.cit.aet.hephaestus.agent.handler.spi.JobMetadataReader.requireInt;
import static de.tum.cit.aet.hephaestus.agent.handler.spi.JobMetadataReader.requireLong;
import static de.tum.cit.aet.hephaestus.agent.handler.spi.JobMetadataReader.requireText;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.context.ContentSource;
import de.tum.cit.aet.hephaestus.agent.context.ContextRequest;
import de.tum.cit.aet.hephaestus.agent.context.EvidencePlan;
import de.tum.cit.aet.hephaestus.agent.context.InsufficientEvidenceException;
import de.tum.cit.aet.hephaestus.agent.context.JobEvidenceFiles;
import de.tum.cit.aet.hephaestus.agent.context.PreparedEvidence;
import de.tum.cit.aet.hephaestus.agent.context.WorkspaceContextBuilder;
import de.tum.cit.aet.hephaestus.agent.handler.composition.ComposedFeedbackUnit;
import de.tum.cit.aet.hephaestus.agent.handler.composition.FeedbackCompositionInputs;
import de.tum.cit.aet.hephaestus.agent.handler.composition.FeedbackCompositionResultParser;
import de.tum.cit.aet.hephaestus.agent.handler.spi.ExistingDeliveryLookup;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobDeliveryException;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobPreparationException;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobSubmission;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobSubmissionRequest;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobTypeHandler;
import de.tum.cit.aet.hephaestus.agent.handler.spi.ObservationsRefusedException;
import de.tum.cit.aet.hephaestus.agent.handler.spi.PreparedJobInputs;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.agent.task.Task;
import de.tum.cit.aet.hephaestus.agent.task.TaskEnvelope;
import de.tum.cit.aet.hephaestus.agent.task.TaskEnvelopeWriter;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmEventPayload;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalName;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Assessment;
import de.tum.cit.aet.hephaestus.practices.model.AssessmentStatus;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.Presence;
import de.tum.cit.aet.hephaestus.practices.model.Severity;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Handles {@link AgentJobType#PULL_REQUEST_REVIEW} jobs.
 * The workspace layout is defined in {@code docs/contributor/agent/workspace-abi.mdx}.
 */
public class PullRequestReviewHandler implements JobTypeHandler {

    private static final Logger log = LoggerFactory.getLogger(PullRequestReviewHandler.class);

    private final JsonMapper objectMapper;
    private final JobEvidenceFiles evidenceFiles;
    private final PracticeCatalogInjector practiceCatalogInjector;
    private final WorkspaceContextBuilder workspaceContextBuilder;
    private final TaskEnvelopeWriter taskEnvelopeWriter;
    private final PracticeDetectionResultParser resultParser;
    private final FeedbackCompositionResultParser compositionResultParser;
    private final PracticeDetectionDeliveryService deliveryService;
    private final FeedbackDeliveryService feedbackService;
    private final SecretDiffScanner secretDiffScanner;
    private final FeedbackResponseSuppressionFilter feedbackResponseSuppressionFilter;
    private final InContextDeliveryGate inContextDeliveryGate;
    private final ObservationRepository observationRepository;

    PullRequestReviewHandler(
            JsonMapper objectMapper,
            JobEvidenceFiles evidenceFiles,
            PracticeCatalogInjector practiceCatalogInjector,
            WorkspaceContextBuilder workspaceContextBuilder,
            TaskEnvelopeWriter taskEnvelopeWriter,
            PracticeDetectionResultParser resultParser,
            FeedbackCompositionResultParser compositionResultParser,
            PracticeDetectionDeliveryService deliveryService,
            FeedbackDeliveryService feedbackService,
            SecretDiffScanner secretDiffScanner,
            FeedbackResponseSuppressionFilter feedbackResponseSuppressionFilter,
            InContextDeliveryGate inContextDeliveryGate,
            ObservationRepository observationRepository) {
        this.objectMapper = objectMapper;
        this.evidenceFiles = evidenceFiles;
        this.practiceCatalogInjector = practiceCatalogInjector;
        this.workspaceContextBuilder = workspaceContextBuilder;
        this.taskEnvelopeWriter = taskEnvelopeWriter;
        this.resultParser = resultParser;
        this.compositionResultParser = compositionResultParser;
        this.deliveryService = deliveryService;
        this.feedbackService = feedbackService;
        this.secretDiffScanner = secretDiffScanner;
        this.feedbackResponseSuppressionFilter = feedbackResponseSuppressionFilter;
        this.inContextDeliveryGate = inContextDeliveryGate;
        this.observationRepository = observationRepository;
    }

    @Override
    public AgentJobType jobType() {
        return AgentJobType.PULL_REQUEST_REVIEW;
    }

    @Override
    public JobSubmission createSubmission(JobSubmissionRequest request) {
        if (!(request instanceof PullRequestReviewSubmissionRequest submissionRequest)) {
            throw new IllegalArgumentException("Expected PullRequestReviewSubmissionRequest, got: "
                    + request.getClass().getSimpleName());
        }

        ScmEventPayload.PullRequestData pullRequestData = submissionRequest.pullRequest();

        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put(
                PracticeDetectionDeliveryService.ORIGIN_METADATA_KEY,
                Objects.requireNonNull(submissionRequest.observationOrigin()).name());
        metadata.put("repository_id", pullRequestData.repository().id());
        metadata.put("repository_full_name", pullRequestData.repository().nameWithOwner());
        metadata.put("pull_request_id", pullRequestData.id());
        metadata.put("pr_number", pullRequestData.number());
        metadata.put("pr_url", pullRequestData.htmlUrl());
        metadata.put("commit_sha", submissionRequest.headRefOid());
        if (submissionRequest.baseRefOid() != null) {
            metadata.put("base_ref_oid", submissionRequest.baseRefOid());
        }
        metadata.put("source_branch", submissionRequest.headRefName());
        metadata.put("target_branch", submissionRequest.baseRefName());
        metadata.put("title", pullRequestData.title());
        metadata.put("body", pullRequestData.body());
        // When present, the catalog injector materialises ONLY the practices bound to this signal, so an
        // authoring practice is not re-litigated on a fixup push. Null = run the full focus set.
        if (submissionRequest.triggerSignal() != null) {
            metadata.put(
                    PracticeCatalogInjector.SIGNAL_METADATA_KEY,
                    submissionRequest.triggerSignal().value());
        }
        if (submissionRequest.reviewId() != null && submissionRequest.aboutUserId() != null) {
            metadata.put("review_id", submissionRequest.reviewId());
            metadata.put("about_user_id", submissionRequest.aboutUserId());
            metadata.put("subject_role", "REVIEWER");
        }

        // The occasion is part of the key: an authoring review, a push re-scan, a reviewer pass and a
        // retrospective of the SAME head SHA are DIFFERENT reviews over different practice sets, so a
        // retrospective must never be deduped against an earlier authoring job for the same commit. It
        // sits BEFORE the SHA so extractCooldownKeyPrefix scopes cooldown per (pr, occasion).
        String phase = submissionRequest.triggerSignal() != null
                ? submissionRequest.triggerSignal().value()
                : "manual";
        String idempotencyKey = "pr_review:" + pullRequestData.repository().nameWithOwner()
                + ":"
                + pullRequestData.number()
                + ":"
                + phase
                + ":"
                + (submissionRequest.reviewId() != null
                        ? "review-" + submissionRequest.reviewId()
                        : submissionRequest.headRefOid());

        return new JobSubmission(metadata, idempotencyKey);
    }

    @Override
    public PreparedJobInputs prepareInputs(AgentJob job) {
        long startNanos = System.nanoTime();
        JsonNode metadata = job.getMetadata();
        if (metadata == null || metadata.isNull() || metadata.isMissingNode()) {
            throw new JobPreparationException("Job has no metadata: jobId=" + job.getId());
        }
        long repositoryId = requireLong(metadata, "repository_id");
        long pullRequestId = requireLong(metadata, "pull_request_id");

        SignalName signal = PracticeCatalogInjector.signalOf(job);
        List<Practice> practices = practiceCatalogInjector.resolveEligiblePractices(job, ArtifactKinds.PULL_REQUEST);
        PreparedEvidence prepared = workspaceContextBuilder.prepare(
                new ContextRequest.PracticeReviewRequest(job), EvidencePlan.compile(practices));
        var artifactSourceManifest = prepared.manifest();
        var readiness = workspaceContextBuilder.prepareAutomatedReviewReadiness(
                prepared.manifest(), practices, job.getId().toString(), job.getCreatedAt(), signal, prepared.files());
        List<Practice> eligible = practices;
        practices = readiness.readyPractices();
        // A practice not put to the model leaves no trace in the delivered review, so a reader cannot
        // distinguish it from one that was assessed and produced no observations; the readiness report
        // records why — evidence we could not read, or a subject that was not in this work — and both the
        // administration surface and the artifact trace read it back from there.
        if (practices.size() < eligible.size()) {
            log.info(
                    "Not asking {} of {} practice(s): jobId={}, skipped={}",
                    eligible.size() - practices.size(),
                    eligible.size(),
                    job.getId(),
                    readiness.report().decisions().stream()
                            .filter(decision -> !decision.ready())
                            .map(decision -> decision.practiceSlug() + decision.reasonCodes())
                            .toList());
        }
        if (practices.isEmpty()) {
            throw new InsufficientEvidenceException(
                    "No practice has sufficient evidence: jobId=" + job.getId(),
                    new PreparedJobInputs(
                            prepared.files(),
                            prepared.filesOnDisk(),
                            prepared.directories(),
                            prepared.cleanups(),
                            artifactSourceManifest,
                            readiness.report()));
        }
        Map<String, byte[]> files = new LinkedHashMap<>(prepared.files());

        files.put(SandboxLayout.TASK_ENVELOPE_FILENAME, taskEnvelopeWriter.write(buildTaskEnvelope(job, metadata)));

        practiceCatalogInjector.inject(files, job, ArtifactKinds.PULL_REQUEST, practices);
        // Asks the run for a second, separate turn once its measurements are final: the feedback to say
        // now, on every lane this occasion can reach, composed over this person's record rather than over
        // this diff alone. Absent for a backfill sweep — see FeedbackCompositionInputs.
        FeedbackCompositionInputs.stage(files, PracticeDetectionDeliveryService.originOf(metadata));
        ContextMapWriter.write(files);

        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        log.info(
                "Context preparation complete: {} files, {} ms, repoId={}, pullRequestId={}",
                files.size(),
                elapsedMs,
                repositoryId,
                pullRequestId);
        return new PreparedJobInputs(
                files,
                prepared.filesOnDisk(),
                prepared.directories(),
                prepared.cleanups(),
                artifactSourceManifest,
                readiness.report());
    }

    private TaskEnvelope buildTaskEnvelope(AgentJob job, JsonNode metadata) {
        if (job.getWorkspace() == null) {
            throw new JobPreparationException("Job has no workspace: jobId=" + job.getId());
        }
        Task task = new Task.PracticeReview(
                buildPrompt(job), requireInt(metadata, "pr_number"), requireText(metadata, "repository_full_name"));
        return TaskEnvelope.of(job.getId(), job.getWorkspace().getId(), task);
    }

    private String buildPrompt(AgentJob job) {
        JsonNode metadata = job.getMetadata();
        if (metadata == null || metadata.isNull() || metadata.isMissingNode()) {
            throw new JobPreparationException("Job has no metadata: jobId=" + job.getId());
        }
        int pullRequestNumber = requireInt(metadata, "pr_number");
        String repoName = requireText(metadata, "repository_full_name");

        String prompt = "Review merge request #" + pullRequestNumber
                + " in "
                + repoName
                + ". Read the context files, then persist every justified observation via the report_observation tool. "
                + "Follow "
                + SandboxLayout.ORCHESTRATOR_PATH
                + " for the schema and rules.";
        log.info("Built orchestrator prompt: {} chars, jobId={}", prompt.length(), job.getId());
        return prompt;
    }

    // Delivery

    @Override
    public void deliver(AgentJob job) {
        if (ObservationAdmissionService.observationsWereRefused(job)) return;
        ObservationAdmissionService.requireMatchingCompositionDigest(job);
        deliverAdmitted(job);
    }

    private void deliverAdmitted(AgentJob job) {
        List<PracticeDetectionResultParser.ValidatedObservation> scopedObservations =
                observationRepository
                        .findByAgentJobId(job.getId(), job.getWorkspace().getId())
                        .stream()
                        .map(observation -> {
                            CitationVerification.requireVerified(job, observation.getEvidence());
                            return validated(observation);
                        })
                        .toList();
        if (scopedObservations.isEmpty()) throw new JobDeliveryException("Admitted observation set is empty");
        if (feedbackService.recoverAutomaticPackageIfPresent(job)) return;
        List<PracticeDetectionResultParser.ValidatedObservation> eligible = feedbackResponseSuppressionFilter
                .evaluate(job, scopedObservations)
                .deliverable();
        List<PracticeDetectionResultParser.ValidatedObservation> proposals =
                inContextDeliveryGate.awaitingApproval(job, eligible);
        List<PracticeDetectionResultParser.ValidatedObservation> loudEnough =
                inContextDeliveryGate.admitInContext(job, eligible);
        List<PracticeDetectionResultParser.ValidatedObservation> deliverable = loudEnough;
        List<ComposedFeedbackUnit> units = compositionResultParser.parse(job.getOutput(), FeedbackChannel.IN_CONTEXT);
        String lead = compositionResultParser.lead(job.getOutput());
        Map<String, String> why = practiceCatalogInjector.whyBySlug(job.getWorkspace(), ArtifactKinds.PULL_REQUEST);
        // Everything either surface would compose from: both render an all-clear when no problem
        // survives the gates, so the coverage question is asked once, over the union.
        List<PracticeDetectionResultParser.ValidatedObservation> composable = java.util.stream.Stream.concat(
                        proposals.stream(), deliverable.stream())
                .toList();
        if (ReviewCoverage.withholdsAllClear(job.getOutput(), composable)) {
            log.info("Withholding an all-clear from a review that did not reach every practice: jobId={}", job.getId());
            return;
        }
        if (!proposals.isEmpty()) {
            Set<String> included = composable.stream()
                    .map(PracticeDetectionResultParser.ValidatedObservation::occurrenceKey)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            List<PracticeDetectionResultParser.ValidatedObservation> reviewPackage = scopedObservations.stream()
                    .filter(observation -> included.contains(observation.occurrenceKey()))
                    .toList();
            feedbackService.recordProposal(
                    job,
                    DeliveryComposer.composeAdmitted(reviewPackage, ArtifactKinds.PULL_REQUEST, why, units, lead),
                    reviewPackage);
            return;
        }
        var content = DeliveryComposer.composeAdmitted(deliverable, ArtifactKinds.PULL_REQUEST, why, units, lead);
        Set<String> contributingPracticeSlugs = deliverable.stream()
                .map(PracticeDetectionResultParser.ValidatedObservation::practiceSlug)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        feedbackService.deliverFeedback(job, content, contributingPracticeSlugs);
    }

    private PracticeDetectionResultParser.ValidatedObservation validated(Observation observation) {
        return new PracticeDetectionResultParser.ValidatedObservation(
                observation.getPractice().getSlug(),
                observation.getSummary(),
                observation.getAssessmentStatus(),
                observation.getPresence(),
                observation.getAssessment(),
                observation.getSeverity(),
                observation.getEvidence(),
                observation.getEvidenceRationale(),
                new ObservationKeys(observation.getOccurrenceKey(), observation.getRecurrenceKey()));
    }

    public void admitObservations(AgentJob job, JsonNode observations) {
        deliveryService.publish(job, prepareObservations(job, observations));
    }

    public PracticeDetectionDeliveryService.PreparedObservations prepareObservations(
            AgentJob job, JsonNode observations) {
        ObjectNode output = objectMapper.createObjectNode();
        ObjectNode raw = objectMapper.createObjectNode();
        raw.set("observations", observations);
        output.put("rawOutput", raw.toString());
        return prepareParsedObservations(job, output);
    }

    private PracticeDetectionDeliveryService.PreparedObservations prepareParsedObservations(
            AgentJob job, JsonNode output) {
        var parsed = resultParser.parse(output);
        if (!parsed.discarded().isEmpty()) {
            log.info(
                    "Discarded {} observations during parsing: jobId={}, reasons={}",
                    parsed.discarded().size(),
                    job.getId(),
                    parsed.discarded());
        }
        if (parsed.validObservations().isEmpty()) {
            throw new ObservationsRefusedException(
                    "no_valid_observations",
                    "No valid observations in agent output: jobId=" + job.getId()
                            + ", discarded="
                            + parsed.discarded().size());
        }

        Set<String> diffFiles = capturedDiffPaths(job);
        Set<String> defectDetectorSlugs = practiceCatalogInjector.defectDetectorSlugs(job);
        List<PracticeDetectionResultParser.ValidatedObservation> secretObservations =
                practiceCatalogInjector.isAdmitted(job, "avoids-insecure-defaults-and-over-broad-permissions")
                        ? scanForSecrets(job)
                        : List.of();

        // What is left to catch is a review that answered without reading the change. The file count
        // above and the patch staged for the run are the same bytes — the evidence snapshot is built
        // from the very map that becomes the sandbox's input files — so an empty patch cannot coexist
        // with a non-empty file count, and a stale one is stale on both sides and invisible from here.
        // A diff citation is the one thing that cannot be produced without the patch: the runner
        // re-reads every citation out of the artifact it names (citationMatchesArtifact in
        // pi-observation-normalize.ts) and rejects the observation when the quote is not there.
        // Both unassessed statuses count as deciding nothing — NOT_APPLICABLE and UNDETERMINED
        // differ in what the run could tell, not in whether it settled anything.
        boolean nothingDecided =
                parsed.validObservations().stream().noneMatch(f -> (f.assessmentStatus() == AssessmentStatus.ASSESSED));
        if (nothingDecided
                && secretObservations.isEmpty()
                && !diffFiles.isEmpty()
                && !readTheDiff(parsed.validObservations())) {
            throw new ObservationsRefusedException(
                    "did_not_read_the_diff",
                    "No observation decided anything or quoted the diff, and the diff contains "
                            + diffFiles.size()
                            + " files — the review answered without reading the change. "
                            + "Refusing to deliver. jobId="
                            + job.getId());
        }

        var scopedObservations = new ArrayList<>(filterByDiffScope(parsed.validObservations(), diffFiles));
        if (scopedObservations.size() < parsed.validObservations().size()) {
            log.info(
                    "Diff scope filter removed {} out-of-scope observations: jobId={}, before={}, after={}",
                    parsed.validObservations().size() - scopedObservations.size(),
                    job.getId(),
                    parsed.validObservations().size(),
                    scopedObservations.size());
        }
        // Secret observations are inherently in-diff (their location is an added line) — inject AFTER the
        // diff-scope filter so a path-normalisation mismatch can never silently drop a credential.
        if (!secretObservations.isEmpty()) {
            Set<String> scannerLocations = secretObservations.stream()
                    .flatMap(f -> {
                        JsonNode evidence = f.evidence();
                        return evidence == null
                                ? java.util.stream.Stream.empty()
                                : evidence.path("citations").valueStream();
                    })
                    .map(citation -> citation.path("path").asString() + ":"
                            + citation.path("startLine").asInt())
                    .collect(java.util.stream.Collectors.toSet());
            scopedObservations.removeIf(observation ->
                    "avoids-insecure-defaults-and-over-broad-permissions".equals(observation.practiceSlug())
                            && observation.evidence() != null
                            && observation
                                    .evidence()
                                    .path("citations")
                                    .valueStream()
                                    .anyMatch(citation -> scannerLocations.contains(
                                            citation.path("path").asString() + ":"
                                                    + citation.path("startLine").asInt())));
            scopedObservations.addAll(secretObservations);
            log.warn(
                    "Secret pre-pass injected {} avoids-insecure-defaults-and-over-broad-permissions PRESENT/BAD observation(s); blocking any all-clear comment: jobId={}",
                    secretObservations.size(),
                    job.getId());
        }
        if (scopedObservations.isEmpty()) {
            throw new ObservationsRefusedException(
                    "out_of_diff_scope",
                    "All observations were filtered by diff scope: jobId=" + job.getId()
                            + ", before="
                            + parsed.validObservations().size()
                            + ", diffFiles="
                            + diffFiles.size());
        }

        // Refuse inconsistent assessments without inventing an applicability claim, and normalize severity
        // before observations are persisted or used to compose feedback.
        scopedObservations =
                new ArrayList<>(PracticeDetectionResultParser.coerceCoherence(scopedObservations, defectDetectorSlugs));

        return deliveryService.prepare(job, scopedObservations);
    }

    @Override
    public ExistingDeliveryLookup findExistingDelivery(AgentJob job) {
        return feedbackService.findExistingSummary(job);
    }

    @Override
    public boolean reconcilesMoreThanOneProviderObject() {
        return true;
    }

    private List<PracticeDetectionResultParser.ValidatedObservation> scanForSecrets(AgentJob job) {
        List<SecretDiffScanner.SecretHit> hits = secretDiffScanner.scan(job);
        if (hits.isEmpty()) return List.of();

        List<PracticeDetectionResultParser.ValidatedObservation> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (SecretDiffScanner.SecretHit hit : hits) {
            String key = hit.path() + ":" + hit.newLine() + ":" + hit.ruleId();
            if (!seen.add(key)) continue;
            out.add(toSecretObservation(hit));
        }
        return out;
    }

    private PracticeDetectionResultParser.ValidatedObservation toSecretObservation(SecretDiffScanner.SecretHit hit) {
        ObjectNode evidence = objectMapper.createObjectNode();
        evidence.put("detector", "secret-diff-scanner");
        ArrayNode citations = evidence.putArray("citations");
        ObjectNode citation = citations.addObject();
        citation.put("sourceKind", "scm.pull-request.diff");
        citation.put("artifactPath", ContentSource.OUTPUT_PREFIX + "diff.patch");
        citation.put("path", hit.path());
        citation.put("side", "NEW");
        citation.put("startLine", hit.newLine());
        citation.put("endLine", hit.newLine());
        citation.put("quoteSha256", hit.lineHash());
        citation.put("quoteRedacted", true);

        boolean lowSignal = secretDiffScanner.isLowSignalPath(hit.path());
        Severity severity = lowSignal ? Severity.MINOR : Severity.MAJOR;

        // The remediation rides in `reasoning` because this observation has no model behind it to bias: the
        // scanner is deterministic, the sentence is written here rather than generated, and a leaked
        // credential is the one case where the cost of the developer not being told what to do dominates
        // everything else. It must not wait on a composition stage that is entitled to withhold.
        String reasoning =
                "A credential appears on the cited changed line. Committed secrets remain in git history even after removal, "
                        + "so treat the credential as compromised: remove the literal value, rotate the credential immediately, and "
                        + "load it at runtime from an environment variable or a secrets manager instead of hardcoding it.";

        return new PracticeDetectionResultParser.ValidatedObservation(
                "avoids-insecure-defaults-and-over-broad-permissions",
                "Hardcoded secret on a changed line",
                AssessmentStatus.ASSESSED,
                Presence.PRESENT,
                Assessment.BAD,
                severity,
                evidence,
                reasoning);
    }

    private Set<String> capturedDiffPaths(AgentJob job) {
        JsonNode sources = job.getEvidenceSnapshot() == null
                ? null
                : job.getEvidenceSnapshot().path("manifest").path("sources");
        if (sources == null || !sources.isArray()) {
            throw new JobDeliveryException("Job has no captured source manifest: jobId=" + job.getId());
        }
        for (JsonNode source : sources) {
            if (!"scm.pull-request.diff".equals(source.path("kind").asString())
                    || !"AVAILABLE"
                            .equals(source.path("state").path("availability").asString())) {
                continue;
            }
            for (JsonNode artifact : source.path("artifacts")) {
                if ((ContentSource.OUTPUT_PREFIX + "diff_paths.nul")
                        .equals(artifact.path("path").asString())) {
                    String sha = artifact.path("sha256").asString();
                    return evidenceFiles
                            .inspect(job, artifact.path("path").asString(), sha, reader -> {
                                Set<String> paths = new HashSet<>();
                                StringBuilder path = new StringBuilder();
                                int value;
                                while ((value = reader.read()) != -1) {
                                    if (value == 0) {
                                        if (path.isEmpty())
                                            throw new JobDeliveryException("Captured diff contains an empty path");
                                        paths.add(path.toString());
                                        path.setLength(0);
                                    } else {
                                        if (path.length() >= 32_768)
                                            throw new JobDeliveryException(
                                                    "Captured diff path exceeds the filesystem path resource bound");
                                        path.append((char) value);
                                    }
                                }
                                if (!path.isEmpty())
                                    throw new JobDeliveryException("Captured diff path is not NUL terminated");
                                return Set.copyOf(paths);
                            })
                            .orElseThrow(() -> new JobDeliveryException("Captured diff is no longer available"));
                }
            }
            throw new JobDeliveryException("Captured diff source has no diff artifact: jobId=" + job.getId());
        }
        return Set.of();
    }

    /**
     * Parse file paths from {@code git diff --name-only} output.
     * Each non-blank line is a file path — no truncation or stat formatting.
     */
    static Set<String> parseDiffNameOnlyPaths(String nameOnlyOutput) {
        Set<String> paths = new HashSet<>();
        for (String line : nameOnlyOutput.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                paths.add(trimmed);
            }
        }
        return paths;
    }

    /**
     * Whether any observation cites the pull request's own diff. The runner admits a citation only after
     * finding its quote at the coordinates it names inside the staged artifact, so this is a report about
     * bytes that were read rather than a claim the model makes about itself.
     */
    static boolean readTheDiff(List<PracticeDetectionResultParser.ValidatedObservation> observations) {
        for (var observation : observations) {
            JsonNode evidence = observation.evidence();
            if (evidence == null) {
                continue;
            }
            for (JsonNode citation : evidence.path("citations")) {
                if (DIFF_SOURCE_KIND.equals(citation.path("sourceKind").asString())) {
                    return true;
                }
            }
            // A practice whose subject lives in the metadata rather than the code answers without a
            // diff citation, and its warrant is where it names the diff among the sources it walked.
            // The model writes that list, so it is weaker than a quote — which is why it only widens a
            // refusal, and never stands in for the evidence an observation itself owes.
            for (String warrant : List.of("search", "inapplicability", "undecidability")) {
                for (JsonNode consulted : evidence.path(warrant).path("consulted")) {
                    if (DIFF_SOURCE_KIND.equals(consulted.asString())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** The staged artifact a review reads the change from. */
    private static final String DIFF_SOURCE_KIND = "scm.pull-request.diff";

    static List<PracticeDetectionResultParser.ValidatedObservation> filterByDiffScope(
            List<PracticeDetectionResultParser.ValidatedObservation> observations, Set<String> diffFiles) {
        if (diffFiles.isEmpty()) return observations;
        List<PracticeDetectionResultParser.ValidatedObservation> filtered = new ArrayList<>();
        for (var observation : observations) {
            JsonNode evidence = observation.evidence();
            if (evidence == null || evidence.isNull() || evidence.isMissingNode()) {
                filtered.add(observation);
                continue;
            }
            JsonNode citations = evidence.get("citations");
            if (citations == null || !citations.isArray() || citations.isEmpty()) {
                filtered.add(observation);
                continue;
            }
            boolean hasInScopeLocation = false;
            for (JsonNode citation : citations) {
                String sourceKind = citation.path("sourceKind").asString();
                if (sourceKind.isBlank()) {
                    continue;
                }
                if (!"scm.pull-request.diff".equals(sourceKind)) {
                    hasInScopeLocation = true;
                    break;
                }
                JsonNode pathNode = citation.get("path");
                if (pathNode == null || pathNode.isNull() || pathNode.isMissingNode()) {
                    continue;
                }
                String path = pathNode.asString();
                if (path.isBlank() || "null".equals(path)) {
                    continue;
                }
                String repoRelative = path.startsWith(SandboxLayout.REPO_MOUNT_RELATIVE)
                        ? path.substring(SandboxLayout.REPO_MOUNT_RELATIVE.length())
                        : path;
                if (diffFiles.contains(path) || diffFiles.contains(repoRelative)) {
                    hasInScopeLocation = true;
                    break;
                }
            }
            if (hasInScopeLocation) {
                filtered.add(observation);
            } else {
                log.info(
                        "Filtered out-of-scope observation: slug={}, citations={}",
                        observation.practiceSlug(),
                        citations);
            }
        }
        return filtered;
    }
}
