package de.tum.cit.aet.hephaestus.integration.scm.domain.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.context.JobFolderIndexBuilder;
import de.tum.cit.aet.hephaestus.evidence.ClasspathSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.evidence.SourceAbsenceReason;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureState;
import de.tum.cit.aet.hephaestus.evidence.SourceCompleteness;
import de.tum.cit.aet.hephaestus.evidence.capture.CaptureRequest;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.fabric.FabricLayout;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureCode;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureException;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemSnapshot;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemVersion;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.RepositoryTreeContentSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReviewRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryLockManager;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryManager;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryProperties;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.RepositoryKey;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.GitTestFixtures;
import de.tum.cit.aet.hephaestus.testconfig.ItemFixtures;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class ScmItemRecordTest extends BaseUnitTest {
    private static final String BASE = "a".repeat(40), HEAD = "b".repeat(40), BLOB = "c".repeat(40);
    private final JsonMapper mapper = new JsonMapper();

    @TempDir
    Path root;

    private final ScmRepositoryAccess access = mock(ScmRepositoryAccess.class);
    private final ScmGitTree tree = mock(ScmGitTree.class);
    private final ScmItemAccess items = mock(ScmItemAccess.class);
    private final RepositoryRepository repositories = mock(RepositoryRepository.class);
    private final ScmWorkLookup workLookup = mock(ScmWorkLookup.class);
    private final PullRequestReviewRepository reviews = mock(PullRequestReviewRepository.class);
    private final ScmItemService service = new ScmItemService(
            items,
            access,
            repositories,
            workLookup,
            reviews,
            mock(IssueEvidenceVersion.class),
            tree,
            mock(ScmFileVersions.class),
            mapper,
            mock(ScmWorkCapture.class),
            Clock.systemUTC());

    @Test
    void shouldStageTheScanRecordAndPinnedTextWithoutInventoryOrGitMetadata() throws Exception {
        var pinned = snapshot("scm.file");
        var holder = new ItemRef(1, 4, ScmItemEvents.REPOSITORY, 3L, null, null);
        var repository = new Repository();
        repository.setId(3L);
        repository.setNameWithOwner("owner/repository");
        var provider = new IdentityProvider(IdentityProviderType.GITHUB, "https://github.example");
        provider.setId(5L);
        repository.setProvider(provider);
        var key = new RepositoryKey(1, 3);
        var entry = new ScmFileVersions.Entry("src/a.ts", "100644", BLOB);
        byte[] bytes = "insecure();\n".getBytes(StandardCharsets.UTF_8);
        var staging = Files.createDirectories(root.resolve("scan"));
        Files.createDirectories(staging.resolve("src"));
        Files.write(staging.resolve("src/a.ts"), bytes);
        when(items.parent(pinned.item(), ScmItemEvents.REPOSITORY)).thenReturn(holder);
        when(repositories.findById(3L)).thenReturn(Optional.of(repository));
        when(access.authorize(1, repository)).thenReturn(pinned.integration());
        when(access.prepare(1, repository)).thenReturn(key);
        when(tree.inventory(key, HEAD)).thenReturn(List.of(entry));
        when(tree.content(key, entry)).thenReturn(bytes);
        when(tree.snapshot(key, HEAD, Set.of("src/a.ts")))
                .thenReturn(new GitRepositoryManager.GitTreeSnapshot(
                        staging, HEAD, "d".repeat(40), bytes.length, 1, true, Set.of()));
        var source = RepositoryTreeContentSource.KIND;
        var capture = service.capture(
                pinned, new CaptureRequest(UUID.randomUUID(), UUID.randomUUID(), 0, Set.of(source), "3.0.0", 3));
        var catalogs = new ClasspathSourceCatalogRegistry(mapper, Clock.systemUTC());
        try (var prepared = new JobFolderIndexBuilder(catalogs, mapper, Clock.systemUTC())
                .build(ScmItemEvents.FILE, Map.of(source, capture))) {
            var manifest = Objects.requireNonNull(prepared.manifest());
            assertThat(manifest.files()).anySatisfy(file -> {
                assertThat(file.kind()).isEqualTo(source);
                assertThat(file.file().path()).isEqualTo("context/item.json");
            });
            var record = service.record(pinned, prepared.files());
            assertThat(record.path("path").asString()).isEqualTo("src/a.ts");
            assertThat(record.path("blobId").asString()).isEqualTo(BLOB);
            assertThat(record.path("commitId").asString()).isEqualTo(HEAD);
            assertThat(prepared.files()).doesNotContainKey("context/inventory.json");
            assertThat(prepared.directories()).singleElement().satisfies(directory -> {
                assertThat(directory.target()).isEqualTo("repos/reviewed/");
                assertThat(directory.source().resolve(".git")).doesNotExist();
                assertThat(directory.source().resolve("src/a.ts")).hasBinaryContent(bytes);
            });
            assertThat(manifest.citableTexts()).anySatisfy(text -> {
                assertThat(text.source()).isEqualTo(source.value());
                assertThat(text.path()).isEqualTo("src/a.ts");
                assertThat(text.commit()).isEqualTo(HEAD);
                assertThat(text.stagedPath()).isEqualTo("repos/reviewed/src/a.ts");
            });
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldStageExactlyTheFrozenScanGrantIncludingBroadContexts(boolean broadContext) throws Exception {
        var original = snapshot("scm.file");
        var source = RepositoryTreeContentSource.KIND;
        var key = new RepositoryKey(1, 3);
        var manager = new GitRepositoryManager(
                new GitRepositoryProperties(true, 2, 64 * 1024 * 1024),
                new GitRepositoryLockManager(),
                new FabricLayout(root.resolve("fabric").toString()));
        var gitTree = new ScmGitTree(manager);
        var versions = new ScmFileVersions(mapper);
        ReflectionTestUtils.setField(service, "tree", gitTree);
        ReflectionTestUtils.setField(service, "fileVersions", versions);
        Path work = Files.createDirectories(root.resolve("source"));
        String commit;
        try (var git = Git.init().setDirectory(work.toFile()).call()) {
            GitTestFixtures.disableSigning(git.getRepository());
            for (var path : List.of("src/a.ts", "docs/rules.md", "config/style.json", "unrelated.ts")) {
                Files.createDirectories(work.resolve(path).getParent());
                Files.writeString(work.resolve(path), "content for " + path + "\n");
            }
            if (!broadContext)
                Files.writeString(work.resolve("large.txt"), "x".repeat((int) ScmItemService.MAX_SCAN_BYTES + 1));
            git.add().addFilepattern(".").call();
            commit = git.commit()
                    .setSign(false)
                    .setMessage("Fixture")
                    .setAuthor("Test", "test@example.org")
                    .setCommitter("Test", "test@example.org")
                    .call()
                    .name();
        }
        manager.ensureRepository(key, work.toUri().toString(), null);
        var inventory = gitTree.inventory(key, commit);
        var assigned = gitTree.index(key, commit).assigned("src/a.ts");
        var version =
                versions.version(assigned.path(), assigned.objectId(), assigned.mode(), List.of(), List.of(), commit);
        var pinned = new ItemSnapshot(
                original.item(), version, original.integration(), Map.of(), List.of(), original.label());
        var repository = new Repository();
        repository.setId(3L);
        repository.setNameWithOwner("owner/repository");
        var provider = new IdentityProvider(IdentityProviderType.GITHUB, "https://github.example");
        provider.setId(5L);
        repository.setProvider(provider);
        when(items.parent(pinned.item(), ScmItemEvents.REPOSITORY))
                .thenReturn(new ItemRef(1, 4, ScmItemEvents.REPOSITORY, 3L, null, null));
        when(repositories.findById(3L)).thenReturn(Optional.of(repository));
        when(access.authorize(1, repository)).thenReturn(pinned.integration());
        when(access.prepare(1, repository)).thenReturn(key);
        var permitted = new HashSet<>(gitTree.index(key, commit)
                .contextPaths("src/a.ts", broadContext ? List.of("**/*") : List.of("docs/*.md", "config/*.json")));
        permitted.add("src/a.ts");
        var contribution = service.capture(
                pinned,
                new CaptureRequest(UUID.randomUUID(), UUID.randomUUID(), 0, Set.of(source), "3.0.0", 3, permitted));
        var catalogs = new ClasspathSourceCatalogRegistry(mapper, Clock.systemUTC());
        try (var prepared = new JobFolderIndexBuilder(catalogs, mapper, Clock.systemUTC())
                .build(ScmItemEvents.FILE, Map.of(source, contribution))) {
            var manifest = Objects.requireNonNull(prepared.manifest());
            assertThat(inventory).hasSize(broadContext ? 4 : 5);
            assertThat(contribution.completeness()).containsEntry(source, SourceCompleteness.COMPLETE);
            Path staged = prepared.directories().getFirst().source();
            try (var files = Files.walk(staged)) {
                assertThat(files.filter(Files::isRegularFile)
                                .map(staged::relativize)
                                .map(Path::toString))
                        .containsExactlyInAnyOrderElementsOf(permitted);
            }
            assertThat(manifest.citableTexts().stream()
                            .filter(text -> text.stagedPath().startsWith("repos/reviewed/"))
                            .map(text -> text.path()))
                    .containsExactlyInAnyOrderElementsOf(permitted.stream()
                            .flatMap(path -> Stream.of(path, path))
                            .toList());
            assertThat(prepared.files()).doesNotContainKey("context/inventory.json");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldWithholdOversizedScanCapturesBeforeStaging(boolean exceedsBytes) {
        var pinned = snapshot("scm.file");
        var repository = new Repository();
        repository.setId(3L);
        repository.setNameWithOwner("owner/repository");
        when(items.parent(pinned.item(), ScmItemEvents.REPOSITORY))
                .thenReturn(new ItemRef(1, 4, ScmItemEvents.REPOSITORY, 3L, null, null));
        when(repositories.findById(3L)).thenReturn(Optional.of(repository));
        when(access.authorize(1, repository)).thenReturn(pinned.integration());
        var key = new RepositoryKey(1, 3);
        when(access.prepare(1, repository)).thenReturn(key);
        var inventory = new ArrayList<ScmFileVersions.Entry>();
        inventory.add(new ScmFileVersions.Entry("src/a.ts", "100644", BLOB));
        if (exceedsBytes) {
            inventory.add(
                    new ScmFileVersions.Entry("large.txt", "100644", BLOB, ScmItemService.MAX_SCAN_BYTES + 1, false));
        } else {
            for (int i = 0; i < ScmItemService.MAX_SCAN_FILES; i++)
                inventory.add(new ScmFileVersions.Entry("file-" + i, "100644", BLOB));
        }
        when(tree.inventory(key, HEAD)).thenReturn(inventory);
        var source = RepositoryTreeContentSource.KIND;
        var contribution = service.capture(
                pinned,
                new CaptureRequest(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        0,
                        Set.of(source),
                        "3.0.0",
                        3,
                        inventory.stream().map(ScmFileVersions.Entry::path).collect(Collectors.toSet())));
        assertThat(contribution.stateOverrides())
                .containsEntry(source, new SourceCaptureState.Unavailable(SourceAbsenceReason.CONTENT_EVICTED));
        assertThat(contribution.files()).isEmpty();
        assertThat(contribution.citableTexts()).isEmpty();
        assertThat(contribution.directories()).isEmpty();
        verify(tree, never()).snapshot(any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"scm.issue", "scm.pull_request", "scm.pull_request_review", "scm.repository", "scm.file"})
    void shouldReadTheGovernedRecordBytesWithoutCallingMirrors(String value) {
        var pinned = snapshot(value);
        var record = mapper.createObjectNode();
        if (value.equals("scm.file") || value.equals("scm.repository")) {
            record.put("repositoryId", "3");
            record.put("providerId", "5");
        } else record.put("body", "Captured words\nQuoted β");
        if (pinned.item().kind().equals(ScmItemEvents.FILE)) {
            record.put("path", pinned.item().path());
            record.put("blobId", BLOB);
            record.put("commitId", HEAD);
        } else if (pinned.item().kind().equals(ScmItemEvents.REPOSITORY)) {
            record.put("repositoryId", "3");
            record.put("commitId", HEAD);
        } else record.put("commit_sha", HEAD);
        String path = value.equals("scm.file") || value.equals("scm.repository")
                ? "context/item.json"
                : "context/metadata.json";
        var source = service;
        assertThat(source.record(pinned, Map.of(path, mapper.writeValueAsBytes(record))))
                .isEqualTo(record);
        verifyNoInteractions(items, repositories, workLookup, reviews);
    }

    @Test
    void shouldRefuseMissingOrMalformedGovernedBytesWithoutFallingBackToCurrentState() {
        var pinned = snapshot("scm.issue");
        var source = service;
        for (var files : List.<Map<String, byte[]>>of(
                Map.of(),
                Map.of("context/metadata.json", new byte[0]),
                Map.of("context/metadata.json", "null".getBytes(StandardCharsets.UTF_8)),
                Map.of("context/metadata.json", "{} {}".getBytes(StandardCharsets.UTF_8)),
                Map.of("context/metadata.json", "[1]".getBytes(StandardCharsets.UTF_8)),
                Map.of("context/metadata.json", "invalid JSON".getBytes(StandardCharsets.UTF_8)))) {
            assertThatThrownBy(() -> source.record(pinned, files))
                    .isInstanceOfSatisfying(
                            ItemCaptureException.class,
                            failure -> assertThat(failure.code()).isEqualTo(ItemCaptureCode.CAPTURE_FAILED));
        }
        verifyNoInteractions(items, repositories, workLookup, reviews);
    }

    @ParameterizedTest
    @ValueSource(strings = {"path", "blobId", "commitId"})
    void shouldRefuseAFileRecordThatContradictsThePinnedFile(String changed) {
        var pinned = snapshot("scm.file");
        var record = mapper.createObjectNode();
        record.put("path", "src/a.ts");
        record.put("blobId", BLOB);
        record.put("commitId", HEAD);
        record.put(changed, changed.equals("path") ? "src/other.ts" : "d".repeat(40));
        assertThatThrownBy(() -> service.record(pinned, Map.of("context/item.json", mapper.writeValueAsBytes(record))))
                .isInstanceOfSatisfying(
                        ItemCaptureException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ItemCaptureCode.VERSION_UNAVAILABLE));
    }

    @Test
    void shouldPropagateProgrammingFailuresWithoutCallingThemMalformedRecords() {
        var brokenMapper = mock(ObjectMapper.class);
        var failure = new NullPointerException("Invalid reader configuration");
        when(brokenMapper.reader()).thenThrow(failure);
        ReflectionTestUtils.setField(service, "mapper", brokenMapper);

        assertThatThrownBy(() -> service.record(snapshot("scm.issue"), Map.of("context/metadata.json", new byte[0])))
                .isSameAs(failure);
    }

    private ItemSnapshot snapshot(String value) {
        ItemKind kind = new ItemKind(value);
        ItemRef item = kind.equals(ScmItemEvents.FILE)
                ? new ItemRef(1, 2, kind, null, 4L, "src/a.ts")
                : new ItemRef(1, 2, kind, 3L, kind.equals(ScmItemEvents.REPOSITORY) ? null : 4L, null);
        ItemVersion version = switch (value) {
            case "scm.file" -> new ItemVersion("scm.file:v1:" + BLOB + ":" + "e".repeat(64), HEAD);
            case "scm.repository" -> new ItemVersion(HEAD, null);
            case "scm.pull_request" -> ScmVersion.pullRequest(BASE, HEAD, "Title", null);
            case "scm.pull_request_review" ->
                ScmVersion.pullRequestReview(
                        BASE, HEAD, "Title", null, new ScmVersion.ReviewContent(HEAD, "Review", "COMMENTED"));
            default -> new ItemVersion("opaque", null);
        };
        return ItemFixtures.snapshot(item, Map.of())
                .version(version)
                .integration(new IntegrationRef(IntegrationKind.GITHUB, 1, null))
                .label(ItemFixtures.label()
                        .itemId(2)
                        .title("Work")
                        .noun("item")
                        .integration(IntegrationKind.GITHUB)
                        .build())
                .build();
    }
}
