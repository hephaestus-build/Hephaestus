package de.tum.cit.aet.hephaestus.integration.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("unit")
class ProviderStateVersionTest {
    @ParameterizedTest
    @CsvSource({
        "2026-01-01T00:00:00Z,2026-01-02T00:00:00Z,true,true",
        "2026-01-02T00:00:00Z,2026-01-02T00:00:00Z,false,true",
        "2026-01-03T00:00:00Z,2026-01-02T00:00:00Z,false,false",
        ",2026-01-02T00:00:00Z,true,true",
        "2026-01-02T00:00:00Z,,false,false",
        ",,false,false"
    })
    void shouldOrderProviderVersionsWithoutUsingArrivalTime(
            @Nullable String incoming, @Nullable String stored, boolean older, boolean cannotReplace) {
        Instant incomingVersion = incoming == null ? null : Instant.parse(incoming);
        Instant storedVersion = stored == null ? null : Instant.parse(stored);
        assertThat(ProviderStateVersion.isOlder(incomingVersion, storedVersion)).isEqualTo(older);
        assertThat(ProviderStateVersion.cannotReplace(incomingVersion, storedVersion))
                .isEqualTo(cannotReplace);
    }

    @ParameterizedTest
    @CsvSource({"-1,false", "0,false", "1,true"})
    void shouldAcceptATiedReadOnlyWhenItsRequestBeganAfterTheStoredWrite(long nanosAfterWrite, boolean accepted) {
        Instant version = Instant.parse("2026-01-01T00:00:00Z");
        Instant writtenAt = version.plusSeconds(60);
        assertThat(ProviderStateVersion.acceptsEqualRead(
                        version, version, writtenAt.plusNanos(nanosAfterWrite), writtenAt))
                .isEqualTo(accepted);
        assertThat(ProviderStateVersion.acceptsEqualRead(version, version, writtenAt.plusNanos(nanosAfterWrite), null))
                .isFalse();
    }

    @ParameterizedTest
    @CsvSource({
        // incoming offset from stored (s), sync read, read begun after the write, refused
        "-1,true,true,true",
        "0,false,true,true",
        "0,true,false,true",
        "0,true,true,false",
        "1,false,false,false"
    })
    void shouldRefuseANotNewerSnapshotUnlessItIsASyncReadBegunAfterTheWrite(
            long secondsAfterStored, boolean syncRead, boolean readAfterWrite, boolean refused) {
        Instant stored = Instant.parse("2026-01-01T00:00:00Z");
        Instant writtenAt = stored.plusSeconds(60);
        Instant observedAt = readAfterWrite ? writtenAt.plusNanos(1) : writtenAt.minusNanos(1);
        assertThat(ProviderStateVersion.refuses(
                        stored.plusSeconds(secondsAfterStored), stored, syncRead, observedAt, writtenAt))
                .isEqualTo(refused);
    }
}
