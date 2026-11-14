package de.tum.cit.aet.hephaestus.practices.groupdetail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemRefDTO;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureCode;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureException;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.practices.profile.PracticeProfileReviewService;
import de.tum.cit.aet.hephaestus.practices.review.ProductItemQuery;
import de.tum.cit.aet.hephaestus.practices.trace.ItemTraceQueryService;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ItemControllerTest extends BaseUnitTest {
    private final ProductItemQuery items = mock(ProductItemQuery.class);
    private final ItemTraceQueryService trace = mock(ItemTraceQueryService.class);
    private final ItemController controller = new ItemController(
            mock(ProductItemDirectoryQuery.class), items, trace, mock(PracticeProfileReviewService.class));
    private final WorkspaceContext workspace = new WorkspaceContext(1L, "pilot", "Pilot", null, null, Set.of());
    private final ItemRef item = new ItemRef(1L, 2L, new ItemKind("scm.pull_request"), 3L, 4L, null);

    @ParameterizedTest
    @EnumSource(
            value = ItemCaptureCode.class,
            names = {"VERSION_UNAVAILABLE", "SOURCE_NOT_READY"})
    void shouldReturnAnItemWithUnknownFactsWhenItsVersionIsUnavailable(ItemCaptureCode code) {
        when(items.require(1L, 2L)).thenReturn(item);
        when(items.pin(item, null)).thenThrow(new ItemCaptureException(code, "Unknown"));
        var reference = new ItemRefDTO("2", "scm.pull_request", "4", null, "#7", "Pilot work", null, null, null, null);
        when(trace.requireItem(1L, 2L)).thenReturn(reference);

        var result = controller.facts(workspace, 2L);

        assertThat(result.item()).isEqualTo(reference);
        assertThat(result.facts()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(
            value = ItemCaptureCode.class,
            names = {"ITEM_REMOVED", "ITEM_NOT_VISIBLE", "CONSENT_WITHHELD"})
    void shouldFindNoFactsForWorkThatIsGoneOrUnreadable(ItemCaptureCode code) {
        when(items.require(1L, 2L)).thenReturn(item);
        when(items.pin(item, null)).thenThrow(new ItemCaptureException(code, "Unavailable"));

        assertThatThrownBy(() -> controller.facts(workspace, 2L)).isInstanceOf(EntityNotFoundException.class);
    }

    @ParameterizedTest
    @EnumSource(
            value = ItemCaptureCode.class,
            names = {"CAPTURE_FAILED", "CHECKOUT_UNVERIFIED", "OCCASION_EVIDENCE_UNAVAILABLE"})
    void shouldKeepOtherCaptureFailuresVisible(ItemCaptureCode code) {
        when(items.require(1L, 2L)).thenReturn(item);
        var failure = new ItemCaptureException(code, "Capture failed");
        when(items.pin(item, null)).thenThrow(failure);

        assertThatThrownBy(() -> controller.facts(workspace, 2L)).isSameAs(failure);
    }
}
