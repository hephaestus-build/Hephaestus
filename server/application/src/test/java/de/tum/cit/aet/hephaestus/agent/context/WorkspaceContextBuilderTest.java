package de.tum.cit.aet.hephaestus.agent.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.evidence.ClasspathSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.evidence.SourceAbsenceReason;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureState;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.evidence.capture.CaptureContribution;
import de.tum.cit.aet.hephaestus.evidence.capture.CaptureRequest;
import de.tum.cit.aet.hephaestus.evidence.capture.ContentSource;
import de.tum.cit.aet.hephaestus.evidence.capture.MentorContextRequest;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemSnapshot;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemSource;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class WorkspaceContextBuilderTest extends BaseUnitTest {
    private final MentorContextRequest request = new MentorContextRequest(1, 2, UUID.randomUUID(), null);

    private WorkspaceContextBuilder builder(ContentSource... sources) {
        return new WorkspaceContextBuilder(
                List.of(sources),
                List.of(),
                mock(ClasspathSourceCatalogRegistry.class),
                mock(JobFolderIndexBuilder.class));
    }

    private ContentSource source(String path, byte[] bytes) {
        return new ContentSource() {
            @Override
            public boolean supports(MentorContextRequest request) {
                return true;
            }

            @Override
            public void contribute(MentorContextRequest request, Map<String, byte[]> files) {
                files.put(path, bytes);
            }
        };
    }

    @Test
    void stagesAnIndependentCopyOfEachSourceFile() {
        byte[] bytes = {1, 2};
        var captured = builder(source("inputs/context/one.json", bytes)).build(request);
        bytes[0] = 9;
        assertThat(captured.get("inputs/context/one.json")).containsExactly((byte) 1, (byte) 2);
    }

    @Test
    void rejectsCollidingSourcesAndPathsOutsideTheDeclaredRoot() {
        assertThatThrownBy(() -> builder(
                                source("inputs/context/one.json", new byte[0]),
                                source("inputs/context/one.json", new byte[0]))
                        .build(request))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> builder(source("outside.json", new byte[0])).build(request))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void preservesOtherContextWhenAnOptionalSourceFails() {
        ContentSource failed = new ContentSource() {
            @Override
            public boolean supports(MentorContextRequest request) {
                return true;
            }

            @Override
            public boolean required() {
                return false;
            }

            @Override
            public void contribute(MentorContextRequest request, Map<String, byte[]> files) {
                throw new IllegalStateException("unavailable");
            }
        };
        assertThat(builder(failed, source("inputs/context/available.json", new byte[0]))
                        .build(request))
                .containsOnlyKeys("inputs/context/available.json");
    }

    @Test
    void shouldReadOnlyAResourceOwnedByOneInstalledSource() {
        var reader = mock(ContentSource.class);
        var path = "inputs/context/merge_readiness/17.json";
        var detail = JsonMapper.builder().build().createObjectNode().put("status", "FOUND");
        when(reader.read(1, 2, path)).thenReturn(Optional.of(detail));
        var contexts = builder(reader, mock(ContentSource.class));
        assertThat(contexts.read(1, 2, path)).contains(detail);
        assertThat(contexts.read(1, 2, "inputs/context/unknown.json")).isEmpty();
    }

    @Test
    void shouldRejectAResourceClaimedByTwoSources() {
        var first = mock(ContentSource.class);
        var second = mock(ContentSource.class);
        var path = "inputs/context/merge_readiness/17.json";
        var detail = JsonMapper.builder().build().createObjectNode().put("status", "FOUND");
        when(first.read(1, 2, path)).thenReturn(Optional.of(detail));
        when(second.read(1, 2, path)).thenReturn(Optional.of(detail));
        assertThatThrownBy(() -> builder(first, second).read(1, 2, path)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldSkipUnsupportedMentorSources() {
        var unsupported = mock(ContentSource.class);
        assertThat(builder(unsupported).build(request)).isEmpty();
        verify(unsupported, never()).contribute(any(), any());
    }

    @Test
    void shouldPropagateARequiredMentorSourceFailure() {
        var failure = new IllegalStateException("required source unavailable");
        ContentSource required = new ContentSource() {
            @Override
            public boolean supports(MentorContextRequest request) {
                return true;
            }

            @Override
            public void contribute(MentorContextRequest request, Map<String, byte[]> files) {
                throw failure;
            }
        };
        assertThatThrownBy(() -> builder(required).build(request)).isSameAs(failure);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2})
    void shouldRejectItemsWithoutExactlyOneCaptureOwner(int owners) {
        var snapshot = mock(ItemSnapshot.class);
        var item = new ItemRef(1, 2, new ItemKind("docs.document"), 20L, null, null);
        when(snapshot.item()).thenReturn(item);
        var first = mock(ItemSource.class);
        var second = mock(ItemSource.class);
        when(first.supports(item)).thenReturn(owners == 2);
        when(second.supports(item)).thenReturn(owners == 2);
        var registry = new ClasspathSourceCatalogRegistry(new JsonMapper(), Clock.systemUTC());
        var contexts = new WorkspaceContextBuilder(
                List.of(),
                List.of(first, second),
                registry,
                new JobFolderIndexBuilder(registry, new JsonMapper(), Clock.systemUTC()));
        var capture = new CaptureRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                0,
                Set.of(new SourceKind("docs.document.core")),
                registry.current().version().value(),
                3);
        assertThatThrownBy(() -> contexts.prepare(snapshot, capture))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("The item must have exactly one installed capture source");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldCaptureOnlySourcesPermittedForAutomatedReview(boolean permitted) {
        var mapper = new JsonMapper();
        var registry = mock(ClasspathSourceCatalogRegistry.class);
        var catalog = new ClasspathSourceCatalogRegistry(mapper, Clock.systemUTC());
        var kind = new SourceKind("docs.document.core");
        when(registry.current()).thenReturn(catalog.current());
        when(registry.catalogDigest()).thenReturn(catalog.catalogDigest());
        when(registry.requireSource(kind)).thenReturn(catalog.requireSource(kind));
        when(registry.isSourceUsePermitted(kind, SourceUsePurpose.AUTOMATED_PRACTICE_REVIEW))
                .thenReturn(permitted);
        var snapshot = mock(ItemSnapshot.class);
        var item = new ItemRef(1, 2, new ItemKind("docs.document"), 20L, null, null);
        when(snapshot.item()).thenReturn(item);
        var owner = mock(ItemSource.class);
        when(owner.supports(item)).thenReturn(true);
        var capture = new CaptureRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                2,
                Set.of(kind),
                catalog.current().version().value(),
                3,
                Set.of("frozen/context.md"));
        if (permitted) when(owner.capture(snapshot, capture)).thenReturn(CaptureContribution.empty());
        var contexts = new WorkspaceContextBuilder(
                List.of(), List.of(owner), registry, new JobFolderIndexBuilder(registry, mapper, Clock.systemUTC()));
        try (var prepared = contexts.prepare(snapshot, capture)) {
            var state = Objects.requireNonNull(prepared.manifest())
                    .sources()
                    .getFirst()
                    .state();
            if (permitted) assertThat(state).isInstanceOf(SourceCaptureState.Available.class);
            else {
                assertThat(state)
                        .isEqualTo(new SourceCaptureState.Unavailable(SourceAbsenceReason.GOVERNANCE_NOT_EFFECTIVE));
                verify(owner, never()).capture(any(), any());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldRejectAContractThatDoesNotMatchTheItem(boolean wrongVersion) {
        var mapper = new JsonMapper();
        var registry = new ClasspathSourceCatalogRegistry(mapper, Clock.systemUTC());
        var snapshot = mock(ItemSnapshot.class);
        var item = new ItemRef(1, 2, new ItemKind("docs.document"), 20L, null, null);
        when(snapshot.item()).thenReturn(item);
        var owner = mock(ItemSource.class);
        when(owner.supports(item)).thenReturn(true);
        var capture = new CaptureRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                0,
                Set.of(new SourceKind(wrongVersion ? "docs.document.core" : "scm.pull-request.core")),
                wrongVersion ? "999.0.0" : registry.current().version().value(),
                3);
        var contexts = new WorkspaceContextBuilder(
                List.of(), List.of(owner), registry, new JobFolderIndexBuilder(registry, mapper, Clock.systemUTC()));
        assertThatThrownBy(() -> contexts.prepare(snapshot, capture))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The requested source contract does not match the item");
        verify(owner, never()).capture(any(), any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldReleaseCapturedInputsAndPreserveTheOriginalFailureWhenIndexingFails(boolean cleanupFails) {
        var registry = new ClasspathSourceCatalogRegistry(new JsonMapper(), Clock.systemUTC());
        var snapshot = mock(ItemSnapshot.class);
        var item = new ItemRef(1, 2, new ItemKind("docs.document"), 20L, null, null);
        when(snapshot.item()).thenReturn(item);
        var owner = mock(ItemSource.class);
        when(owner.supports(item)).thenReturn(true);
        var capture = new CaptureRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                0,
                Set.of(new SourceKind("docs.document.core")),
                registry.current().version().value(),
                3);
        var released = new AtomicBoolean();
        var cleanupFailure = new IllegalStateException("cleanup failed");
        var contribution = new CaptureContribution(
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                () -> {
                    released.set(true);
                    if (cleanupFails) throw cleanupFailure;
                },
                Map.of(),
                List.of(),
                List.of(),
                List.of());
        when(owner.capture(snapshot, capture)).thenReturn(contribution);
        var manifests = mock(JobFolderIndexBuilder.class);
        var failure = new IllegalArgumentException("index rejected");
        when(manifests.build(any(), any())).thenThrow(failure);
        var contexts = new WorkspaceContextBuilder(List.of(), List.of(owner), registry, manifests);
        assertThatThrownBy(() -> contexts.prepare(snapshot, capture)).isSameAs(failure);
        assertThat(released).isTrue();
        assertThat(failure.getSuppressed())
                .containsExactly(cleanupFails ? new Throwable[] {cleanupFailure} : new Throwable[0]);
    }

    @Test
    void shouldValidateTheEntirePlanBeforeCapturingAnySource() {
        var mapper = new JsonMapper();
        var registry = new ClasspathSourceCatalogRegistry(mapper, Clock.systemUTC());
        var snapshot = mock(ItemSnapshot.class);
        var item = new ItemRef(1, 2, new ItemKind("scm.pull_request"), 20L, null, null);
        when(snapshot.item()).thenReturn(item);
        var owner = mock(ItemSource.class);
        when(owner.supports(item)).thenReturn(true);
        var capture = new CaptureRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                0,
                Set.of(new SourceKind("scm.pull-request.core"), new SourceKind("zz.uninstalled.source")),
                "3.0.0",
                3);
        var contexts = new WorkspaceContextBuilder(
                List.of(), List.of(owner), registry, new JobFolderIndexBuilder(registry, mapper, Clock.systemUTC()));
        assertThatThrownBy(() -> contexts.prepare(snapshot, capture)).isInstanceOf(IllegalArgumentException.class);
        verify(owner, never()).capture(any(), any());
    }

    @Test
    void shouldRejectASelectedSourceOutsideThePermittedPlan() {
        var core = new SourceKind("scm.pull-request.core");
        var diff = new SourceKind("scm.pull-request.diff");
        assertThatThrownBy(() -> new CaptureRequest(
                        UUID.randomUUID(), UUID.randomUUID(), 0, Set.of(diff), "3.0.0", 3, null, Set.of(core)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Selected capture sources are outside the permitted plan");
    }
}
