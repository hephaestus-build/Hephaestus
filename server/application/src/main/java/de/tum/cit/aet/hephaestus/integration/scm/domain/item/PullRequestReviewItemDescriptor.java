package de.tum.cit.aet.hephaestus.integration.scm.domain.item;

import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.AboutRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyCapability;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemDescriptor;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemEventDefinition;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewCapabilities;
import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewLimitation;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.PullRequestContentSource;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class PullRequestReviewItemDescriptor implements ItemDescriptor {
    @Override
    public ItemKind kind() {
        return ScmItemEvents.PULL_REQUEST_REVIEW;
    }

    @Override
    public String displayName() {
        return "Pull or merge request review";
    }

    @Override
    public List<ItemEventDefinition> events() {
        return List.of(ScmEventSources.declare(
                ScmItemEvents.PULL_REQUEST_REVIEW_SUBMITTED,
                "Review submitted",
                Set.of(
                        ScmEventSources.GITHUB_PULL_REQUEST_REVIEW,
                        ScmEventSources.GITLAB_MERGE_REQUEST,
                        ScmEventSources.GITLAB_NOTE),
                true,
                true));
    }

    @Override
    public Set<AboutRole> roles() {
        return Set.of(AboutRole.REVIEWER);
    }

    @Override
    public Set<CopyCapability> copyCapabilities() {
        return Set.of(CopyCapability.SUMMARY, CopyCapability.LINE_NOTE);
    }

    @Override
    public boolean reviewable() {
        return true;
    }

    @Override
    public Set<ItemKind> memberKinds() {
        return Set.of();
    }

    @Override
    public String recordSchemaResource() {
        return "contracts/items/scm/pull_request_review.schema.json";
    }

    @Override
    public SourceKind recordSource() {
        return PullRequestContentSource.CORE;
    }

    @Override
    public ReviewCapabilities reviewCapabilities() {
        return new ReviewCapabilities(
                List.of(new ReviewLimitation(
                        "RUNTIME_BEHAVIOR_NOT_OBSERVED",
                        "Repository evidence does not establish behavior in a deployed runtime.")),
                List.of(),
                Set.of("CHANGED_PATH", "DIFF_CONTAINS", "HAS_ITEMS"),
                Set.of("scm.review-threads", "scm.inline-review-comments", "scm.general-review-comments"),
                Set.of());
    }
}
