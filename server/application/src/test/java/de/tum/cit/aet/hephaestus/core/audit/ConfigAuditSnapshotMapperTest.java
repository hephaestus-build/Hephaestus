package de.tum.cit.aet.hephaestus.core.audit;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditSnapshot;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The stored snapshot text is an append-only record that readers compare across releases, so its shape must not
 * follow a JSON library's defaults. Each component of {@link Sample} pins one default that differs between Jackson
 * versions.
 */
@Tag("unit")
class ConfigAuditSnapshotMapperTest {

    enum Mode {
        ENFORCED;

        @Override
        public String toString() {
            return "label";
        }
    }

    record Nested(int zeta, boolean alpha) {}

    record Sample(
            String name,
            @Nullable String cleared,
            Instant changedAt,
            BigDecimal budget,
            Duration window,
            Mode mode,
            List<Integer> sizes,
            Nested nested)
            implements ConfigAuditSnapshot {}

    @Test
    void shouldKeepDeclarationOrderNullsIsoInstantsAndEnumNamesWhenSerializingASnapshot() {
        var sample = new Sample(
                "policy",
                null,
                Instant.parse("2026-11-13T12:00:00.5Z"),
                new BigDecimal("1.50"),
                Duration.ofMinutes(90),
                Mode.ENFORCED,
                List.of(3, 1),
                new Nested(2, true));

        assertThat(ConfigAuditSnapshotMapper.toJson(ConfigAuditSnapshotMapper.toNode(sample)))
                .isEqualTo("""
                        {"name":"policy","cleared":null,"changedAt":"2026-11-13T12:00:00.500Z","budget":1.5,\
                        "window":5.4E+3,"mode":"ENFORCED","sizes":[3,1],"nested":{"zeta":2,"alpha":true}}""");
    }

    @Test
    void shouldTreatTrailingDecimalZerosAsNoChangeWhenDiffingSnapshots() {
        var before = ConfigAuditSnapshotMapper.toNode(new Budget(new BigDecimal("1.50")));
        var after = ConfigAuditSnapshotMapper.toNode(new Budget(new BigDecimal("1.5")));

        assertThat(ConfigAuditDiff.changedKeys(before, after)).isEmpty();
    }

    record Budget(BigDecimal amount) implements ConfigAuditSnapshot {}
}
