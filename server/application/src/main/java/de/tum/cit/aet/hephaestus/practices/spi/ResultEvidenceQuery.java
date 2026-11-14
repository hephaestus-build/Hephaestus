package de.tum.cit.aet.hephaestus.practices.spi;

import de.tum.cit.aet.hephaestus.practices.model.Result;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public interface ResultEvidenceQuery {
    /**
     * Every citation of each loaded result and of its problems: evidence, open questions, example and problems.
     *
     * @return keyed by result id; a result without an id is absent
     */
    Map<UUID, List<JsonNode>> citations(long workspaceId, Collection<Result> results);
}
