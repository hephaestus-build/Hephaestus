package de.tum.cit.aet.hephaestus.agent.context;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.spi.CapturedCheckouts;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.PlatformTransactionManager;

/** The recorded files of an attempt: all of a running attempt's, or the capture fails. */
@Tag("unit")
class AttemptInputsTest {
    @TempDir
    Path root;

    private final ReviewAttemptInputRepository rows = mock(ReviewAttemptInputRepository.class);
    private final AttemptInputs inputs =
            new AttemptInputs(rows, mock(CapturedCheckouts.class), mock(PlatformTransactionManager.class));

    @Test
    void shouldFailTheCaptureWhenTheAttemptNoLongerRunsItsJob() throws Exception {
        Path file = Files.writeString(root.resolve("item.json"), "{}");
        when(rows.record(anyLong(), any(), anyInt(), anyString(), anyString(), anyString(), any()))
                .thenReturn(0);

        assertThatThrownBy(() -> inputs.record(UUID.randomUUID(), 1L, 0, Map.of("context/item.json", file)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no longer runs");
    }

    @Test
    void shouldRecordEveryFileOfARunningAttempt() throws Exception {
        Path file = Files.writeString(root.resolve("item.json"), "{}");
        when(rows.record(anyLong(), any(), anyInt(), anyString(), anyString(), anyString(), any()))
                .thenReturn(1);

        assertThatCode(() -> inputs.record(UUID.randomUUID(), 1L, 0, Map.of("context/item.json", file)))
                .doesNotThrowAnyException();
    }
}
