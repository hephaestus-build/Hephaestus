package de.tum.cit.aet.hephaestus.integration.scm.gitlab.issue.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabEventAction;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.dto.GitLabWebhookLabel;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.dto.GitLabWebhookProject;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.dto.GitLabWebhookUser;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * DTO for GitLab issue webhook events.
 * <p>
 * Maps both {@code event_type: "issue"} and {@code event_type: "confidential_issue"} payloads.
 * Both arrive on the same NATS subject ({@code object_kind: "issue"}).
 *
 * @param objectKind       always "issue"
 * @param eventType        "issue" or "confidential_issue"
 * @param user             the user who triggered the event
 * @param project          the project context
 * @param objectAttributes the issue details
 * @param labels           current labels on the issue
 * @param assignees        current assignees
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GitLabIssueEventDTO(
        @JsonProperty("object_kind") String objectKind,
        @JsonProperty("event_type") String eventType,
        @Nullable GitLabWebhookUser user,
        @Nullable GitLabWebhookProject project,
        @JsonProperty("object_attributes") @Nullable ObjectAttributes objectAttributes,
        @Nullable List<GitLabWebhookLabel> labels,
        @Nullable List<GitLabWebhookUser> assignees,
        @JsonProperty("changes") @Nullable Changes changes) {

    /**
     * The assignees now. GitLab leaves the key out of every issue hook when there are none, so an absent list is an
     * empty one.
     *
     * @see <a href="https://gitlab.com/gitlab-org/gitlab/-/blob/v18.4.0-ee/lib/gitlab/data_builder/issuable.rb#L28">GitLab's issuable hook data builder</a>
     */
    public List<GitLabWebhookUser> currentAssignees() {
        return assignees == null ? List.of() : assignees;
    }

    /**
     * The {@code changes} diff GitLab sends on an {@code action=update} event: one entry per attribute
     * the update moved. GitLab has no per-attribute action, so this diff is the only thing that says
     * what an {@code update} was — without it a due-date edit is indistinguishable from a retitling.
     *
     * <p>Only the attributes the shared domain has a field name for are declared; the rest (due date,
     * weight, time tracking, confidentiality) are what {@code ignoreUnknown} drops.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Changes(
            @Nullable LabelsChange labels,
            @Nullable AttributeChange title,
            @Nullable AttributeChange description,
            @Nullable AttributeChange assignees,
            @JsonProperty("milestone_id") @Nullable AttributeChange milestoneId,
            @JsonProperty("state_id") @Nullable AttributeChange stateId) {}

    /**
     * An attribute GitLab reported as changed. Its {@code previous}/{@code current} values are
     * deliberately not bound: the mirror is refreshed from {@code object_attributes} either way, so the
     * only thing read here is that the key was present.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AttributeChange() {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LabelsChange(
            @Nullable List<GitLabWebhookLabel> previous,
            @Nullable List<GitLabWebhookLabel> current) {}

    /**
     * What this update moved, in the field vocabulary {@code ScmDomainEvent.IssueUpdated} carries, so a
     * consumer reads the same names whichever provider raised the event.
     */
    public Set<String> changedFields() {
        if (changes == null) {
            return Set.of();
        }
        Set<String> fields = new LinkedHashSet<>();
        if (changes.title() != null) {
            fields.add("title");
        }
        if (changes.description() != null) {
            fields.add("body");
        }
        if (changes.stateId() != null) {
            fields.add("state");
        }
        if (changes.milestoneId() != null) {
            fields.add("milestone");
        }
        if (changes.labels() != null || changes.assignees() != null) {
            fields.add("relationships");
        }
        return Set.copyOf(fields);
    }

    /**
     * Labels newly added in this update (current minus previous, keyed by id). Empty when the update
     * carried no label change — so an ordinary title/description edit never spuriously triggers
     * label-based detection.
     */
    public List<GitLabWebhookLabel> addedLabels() {
        if (changes == null || changes.labels() == null || changes.labels().current() == null) {
            return List.of();
        }
        List<GitLabWebhookLabel> previous = changes.labels().previous();
        Set<Long> previousIds = previous == null
                ? Set.of()
                : previous.stream()
                        .map(GitLabWebhookLabel::id)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());
        return changes.labels().current().stream()
                // A current label with a null id is treated as added: GitLab's changes.labels diff reliably carries
                // ids, so this branch is defensive only and deliberately favours an extra activity entry over
                // silently losing a real add on a malformed payload.
                .filter(label -> label.id() == null || !previousIds.contains(label.id()))
                .toList();
    }

    /**
     * The issue details within the webhook payload.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ObjectAttributes(
            @Nullable Long id,
            @Nullable Integer iid,
            @Nullable String title,
            @Nullable String description,
            @Nullable String state,
            @Nullable String action,
            boolean confidential,
            @JsonProperty("author_id") @Nullable Long authorId,
            @JsonProperty("assignee_id") @Nullable Long assigneeId,
            @JsonProperty("milestone_id") @Nullable Long milestoneId,
            @JsonProperty("created_at") @Nullable String createdAt,
            @JsonProperty("updated_at") @Nullable String updatedAt,
            @JsonProperty("closed_at") @Nullable String closedAt,
            @JsonProperty("duplicated_to_id") @Nullable Long duplicatedToId,
            @Nullable String type,
            @Nullable String url) {}

    public boolean isConfidential() {
        return objectAttributes != null && objectAttributes.confidential();
    }

    public GitLabEventAction actionType() {
        if (objectAttributes == null || objectAttributes.action() == null) {
            return GitLabEventAction.UNKNOWN;
        }
        return GitLabEventAction.fromString(objectAttributes.action());
    }
}
