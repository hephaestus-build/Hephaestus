package de.tum.cit.aet.hephaestus.integration.scm.domain.item;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.evidence.ClasspathSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.evidence.SourceAbsenceReason;
import de.tum.cit.aet.hephaestus.evidence.SourceCompleteness;
import de.tum.cit.aet.hephaestus.evidence.SourceContentState;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.evidence.capture.CaptureContribution;
import de.tum.cit.aet.hephaestus.evidence.capture.CaptureRequest;
import de.tum.cit.aet.hephaestus.evidence.capture.CitableText;
import de.tum.cit.aet.hephaestus.evidence.capture.EvidenceDirectory;
import de.tum.cit.aet.hephaestus.integration.core.spi.AboutRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConditionalOnCaptureRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.FoundBy;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureCode;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureException;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemEventInput;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemLabel;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemSnapshot;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemSource;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemVersion;
import de.tum.cit.aet.hephaestus.integration.core.spi.RoleHolder;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.LinkedWorkItemContentSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.PullRequestContentSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.RepositoryTreeContentSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.ReviewThreadContentSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.ScmCapturedWork;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.ScmCitableTexts;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReview;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReviewRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.events.ScmEventPayload;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
@Slf4j
@ConditionalOnCaptureRole
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ScmItemService implements ItemSource {
    // Bound both blob volume and manifest entries before the runner loads them into its fixed heap.
    static final long MAX_SCAN_BYTES = 8 * 1024 * 1024;
    static final int MAX_SCAN_FILES = 2_000;
    private final ScmItemAccess items;
    private final ScmRepositoryAccess repositories;
    private final RepositoryRepository repositoryRows;
    private final ScmWorkLookup workLookup;
    private final PullRequestReviewRepository reviews;
    private final IssueEvidenceVersion issueVersions;
    private final ScmGitTree tree;
    private final ScmFileVersions fileVersions;
    private final ObjectMapper mapper;
    private final ScmWorkCapture workCapture;
    private final Clock clock;

    @Override
    public boolean supports(ItemRef item) {
        return ScmItemEvents.ALL.contains(item.kind());
    }

    @Override
    public Map<Long, ItemLabel> labels(long workspaceId, Collection<ItemRef> refs) {
        items.requireAll(workspaceId, refs);
        return items.labels(workspaceId, refs.stream().map(ItemRef::itemId).toList());
    }

    @Transactional(readOnly = true, noRollbackFor = ItemCaptureException.class)
    @Override
    public Optional<ItemSnapshot> current(ItemRef item) {
        try {
            return Optional.of(pinItem(item, null));
        } catch (ItemCaptureException e) {
            if (e.code() == ItemCaptureCode.ITEM_REMOVED || e.code() == ItemCaptureCode.ITEM_NOT_VISIBLE)
                return Optional.empty();
            throw e;
        }
    }

    @Transactional(readOnly = true, noRollbackFor = ItemCaptureException.class)
    @Override
    public ItemSnapshot pin(ItemRef item, @Nullable ItemVersion requested) {
        return pinItem(item, requested);
    }

    /**
     * The version {@link #pin} would pin now for an issue or a pull request, behind the same item and repository
     * checks. A pull request's version reads only its stored revision, title, description and closing issues, so a
     * stored field outside them may be missing. A review, a repository and a file state none here.
     */
    @Transactional(readOnly = true, noRollbackFor = ItemCaptureException.class)
    @Override
    public Optional<ItemVersion> currentVersion(ItemRef item) {
        if (!item.kind().equals(ScmItemEvents.ISSUE) && !item.kind().equals(ScmItemEvents.PULL_REQUEST))
            return Optional.empty();
        items.require(item);
        Long sourceId = item.sourceId();
        if (sourceId == null) throw invisible();
        if (item.kind().equals(ScmItemEvents.ISSUE)) {
            Issue work = workLookup.find(item.kind(), sourceId).orElseThrow(ScmItemService::gone);
            authorize(item, work);
            title(work.getTitle());
            return Optional.of(issueVersions.of(ScmEventPayload.IssueData.from(work)));
        }
        var work = items.work(item.kind(), sourceId).orElseThrow(ScmItemService::gone);
        if (work.getDeleted()) throw gone();
        Long repositoryId = work.getRepositoryId();
        if (repositoryId == null) throw gone();
        authorize(item, repositoryRows.findById(repositoryId).orElseThrow(ScmItemService::gone));
        String base = objectId(work.getBaseRefOid());
        String head = objectId(work.getHeadRefOid());
        String title = title(work.getTitle());
        var version = ScmVersion.pullRequest(base, head, title, work.getBody());
        var linkedVersion = LinkedWorkItemContentSource.currentClosingMaterialVersion(
                head, title, work.getBody(), items.closingIssues(sourceId));
        return Optional.of(linkedVersion
                .map(context -> ScmVersion.withContext(version, context))
                .orElse(version));
    }

    private ItemSnapshot pinItem(ItemRef item, @Nullable ItemVersion requested) {
        items.require(item);
        if (item.kind().equals(ScmItemEvents.FILE) ? item.path() == null : item.sourceId() == null) throw invisible();
        ItemSnapshot snapshot;
        if (item.kind().equals(ScmItemEvents.REPOSITORY)) snapshot = repository(item, requested);
        else if (item.kind().equals(ScmItemEvents.FILE)) snapshot = file(item, requested);
        else snapshot = work(item);
        if (requested != null) {
            var actual = snapshot.version();
            boolean omitsRedundantBase = requested.baseVersion() == null
                    && (item.kind().equals(ScmItemEvents.PULL_REQUEST)
                            || item.kind().equals(ScmItemEvents.PULL_REQUEST_REVIEW));
            if (!requested.value().equals(actual.value())
                    || (!omitsRedundantBase && !Objects.equals(requested.baseVersion(), actual.baseVersion())))
                throw versionUnavailable();
        }
        return snapshot;
    }

    private ItemSnapshot work(ItemRef item) {
        var observed = observe(item, true);
        return new ItemSnapshot(
                item,
                Objects.requireNonNull(observed.version()),
                observed.integration(),
                observed.state(),
                observed.roles(),
                observed.label());
    }

    /**
     * The work as stored now. A pull request without a stored diff base has no version only when {@code
     * requireVersion} is off; otherwise every missing or malformed Git object refuses as before.
     */
    private Observed observe(ItemRef item, boolean requireVersion) {
        PullRequestReview review = null;
        ItemRef workItem = item;
        Issue work;
        if (item.kind().equals(ScmItemEvents.PULL_REQUEST_REVIEW)) {
            review = reviews.findById(Objects.requireNonNull(item.sourceId())).orElseThrow(ScmItemService::gone);
            work = Objects.requireNonNull(review.getPullRequest(), "Provider review has no pull request");
            workItem = items.parent(item, ScmItemEvents.PULL_REQUEST);
            if (!Objects.equals(workItem.sourceId(), work.getId())) throw invisible();
        } else {
            work = workLookup
                    .find(item.kind(), Objects.requireNonNull(item.sourceId()))
                    .orElseThrow(ScmItemService::gone);
        }
        IntegrationRef integration = authorize(workItem, work);
        Repository repository = Objects.requireNonNull(work.getRepository());
        List<RoleHolder> roles = new ArrayList<>();
        // The descriptor declares these review-state dimensions; practices match reviewOn.state against them.
        Map<String, String> state = new HashMap<>(work.reviewState());
        if (work instanceof PullRequest pull && !pull.getBaseRefName().isBlank())
            state.put("baseBranch", pull.getBaseRefName());
        ItemVersion version;
        String linkedContext = null;
        if (review != null) {
            add(roles, AboutRole.REVIEWER, review.getAuthor());
            PullRequest pull = (PullRequest) work;
            version = ScmVersion.pullRequestReview(
                    objectId(pull.getBaseRefOid()),
                    objectId(pull.getHeadRefOid()),
                    pull.getTitle(),
                    pull.getBody(),
                    new ScmVersion.ReviewContent(
                            objectId(review.getCommitId()),
                            review.getBody(),
                            review.getState().name()));
            state.put("reviewState", review.getState().name());
        } else {
            add(roles, AboutRole.AUTHOR, work.getAuthor());
            work.getAssignees().stream()
                    .sorted(Comparator.comparing(User::getId))
                    .forEach(u -> add(roles, AboutRole.ASSIGNEE, u));
            if (work instanceof PullRequest pull) {
                add(roles, AboutRole.MERGER, pull.getMergedBy());
                String head = objectId(pull.getHeadRefOid());
                var linkedVersion = LinkedWorkItemContentSource.currentClosingMaterialVersion(pull);
                linkedContext = linkedVersion.map(ItemVersion::value).orElse(null);
                if (!requireVersion && pull.getBaseRefOid() == null) version = null;
                else {
                    version = ScmVersion.pullRequest(
                            objectId(pull.getBaseRefOid()), head, pull.getTitle(), pull.getBody());
                    if (linkedVersion.isPresent()) version = ScmVersion.withContext(version, linkedVersion.get());
                }
            } else version = issueVersions.of(ScmEventPayload.IssueData.from(work));
        }
        String noun = work instanceof PullRequest
                ? integration.kind() == IntegrationKind.GITLAB ? "merge request" : "pull request"
                : "issue";
        if (review != null) noun = "review";
        ItemLabel label = new ItemLabel(
                item.itemId(),
                ScmItemAccess.nativeLabel(integration.kind(), noun, work.getNumber(), work.getTitle()),
                review == null ? work.getTitle() : "Review of " + work.getTitle(),
                noun,
                work.getNumber(),
                repository.getNameWithOwner(),
                review == null ? work.getHtmlUrl() : review.getHtmlUrl(),
                integration.kind());
        return new Observed(work, review, version, linkedContext, integration, state, roles, label);
    }

    private record Observed(
            Issue work,
            @Nullable PullRequestReview review,
            @Nullable ItemVersion version,
            @Nullable String linkedContext,
            IntegrationRef integration,
            Map<String, String> state,
            List<RoleHolder> roles,
            ItemLabel label) {}

    /**
     * What an occasion on {@code item} retains, read in the transaction that records it: the work as this transaction
     * stores it at {@code version}, which the caller pinned under the item lock. Null for an item kind that retains
     * nothing. When the record cannot be kept, it is an {@link ItemEventInput#unretained} statement of why: the work
     * no longer has that version ({@code NOT_OBSERVED}), or {@link ScmWorkCapture#retain} refuses to keep it. The
     * first record of an occasion stays; a redelivery never replaces it.
     */
    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = ItemCaptureException.class)
    public @Nullable String retain(ItemRef item, @Nullable ItemVersion version, FoundBy foundBy) {
        if (!ScmWorkCapture.retains(item.kind())) return null;
        Observed observed;
        try {
            observed = observe(item, false);
        } catch (ItemCaptureException unavailable) {
            // The occasion is still recorded; it keeps only the statement that its work could not be read.
            return ItemEventInput.unretained(ScmWorkCapture.NOT_OBSERVED);
        }
        if (!Objects.equals(observed.version(), version)) return ItemEventInput.unretained(ScmWorkCapture.NOT_OBSERVED);
        PullRequest pull = (PullRequest) observed.work();
        PullRequestReview review = observed.review();
        // Every person the record names, whatever their role: the author, assignees, merger and reviewer.
        List<User> copied = new ArrayList<>(pull.getAssignees());
        if (pull.getAuthor() != null) copied.add(pull.getAuthor());
        if (pull.getMergedBy() != null) copied.add(pull.getMergedBy());
        if (review != null && review.getAuthor() != null) copied.add(review.getAuthor());
        var participants = copied.stream()
                .collect(Collectors.toMap(User::getId, user -> user, (first, second) -> first, LinkedHashMap::new))
                .values()
                .stream()
                .sorted(Comparator.comparing(User::getId))
                .map(ScmOccasionEvidence.Participant::of)
                .toList();
        Instant observedAt = clock.instant();
        var work = ScmCapturedWork.of(pull, review);
        var evidence = new ScmOccasionEvidence(
                ScmOccasionEvidence.SCHEMA,
                item.kind().value(),
                Objects.requireNonNull(item.sourceId()),
                version == null ? null : version.value(),
                version == null ? null : version.baseVersion(),
                observed.linkedContext(),
                observed.integration(),
                observed.state(),
                observed.roles(),
                observed.label(),
                work,
                PullRequestContentSource.occasionRecord(pull, work, observedAt),
                review == null ? null : ReviewThreadContentSource.occasionDecision(review, observedAt),
                participants,
                observedAt,
                foundBy);
        return workCapture.retain(evidence, item.itemId());
    }

    /**
     * Pins an event review's occasion. A pull request or review occasion supplies its own version, state and roles
     * from its retained record; the work must still exist in this workspace, and the source must still authorize it
     * through the same connection. Without a usable record it refuses with
     * {@link ItemCaptureCode#OCCASION_EVIDENCE_UNAVAILABLE}, never with the current work in its place. Other kinds pin
     * as {@link #pin} does.
     */
    @Transactional(readOnly = true, noRollbackFor = ItemCaptureException.class)
    @Override
    public ItemSnapshot pinOccasion(ItemRef item, ItemVersion requested, UUID eventId) {
        if (!ScmWorkCapture.retains(item.kind())) return pinItem(item, requested);
        return occasion(item, requested, eventId).snapshot();
    }

    private record Retained(
            ScmOccasionEvidence evidence,
            ItemSnapshot snapshot,
            Issue work,
            @Nullable PullRequestReview review) {}

    private Retained occasion(ItemRef item, ItemVersion requested, UUID eventId) {
        var evidence = workCapture.occasion(item, eventId);
        if (!evidence.matches(item, requested)) throw versionUnavailable();
        items.require(item);
        PullRequestReview review = null;
        ItemRef workItem = item;
        Issue work;
        if (item.kind().equals(ScmItemEvents.PULL_REQUEST_REVIEW)) {
            review = reviews.findById(Objects.requireNonNull(item.sourceId())).orElseThrow(ScmItemService::gone);
            work = Objects.requireNonNull(review.getPullRequest(), "Provider review has no pull request");
            workItem = items.parent(item, ScmItemEvents.PULL_REQUEST);
            if (!Objects.equals(workItem.sourceId(), work.getId())) throw invisible();
        } else
            work = workLookup
                    .find(item.kind(), Objects.requireNonNull(item.sourceId()))
                    .orElseThrow(ScmItemService::gone);
        // Current access, never the access the occasion had: the same connection must still authorize the work.
        if (!authorize(workItem, work).equals(evidence.integration())
                || !Objects.equals(evidence.work().sourceId(), work.getId())) throw versionUnavailable();
        return new Retained(
                evidence,
                new ItemSnapshot(
                        item,
                        evidence.version(),
                        evidence.integration(),
                        evidence.state(),
                        evidence.roles(),
                        evidence.label()),
                work,
                review);
    }

    private ItemSnapshot repository(ItemRef item, @Nullable ItemVersion requested) {
        Repository repository =
                repositoryRows.findById(Objects.requireNonNull(item.sourceId())).orElseThrow(ScmItemService::gone);
        IntegrationRef integration = repositories.authorize(item.workspaceId(), repository);
        var key = repositories.prepare(item.workspaceId(), repository);
        if (requested == null && repository.getDefaultBranch() == null) throw versionUnavailable();
        String sha = requested == null
                ? tree.resolve(key, "refs/heads/" + Objects.requireNonNull(repository.getDefaultBranch()))
                : objectId(requested.value());
        tree.inventory(key, sha);
        return new ItemSnapshot(
                item,
                new ItemVersion(sha, null),
                integration,
                repository.getDefaultBranch() == null
                        ? Map.of()
                        : Map.of("defaultBranch", repository.getDefaultBranch()),
                List.of(),
                new ItemLabel(
                        item.itemId(),
                        repository.getNameWithOwner(),
                        repository.getNameWithOwner(),
                        "repository",
                        null,
                        null,
                        repository.getHtmlUrl(),
                        integration.kind()));
    }

    private ItemSnapshot file(ItemRef item, @Nullable ItemVersion requested) {
        ItemRef parent = items.parent(item, ScmItemEvents.REPOSITORY);
        if (requested != null && requested.baseVersion() == null) throw versionUnavailable();
        ItemSnapshot holder = repository(
                parent,
                requested == null
                        ? null
                        : new ItemVersion(
                                Objects.requireNonNull(
                                        requested.baseVersion(), "File version has no repository version"),
                                null));
        Repository repository = repositoryRows
                .findById(Objects.requireNonNull(parent.sourceId()))
                .orElseThrow(ScmItemService::gone);
        var key = repositories.prepare(item.workspaceId(), repository);
        var inventory = tree.inventory(key, holder.version().value());
        var file = inventory.stream()
                .filter(e -> e.path().equals(item.path()))
                .findFirst()
                .orElseThrow(ScmItemService::gone);
        ItemVersion version = fileVersions.version(
                file.path(),
                file.objectId(),
                file.mode(),
                List.of(),
                List.of(),
                holder.version().value());
        if (requested != null) {
            var parsed = ScmFileVersions.parse(requested).orElseThrow(ScmItemService::versionUnavailable);
            if (!parsed.blobId().equals(file.objectId())) throw versionUnavailable();
            version = requested;
        }
        return new ItemSnapshot(
                item,
                version,
                holder.integration(),
                Map.of("mode", file.mode()),
                List.of(),
                new ItemLabel(
                        item.itemId(),
                        file.path(),
                        file.path(),
                        "file",
                        null,
                        repository.getNameWithOwner(),
                        null,
                        holder.integration().kind()));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    @Override
    public CaptureContribution capture(ItemSnapshot pinned, CaptureRequest request) {
        var eventId = request.eventId();
        // An event review captures its retained occasion: the same record, checked against current access again.
        var occasion = eventId == null || !ScmWorkCapture.retains(pinned.item().kind())
                ? null
                : occasion(pinned.item(), pinned.version(), eventId);
        ItemSnapshot verified = occasion != null ? occasion.snapshot() : pinItem(pinned.item(), pinned.version());
        if (!verified.integration().equals(pinned.integration())
                || !verified.roles().equals(pinned.roles())) throw versionUnavailable();
        if (!request.sourceContractVersion().equals(ClasspathSourceCatalogRegistry.CURRENT_VERSION_VALUE)
                || request.scriptContractVersion() != 3)
            throw new ItemCaptureException(ItemCaptureCode.CAPTURE_FAILED, "Capture contract is unavailable");
        if (pinned.item().kind().equals(ScmItemEvents.REPOSITORY)
                || pinned.item().kind().equals(ScmItemEvents.FILE)) return captureTree(pinned, request);
        Issue work;
        PullRequestReview review = null;
        if (occasion != null) {
            work = occasion.work();
            review = occasion.review();
        } else if (pinned.item().kind().equals(ScmItemEvents.PULL_REQUEST_REVIEW)) {
            review = reviews.findById(Objects.requireNonNull(pinned.item().sourceId()))
                    .orElseThrow(ScmItemService::gone);
            work = Objects.requireNonNull(review.getPullRequest());
        } else
            work = workLookup
                    .find(
                            pinned.item().kind(),
                            Objects.requireNonNull(pinned.item().sourceId()))
                    .orElseThrow(ScmItemService::gone);
        return workCapture.capture(pinned, request, work, review, occasion != null ? occasion.evidence() : null);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @Override
    public JsonNode record(ItemSnapshot pinned, Map<String, byte[]> capturedFiles) {
        ItemKind kind = pinned.item().kind();
        boolean treeRecord = kind.equals(ScmItemEvents.FILE) || kind.equals(ScmItemEvents.REPOSITORY);
        if (!treeRecord
                && !kind.equals(ScmItemEvents.ISSUE)
                && !kind.equals(ScmItemEvents.PULL_REQUEST)
                && !kind.equals(ScmItemEvents.PULL_REQUEST_REVIEW)) throw invisible();
        byte[] bytes = capturedFiles.get(treeRecord ? "context/item.json" : "context/metadata.json");
        if (bytes == null)
            throw new ItemCaptureException(ItemCaptureCode.CAPTURE_FAILED, "Governed SCM item record is unavailable");
        try {
            JsonNode record = mapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(bytes);
            if (!record.isObject())
                throw new ItemCaptureException(
                        ItemCaptureCode.CAPTURE_FAILED, "Captured SCM item record is not an object");
            if (kind.equals(ScmItemEvents.FILE)) {
                var version = ScmFileVersions.parse(pinned.version()).orElseThrow(ScmItemService::versionUnavailable);
                if (!Objects.equals(pinned.item().path(), record.path("path").asString())
                        || !version.blobId().equals(record.path("blobId").asString())
                        || !Objects.equals(
                                pinned.version().baseVersion(),
                                record.path("commitId").asString())) throw versionUnavailable();
            } else if (kind.equals(ScmItemEvents.REPOSITORY)) {
                if (!Long.toString(Objects.requireNonNull(pinned.item().sourceId()))
                                .equals(record.path("repositoryId").asString())
                        || !pinned.version()
                                .value()
                                .equals(record.path("commitId").asString())) throw versionUnavailable();
            } else if ((kind.equals(ScmItemEvents.PULL_REQUEST) || kind.equals(ScmItemEvents.PULL_REQUEST_REVIEW))
                    && !ScmVersion.reviewedHead(pinned.version())
                            .equals(record.path("commit_sha").asString())) throw versionUnavailable();
            return record;
        } catch (JacksonException malformed) {
            throw new ItemCaptureException(
                    ItemCaptureCode.CAPTURE_FAILED, "Captured SCM item record is invalid", malformed);
        }
    }

    private CaptureContribution captureTree(ItemSnapshot pinned, CaptureRequest request) {
        SourceKind source = RepositoryTreeContentSource.KIND;
        if (!request.sources().contains(source)) return ScmCaptureContributions.combine(List.of());
        ItemRef holder = pinned.item().kind().equals(ScmItemEvents.FILE)
                ? items.parent(pinned.item(), ScmItemEvents.REPOSITORY)
                : pinned.item();
        Repository repository = repositoryRows
                .findById(Objects.requireNonNull(holder.sourceId()))
                .orElseThrow(ScmItemService::gone);
        var key = repositories.prepare(holder.workspaceId(), repository);
        String sha = pinned.item().kind().equals(ScmItemEvents.FILE)
                ? Objects.requireNonNull(pinned.version().baseVersion())
                : pinned.version().value();
        var scanPaths = request.scanPaths();
        var inventory = tree.inventory(key, sha).stream()
                .filter(entry -> scanPaths == null || scanPaths.contains(entry.path()))
                .toList();
        long bytes =
                inventory.stream().mapToLong(ScmFileVersions.Entry::sizeBytes).sum();
        if (inventory.size() > MAX_SCAN_FILES || bytes > MAX_SCAN_BYTES) {
            log.warn(
                    "Scan capture exceeds limit: jobId={}, files={}, bytes={}, maxFiles={}, maxBytes={}",
                    request.jobId(),
                    inventory.size(),
                    bytes,
                    MAX_SCAN_FILES,
                    MAX_SCAN_BYTES);
            return CaptureContribution.unavailable(Set.of(source), SourceAbsenceReason.CONTENT_EVICTED);
        }
        var record = mapper.createObjectNode();
        record.put("repositoryId", Long.toString(repository.getId()));
        record.put(
                "providerId",
                Long.toString(Objects.requireNonNull(
                        repository.getProvider().getId(), "Persisted SCM provider requires an id")));
        if (pinned.item().path() != null) {
            var entry = inventory.stream()
                    .filter(e -> e.path().equals(pinned.item().path()))
                    .findFirst()
                    .orElseThrow(ScmItemService::gone);
            tree.content(key, entry);
            record.put("path", entry.path());
            record.put("blobId", entry.objectId());
        }
        record.put("commitId", sha);
        byte[] recordBytes = mapper.writeValueAsBytes(record);
        var paths = scanPaths == null
                ? inventory.stream().map(ScmFileVersions.Entry::path).collect(Collectors.toSet())
                : scanPaths;
        var snapshot = tree.snapshot(key, sha, paths);
        var metadataFiles = new LinkedHashMap<>(Map.of("context/item.json", recordBytes));
        List<CitableText> citable;
        try {
            citable = new ArrayList<>(ScmCitableTexts.tree(snapshot.stagingDir(), sha));
            citable.addAll(ScmCitableTexts.files(metadataFiles, path -> source, mapper));
        } catch (RuntimeException failure) {
            snapshot.close();
            throw failure;
        }
        return new CaptureContribution(
                metadataFiles,
                Map.of(source, snapshot.complete() ? SourceCompleteness.COMPLETE : SourceCompleteness.PARTIAL),
                Map.of(source, sha),
                Map.of(source, clock.instant()),
                Map.of(),
                Map.of(source, inventory.isEmpty() ? SourceContentState.EMPTY : SourceContentState.NON_EMPTY),
                Map.of(),
                Map.of(),
                snapshot,
                snapshot.limitations().isEmpty() ? Map.of() : Map.of(source, List.copyOf(snapshot.limitations())),
                List.of(new EvidenceDirectory("repos/reviewed/", snapshot.stagingDir())),
                List.of(),
                citable);
    }

    private static void add(List<RoleHolder> roles, AboutRole role, @Nullable User user) {
        if (user != null)
            roles.add(new RoleHolder(
                    role,
                    new PersonIdentity(
                            Objects.requireNonNull(user.getProvider().getId(), "Persisted SCM provider requires an id"),
                            Long.toString(user.getNativeId()),
                            null),
                    user.getType() == User.Type.USER));
    }

    /**
     * The pending fence of a pull request item: {@link ScmVersion#pullRequestFence} over the same stored inputs and
     * checks as {@link #pin}, without the diff base. It reads only the mirror.
     */
    @Transactional(readOnly = true, noRollbackFor = ItemCaptureException.class)
    public Optional<String> pendingFence(ItemRef item) {
        if (!item.kind().equals(ScmItemEvents.PULL_REQUEST)) return Optional.empty();
        items.require(item);
        var work = workLookup
                .find(item.kind(), Objects.requireNonNull(item.sourceId(), "A pull request item has a source"))
                .orElseThrow(ScmItemService::gone);
        authorize(item, work);
        if (!(work instanceof PullRequest pull)) return Optional.empty();
        return Optional.of(ScmVersion.pullRequestFence(
                objectId(pull.getHeadRefOid()),
                pull.getBaseRefName(),
                pull.getTitle(),
                pull.getBody(),
                LinkedWorkItemContentSource.currentClosingMaterialVersion(pull).orElse(null)));
    }

    private IntegrationRef authorize(ItemRef workItem, Issue work) {
        if (work.getDeletedAt() != null) throw gone();
        Repository repository = work.getRepository();
        if (repository == null) throw gone();
        return authorize(workItem, repository);
    }

    private IntegrationRef authorize(ItemRef workItem, Repository repository) {
        ItemRef holder = items.parent(workItem, ScmItemEvents.REPOSITORY);
        if (!Objects.equals(holder.sourceId(), repository.getId())) throw invisible();
        return repositories.authorize(workItem.workspaceId(), repository);
    }

    private static String objectId(@Nullable String value) {
        if (value == null || !value.matches("[0-9a-f]{40}|[0-9a-f]{64}")) throw versionUnavailable();
        return value;
    }

    private static ItemCaptureException gone() {
        return new ItemCaptureException(ItemCaptureCode.ITEM_REMOVED, "SCM item is unavailable");
    }

    private static ItemCaptureException invisible() {
        return new ItemCaptureException(
                ItemCaptureCode.ITEM_NOT_VISIBLE, "SCM item does not belong to this workspace reference");
    }

    // A pin labels the work with its title, so work without one has no version either.
    private static String title(@Nullable String value) {
        if (value == null || value.isBlank()) throw versionUnavailable();
        return value;
    }

    private static ItemCaptureException versionUnavailable() {
        return new ItemCaptureException(ItemCaptureCode.VERSION_UNAVAILABLE, "Pinned SCM version is unavailable");
    }
}
