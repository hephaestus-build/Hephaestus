package de.tum.cit.aet.hephaestus.integration.scm.domain.capture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.evidence.SourceAbsenceReason;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureCode;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureException;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryManager;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.io.IOException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ScmCapturePreparationTest extends BaseUnitTest {
    private final ReviewRepositoryPreparer preparer = mock(ReviewRepositoryPreparer.class);
    private final ScmCaptureContext context = ScmCaptureFixtures.context(new JsonMapper().createObjectNode(), 1, false);

    @Test
    void shouldReportAnUnavailablePinnedRangeWithoutUsingAnotherVersion() {
        when(preparer.prepare(context))
                .thenThrow(new ItemCaptureException(ItemCaptureCode.VERSION_UNAVAILABLE, "range"));

        assertThat(context.preparation().prepare(preparer, context)).isNull();
        assertThat(context.preparation().unavailableReason()).isEqualTo(SourceAbsenceReason.REVIEW_VERSION_UNAVAILABLE);
        assertThat(context.preparation().prepared()).isNull();
    }

    @Test
    void shouldKeepWorkspaceAuthorizationFailuresFatal() {
        var denied = new ItemCaptureException(ItemCaptureCode.ITEM_NOT_VISIBLE, "workspace");
        when(preparer.prepare(context)).thenThrow(denied);

        assertThatThrownBy(() -> context.preparation().prepare(preparer, context))
                .isSameAs(denied);
        assertThat(context.preparation().unavailableReason()).isNull();
    }

    @Test
    void shouldRefuseAReadOfTheCloneThatNoFetchPreceded() {
        var preparation = new ScmCapturePreparation();

        assertThatThrownBy(() -> preparation.prepare(preparer, context)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(preparer);
    }

    @Test
    void shouldReadTheMirrorAsItIsWhenThePreparationHasNoFetch() {
        var preparation = ScmCapturePreparation.fromMirror();
        when(preparer.prepare(context))
                .thenThrow(new ItemCaptureException(ItemCaptureCode.VERSION_UNAVAILABLE, "range"));

        assertThat(preparation.prepare(preparer, context)).isNull();
        assertThat(preparation.unavailableReason()).isEqualTo(SourceAbsenceReason.REVIEW_VERSION_UNAVAILABLE);
        verify(preparer, never()).fetch(any());
    }

    @Test
    void shouldAnswerForAFailedFetchOnlyWhenASourceReadsTheClone() {
        var unreachable = new ScmCapturePreparation();
        unreachable.fetch(() -> {
            throw new GitRepositoryManager.RepositoryFetchException(new InvalidRemoteException("upstream"));
        });
        var uncredentialed = new ScmCapturePreparation();
        var missing = new ItemCaptureException(ItemCaptureCode.SOURCE_NOT_READY, "credentials");
        uncredentialed.fetch(() -> {
            throw missing;
        });

        assertThat(unreachable.prepare(preparer, context)).isNull();
        assertThat(unreachable.unavailableReason()).isEqualTo(SourceAbsenceReason.REPOSITORY_FETCH_FAILED);
        assertThatThrownBy(() -> uncredentialed.prepare(preparer, context)).isSameAs(missing);
        verifyNoInteractions(preparer);
    }

    @Test
    void shouldKeepLocalStorageFailuresDistinctFromUpstreamAvailability() {
        var failure = new GitRepositoryManager.GitOperationException("local storage", new IOException("disk full"));
        when(preparer.prepare(context)).thenThrow(failure);

        assertThatThrownBy(() -> context.preparation().prepare(preparer, context))
                .isSameAs(failure);
        assertThat(context.preparation().unavailableReason()).isNull();
    }
}
