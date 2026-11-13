package de.tum.cit.aet.hephaestus.agent.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

/** The permit lifecycle lives in native SQL; each statement that moves a permit names a legal source state. */
class LlmCallPermitSqlTest extends BaseUnitTest {
    // A statement that moves a permit ends at its RETURNING clause or at the end of the query.
    private static final Pattern UPDATE =
            Pattern.compile("UPDATE llm_call_permit(?: \\w+)? SET state='(\\w+)'(.*?)(?=\\bRETURNING\\b|$)");
    private static final Pattern SOURCE = Pattern.compile("(?:\\w+\\.)?state\\s*(?:=\\s*'(\\w+)'|IN\\s*\\(([^)]*)\\))");

    @Test
    void shouldMoveAPermitOnlyFromALegalSourceStateWhenTheSqlUpdatesIt() {
        var moves = new HashSet<Map.Entry<LlmCallPermitState, LlmCallPermitState>>();
        for (var sql : queries()) {
            var update = UPDATE.matcher(sql);
            while (update.find()) {
                var target = LlmCallPermitState.valueOf(update.group(1));
                var where =
                        update.group(2).substring(Math.max(0, update.group(2).indexOf(" WHERE ")));
                var sources = sources(where);
                assertThat(sources).as("source states of %s", update.group()).isNotEmpty();
                for (var source : sources) {
                    assertThat(source.canTransitionTo(target))
                            .as("%s -> %s in %s", source, target, update.group())
                            .isTrue();
                    moves.add(Map.entry(source, target));
                }
            }
        }
        var legal = new HashSet<Map.Entry<LlmCallPermitState, LlmCallPermitState>>();
        for (var source : LlmCallPermitState.values()) {
            for (var target : LlmCallPermitState.values()) {
                if (source.canTransitionTo(target)) legal.add(Map.entry(source, target));
            }
        }
        assertThat(moves).as("every legal move has its statement").isEqualTo(legal);
    }

    @Test
    void shouldAdmitEveryPermitWaitingWhenTheSqlInsertsIt() {
        var inserts = queries().stream()
                .filter(sql -> sql.contains("INSERT INTO llm_call_permit"))
                .toList();
        assertThat(inserts).isNotEmpty();
        for (var sql : inserts) {
            var states = EnumSet.noneOf(LlmCallPermitState.class);
            for (var state : LlmCallPermitState.values()) {
                if (sql.contains("'" + state.name() + "'")) states.add(state);
            }
            assertThat(states).as(sql).containsExactly(LlmCallPermitState.WAITING);
        }
    }

    private static Set<LlmCallPermitState> sources(String where) {
        var found = EnumSet.noneOf(LlmCallPermitState.class);
        var matcher = SOURCE.matcher(where);
        while (matcher.find()) {
            var list = matcher.group(1) != null
                    ? List.of(matcher.group(1))
                    : Arrays.stream(Objects.requireNonNull(matcher.group(2)).split(","))
                            .map(value -> value.strip().replace("'", ""))
                            .toList();
            list.forEach(value -> found.add(LlmCallPermitState.valueOf(value)));
        }
        return found;
    }

    private static List<String> queries() {
        var queries = new ArrayList<String>();
        for (var method : LlmCallPermitRepository.class.getDeclaredMethods()) {
            var query = method.getAnnotation(Query.class);
            if (query != null) queries.add(query.value().replaceAll("\\s+", " "));
        }
        return queries;
    }
}
