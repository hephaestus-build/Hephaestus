package de.tum.cit.aet.hephaestus.integration.scm.gitlab.team.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

/**
 * DTO for GitLab subgroup webhook events (group structure changes).
 * <p>
 * Subgroup events use {@code event_name} (not {@code object_kind}) as the discriminator.
 * The webhook receiver normalizes all subgroup event names to "subgroup" for provider inbox routing.
 * <p>
 * Supported event_name values:
 * <ul>
 *   <li>{@code subgroup_create} - Subgroup created under a group</li>
 *   <li>{@code subgroup_destroy} - Subgroup removed from a group</li>
 * </ul>
 *
 * @see <a href="https://docs.gitlab.com/ee/user/project/integrations/webhook_events.html#subgroup-events">
 *      GitLab Subgroup Events</a>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GitLabSubgroupEventDTO(
        @JsonProperty("event_name") String eventName,
        @JsonProperty("name") String name,
        @JsonProperty("path") String path,
        @JsonProperty("full_path") String fullPath,
        @JsonProperty("group_id") long groupId,
        @JsonProperty("parent_group_id") long parentGroupId,
        @JsonProperty("parent_name") @Nullable String parentName,
        @JsonProperty("parent_path") @Nullable String parentPath,
        @JsonProperty("parent_full_path") @Nullable String parentFullPath,
        @JsonProperty("created_at") @Nullable String createdAt,
        @JsonProperty("updated_at") @Nullable String updatedAt) {
    public static final String EVENT_SUBGROUP_CREATE = "subgroup_create";
    public static final String EVENT_SUBGROUP_DESTROY = "subgroup_destroy";

    public boolean isCreation() {
        return EVENT_SUBGROUP_CREATE.equals(eventName);
    }

    public boolean isDeletion() {
        return EVENT_SUBGROUP_DESTROY.equals(eventName);
    }
}
