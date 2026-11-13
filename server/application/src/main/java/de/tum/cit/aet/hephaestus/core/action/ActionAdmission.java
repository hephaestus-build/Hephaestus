package de.tum.cit.aet.hephaestus.core.action;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * The rows that this build can decode: for each {@link ActionKind} that has an adapter, the JSON path of that adapter's
 * {@link ActionVariants}. Its JSON binds to {@link AgentActionRepository#DECODABLE}. A kind without an adapter, or
 * that this build does not declare, selects nothing.
 *
 * @param json the path of each kind by its name
 */
record ActionAdmission(String json) {

    static ActionAdmission of(Collection<? extends ActionAdapter> adapters) {
        Map<String, String> paths = new LinkedHashMap<>();
        for (ActionAdapter adapter : adapters)
            paths.put(adapter.kind().name(), adapter.variants().path());
        return new ActionAdmission(JsonMapper.shared().writeValueAsString(paths));
    }
}
