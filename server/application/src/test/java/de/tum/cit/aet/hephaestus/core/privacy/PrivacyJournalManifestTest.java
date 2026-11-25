package de.tum.cit.aet.hephaestus.core.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PrivacyJournalKind;
import de.tum.cit.aet.hephaestus.core.privacy.spi.ResearchSubjectReference;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

@Tag("unit")
class PrivacyJournalManifestTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @ParameterizedTest
    @ValueSource(strings = {"SETTINGS_CHANGED", "DISAGREEMENT_RECORDED", "MUTED", "RECORD_CLOSED"})
    void shouldRefuseChangesThatRecoveryMayLose(String kind) {
        assertThatThrownBy(() -> PrivacyJournalKind.valueOf(kind)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldWriteOneVersionFiveEntryWithTheVersionFourMessageReferences() {
        var time = Instant.parse("2026-11-24T12:00:00Z");
        var message = new PersonDataRecovery.MessageDeletion(37, UUID.randomUUID());
        var entryId = UUID.randomUUID();
        var row = new PrivacyJournalRow(entryId, PrivacyJournalKind.ENTRY_DELETED, mapper.valueToTree(message), time);
        var manifest = new PrivacyJournalManifest(
                PrivacyJournalManifest.VERSION, time.minusSeconds(1), List.of(row.manifestEntry()));

        var json = mapper.readTree(mapper.writeValueAsString(manifest));
        assertThat(json.path("version").asInt()).isEqualTo(5);
        assertThat(json.path("entries").size()).isEqualTo(1);
        var entry = json.path("entries").path(0);
        assertThat(entry.path("entryId").asString()).isEqualTo(entryId.toString());
        assertThat(entry.path("kind").asString()).isEqualTo("ENTRY_DELETED");
        assertThat(mapper.treeToValue(entry.path("refs"), PersonDataRecovery.MessageDeletion.class))
                .isEqualTo(message);
    }

    @Test
    void keepsAFactRecordedBeforeTheRecoveryPointInTheManifest() {
        var recordedAt = Instant.parse("2026-11-24T12:00:00Z");
        var entryId = UUID.randomUUID();
        var row = new PrivacyJournalRow(
                entryId, PrivacyJournalKind.ACCOUNT_ERASED, mapper.valueToTree(Map.of("accountId", 37L)), recordedAt);

        var manifest = new PrivacyJournalManifest(
                PrivacyJournalManifest.VERSION, recordedAt.plusSeconds(1), List.of(row.manifestEntry()));

        assertThat(mapper.readTree(mapper.writeValueAsString(manifest))
                        .path("entries")
                        .path(0)
                        .path("entryId")
                        .asString())
                .isEqualTo(entryId.toString());
        assertThat(manifest.entries())
                .singleElement()
                .satisfies(entry -> assertThat(entry.recordedAt()).isEqualTo(recordedAt));
    }

    @Test
    void shouldKeepAnImmutableReleaseAndItsLaterMappingErasureInTheRecoveryManifest() {
        var releasedAt = Instant.parse("2026-11-24T12:00:00Z");
        var closedAt = releasedAt.plusSeconds(2);
        var dataset = UUID.randomUUID();
        var links = List.of(new ResearchSubjectReference(37L, "released-row", 17L));
        var release = Map.<String, Object>of(
                "datasetId",
                dataset,
                "workspaceId",
                5L,
                "receiptDigest",
                "a".repeat(64),
                "releasedAt",
                releasedAt,
                "useUntil",
                closedAt.plusSeconds(1),
                "subjectLinks",
                links);
        var closure = Map.<String, Object>of("datasetId", dataset, "closure", "DESTROYED", "closedAt", closedAt);
        PrivacyJournalReferences.validate(PrivacyJournalKind.RESEARCH_DATASET_RELEASED, release);
        PrivacyJournalReferences.validate(PrivacyJournalKind.RESEARCH_MAPPING_ERASED, closure);
        var releaseFact = new PrivacyJournalRow(
                dataset, PrivacyJournalKind.RESEARCH_DATASET_RELEASED, mapper.valueToTree(release), releasedAt);
        var closureFact = new PrivacyJournalRow(
                UUID.randomUUID(), PrivacyJournalKind.RESEARCH_MAPPING_ERASED, mapper.valueToTree(closure), closedAt);

        var manifest = new PrivacyJournalManifest(
                PrivacyJournalManifest.VERSION,
                releasedAt.plusSeconds(1),
                List.of(releaseFact.manifestEntry(), closureFact.manifestEntry()));
        var entries = mapper.readTree(mapper.writeValueAsString(manifest)).path("entries");
        var subject = entries.path(0).path("refs").path("subjectLinks").path(0);
        assertThat(subject.propertyNames()).containsExactlyInAnyOrder("accountId", "rowKey", "grantId");
        assertThat(entries.path(1).path("kind").asString()).isEqualTo("RESEARCH_MAPPING_ERASED");
        assertThat(entries.path(1).path("refs")).isEqualTo(mapper.valueToTree(closure));
    }

    @Test
    void shouldPreserveExactNativeIdentityWhenThereIsNoAccount() {
        var time = Instant.parse("2026-11-24T12:00:00Z");
        var identity = new PersonDataRecovery.NativeIdentity("SLACK", "https://slack.example.test", "U123", "T123");
        Map<String, Object> refs = Map.of("identities", List.of(identity), "externalCopiesRemoved", true);
        PrivacyJournalReferences.validate(PrivacyJournalKind.PERSON_ERASED, refs);

        var row = new PrivacyJournalRow(
                UUID.randomUUID(), PrivacyJournalKind.PERSON_ERASED, mapper.valueToTree(refs), time);

        assertThat(mapper.treeToValue(row.manifestEntry().refs(), PersonDataRecovery.Person.class))
                .isEqualTo(new PersonDataRecovery.Person(null, List.of(identity), true));
    }

    @Test
    void shouldRefuseAnErasureWithNoAccountOrNativeIdentity() {
        assertThatThrownBy(() -> PrivacyJournalReferences.validate(
                        PrivacyJournalKind.PERSON_ERASED,
                        Map.of("identities", List.of(), "externalCopiesRemoved", false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid recovery references for PERSON_ERASED");
    }

    @Test
    void shouldRefuseToSwapTheRevokedSessionAndTheSessionThatStaysOpen() {
        var time = Instant.parse("2026-11-24T12:00:00Z");
        var existingSession = UUID.randomUUID();
        assertThatThrownBy(() -> PrivacyJournalReferences.validate(
                        PrivacyJournalKind.SESSION_REVOKED,
                        Map.of(
                                "accountId",
                                37L,
                                "target",
                                "SESSION",
                                "through",
                                time,
                                "keepSessionId",
                                existingSession)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PrivacyJournalReferences.validate(
                        PrivacyJournalKind.SESSION_REVOKED,
                        Map.of("accountId", 37L, "target", "ACCOUNT", "through", time, "sessionId", existingSession)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRefuseContentInARecoveryFact() {
        assertThatThrownBy(() -> PrivacyJournalReferences.validate(
                        PrivacyJournalKind.ENTRY_DELETED,
                        Map.of("workspaceId", 37L, "entryId", UUID.randomUUID(), "message", "private words")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid recovery references for ENTRY_DELETED");
    }
}
