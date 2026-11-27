package de.tum.cit.aet.hephaestus.practices.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataSelection;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonFeedbackAnswerRedaction;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackCopyCleanupCustody;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackCopyReadback;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Tag("unit")
class ExternalFeedbackPersonDataStoreTest {
    @ParameterizedTest
    @EnumSource(FeedbackCopyReadback.class)
    void shouldExportAbsenceWithoutCallingAnUncertainWriteAbsent(FeedbackCopyReadback readback) {
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        var mapper = new JsonMapper();
        JsonNode row = mapper.createObjectNode()
                .put("id", "test-copy")
                .put("workspaceId", 1)
                .put("readback", readback.name())
                .put("state", "FAILED")
                .put("lastError", "ABSENT: old diagnostic text is not authoritative")
                .put("writeStartedAt", "2026-10-14T00:00:00Z")
                .putNull("postedAt")
                .putNull("externalRef")
                .putNull("externalUrl");
        when(jdbc.query(anyString(), anyMap(), ArgumentMatchers.<RowMapper<JsonNode>>any()))
                .thenReturn(List.of(row));
        var store = new ExternalFeedbackPersonDataStore(
                mock(FeedbackCopyCleanupCustody.class),
                mock(PersonFeedbackAnswerRedaction.class),
                jdbc,
                mapper,
                "copies",
                "feedback_copy",
                "true",
                "id",
                "id",
                "",
                1);
        var selection = new PersonDataSelection(List.of(new PersonDataSelection.RowKey(Map.of("id", "1"))));
        if (readback == FeedbackCopyReadback.ABSENT)
            assertThat(store.externalDeliveries(selection)).isEmpty();
        else assertThatThrownBy(() -> store.externalDeliveries(selection)).isInstanceOf(ResponseStatusException.class);
    }
}
