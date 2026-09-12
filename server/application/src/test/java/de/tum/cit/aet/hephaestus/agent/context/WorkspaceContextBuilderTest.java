package de.tum.cit.aet.hephaestus.agent.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.handler.spi.JobPreparationException;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.evidence.SourceAbsenceReason;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureState;
import de.tum.cit.aet.hephaestus.evidence.SourceCompleteness;
import de.tum.cit.aet.hephaestus.evidence.SourceContentState;
import de.tum.cit.aet.hephaestus.evidence.SourceContractVersion;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.evidence.internal.ClasspathArtifactSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.Ordered;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class WorkspaceContextBuilderTest extends BaseUnitTest {

    private static AgentJob anyJob() {
        var job = new AgentJob();
        job.setId(UUID.randomUUID());
        return job;
    }

    private static ContextRequest.PracticeReviewRequest reviewRequest() {
        return new ContextRequest.PracticeReviewRequest(anyJob());
    }

    private static WorkspaceContextBuilder builderOf(ContentSource... providers) {
        return new WorkspaceContextBuilder(List.of(providers), new SimpleMeterRegistry(), null);
    }

    private static SimpleMeterRegistry sharedRegistry = new SimpleMeterRegistry();

    private static WorkspaceContextBuilder builderWithSharedRegistry(ContentSource... providers) {
        sharedRegistry = new SimpleMeterRegistry();
        return new WorkspaceContextBuilder(List.of(providers), sharedRegistry, null);
    }

    private static ContentSource stubProvider(boolean required, String pathSuffix, byte[] payload, boolean throwError) {
        return new ContentSource() {
            @Override
            public boolean supports(ContextRequest request) {
                return true;
            }

            @Override
            public boolean required() {
                return required;
            }

            @Override
            public void contribute(ContextRequest request, Map<String, byte[]> files) {
                if (throwError) {
                    throw new EvidenceCollectionException("provider boom", new RuntimeException("downstream failure"));
                }
                files.put(OUTPUT_PREFIX + pathSuffix, payload);
            }
        };
    }

    @Nested
    class Metrics {

        @Test
        void recordsBuildDurationTimer() {
            var p = stubProvider(true, "a.txt", "A".getBytes(StandardCharsets.UTF_8), false);
            builderWithSharedRegistry(p).build(reviewRequest());
            assertThat(sharedRegistry
                            .timer("agent.context.build.duration", "kind", "PracticeReviewRequest")
                            .count())
                    .isEqualTo(1L);
        }

        @Test
        void emitsRequiredFailureCounter() {
            var bad = stubProvider(true, "x.txt", new byte[0], true);
            assertThatThrownBy(() -> builderWithSharedRegistry(bad).build(reviewRequest()))
                    .isInstanceOf(JobPreparationException.class);
            String providerName = bad.getClass().getSimpleName();
            assertThat(sharedRegistry
                            .counter("agent.context.provider.required.failure", "provider", providerName)
                            .count())
                    .isEqualTo(1d);
        }
    }

    @Nested
    class HappyPath {

        @Test
        void invokesAllMatching() {
            var a = stubProvider(true, "a.txt", "A".getBytes(StandardCharsets.UTF_8), false);
            var b = stubProvider(false, "b.txt", "B".getBytes(StandardCharsets.UTF_8), false);

            Map<String, byte[]> files = builderOf(a, b).build(reviewRequest());

            assertThat(files).hasSize(2);
            assertThat(files.get("inputs/context/a.txt"))
                    .asString(StandardCharsets.UTF_8)
                    .isEqualTo("A");
            assertThat(files.get("inputs/context/b.txt"))
                    .asString(StandardCharsets.UTF_8)
                    .isEqualTo("B");
        }

        @Test
        @DisplayName("empty provider list returns empty file map")
        void emptyProvidersReturnsEmpty() {
            assertThat(builderOf().build(reviewRequest())).isEmpty();
        }

        @Test
        @DisplayName("skips providers that do not support the request")
        void skipsUnsupported() {
            var supports = stubProvider(true, "a.txt", "A".getBytes(StandardCharsets.UTF_8), false);
            var skips = new ContentSource() {
                @Override
                public boolean supports(ContextRequest request) {
                    return false;
                }

                @Override
                public void contribute(ContextRequest request, Map<String, byte[]> files) {
                    files.put("inputs/context/should-not-appear.txt", new byte[0]);
                }
            };
            Map<String, byte[]> files = builderOf(skips, supports).build(reviewRequest());
            assertThat(files).containsOnlyKeys("inputs/context/a.txt");
        }
    }

    @Nested
    class FailurePolicy {

        @Test
        void requiredFailureThrows() {
            var bad = stubProvider(true, "x.txt", new byte[0], true);
            assertThatThrownBy(() -> builderOf(bad).build(reviewRequest()))
                    .isInstanceOf(JobPreparationException.class)
                    .hasMessageContaining("Required content provider failed");
        }

        @Test
        void optionalFailureSkipped() {
            var bad = stubProvider(false, "x.txt", new byte[0], true);
            var good = stubProvider(true, "y.txt", "Y".getBytes(StandardCharsets.UTF_8), false);
            Map<String, byte[]> files = builderOf(bad, good).build(reviewRequest());
            assertThat(files).containsOnlyKeys("inputs/context/y.txt");
        }

        @Test
        void unexpectedProviderBugPropagates() {
            var bad = new ContentSource() {
                @Override
                public boolean supports(ContextRequest request) {
                    return true;
                }

                @Override
                public void contribute(ContextRequest request, Map<String, byte[]> files) {
                    throw new IllegalStateException("programmer bug");
                }
            };

            assertThatThrownBy(() -> builderOf(bad).build(reviewRequest()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("programmer bug");
        }

        @Test
        void rejectsDetectorProviderWithoutSourceKinds() {
            ContentSource provider = stubProvider(true, "untracked.json", new byte[0], false);
            ContextManifestBuilder manifests = mock(ContextManifestBuilder.class);
            when(manifests.stagedSources(any())).thenReturn(Set.of(new SourceKind("scm.pull-request.core")));
            var builder = new WorkspaceContextBuilder(List.of(provider), new SimpleMeterRegistry(), manifests);
            EvidencePlan plan = new EvidencePlan(new SourceContractVersion("1.1.0"), ArtifactKinds.PULL_REQUEST);

            assertThatThrownBy(() -> builder.prepare(reviewRequest(), plan))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("must declare source kinds");
        }

        static Stream<RuntimeException> collectionFailures() {
            return Stream.of(
                    new EvidenceCollectionException("provider boom", new RuntimeException("downstream failure")),
                    // The shape a repository actually throws, and the reason the catch cannot be narrowed to
                    // the declared one: letting it escape would abort every source that had already succeeded.
                    new org.springframework.dao.QueryTimeoutException("statement timed out"));
        }

        /**
         * A required source that fails costs its own source and nothing else, and the refusal is persisted:
         * the manifest is what a later reader has to tell a conservative refusal from a clean review.
         */
        @ParameterizedTest
        @MethodSource("collectionFailures")
        void aFailedRequiredSourceIsRecordedAndIsolated(RuntimeException failure, @TempDir Path root) {
            SourceKind comments = new SourceKind("scm.pull-request.comments");
            EvidenceSource bad = new EvidenceSource() {
                @Override
                public boolean supports(ContextRequest request) {
                    return true;
                }

                @Override
                public boolean required() {
                    return true;
                }

                @Override
                public Set<SourceKind> sourceKinds() {
                    return Set.of(comments);
                }

                @Override
                public SourceKind sourceKindFor(String path) {
                    return comments;
                }

                @Override
                public void contribute(ContextRequest request, Map<String, byte[]> files) {
                    throw failure;
                }
            };
            JsonMapper mapper = JsonMapper.builder().build();
            ContextManifestBuilder manifestBuilder = new ContextManifestBuilder(
                    mapper,
                    new ClasspathArtifactSourceCatalogRegistry(mapper, java.time.Clock.systemUTC()),
                    new PracticeSubjectEvaluator(mapper),
                    Clock.systemUTC());
            var builder = new WorkspaceContextBuilder(List.of(bad), new SimpleMeterRegistry(), manifestBuilder);
            EvidencePlan plan = new EvidencePlan(new SourceContractVersion("1.1.0"), ArtifactKinds.PULL_REQUEST);
            ContextRequest.PracticeReviewRequest request = reviewRequest();

            PreparedEvidence prepared = builder.prepare(request, plan);

            var capture = java.util.Objects.requireNonNull(prepared.manifest()).sources().stream()
                    .filter(source -> source.kind().equals(comments))
                    .findFirst()
                    .orElseThrow();
            assertThat(capture.state())
                    .isEqualTo(new SourceCaptureState.CollectionError(SourceAbsenceReason.PROVIDER_FAILURE));
            assertThat(prepared.files()).containsKey("inputs/manifest.json");
        }

        /**
         * What an earlier collector staged on disk has no owner once a later one fails the build; the
         * builder is the only party that ever held its cleanup.
         */
        @Test
        void shouldReleaseEarlierCapturesWhenALaterProviderFailsTheBuild() {
            SourceKind diff = new SourceKind("scm.pull-request.diff");
            SourceKind comments = new SourceKind("scm.pull-request.comments");
            var released = new java.util.concurrent.atomic.AtomicBoolean();
            EvidenceSource staged = new EvidenceSource() {
                @Override
                public Set<SourceKind> sourceKinds() {
                    return Set.of(diff);
                }

                @Override
                public SourceKind sourceKindFor(String path) {
                    return diff;
                }

                @Override
                public EvidenceContribution capture(ContextRequest request, Set<SourceKind> selectedKinds) {
                    return new EvidenceContribution(
                            Map.of("inputs/context/diff.patch", new byte[] {1}),
                            Map.of(diff, SourceCompleteness.COMPLETE),
                            Map.of(),
                            Map.of(),
                            Map.of(),
                            Map.of(diff, SourceContentState.NON_EMPTY),
                            Map.of(),
                            Map.of(),
                            () -> released.set(true));
                }

                @Override
                public boolean supports(ContextRequest request) {
                    return true;
                }

                @Override
                public void contribute(ContextRequest request, Map<String, byte[]> files) {}
            };
            EvidenceSource clashing = new EvidenceSource() {
                @Override
                public Set<SourceKind> sourceKinds() {
                    return Set.of(comments);
                }

                @Override
                public SourceKind sourceKindFor(String path) {
                    return comments;
                }

                @Override
                public EvidenceContribution capture(ContextRequest request, Set<SourceKind> selectedKinds) {
                    // The same path the first source already owns: a wiring bug the build must refuse.
                    return new EvidenceContribution(
                            Map.of("inputs/context/diff.patch", new byte[] {2}),
                            Map.of(comments, SourceCompleteness.COMPLETE));
                }

                @Override
                public boolean supports(ContextRequest request) {
                    return true;
                }

                @Override
                public void contribute(ContextRequest request, Map<String, byte[]> files) {}
            };
            ContextManifestBuilder manifests = mock(ContextManifestBuilder.class);
            when(manifests.isSourceUsePermitted(any(), any())).thenReturn(true);
            when(manifests.stagedSources(any())).thenReturn(Set.of(diff, comments));
            var builder = new WorkspaceContextBuilder(List.of(staged, clashing), new SimpleMeterRegistry(), manifests);
            EvidencePlan plan = new EvidencePlan(new SourceContractVersion("1.1.0"), ArtifactKinds.PULL_REQUEST);

            assertThatThrownBy(() -> builder.prepare(reviewRequest(), plan))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Duplicate workspace key");
            assertThat(released).isTrue();
        }

        @Test
        void preservesSerializedArtifactsForAValidEmptySource(@TempDir Path root) {
            SourceKind diff = new SourceKind("scm.pull-request.diff");
            EvidenceSource provider = new EvidenceSource() {
                @Override
                public Set<SourceKind> sourceKinds() {
                    return Set.of(diff);
                }

                @Override
                public SourceKind sourceKindFor(String path) {
                    return diff;
                }

                @Override
                public EvidenceContribution capture(ContextRequest request, Set<SourceKind> selectedKinds) {
                    return new EvidenceContribution(
                            Map.of("inputs/context/diff.patch", new byte[0]),
                            Map.of(diff, SourceCompleteness.COMPLETE),
                            Map.of(),
                            Map.of(),
                            Map.of(),
                            Map.of(diff, SourceContentState.EMPTY));
                }

                @Override
                public boolean supports(ContextRequest request) {
                    return true;
                }

                @Override
                public void contribute(ContextRequest request, Map<String, byte[]> files) {}
            };
            JsonMapper mapper = JsonMapper.builder().build();
            ContextManifestBuilder manifests = new ContextManifestBuilder(
                    mapper,
                    new ClasspathArtifactSourceCatalogRegistry(mapper, Clock.systemUTC()),
                    new PracticeSubjectEvaluator(mapper),
                    Clock.systemUTC());
            var builder = new WorkspaceContextBuilder(List.of(provider), new SimpleMeterRegistry(), manifests);
            EvidencePlan plan = new EvidencePlan(new SourceContractVersion("1.1.0"), ArtifactKinds.PULL_REQUEST);

            var capture =
                    java.util.Objects.requireNonNull(
                                    builder.prepare(reviewRequest(), plan).manifest())
                            .sources()
                            .stream()
                            .filter(source -> source.kind().equals(diff))
                            .findFirst()
                            .orElseThrow();

            assertThat(capture.kind()).isEqualTo(diff);
            assertThat(capture.state())
                    .isInstanceOfSatisfying(
                            SourceCaptureState.Available.class,
                            available -> assertThat(available.content()).isEqualTo(SourceContentState.EMPTY));
            assertThat(capture.artifacts()).extracting("path").containsExactly("inputs/context/diff.patch");
        }

        @Test
        void rejectsCaptureFactsForAnotherSource() {
            SourceKind comments = new SourceKind("scm.pull-request.comments");
            SourceKind core = new SourceKind("scm.pull-request.core");
            EvidenceSource provider = new EvidenceSource() {
                @Override
                public Set<SourceKind> sourceKinds() {
                    return Set.of(comments);
                }

                @Override
                public SourceKind sourceKindFor(String path) {
                    return comments;
                }

                @Override
                public EvidenceContribution capture(ContextRequest request, Set<SourceKind> selectedKinds) {
                    return new EvidenceContribution(Map.of(), Map.of(core, SourceCompleteness.COMPLETE));
                }

                @Override
                public boolean supports(ContextRequest request) {
                    return true;
                }

                @Override
                public void contribute(ContextRequest request, Map<String, byte[]> files) {}
            };
            ContextManifestBuilder manifests = mock(ContextManifestBuilder.class);
            when(manifests.isSourceUsePermitted(any(), any())).thenReturn(true);
            when(manifests.stagedSources(any())).thenReturn(Set.of(comments, core));
            var builder = new WorkspaceContextBuilder(List.of(provider), new SimpleMeterRegistry(), manifests);
            EvidencePlan plan = new EvidencePlan(new SourceContractVersion("1.1.0"), ArtifactKinds.PULL_REQUEST);

            assertThatThrownBy(() -> builder.prepare(reviewRequest(), plan))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("undeclared or unselected sources");
        }

        @Test
        void rejectsFilesEmittedForAnotherIndependentCapture() {
            SourceKind comments = new SourceKind("scm.pull-request.comments");
            SourceKind core = new SourceKind("scm.pull-request.core");
            EvidenceSource provider = new EvidenceSource() {
                @Override
                public Set<SourceKind> sourceKinds() {
                    return Set.of(comments, core);
                }

                @Override
                public SourceKind sourceKindFor(String path) {
                    return path.endsWith("core.json") ? core : comments;
                }

                @Override
                public EvidenceContribution capture(ContextRequest request, Set<SourceKind> selectedKinds) {
                    return new EvidenceContribution(
                            Map.of("inputs/context/core.json", new byte[] {1}),
                            Map.of(),
                            Map.of(),
                            Map.of(),
                            Map.of(),
                            Map.of());
                }

                @Override
                public boolean supports(ContextRequest request) {
                    return true;
                }

                @Override
                public void contribute(ContextRequest request, Map<String, byte[]> files) {}
            };
            ContextManifestBuilder manifests = mock(ContextManifestBuilder.class);
            when(manifests.isSourceUsePermitted(any(), any())).thenReturn(true);
            when(manifests.stagedSources(any())).thenReturn(Set.of(comments, core));
            var builder = new WorkspaceContextBuilder(List.of(provider), new SimpleMeterRegistry(), manifests);
            EvidencePlan plan = new EvidencePlan(new SourceContractVersion("1.1.0"), ArtifactKinds.PULL_REQUEST);

            assertThatThrownBy(() -> builder.prepare(reviewRequest(), plan))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("outside this capture");
        }

        @Test
        @DisplayName("re-raises JobPreparationException without re-wrapping")
        void jpePassThrough() {
            var bad = new ContentSource() {
                @Override
                public boolean supports(ContextRequest request) {
                    return true;
                }

                @Override
                public void contribute(ContextRequest request, Map<String, byte[]> files) {
                    throw new JobPreparationException("data error");
                }
            };
            assertThatThrownBy(() -> builderOf(bad).build(reviewRequest()))
                    .isInstanceOf(JobPreparationException.class)
                    .hasMessage("data error");
        }
    }

    @Nested
    class OutputKeyDedup {

        @Test
        @DisplayName("two providers writing the same path is a wiring bug")
        void detectsConflictingKey() {
            ContentSource first = new ProviderA();
            ContentSource second = new ProviderB();
            assertThatThrownBy(() -> builderOf(first, second).build(reviewRequest()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Duplicate workspace key");
        }

        @Test
        void isolatesPreviouslyCollectedBytesFromLaterProviders() {
            byte[] shared = "FIRST".getBytes(StandardCharsets.UTF_8);
            ContentSource first = stubProvider(true, "first.txt", shared, false);
            ContentSource mutating = new ContentSource() {
                @Override
                public boolean supports(ContextRequest request) {
                    return true;
                }

                @Override
                public void contribute(ContextRequest request, Map<String, byte[]> files) {
                    shared[0] = 'X';
                    files.put(OUTPUT_PREFIX + "second.txt", new byte[0]);
                }
            };

            Map<String, byte[]> files = builderOf(first, mutating).build(reviewRequest());

            assertThat(files.get("inputs/context/first.txt"))
                    .asString(StandardCharsets.UTF_8)
                    .isEqualTo("FIRST");
        }

        private final class ProviderA implements ContentSource {

            @Override
            public boolean supports(ContextRequest request) {
                return true;
            }

            @Override
            public void contribute(ContextRequest request, Map<String, byte[]> files) {
                files.put(OUTPUT_PREFIX + "shared.txt", "FIRST".getBytes(StandardCharsets.UTF_8));
            }
        }

        private final class ProviderB implements ContentSource {

            @Override
            public boolean supports(ContextRequest request) {
                return true;
            }

            @Override
            public void contribute(ContextRequest request, Map<String, byte[]> files) {
                files.put(OUTPUT_PREFIX + "shared.txt", "SECOND".getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    @Nested
    class PrefixEnforcement {

        @Test
        @DisplayName("rejects providers that write outside inputs/context/")
        void rejectsBadPrefix() {
            var wrong = new ContentSource() {
                @Override
                public boolean supports(ContextRequest request) {
                    return true;
                }

                @Override
                public void contribute(ContextRequest request, Map<String, byte[]> files) {
                    files.put("rogue/file.txt", new byte[0]);
                }
            };
            assertThatThrownBy(() -> builderOf(wrong).build(reviewRequest()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("rogue/file.txt");
        }
    }

    @Nested
    class ProviderOrdering {

        @Test
        @DisplayName("Ordered providers run in ascending precedence (lower order first)")
        void respectsOrderedInterface() {
            var first = new OrderedStubProvider(1, "first.txt");
            var second = new OrderedStubProvider(2, "second.txt");
            Map<String, byte[]> files = builderOf(second, first).build(reviewRequest());
            var iter = files.keySet().iterator();
            assertThat(iter.next()).isEqualTo("inputs/context/first.txt");
            assertThat(iter.next()).isEqualTo("inputs/context/second.txt");
        }
    }

    @Nested
    class ConcurrentPreparation {

        @Test
        void shouldPrepareIndependentAttemptsForTheSameRepositoryConcurrently() throws Exception {
            CountDownLatch bothInside = new CountDownLatch(2);
            @Nullable CountDownLatch mayFinish = new CountDownLatch(1);
            AtomicInteger inFlight = new AtomicInteger();
            AtomicInteger maxInFlight = new AtomicInteger();
            ContentSource concurrentProbe = new ConcurrentProbeProvider(bothInside, mayFinish, inFlight, maxInFlight);
            var builder = new WorkspaceContextBuilder(List.of(concurrentProbe), new SimpleMeterRegistry(), null);

            AgentJob jobA = new AgentJob();
            jobA.setId(UUID.randomUUID());
            jobA.setMetadata(new ObjectMapper().createObjectNode().put("repository_id", 7L));
            AgentJob jobB = new AgentJob();
            jobB.setId(UUID.randomUUID());
            jobB.setMetadata(new ObjectMapper().createObjectNode().put("repository_id", 7L));

            Thread t1 = new Thread(() -> builder.build(new ContextRequest.IssueReviewRequest(jobA)), "t1-null");
            Thread t2 = new Thread(() -> builder.build(new ContextRequest.IssueReviewRequest(jobB)), "t2-null");
            t1.start();
            t2.start();
            try {
                assertThat(bothInside.await(2, TimeUnit.SECONDS))
                        .as("independent attempt captures must not serialize behind a JVM repository lock")
                        .isTrue();
            } finally {
                mayFinish.countDown();
            }
            t1.join(2_000);
            t2.join(2_000);
            assertThat(t1.isAlive()).isFalse();
            assertThat(t2.isAlive()).isFalse();
            assertThat(maxInFlight.get()).isEqualTo(2);
        }
    }

    private static final class ConcurrentProbeProvider implements ContentSource {

        private final CountDownLatch bothInside;
        private final @Nullable CountDownLatch mayFinish;
        private final AtomicInteger inFlight;
        private final AtomicInteger maxInFlight;

        ConcurrentProbeProvider(
                CountDownLatch bothInside,
                @Nullable CountDownLatch mayFinish,
                AtomicInteger inFlight,
                AtomicInteger maxInFlight) {
            this.bothInside = bothInside;
            this.mayFinish = mayFinish;
            this.inFlight = inFlight;
            this.maxInFlight = maxInFlight;
        }

        @Override
        public boolean supports(ContextRequest request) {
            return true;
        }

        @Override
        public void contribute(ContextRequest request, Map<String, byte[]> files) {
            int active = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(active, Math::max);
            bothInside.countDown();
            try {
                if (mayFinish != null) {
                    mayFinish.await();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                inFlight.decrementAndGet();
            }
            files.put(OUTPUT_PREFIX + "marker-" + System.nanoTime() + ".txt", new byte[0]);
        }
    }

    private static final class OrderedStubProvider implements ContentSource, Ordered {

        private final int order;
        private final String pathSuffix;

        OrderedStubProvider(int order, String pathSuffix) {
            this.order = order;
            this.pathSuffix = pathSuffix;
        }

        @Override
        public int getOrder() {
            return order;
        }

        @Override
        public boolean supports(ContextRequest request) {
            return true;
        }

        @Override
        public void contribute(ContextRequest request, Map<String, byte[]> files) {
            files.put(OUTPUT_PREFIX + pathSuffix, new byte[0]);
        }
    }
}
