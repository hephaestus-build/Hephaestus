package de.tum.cit.aet.hephaestus.integration.scm.gitlab.organization.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

/**
 * DTO for GitLab member webhook events (group membership changes).
 * <p>
 * Member events use {@code event_name} (not {@code object_kind}) as the discriminator.
 * The webhook receiver normalizes all member event names to "member" for provider inbox routing.
 * <p>
 * Supported event_name values:
 * <ul>
 *   <li>{@code user_add_to_group} - User added to group</li>
 *   <li>{@code user_remove_from_group} - User removed from group</li>
 *   <li>{@code user_update_for_group} - Access level changed</li>
 * </ul>
 *
 * @see <a href="https://docs.gitlab.com/ee/user/project/integrations/webhook_events.html#group-member-events">
 *      GitLab Group Member Events</a>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GitLabMemberEventDTO(
        @JsonProperty("event_name") String eventName,
        @JsonProperty("group_name") String groupName,
        @JsonProperty("group_path") String groupPath,
        @JsonProperty("group_id") long groupId,
        @JsonProperty("user_username") String userUsername,
        @JsonProperty("user_name") String userName,
        @JsonProperty("user_email") @Nullable String userEmail,
        @JsonProperty("user_id") long userId,
        @JsonProperty("group_access") @Nullable String groupAccess,
        @JsonProperty("user_avatar") @Nullable String userAvatar,
        @JsonProperty("created_at") @Nullable String createdAt,
        @JsonProperty("updated_at") @Nullable String updatedAt,
        @JsonProperty("expires_at") @Nullable String expiresAt) {
    public static final String EVENT_USER_ADD = "user_add_to_group";
    public static final String EVENT_USER_REMOVE = "user_remove_from_group";
    public static final String EVENT_USER_UPDATE = "user_update_for_group";

    public boolean isAddition() {
        return EVENT_USER_ADD.equals(eventName);
    }

    public boolean isRemoval() {
        return EVENT_USER_REMOVE.equals(eventName);
    }

    public boolean isUpdate() {
        return EVENT_USER_UPDATE.equals(eventName);
    }
}
