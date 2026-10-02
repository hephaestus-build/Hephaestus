package de.tum.cit.aet.hephaestus.core.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonCopyIdentity;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

@Tag("unit")
class ExactPersonDataCopyRecorderTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ExactPersonDataCopyRecorder recorder = new ExactPersonDataCopyRecorder(jdbc);
    private final PersonCopyIdentity first = new PersonCopyIdentity("GITLAB", "https://git.example", "42", null);
    private final PersonCopyIdentity second = new PersonCopyIdentity("SLACK", "https://slack.com", "U42", "T1");

    @Test
    void shouldUseTheSameExactOriginForEquivalentUrlSpellings() {
        assertThat(new PersonCopyIdentity("GITLAB", "HTTPS://GIT.EXAMPLE:443/", "42", null))
                .isEqualTo(first);
        assertThatThrownBy(() -> new PersonCopyIdentity("GITLAB", "not-an-origin", "42", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void capturesOnlyExactKeysAndKeepsTheCopiedValue() {
        recorder.recordIdentity(second);
        var captured = recorder.capture(() -> {
            recorder.recordIdentity(second);
            recorder.recordIdentity(first);
            recorder.recordIdentity(first);
            return "copied bytes";
        });
        assertThat(captured.value()).isEqualTo("copied bytes");
        assertThat(captured.identities()).containsExactly(first, second);
        assertThatThrownBy(() -> captured.identities().clear()).isInstanceOf(UnsupportedOperationException.class);
        verifyNoInteractions(jdbc);
    }

    @Test
    void nestedCaptureReturnsItsOwnKeysAndMergesIntoItsParent() {
        var outer = recorder.capture(() -> {
            recorder.recordIdentity(first);
            var inner = recorder.capture(() -> {
                recorder.recordIdentity(second);
                return 7;
            });
            assertThat(inner.identities()).containsExactly(second);
            return inner.value();
        });
        assertThat(outer.identities()).containsExactly(first, second);
        assertThat(outer.value()).isEqualTo(7);
    }

    @Test
    void failureRestoresTheParentAndDoesNotLeakIntoTheNextCapture() {
        var outer = recorder.capture(() -> {
            recorder.recordIdentity(first);
            assertThatThrownBy(() -> recorder.capture(() -> {
                        recorder.recordIdentity(second);
                        throw new IllegalStateException("producer failed");
                    }))
                    .isInstanceOf(IllegalStateException.class);
            return "parent";
        });
        assertThat(outer.identities()).containsExactly(first, second);
        assertThat(recorder.capture(() -> "next").identities()).isEmpty();
    }

    @Test
    void independentThreadsCannotAttributeEachOthersContent() {
        var captured = recorder.capture(() -> {
            recorder.recordIdentity(first);
            return CompletableFuture.supplyAsync(() -> recorder.capture(() -> {
                        recorder.recordIdentity(second);
                        return "other thread";
                    }))
                    .join();
        });
        assertThat(captured.identities()).containsExactly(first);
        assertThat(captured.value().identities()).containsExactly(second);
    }

    @Test
    void nestedProducersUpdateTheOuterReceiptBeforeWritingContent() {
        var observations = new java.util.ArrayList<List<PersonCopyIdentity>>();
        try (var outer = recorder.begin()) {
            outer.onChange(() -> observations.add(outer.identities()));
            recorder.capture(() -> {
                recorder.recordIdentity(second);
                assertThat(observations).containsExactly(List.of(second));
                recorder.recordRepository(7);
                assertThat(outer.repositoryIds()).containsExactly(7L);
                return "bytes written only after the receipt";
            });
        }
        assertThat(recorder.capture(() -> "next").identities()).isEmpty();
    }

    @Test
    void inactiveUserRecordingDoesNotReadProfiles() {
        recorder.recordUser(42);
        verifyNoInteractions(jdbc);
    }

    @Test
    void rejectsDisplayAttributionAndIncompleteSlackIdentity() {
        assertThatThrownBy(() -> new PersonCopyIdentity("EMAIL", "https://git.example", "42", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PersonCopyIdentity("SLACK", "https://slack.com", "U42", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PersonCopyIdentity("GITLAB", "https://git.example", "42", "T1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PersonCopyIdentity("GITLAB", "https://git.example", " ", null))
                .isInstanceOf(IllegalArgumentException.class);
        var source = new java.util.ArrayList<>(List.of(first));
        var captured =
                new de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyRecorder.Captured<>("bytes", source);
        source.clear();
        assertThat(captured.identities()).containsExactly(first);
    }
}
