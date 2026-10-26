package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.context.EvidenceCollectionException;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobPreparationException;
import de.tum.cit.aet.hephaestus.agent.handler.spi.ReviewSourceNotReadyException;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureCode;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.stream.Stream;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.api.errors.TransportException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.CannotCreateTransactionException;

@Tag("unit")
class PreparationFailureTest {
    static Stream<Arguments> failures() {
        return Stream.of(
                Arguments.of(new ReviewSourceNotReadyException("pending"), PreparationFailure.Kind.SOURCE_NOT_READY),
                Arguments.of(new IOException("file"), PreparationFailure.Kind.IO),
                Arguments.of(
                        new ItemCaptureException(ItemCaptureCode.ITEM_NOT_VISIBLE, "private"),
                        PreparationFailure.Kind.SOURCE_CAPTURE),
                Arguments.of(new UncheckedIOException(new IOException("file")), PreparationFailure.Kind.IO),
                Arguments.of(new DataIntegrityViolationException("constraint"), PreparationFailure.Kind.DATABASE),
                Arguments.of(new CannotCreateTransactionException("database"), PreparationFailure.Kind.DATABASE),
                Arguments.of(new JobPreparationException("metadata"), PreparationFailure.Kind.INPUT_PREPARATION),
                Arguments.of(new IllegalStateException("unknown"), PreparationFailure.Kind.UNKNOWN),
                Arguments.of(
                        new JGitInternalException("lock", new IOException("config.lock")),
                        PreparationFailure.Kind.REPOSITORY),
                Arguments.of(
                        new JobPreparationException("checkout", new TransportException("fetch")),
                        PreparationFailure.Kind.REPOSITORY),
                Arguments.of(
                        new JobPreparationException("folder", new UncheckedIOException(new IOException("file"))),
                        PreparationFailure.Kind.IO),
                Arguments.of(
                        new JobPreparationException(
                                "required",
                                new EvidenceCollectionException(
                                        "collection", new DataIntegrityViolationException("constraint"))),
                        PreparationFailure.Kind.DATABASE),
                Arguments.of(
                        new JobPreparationException("wrapper", new ReviewSourceNotReadyException("pending")),
                        PreparationFailure.Kind.SOURCE_NOT_READY),
                Arguments.of(
                        new JobPreparationException(
                                "wrapper", new RuntimeException("unclassified", new IOException("buried"))),
                        PreparationFailure.Kind.INPUT_PREPARATION),
                Arguments.of(
                        new RuntimeException("unclassified", new IOException("buried")),
                        PreparationFailure.Kind.UNKNOWN),
                Arguments.of(new EvidenceCollectionException("unknown", null), PreparationFailure.Kind.UNKNOWN));
    }

    @ParameterizedTest
    @MethodSource("failures")
    void shouldPreserveKnownTypesOnlyThroughPreparationWrappers(Throwable failure, PreparationFailure.Kind kind) {
        var diagnosis = PreparationFailure.of(PreparationFailure.Stage.INPUTS, failure);
        assertThat(diagnosis.stage()).isEqualTo(PreparationFailure.Stage.INPUTS);
        assertThat(diagnosis.kind()).isEqualTo(kind);
    }

    @Test
    void shouldStopWhenKnownWrappersHaveACycle() {
        var first = new JobPreparationException("first");
        var second = new JobPreparationException("second", first);
        first.initCause(second);
        assertThat(PreparationFailure.of(PreparationFailure.Stage.INPUTS, first).kind())
                .isEqualTo(PreparationFailure.Kind.INPUT_PREPARATION);
        assertThat(PreparationFailure.of(PreparationFailure.Stage.INPUTS, first).exceptionTypes())
                .containsExactly(JobPreparationException.class.getName(), JobPreparationException.class.getName());
    }

    @Test
    void shouldClassifyAnUnavailablePinnedRangeWithoutReadingTheMessage() {
        var failure = PreparationFailure.of(
                PreparationFailure.Stage.INPUTS,
                new ItemCaptureException(ItemCaptureCode.VERSION_UNAVAILABLE, "secret"));
        assertThat(failure.kind()).isEqualTo(PreparationFailure.Kind.SOURCE_CAPTURE);
        assertThat(failure.reason()).isEqualTo(JobPreparationException.Reason.REVIEW_RANGE_UNAVAILABLE);
        assertThat(failure.exceptionTypes()).containsExactly(ItemCaptureException.class.getName());
    }

    @Test
    void shouldIgnoreSuppressedCleanupCauses() {
        var failure = new JobPreparationException("primary");
        failure.addSuppressed(new IOException("cleanup"));
        assertThat(PreparationFailure.of(PreparationFailure.Stage.INPUTS, failure)
                        .kind())
                .isEqualTo(PreparationFailure.Kind.INPUT_PREPARATION);
    }

    @Test
    void shouldKeepTheSourceOwnedReasonAlongsideAFileOrDatabaseCause() {
        String privateText = "token=private path=/private/student/repository";
        var io = new JobPreparationException(
                JobPreparationException.Reason.WORKSPACE_RENDERING_FAILED,
                privateText,
                new UncheckedIOException(new IOException(privateText)));
        var database = new JobPreparationException(
                JobPreparationException.Reason.REQUIRED_SOURCE_COLLECTION_FAILED,
                privateText,
                new EvidenceCollectionException(privateText, new DataIntegrityViolationException(privateText)));

        var ioFailure = PreparationFailure.of(PreparationFailure.Stage.INPUTS, io);
        var databaseFailure = PreparationFailure.of(PreparationFailure.Stage.INPUTS, database);

        assertThat(ioFailure.kind()).isEqualTo(PreparationFailure.Kind.IO);
        assertThat(ioFailure.reason()).isEqualTo(JobPreparationException.Reason.WORKSPACE_RENDERING_FAILED);
        assertThat(databaseFailure.kind()).isEqualTo(PreparationFailure.Kind.DATABASE);
        assertThat(databaseFailure.reason())
                .isEqualTo(JobPreparationException.Reason.REQUIRED_SOURCE_COLLECTION_FAILED);
        assertThat(io.getCause()).isInstanceOf(UncheckedIOException.class);
        assertThat(database.getCause()).isInstanceOf(EvidenceCollectionException.class);
        assertThat(ioFailure.message()).doesNotContain(privateText);
        assertThat(databaseFailure.message()).doesNotContain(privateText);
        assertThat(ReviewJobExecutor.isRetryableExecutionFailure(io)).isFalse();
        assertThat(ReviewJobExecutor.isRetryableExecutionFailure(database)).isFalse();
    }

    @Test
    void shouldKeepAnExplicitPreparationReasonWhenTheCauseIsAnItemCaptureFailure() {
        var failure = new JobPreparationException(
                JobPreparationException.Reason.WORKSPACE_RENDERING_FAILED,
                "private",
                new ItemCaptureException(ItemCaptureCode.CAPTURE_FAILED, "private"));
        assertThat(PreparationFailure.of(PreparationFailure.Stage.INPUTS, failure)
                        .reason())
                .isEqualTo(JobPreparationException.Reason.WORKSPACE_RENDERING_FAILED);
    }

    @Test
    void shouldNotInferAReasonFromTextOrAnArbitraryCause() {
        var coded = new JobPreparationException(JobPreparationException.Reason.REVIEW_RANGE_UNAVAILABLE, "private");
        var failure = new RuntimeException("REVIEW_RANGE_UNAVAILABLE", coded);
        assertThat(PreparationFailure.of(PreparationFailure.Stage.INPUTS, failure)
                        .reason())
                .isEqualTo(JobPreparationException.Reason.UNCLASSIFIED);
        assertThat(PreparationFailure.of(
                                PreparationFailure.Stage.INPUTS,
                                new JobPreparationException("REVIEW_RANGE_UNAVAILABLE"))
                        .reason())
                .isEqualTo(JobPreparationException.Reason.UNCLASSIFIED);
    }
}
