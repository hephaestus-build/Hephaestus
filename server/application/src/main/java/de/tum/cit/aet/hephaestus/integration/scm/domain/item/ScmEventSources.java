package de.tum.cit.aet.hephaestus.integration.scm.domain.item;

import de.tum.cit.aet.hephaestus.integration.core.spi.EventTypeKey;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemEventDefinition;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemEventName;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemVersionScheme;
import de.tum.cit.aet.hephaestus.integration.core.spi.OccasionKind;
import java.util.Set;

final class ScmEventSources {
    static final EventTypeKey GITHUB_PULL_REQUEST = new EventTypeKey(IntegrationKind.GITHUB, "repository.pull_request");
    static final EventTypeKey GITHUB_PULL_REQUEST_REVIEW =
            new EventTypeKey(IntegrationKind.GITHUB, "repository.pull_request_review");
    static final EventTypeKey GITHUB_ISSUES = new EventTypeKey(IntegrationKind.GITHUB, "repository.issues");
    static final EventTypeKey GITHUB_CHECK_SUITE = new EventTypeKey(IntegrationKind.GITHUB, "repository.check_suite");
    static final EventTypeKey GITHUB_STATUS = new EventTypeKey(IntegrationKind.GITHUB, "repository.status");
    static final EventTypeKey GITLAB_MERGE_REQUEST = new EventTypeKey(IntegrationKind.GITLAB, "merge_request");
    static final EventTypeKey GITLAB_NOTE = new EventTypeKey(IntegrationKind.GITLAB, "note");
    static final EventTypeKey GITLAB_ISSUE = new EventTypeKey(IntegrationKind.GITLAB, "issue");

    private static final Set<ItemEventName> MILESTONES =
            Set.of(ScmItemEvents.PULL_REQUEST_OPENED, ScmItemEvents.PULL_REQUEST_READY, ScmItemEvents.ISSUE_OPENED);
    // An outcome or a reviewer's own review is its own moment: a later push never joins it.
    private static final Set<ItemEventName> AT_ONCE = Set.of(
            ScmItemEvents.PULL_REQUEST_MERGED,
            ScmItemEvents.PULL_REQUEST_CLOSED,
            ScmItemEvents.PULL_REQUEST_REVIEW_SUBMITTED,
            ScmItemEvents.ISSUE_CLOSED,
            ScmItemEvents.ISSUE_DELETED);

    static ItemEventDefinition declare(
            ItemEventName name, String label, Set<EventTypeKey> sources, boolean recommended, boolean bindable) {
        ItemVersionScheme scheme = name.value().startsWith("scm.pull_request.")
                ? ItemVersionScheme.HEAD_COMMIT
                : ItemVersionScheme.CONTENT_DIGEST;
        return new ItemEventDefinition(name, label, sources, scheme, recommended, bindable, occasion(name));
    }

    private static OccasionKind occasion(ItemEventName name) {
        if (MILESTONES.contains(name)) return OccasionKind.MILESTONE;
        return AT_ONCE.contains(name) ? OccasionKind.IMMEDIATE : OccasionKind.CONTINUING;
    }

    private ScmEventSources() {}
}
