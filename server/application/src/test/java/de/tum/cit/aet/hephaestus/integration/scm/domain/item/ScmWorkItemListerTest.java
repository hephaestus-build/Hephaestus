package de.tum.cit.aet.hephaestus.integration.scm.domain.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.api.ItemIndexService;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemListRequest;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRegistration;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemVersion;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.ItemFixtures;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.data.projection.SpelAwareProxyProjectionFactory;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

class ScmWorkItemListerTest extends BaseUnitTest {
    @Test
    void shouldRetainMembershipAndVersionsEstablishedByTheFirstPage() {
        var sources = mock(ScmItemService.class);
        var writer = mock(ItemIndexService.class);
        var queries = mock(ScmItemQueryRepository.class);
        var root = new ItemRef(1, 2, ScmItemEvents.REPOSITORY, 10L, null, null);
        var commit = new ItemVersion("a".repeat(40), null);
        var revision = new AtomicReference<>("before");
        when(sources.pin(any(), nullable(ItemVersion.class))).thenAnswer(call -> {
            ItemRef item = call.getArgument(0);
            var version = item.kind().equals(ScmItemEvents.REPOSITORY)
                    ? commit
                    : new ItemVersion(revision.get() + ":" + item.sourceId(), null);
            return ItemFixtures.snapshot(item, Map.of())
                    .version(version)
                    .integration(new IntegrationRef(IntegrationKind.GITHUB, 1, null))
                    .label(ItemFixtures.label()
                            .itemId(item.itemId())
                            .title("Work")
                            .noun("Issue")
                            .number(1)
                            .container("owner/repo")
                            .integration(IntegrationKind.GITHUB)
                            .build())
                    .build();
        });
        when(writer.upsert(any())).thenAnswer(call -> {
            ItemRegistration registration = call.getArgument(0);
            long sourceId = Objects.requireNonNull(registration.sourceId());
            return new ItemRef(
                    registration.workspaceId(),
                    sourceId + 100,
                    registration.kind(),
                    registration.sourceId(),
                    registration.parentId(),
                    null);
        });
        var projections = new SpelAwareProxyProjectionFactory();
        var rows = List.of(11L, 12L).stream()
                .map(id -> projections.createProjection(
                        ScmItemQueryRepository.WorkRow.class, Map.of("sourceId", id, "repositoryId", 10L)))
                .toList();
        when(queries.listWork(10, "ISSUE")).thenReturn(rows);
        when(queries.owners(List.of(11L, 12L), false)).thenReturn(rows);
        var lister = new ScmWorkItemLister(
                sources,
                new ScmWorkItemIndex(writer, queries),
                new ObjectMapper(),
                queries,
                mock(PlatformTransactionManager.class));
        var first = lister.list(new ItemListRequest(root, commit, ScmItemEvents.ISSUE, null, 1));
        assertThat(first.complete()).isFalse();
        revision.set("after");
        var second = lister.list(new ItemListRequest(root, commit, ScmItemEvents.ISSUE, first.nextCursor(), 1));
        assertThat(second.complete()).isTrue();
        assertThat(second.items()).singleElement().satisfies(item -> {
            assertThat(item.item().sourceId()).isEqualTo(12L);
            assertThat(item.version().value()).isEqualTo("before:12");
        });
        var foreign = new ItemRef(3, root.itemId(), root.kind(), root.sourceId(), null, null);
        assertThatThrownBy(() ->
                        lister.list(new ItemListRequest(foreign, commit, ScmItemEvents.ISSUE, first.nextCursor(), 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("another pinned listing");
    }
}
