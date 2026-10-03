package de.tum.cit.aet.hephaestus.agent.context.providers;

import de.tum.cit.aet.hephaestus.agent.context.ContextRequest;
import de.tum.cit.aet.hephaestus.agent.context.EvidenceContribution;
import de.tum.cit.aet.hephaestus.agent.context.EvidenceDirectory;
import de.tum.cit.aet.hephaestus.agent.context.EvidenceSource;
import de.tum.cit.aet.hephaestus.agent.context.JobEvidenceFiles;
import de.tum.cit.aet.hephaestus.agent.context.WorkspaceRefusal;
import de.tum.cit.aet.hephaestus.agent.conversation.ConversationThreadProjection;
import de.tum.cit.aet.hephaestus.agent.documentation.DocumentProjection;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewaySessions;
import de.tum.cit.aet.hephaestus.agent.gateway.WorkspaceBudgetExceededException;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.ReviewMemberAiPolicy;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyRecorder;
import de.tum.cit.aet.hephaestus.evidence.ArtifactSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.evidence.ArtifactSourceContract;
import de.tum.cit.aet.hephaestus.evidence.SourceAbsenceReason;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureState;
import de.tum.cit.aet.hephaestus.evidence.SourceCompleteness;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.integration.scm.context.WorkspaceScmProjection;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryManager;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.RepositoryKey;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembershipRepository;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.commons.io.FileUtils;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** The workspace file read model. Integration-owned projections retain their consent and erasure gates. */
@Component
public class WorkspaceFolderRenderer implements EvidenceSource {
    private final WorkspaceScmProjection scmProjection;
    private final WorkspaceMembershipRepository memberships;
    private final PracticeRepository practices;
    private static final SourceKind OUTLINE = new SourceKind("outline.documents");
    private static final SourceKind INVENTORY = new SourceKind("workspace.project-inventory");
    private final JsonMapper mapper;
    private final DocumentProjection documents;
    private final ConversationThreadProjection conversations;
    private final ArtifactSourceCatalogRegistry policies;
    private final GitRepositoryManager git;
    private final JobEvidenceFiles evidenceFiles;
    private final ReviewRepositoryPreparer repositoryPreparer;
    private final ReviewHistoryContentSource history;
    private final ReviewMemberAiPolicy memberAiPolicy;
    private final PersonDataCopyRecorder personCopies;

    public WorkspaceFolderRenderer(
            WorkspaceScmProjection scmProjection,
            JsonMapper mapper,
            DocumentProjection documents,
            ConversationThreadProjection conversations,
            ArtifactSourceCatalogRegistry policies,
            GitRepositoryManager git,
            JobEvidenceFiles evidenceFiles,
            ReviewRepositoryPreparer repositoryPreparer,
            ReviewHistoryContentSource history,
            ReviewMemberAiPolicy memberAiPolicy,
            WorkspaceMembershipRepository memberships,
            PracticeRepository practices,
            PersonDataCopyRecorder personCopies) {
        this.scmProjection = scmProjection;
        this.memberships = memberships;
        this.practices = practices;
        this.personCopies = personCopies;
        this.mapper = mapper;
        this.documents = documents;
        this.conversations = conversations;
        this.policies = policies;
        this.git = git;
        this.evidenceFiles = evidenceFiles;
        this.repositoryPreparer = repositoryPreparer;
        this.history = history;
        this.memberAiPolicy = memberAiPolicy;
    }

    @Override
    public boolean supports(ContextRequest request) {
        return !(request instanceof ContextRequest.MentorChatRequest);
    }

    @Override
    public boolean ownsPath(String path) {
        return path.startsWith("context/") || path.startsWith("repos/");
    }

    @Override
    public Set<SourceKind> sourceKinds() {
        return policies.current().sources().stream()
                .map(ArtifactSourceContract::kind)
                .collect(java.util.stream.Collectors.toSet());
    }

    @Override
    public SourceKind sourceKindFor(String path) {
        if (path.equals(PullRequestContentSource.COMMITS_FILE)) return PullRequestContentSource.CORE;
        if (path.startsWith("repos/")) return new SourceKind("scm.repository.tree");
        if (path.startsWith("context/chat/")) return new SourceKind("slack.conversation.thread");
        if (path.startsWith("context/docs/")) return new SourceKind("outline.documents");
        if (path.endsWith("observations.jsonl")) return new SourceKind("hephaestus.observation-history");
        if (path.endsWith("feedback.jsonl")) return new SourceKind("hephaestus.feedback-history");
        if (path.contains("/pulls/")) {
            if (path.endsWith("inline-comments.jsonl")) return new SourceKind("scm.pull-request.comments");
            if (path.endsWith("reviews.jsonl")) return new SourceKind("scm.general-review-comments");
            if (path.endsWith("threads.jsonl")) return new SourceKind("scm.review-threads");
            if (path.endsWith("comments.jsonl")) return new SourceKind("scm.general-review-comments");
            return new SourceKind("scm.pull-request.core");
        }
        if (path.contains("/issues/"))
            return new SourceKind(path.endsWith("comments.jsonl") ? "scm.issue.comments" : "scm.issue.core");
        return new SourceKind("workspace.project-inventory");
    }

    @Override
    public void contribute(ContextRequest request, Map<String, byte[]> files) {
        throw new IllegalStateException("Workspace folders are rendered on disk, not into a byte-array capture");
    }

    @Override
    public EvidenceContribution capture(ContextRequest request, Set<SourceKind> selected) {
        AgentJob job =
                switch (request) {
                    case ContextRequest.PracticeReviewRequest r -> r.job();
                    case ContextRequest.IssueReviewRequest r -> r.job();
                    case ContextRequest.ConversationReviewRequest r -> r.job();
                    case ContextRequest.DocumentReviewRequest r -> r.job();
                    case ContextRequest.MentorChatRequest mentor ->
                        throw new IllegalArgumentException(
                                "No review job for mentor workspace " + mentor.workspaceId());
                };
        long workspace = job.getWorkspace().getId();
        Path root = evidenceFiles.renderingDirectory(job);
        BudgetedFiles files = new BudgetedFiles();
        List<EvidenceDirectory> directories = new ArrayList<>();
        List<WorkspaceRefusal> refusals = new ArrayList<>();
        try {
            var allowed = new HashSet<SourceKind>();
            for (SourceKind kind : selected) {
                if (policies.isSourceUsePermitted(
                        policies.current().version(), kind, SourceUsePurpose.AUTOMATED_PRACTICE_REVIEW))
                    allowed.add(kind);
            }
            boolean documentsReadable = allowed.contains(OUTLINE) && documents.workspaceReadable(workspace);
            Map<SourceKind, SourceCaptureState> states = new HashMap<>();
            if (allowed.contains(OUTLINE) && !documentsReadable) {
                refusals.add(new WorkspaceRefusal(
                        WorkspaceRefusal.Target.AREA, "docs", SourceAbsenceReason.ACCESS_NOT_PERMITTED));
                states.put(OUTLINE, new SourceCaptureState.Unavailable(SourceAbsenceReason.ACCESS_NOT_PERMITTED));
            }
            for (var contract : policies.current().sources()) {
                if (!allowed.contains(contract.kind())) {
                    refusals.add(new WorkspaceRefusal(
                            WorkspaceRefusal.Target.SOURCE,
                            contract.kind().value(),
                            SourceAbsenceReason.GOVERNANCE_NOT_EFFECTIVE));
                }
            }
            for (var area : Map.of(
                            "chat",
                            Set.of("slack.conversation.thread"),
                            "docs",
                            Set.of("outline.documents"),
                            "people",
                            Set.of(
                                    "workspace.project-inventory",
                                    "hephaestus.observation-history",
                                    "hephaestus.feedback-history"),
                            "practices",
                            Set.of("workspace.project-inventory"),
                            "scm",
                            Set.of(
                                    "scm.pull-request.core",
                                    "scm.issue.core",
                                    "scm.pull-request.comments",
                                    "scm.issue.comments",
                                    "scm.general-review-comments",
                                    "scm.review-threads"))
                    .entrySet()) {
                if (area.getValue().stream().noneMatch(kind -> allowed.contains(new SourceKind(kind)))) {
                    refusals.add(new WorkspaceRefusal(
                            WorkspaceRefusal.Target.AREA, area.getKey(), SourceAbsenceReason.GOVERNANCE_NOT_EFFECTIVE));
                }
            }
            var permittedRepositories = repositoryPreparer.permittedRepositories(workspace);
            var permittedIds = permittedRepositories.stream()
                    .map(repository -> repository.getId())
                    .collect(java.util.stream.Collectors.toSet());
            for (var repository : repositoryPreparer.monitoredRepositories(workspace)) {
                if (!permittedIds.contains(repository.getId()))
                    refusals.add(new WorkspaceRefusal(
                            WorkspaceRefusal.Target.REPOSITORY,
                            Long.toString(repository.getId()),
                            SourceAbsenceReason.ACCESS_NOT_PERMITTED));
            }
            for (var permitted : permittedRepositories) {
                var repository = mapper.createObjectNode()
                        .put("id", permitted.getId())
                        .put("name", permitted.getNameWithOwner())
                        .put("default_branch", permitted.getDefaultBranch());
                if (permitted.getLastSyncAt() == null) repository.putNull("synced_at");
                else repository.put("synced_at", permitted.getLastSyncAt().toString());
                long reviewedRepository = job.getMetadata() == null
                        ? -1
                        : job.getMetadata().path("repository_id").asLong(-1);
                String repo = repository.path("id").asLong() == reviewedRepository
                        ? "reviewed"
                        : Long.toString(repository.path("id").asLong());
                long id = repository.path("id").asLong();
                if (allowed.contains(new SourceKind("workspace.project-inventory")))
                    write(root, files, "context/scm/" + repo + "/repository.json", repository);
                scm(root, files, workspace, id, repo, allowed);
                if (!allowed.contains(new SourceKind("scm.repository.tree"))) {
                    refusals.add(new WorkspaceRefusal(
                            WorkspaceRefusal.Target.REPOSITORY, repo, SourceAbsenceReason.GOVERNANCE_NOT_EFFECTIVE));
                } else if (!repo.equals("reviewed") || !(request instanceof ContextRequest.PracticeReviewRequest)) {
                    var key = new RepositoryKey(workspace, id);
                    String head = git.isEnabled() && git.isRepositoryCloned(key)
                            ? git.resolveBranchHead(
                                    key, repository.path("default_branch").asString("main"))
                            : null;
                    if (head == null) {
                        refusals.add(new WorkspaceRefusal(
                                WorkspaceRefusal.Target.REPOSITORY, repo, SourceAbsenceReason.NO_WORKING_COPY));
                    } else {
                        try (var snapshot = git.readTreeSnapshot(key, head)) {
                            if (!snapshot.complete()) {
                                refusals.add(new WorkspaceRefusal(
                                        WorkspaceRefusal.Target.REPOSITORY,
                                        repo,
                                        SourceAbsenceReason.PROVIDER_FAILURE));
                            } else {
                                personCopies.recordRepository(id);
                                files.addDirectory(snapshot.stagingDir());
                                Path target = root.resolve("repos/" + repo);
                                copy(snapshot.stagingDir(), target);
                                directories.add(new EvidenceDirectory("repos/" + repo + "/", target));
                                files.put("repos/" + repo + "/.git/HEAD", target.resolve(".git/HEAD"));
                                files.put(
                                        "repos/" + repo + "/.git/hephaestus-captured-refs",
                                        target.resolve(".git/hephaestus-captured-refs"));
                            }
                        }
                    }
                }
            }
            if (request instanceof ContextRequest.PracticeReviewRequest review
                    && allowed.contains(PullRequestContentSource.CORE)) {
                var prepared = review.preparation().prepared();
                if (prepared != null) commits(root, files, prepared);
            }
            if (allowed.contains(new SourceKind("workspace.project-inventory"))) {
                inventory(root, files, workspace);
            }
            if (documentsReadable) {
                for (var document : documents.documentsForWorkspace(workspace)) {
                    if (document.deleted()) continue;
                    if (document.bodyMarkdown() == null) {
                        refusals.add(new WorkspaceRefusal(
                                WorkspaceRefusal.Target.RECORD,
                                "docs/" + segment(document.collectionSlug()) + "/" + segment(document.slug()),
                                SourceAbsenceReason.CONTENT_EVICTED));
                        continue;
                    }
                    String path = "context/docs/" + segment(document.collectionSlug()) + "/" + segment(document.slug())
                            + ".md";
                    text(
                            root,
                            files,
                            path,
                            "---\nsynced_at: " + document.syncedAt() + "\nsource_id: "
                                    + mapper.writeValueAsString(document.sourceId()) + "\n---\n\n"
                                    + document.bodyMarkdown());
                }
            }
            if (allowed.contains(new SourceKind("slack.conversation.thread"))) {
                conversations.forEachWorkspaceMessage(workspace, message -> {
                    String month = message.path("month").asString();
                    append(
                            root,
                            files,
                            "context/chat/" + segment(message.path("channel").asString()) + "/" + month + ".jsonl",
                            message);
                });
            }
            boolean profiles = allowed.contains(INVENTORY);
            boolean observations = allowed.contains(new SourceKind("hephaestus.observation-history"));
            boolean feedback = allowed.contains(new SourceKind("hephaestus.feedback-history"));
            if (profiles || observations || feedback) {
                for (var membership : memberships.findByWorkspace_Id(workspace)) {
                    if (membership.isHidden()) continue;
                    var person = membership.getUser();
                    personCopies.recordUser(person.getId());
                    if (!memberAiPolicy.allowsPerson(job, person.getId())) {
                        refusals.add(new WorkspaceRefusal(
                                WorkspaceRefusal.Target.RECORD,
                                "people/" + person.getId(),
                                SourceAbsenceReason.CONSENT_NOT_ACTIVE));
                        continue;
                    }
                    String prefix = "context/people/" + person.getId() + "/";
                    if (profiles)
                        write(
                                root,
                                files,
                                prefix + "person.json",
                                mapper.createObjectNode()
                                        .put("id", person.getId())
                                        .put("login", person.getLogin())
                                        .put("name", person.getName())
                                        .putNull("synced_at"));
                    if (observations) text(root, files, prefix + "observations.jsonl", "");
                    if (feedback) text(root, files, prefix + "feedback.jsonl", "");
                    if (observations || feedback)
                        history.renderPersonHistory(
                                workspace,
                                person.getId(),
                                allowed,
                                job.getId(),
                                record -> append(root, files, prefix + "observations.jsonl", record),
                                record -> append(root, files, prefix + "feedback.jsonl", record));
                }
            }
            if (profiles)
                for (var practice : practices.findByWorkspaceId(workspace)) {
                    text(
                            root,
                            files,
                            "context/practices/" + segment(practice.getSlug()) + ".md",
                            "---\nsynced_at: " + practice.getUpdatedAt() + "\n---\n\n" + practice.getCriteria());
                }
            files.require(0);
            Map<SourceKind, SourceCompleteness> complete = new HashMap<>();
            if (allowed.contains(INVENTORY)) complete.put(INVENTORY, SourceCompleteness.COMPLETE);
            if (documentsReadable)
                complete.put(
                        OUTLINE,
                        refusals.stream()
                                        .anyMatch(refusal -> refusal.id().startsWith("docs/")
                                                && refusal.reason() == SourceAbsenceReason.CONTENT_EVICTED)
                                ? SourceCompleteness.PARTIAL
                                : SourceCompleteness.COMPLETE);

            return new EvidenceContribution(
                    Map.of(),
                    complete,
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    states,
                    files,
                    () -> FileUtils.deleteDirectory(root.toFile()),
                    Map.of(),
                    directories,
                    refusals);
        } catch (RuntimeException exception) {
            try {
                FileUtils.deleteDirectory(root.toFile());
            } catch (IOException cleanup) {
                exception.addSuppressed(cleanup);
            }
            throw exception;
        }
    }

    private static void copy(Path source, Path target) {
        try {
            Files.walkFileTree(source, new java.nio.file.SimpleFileVisitor<>() {
                @Override
                public java.nio.file.FileVisitResult preVisitDirectory(
                        Path directory, java.nio.file.attribute.BasicFileAttributes attributes) throws IOException {
                    Files.createDirectories(target.resolve(source.relativize(directory)));
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult visitFile(
                        Path file, java.nio.file.attribute.BasicFileAttributes attributes) throws IOException {
                    if (!attributes.isRegularFile())
                        throw new IOException("Repository snapshot contains a non-regular file");
                    Files.copy(
                            file,
                            target.resolve(source.relativize(file)),
                            java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private void scm(
            Path root, BudgetedFiles files, long workspace, long repository, String name, Set<SourceKind> allowed) {
        scmProjection.forEachRecord(workspace, repository, allowed, record -> {
            String path = "context/scm/" + name + "/" + record.path();
            switch (record.format()) {
                case JSON -> write(root, files, path, record.value());
                case JSONL -> append(root, files, path, record.value());
                case MARKDOWN ->
                    text(
                            root,
                            files,
                            path,
                            "---\nsynced_at: "
                                    + record.value().path("synced_at").asString() + "\n---\n\n"
                                    + record.value().path("body").asString(""));
            }
        });
    }

    private void commits(Path root, BudgetedFiles files, ReviewRepositoryPreparer.PreparedReview prepared) {
        Path target = root.resolve(PullRequestContentSource.COMMITS_FILE);
        try {
            Files.createDirectories(target.getParent());
            try (var output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW);
                    var generator = mapper.createGenerator(output)) {
                generator.writeStartObject();
                generator.writeNullProperty("synced_at");
                generator.writeArrayPropertyStart("commits");
                for (var commit : git.commitsBetween(prepared.key(), prepared.target(), prepared.head())) {
                    var record = mapper.createObjectNode()
                            .put("sha", commit.sha())
                            .put("author", commit.authorName())
                            .put("authoredAt", commit.authoredAt().toString())
                            .put("committer", commit.committerName())
                            .put("committedAt", commit.committedAt().toString())
                            .putNull("synced_at");
                    record.put(
                            "message",
                            commit.messageBody() == null
                                    ? commit.message()
                                    : commit.message() + "\n\n" + commit.messageBody());
                    commit.parentShas().forEach(record.putArray("parents")::add);
                    var changes = record.putArray("files");
                    for (var change : commit.fileChanges()) {
                        var changed = changes.addObject()
                                .put("path", change.filename())
                                .put(
                                        "status",
                                        switch (change.changeType()) {
                                            case ADDED -> "A";
                                            case REMOVED -> "D";
                                            case RENAMED -> "R";
                                            case COPIED -> "C";
                                            default -> "M";
                                        })
                                .put("additions", change.additions())
                                .put("deletions", change.deletions());
                        if (change.previousFilename() != null) changed.put("oldPath", change.previousFilename());
                    }
                    generator.writeTree(record);
                    generator.flush();
                    files.require(Files.size(target));
                }
                generator.writeEndArray();
                generator.writeEndObject();
            }
            files.put(PullRequestContentSource.COMMITS_FILE, target);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** One uncapped precompute projection of the same folder corpus, not a second content collector. */
    private void inventory(Path root, BudgetedFiles files, long workspace) {
        Path target = root.resolve("context/project_inventory.json");
        try {
            Files.createDirectories(target.getParent());
            try (var output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW);
                    var generator = mapper.createGenerator(output)) {
                generator.writeStartObject();
                generator.writeNullProperty("synced_at");
                generator.writeBooleanProperty("truncated", false);
                for (String type : List.of("ISSUE", "PULL_REQUEST")) {
                    generator.writeArrayPropertyStart(type.equals("ISSUE") ? "issues" : "pullRequests");
                    for (var repository : repositoryPreparer.permittedRepositories(workspace)) {
                        scmProjection.forEachInventoryRecord(workspace, repository.getId(), type, record -> {
                            generator.writeTree(record);
                            generator.flush();
                            try {
                                files.require(Files.size(target));
                            } catch (IOException exception) {
                                throw new UncheckedIOException(exception);
                            }
                        });
                    }
                    generator.writeEndArray();
                }
                generator.writeEndObject();
            }
            files.put("context/project_inventory.json", target);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private void write(Path root, BudgetedFiles files, String path, ObjectNode value) {
        text(root, files, path, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n");
    }

    private void append(Path root, BudgetedFiles files, String path, ObjectNode value) {
        String line = mapper.writeValueAsString(value) + "\n";
        files.require(line.getBytes(StandardCharsets.UTF_8).length);
        try {
            Path target = root.resolve(path);
            Files.createDirectories(target.getParent());
            Files.writeString(target, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            files.put(path, target);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void text(Path root, BudgetedFiles files, String path, String text) {
        files.require(text.getBytes(StandardCharsets.UTF_8).length);
        try {
            Path target = root.resolve(path);
            Files.createDirectories(target.getParent());
            Files.writeString(target, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            files.put(path, target);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Per-render accounting is constant time per record and includes Git objects, not just worktree blobs. */
    private static final class BudgetedFiles extends LinkedHashMap<String, Path> {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        private final HashMap<String, Long> sizes = new HashMap<>();
        private long bytes;

        void require(long extra) {
            if (extra < 0 || bytes > SandboxGatewaySessions.WORKSPACE_BYTE_BUDGET - extra)
                throw new WorkspaceBudgetExceededException(bytes + extra, SandboxGatewaySessions.WORKSPACE_BYTE_BUDGET);
        }

        void addDirectory(Path directory) {
            try (var paths = Files.walk(directory)) {
                long size = 0;
                var files = paths.filter(Files::isRegularFile).iterator();
                while (files.hasNext()) {
                    size = Math.addExact(size, Files.size(files.next()));
                    require(size);
                }
                require(size);
                bytes += size;
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }

        @Override
        public Path put(String key, Path value) {
            try {
                if (!key.startsWith("repos/")) {
                    long size = Files.size(value);
                    long extra = size - sizes.getOrDefault(key, 0L);
                    require(extra);
                    bytes += extra;
                    sizes.put(key, size);
                }
                return super.put(key, value);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }
    }

    private static String segment(String input) {
        String encoded =
                java.net.URLEncoder.encode(input, StandardCharsets.UTF_8).replace("+", "%20");
        if (encoded.equals(".")) return "%2E";
        if (encoded.equals("..")) return "%2E%2E";
        return encoded.isEmpty() ? "_" : encoded;
    }
}
