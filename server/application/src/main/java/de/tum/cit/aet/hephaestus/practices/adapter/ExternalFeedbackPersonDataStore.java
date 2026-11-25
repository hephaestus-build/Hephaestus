package de.tum.cit.aet.hephaestus.practices.adapter;

import de.tum.cit.aet.hephaestus.core.privacy.spi.JdbcPersonDataStore;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataSelection;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonScope;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackCopyCleanupCustody;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackCopyReadback;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class ExternalFeedbackPersonDataStore extends JdbcPersonDataStore {
    private final FeedbackCopyCleanupCustody custody;

    ExternalFeedbackPersonDataStore(
            FeedbackCopyCleanupCustody custody,
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper mapper,
            String store,
            String table,
            String predicate,
            String columns,
            String keys,
            String redaction,
            int order) {
        super(jdbc, mapper, store, table, predicate, columns, keys, redaction, order);
        this.custody = custody;
    }

    @Override
    public PersonDataSelection select(PersonScope scope) {
        var selected = super.select(scope);
        return selected.withExternalDeliveryFacts(inspectionRows(selected));
    }

    private List<JsonNode> inspectionRows(PersonDataSelection selection) {
        return querySelected(
                "jsonb_build_object('id',t.id,'workspaceId',t.workspace_id,'state',t.state,'readback',t.readback,'lastError',t.last_error,'feedbackId',t.feedback_id,'writeStartedAt',t.write_started_at,'writeMarker',t.write_marker,'externalRef',t.external_ref,'externalUrl',t.external_url,'postedAt',t.posted_at)::text",
                "feedback_copy t",
                selection);
    }

    @Override
    public void prepareErasure(PersonDataSelection selection, PersonScope frozenScope) {
        var byWorkspace = inspectionRows(selection).stream()
                .collect(Collectors.groupingBy(row -> row.path("workspaceId").longValue()));
        byWorkspace.forEach((workspace, rows) -> custody.requireAcknowledged(
                workspace,
                rows.stream()
                        .map(row -> UUID.fromString(row.path("feedbackId").asString()))
                        .distinct()
                        .toList()));
    }

    @Override
    public List<ExternalDelivery> externalDeliveries(PersonDataSelection selection) {
        var deliveries = new ArrayList<ExternalDelivery>();
        for (var row : inspectionRows(selection)) {
            boolean absent = FeedbackCopyReadback.ABSENT
                    .name()
                    .equals(row.path("readback").asString());
            boolean uncertain = !absent
                    && !row.path("writeStartedAt").isNull()
                    && row.path("postedAt").isNull();
            var url = row.path("externalUrl");
            var ref = row.path("externalRef");
            String locator = url.isString() && !url.asString().isBlank()
                    ? url.asString()
                    : ref.isString() && !ref.asString().isBlank() ? ref.asString() : null;
            if (locator != null)
                deliveries.add(new ExternalDelivery(row.path("workspaceId").asLong(), locator));
            else if (uncertain)
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "The feedback copy " + row.path("id").asString()
                                + " has an unresolved provider write. Retain its cleanup custody before erasure.");
        }
        return deliveries.stream().distinct().toList();
    }
}
