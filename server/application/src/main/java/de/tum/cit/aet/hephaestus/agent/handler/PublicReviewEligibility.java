package de.tum.cit.aet.hephaestus.agent.handler;

import de.tum.cit.aet.hephaestus.agent.context.providers.GeneralReviewCommentContentSource;
import de.tum.cit.aet.hephaestus.agent.context.providers.IssueContentSource;
import de.tum.cit.aet.hephaestus.agent.context.providers.LinkedWorkItemContentSource;
import de.tum.cit.aet.hephaestus.agent.context.providers.PullRequestContentSource;
import de.tum.cit.aet.hephaestus.agent.context.providers.RepositoryTreeContentSource;
import de.tum.cit.aet.hephaestus.agent.context.providers.ReviewThreadContentSource;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * Public composition excludes an entire observation when its recorded provenance names private or unknown sources.
 * The same rule governs model input and admission; removing citations cannot remove claims derived from them.
 */
final class PublicReviewEligibility {

    /** The sources that capture the reviewed work, its repository and its discussion. */
    private static final Set<String> CAPTURED_WORK = Stream.of(
                    PullRequestContentSource.CORE,
                    PullRequestContentSource.DIFF,
                    PullRequestContentSource.COMMENTS,
                    IssueContentSource.CORE,
                    IssueContentSource.COMMENTS,
                    RepositoryTreeContentSource.KIND,
                    LinkedWorkItemContentSource.KIND,
                    ReviewThreadContentSource.KIND,
                    GeneralReviewCommentContentSource.KIND)
            .map(SourceKind::value)
            .collect(Collectors.toUnmodifiableSet());

    /** The evidence branches that list what an observation consulted. */
    private static final Set<String> CONSULTING_BRANCHES = Set.of("search", "inapplicability", "undecidability");

    private PublicReviewEligibility() {}

    /** Whether an observation with this evidence may support what the reviewed work is told. */
    static boolean admits(@Nullable JsonNode evidence) {
        if (evidence == null || !evidence.isObject()) {
            return false;
        }
        boolean namesASource = false;
        for (JsonNode citation : evidence.path("citations")) {
            if (!CAPTURED_WORK.contains(citation.path("sourceKind").asString(""))) {
                return false;
            }
            namesASource = true;
        }
        for (String branch : CONSULTING_BRANCHES) {
            for (JsonNode consulted : evidence.path(branch).path("consulted")) {
                if (!CAPTURED_WORK.contains(consulted.asString(""))) {
                    return false;
                }
                namesASource = true;
            }
        }
        return namesASource;
    }
}
