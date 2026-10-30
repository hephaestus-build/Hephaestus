package de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest;

import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** The checks source record shared by item capture and the workspace folder. */
public final class PullRequestChecksRecord {
    public static final SourceKind KIND = new SourceKind("scm.pull-request.checks");
    public static final String FILE_NAME = "checks.json";
    private static final List<String> FIELDS = List.of("head_checks", "head_check_runs", "merge_state_status");

    private PullRequestChecksRecord() {}

    public static ObjectNode current(PullRequest pull, String head) {
        String merge = head.equals(pull.getHeadRefOid()) && pull.getMergeStateStatus() != null
                ? pull.getMergeStateStatus().name()
                : null;
        return render(head, pull.getHeadCheckState(), pull.getHeadCheckSha(), merge, pull.getHeadCheckRuns());
    }

    /** A retained occasion supplies its own status; today's mirror never joins it. */
    public static ObjectNode retained(ObjectNode record) {
        ObjectNode checks = JsonNodeFactory.instance.objectNode();
        for (String field : List.of("basis", "commit_sha", "head_checks", "head_check_runs", "merge_state_status")) {
            if (record.has(field)) checks.set(field, record.get(field).deepCopy());
        }
        return checks;
    }

    public static ObjectNode core(ObjectNode record) {
        ObjectNode core = record.deepCopy();
        core.remove(FIELDS);
        core.remove(List.of("head_check_state", "head_check_sha", "head_check_observed_at", "review_decision"));
        return core;
    }

    /** The workspace projection receives stored column names rather than an entity. */
    public static Optional<ObjectNode> mirrored(ObjectNode row, ObjectMapper mapper) {
        String head = row.path("head_ref_oid").asString("");
        if (head.isEmpty()) return Optional.empty();
        JsonNode storedRuns = row.path("head_check_runs");
        HeadCheckRuns runs = storedRuns.isObject() ? mapper.treeToValue(storedRuns, HeadCheckRuns.class) : null;
        JsonNode state = row.path("head_check_state");
        ObjectNode record = render(
                head,
                state.isString() ? CheckState.valueOf(state.asString()) : null,
                row.path("head_check_sha").asString(null),
                row.path("merge_state_status").asString(null),
                runs);
        var basis = record.putObject("basis");
        basis.put("other_fields", "MIRROR");
        if (row.path("synced_at").isString()) basis.set("mirror_synced_at", row.get("synced_at"));
        return Optional.of(record);
    }

    private static ObjectNode render(
            String head,
            @Nullable CheckState state,
            @Nullable String stateHead,
            @Nullable String merge,
            @Nullable HeadCheckRuns checkRuns) {
        ObjectNode record = JsonNodeFactory.instance.objectNode();
        record.put("commit_sha", head);
        if (state != null && head.equals(stateHead)) {
            CheckState effective =
                    checkRuns != null && checkRuns.sha().equals(stateHead) ? checkRuns.effectiveState(state) : state;
            record.put("head_checks", effective.name());
        }
        if (merge != null) record.put("merge_state_status", merge);
        if (checkRuns != null && head.equals(checkRuns.sha())) {
            ObjectNode node = record.putObject("head_check_runs");
            node.put("complete", checkRuns.complete());
            if (checkRuns.limitation() != null) node.put("limitation", checkRuns.limitation());
            node.put("observed_at", checkRuns.observedAt().toString());
            var runs = node.putArray("runs");
            for (HeadCheckRuns.Run run : checkRuns.runs()) {
                ObjectNode entry = runs.addObject();
                entry.put("name", run.name());
                if (run.group() != null) entry.put("group", run.group());
                entry.put("state", run.state());
                entry.put("finished", run.finished());
                if (run.url() != null) entry.put("url", run.url());
                if (run.startedAt() != null)
                    entry.put("started_at", run.startedAt().toString());
                if (run.finishedAt() != null)
                    entry.put("finished_at", run.finishedAt().toString());
            }
        }
        return record;
    }
}
