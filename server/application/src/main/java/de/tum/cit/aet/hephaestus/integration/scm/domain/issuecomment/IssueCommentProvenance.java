package de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment;

import de.tum.cit.aet.hephaestus.integration.core.spi.DeliveredIssueCommentLookup;
import java.net.URI;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/** Maps recorded provider identities to the native ids in the synchronized mirror. */
@Component
public class IssueCommentProvenance {
    private static final Pattern NUMERIC_ID = Pattern.compile("[0-9]+");
    private static final String GITLAB_NOTE = "gid://gitlab/Note/";
    private static final String GITHUB_COMMENT = "issuecomment-";
    private final DeliveredIssueCommentLookup deliveries;

    public IssueCommentProvenance(DeliveredIssueCommentLookup deliveries) {
        this.deliveries = deliveries;
    }

    public Set<Long> deliveredIds(long issueId) {
        return nativeIds(deliveries.findForIssue(issueId));
    }

    /** {@link #deliveredIds} for every issue of a repository the workspace monitors, read at once. */
    public Map<Long, Set<Long>> deliveredIdsByIssue(long workspaceId, long repositoryId) {
        Map<Long, Set<Long>> ids = new HashMap<>();
        deliveries
                .findForRepository(workspaceId, repositoryId)
                .forEach((issue, found) -> ids.put(issue, nativeIds(found)));
        return Map.copyOf(ids);
    }

    private static Set<Long> nativeIds(List<DeliveredIssueCommentLookup.DeliveredComment> found) {
        Set<Long> ids = new HashSet<>();
        for (var delivery : found) {
            String ref = delivery.externalRef();
            addNumeric(ids, ref.startsWith(GITLAB_NOTE) ? ref.substring(GITLAB_NOTE.length()) : ref);
            // GitHub returns an opaque GraphQL id; its recorded permalink carries the native comment id.
            if (delivery.url() != null) {
                try {
                    String fragment = URI.create(delivery.url()).getFragment();
                    if (fragment != null && fragment.startsWith(GITHUB_COMMENT)) {
                        addNumeric(ids, fragment.substring(GITHUB_COMMENT.length()));
                    }
                } catch (IllegalArgumentException ignored) {
                    // An unusable historical identity cannot exclude authored evidence.
                }
            }
        }
        return Set.copyOf(ids);
    }

    private static void addNumeric(Set<Long> ids, @Nullable String value) {
        if (value != null && NUMERIC_ID.matcher(value).matches()) {
            try {
                ids.add(Long.parseLong(value));
            } catch (NumberFormatException ignored) {
                // A provider id outside the mirror's bigint range cannot match a mirrored comment.
            }
        }
    }
}
