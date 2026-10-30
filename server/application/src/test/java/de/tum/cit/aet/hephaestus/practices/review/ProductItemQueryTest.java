package de.tum.cit.aet.hephaestus.practices.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureCode;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureException;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemSource;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemVersion;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class ProductItemQueryTest extends BaseUnitTest {
    final ProductItemRepository rows = mock(ProductItemRepository.class);
    final ItemSource source = mock(ItemSource.class);
    final ProductItemQuery query = new ProductItemQuery(rows, List.of(source), List.of(), List.of());

    @ParameterizedTest
    @ValueSource(strings = {"scm.issue", "scm.pull_request"})
    void shouldReadTheStoredVersionOfIssueAndPullRequestWork(String kind) {
        var item = stored(kind, null);
        when(source.currentVersion(item)).thenReturn(Optional.of(new ItemVersion("opaque-stored-version", null)));

        assertThat(query.currentWorkVersion(1, 2))
                .isEqualTo(new ProductItemQuery.CurrentWorkVersion.Known("opaque-stored-version"));
        verify(source, never()).current(any());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"scm.repository", "scm.file", "scm.pull_request_review", "slack.thread", "outline.document"})
    void shouldNotReadTheSourceWhenTheKindHasNoComparableWorkVersion(String kind) {
        stored(kind, null);

        assertThat(query.currentWorkVersion(1, 2)).isEqualTo(ProductItemQuery.CurrentWorkVersion.Unknown.INSTANCE);
        verifyNoInteractions(source);
    }

    @Test
    void shouldNotReadTheSourceWhenTheItemWasRemoved() {
        stored("scm.pull_request", Instant.parse("2026-10-20T10:00:00Z"));

        assertThat(query.currentWorkVersion(1, 2)).isEqualTo(ProductItemQuery.CurrentWorkVersion.Removed.INSTANCE);
        verifyNoInteractions(source);
    }

    @Test
    void shouldBeUnknownWhenTheSourceHasNoCurrentWork() {
        var item = stored("scm.issue", null);
        when(source.currentVersion(item)).thenReturn(Optional.empty());

        assertThat(query.currentWorkVersion(1, 2)).isEqualTo(ProductItemQuery.CurrentWorkVersion.Unknown.INSTANCE);
    }

    @ParameterizedTest
    @EnumSource(
            value = ItemCaptureCode.class,
            names = {"SOURCE_NOT_READY", "VERSION_UNAVAILABLE", "ITEM_NOT_VISIBLE", "ITEM_REMOVED", "CONSENT_WITHHELD"})
    void shouldBeUnknownWhenTheSourceCannotShowItsWork(ItemCaptureCode code) {
        var item = stored("scm.pull_request", null);
        when(source.currentVersion(item)).thenThrow(new ItemCaptureException(code, "Unavailable"));

        assertThat(query.currentWorkVersion(1, 2)).isEqualTo(ProductItemQuery.CurrentWorkVersion.Unknown.INSTANCE);
    }

    @Test
    void shouldPropagateACaptureFailure() {
        var item = stored("scm.pull_request", null);
        var failure = new ItemCaptureException(ItemCaptureCode.CAPTURE_FAILED, "Failed");
        when(source.currentVersion(item)).thenThrow(failure);

        assertThatThrownBy(() -> query.currentWorkVersion(1, 2)).isSameAs(failure);
    }

    @Test
    void shouldRefuseAnItemOutsideTheWorkspaceWithoutReadingTheSource() {
        when(rows.items(1, List.of(2L))).thenReturn(List.of());

        assertThatThrownBy(() -> query.currentWorkVersion(1, 2)).isInstanceOf(NoSuchElementException.class);
        verifyNoInteractions(source);
    }

    /** Item 2 of workspace 1, stored with {@code kind}; the source supports it when it is read. */
    private ItemRef stored(String kind, @Nullable Instant removedAt) {
        var row = new ProductItemRepository.ItemRow() {
            @Override
            public long getId() {
                return 2;
            }

            @Override
            public String getKind() {
                return kind;
            }

            @Override
            public @Nullable Long getSourceId() {
                return 3L;
            }

            @Override
            public @Nullable Long getParentId() {
                return null;
            }

            @Override
            public @Nullable String getPath() {
                return null;
            }

            @Override
            public @Nullable Instant getRemovedAt() {
                return removedAt;
            }
        };
        when(rows.items(1, List.of(2L))).thenReturn(List.of(row));
        lenient().when(source.supports(any())).thenReturn(true);
        return new ItemRef(1, 2, new ItemKind(kind), 3L, null, null);
    }
}
