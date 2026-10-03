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
    void capturesOnlyExactKeys() {
        recorder.recordIdentity(second);
        try (var capture = recorder.begin()) {
            recorder.recordIdentity(second);
            recorder.recordIdentity(first);
            recorder.recordIdentity(first);
            assertThat(capture.identities()).containsExactly(first, second);
            assertThatThrownBy(() -> capture.identities().clear()).isInstanceOf(UnsupportedOperationException.class);
        }
        verifyNoInteractions(jdbc);
    }

    @Test
    void nestedCaptureReturnsItsOwnKeysAndMergesIntoItsParent() {
        try (var outer = recorder.begin()) {
            recorder.recordIdentity(first);
            try (var inner = recorder.begin()) {
                recorder.recordIdentity(second);
                assertThat(inner.identities()).containsExactly(second);
            }
            assertThat(outer.identities()).containsExactly(first, second);
        }
    }

    @Test
    void failureRestoresTheParentAndDoesNotLeakIntoTheNextCapture() {
        try (var outer = recorder.begin()) {
            recorder.recordIdentity(first);
            assertThatThrownBy(() -> {
                        try (var inner = recorder.begin()) {
                            recorder.recordIdentity(second);
                            assertThat(inner.identities()).containsExactly(second);
                            throw new IllegalStateException("producer failed");
                        }
                    })
                    .isInstanceOf(IllegalStateException.class);
            recorder.recordIdentity(first);
            assertThat(outer.identities()).containsExactly(first, second);
        }
        try (var next = recorder.begin()) {
            assertThat(next.identities()).isEmpty();
        }
    }

    @Test
    void independentThreadsCannotAttributeEachOthersContent() {
        try (var outer = recorder.begin()) {
            recorder.recordIdentity(first);
            var other = CompletableFuture.supplyAsync(() -> {
                        try (var inner = recorder.begin()) {
                            recorder.recordIdentity(second);
                            return inner.identities();
                        }
                    })
                    .join();
            assertThat(outer.identities()).containsExactly(first);
            assertThat(other).containsExactly(second);
        }
    }

    @Test
    void nestedProducersUpdateTheOuterReceiptBeforeWritingContent() {
        var observations = new java.util.ArrayList<List<PersonCopyIdentity>>();
        try (var outer = recorder.begin()) {
            outer.onChange(() -> observations.add(outer.identities()));
            try (var inner = recorder.begin()) {
                recorder.recordIdentity(second);
                assertThat(observations).containsExactly(List.of(second));
                assertThat(inner.identities()).containsExactly(second);
                recorder.recordRepository(7);
                assertThat(outer.repositoryIds()).containsExactly(7L);
            }
        }
        try (var next = recorder.begin()) {
            assertThat(next.identities()).isEmpty();
        }
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
    }
}
