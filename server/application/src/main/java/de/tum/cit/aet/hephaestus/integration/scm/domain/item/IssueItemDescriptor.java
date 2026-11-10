package de.tum.cit.aet.hephaestus.integration.scm.domain.item;

import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.AboutRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyCapability;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemDescriptor;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemEventDefinition;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewCapabilities;
import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewLimitation;
import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewStateDimension;
import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewStateValue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.IssueContentSource;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class IssueItemDescriptor implements ItemDescriptor {
    @Override
    public ItemKind kind() {
        return ScmItemEvents.ISSUE;
    }

    @Override
    public String displayName() {
        return "Issue";
    }

    @Override
    public List<ItemEventDefinition> events() {
        return List.of(
                ScmEventSources.declare(
                        ScmItemEvents.ISSUE_OPENED,
                        "Opened",
                        Set.of(ScmEventSources.GITHUB_ISSUES, ScmEventSources.GITLAB_ISSUE),
                        true,
                        true),
                ScmEventSources.declare(
                        ScmItemEvents.ISSUE_UPDATED,
                        "Details changed",
                        Set.of(ScmEventSources.GITHUB_ISSUES, ScmEventSources.GITLAB_ISSUE),
                        true,
                        true),
                ScmEventSources.declare(
                        ScmItemEvents.ISSUE_CLOSED,
                        "Closed",
                        Set.of(ScmEventSources.GITHUB_ISSUES, ScmEventSources.GITLAB_ISSUE),
                        false,
                        true),
                ScmEventSources.declare(
                        ScmItemEvents.ISSUE_DELETED,
                        "Deleted",
                        Set.of(ScmEventSources.GITHUB_ISSUES, ScmEventSources.GITLAB_ISSUE),
                        false,
                        false));
    }

    @Override
    public Set<AboutRole> roles() {
        return Set.of(AboutRole.AUTHOR, AboutRole.ASSIGNEE);
    }

    @Override
    public Set<CopyCapability> copyCapabilities() {
        return Set.of(CopyCapability.SUMMARY);
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
        return "contracts/items/scm/issue.schema.json";
    }

    @Override
    public SourceKind recordSource() {
        return IssueContentSource.CORE;
    }

    @Override
    public ReviewCapabilities reviewCapabilities() {
        return new ReviewCapabilities(
                List.of(new ReviewLimitation(
                        "RUNTIME_BEHAVIOR_NOT_OBSERVED",
                        "Repository evidence does not establish behavior in a deployed runtime.")),
                List.of(new ReviewStateDimension(
                        "state",
                        "State",
                        List.of(new ReviewStateValue("OPEN", "Open"), new ReviewStateValue("CLOSED", "Closed")),
                        Set.of("OPEN", "CLOSED"))),
                Set.of(),
                Set.of(),
                Set.of());
    }
}
