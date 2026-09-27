package de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.graphql;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Response POJO for GitLab Group Member GraphQL queries.
 * <p>
 * Maps to the {@code groupMembers.nodes} field returned by the {@code GetGroupMembers} query.
 *
 * @param user        the member user data
 * @param accessLevel the member's access level in the group
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GitLabGroupMemberResponse(
        @Nullable GitLabMemberUser user, @Nullable GitLabAccessLevel accessLevel) {

    /**
     * Everyone the connected group grants access: its own members, those it inherits from its ancestors and the
     * members of groups invited to it. {@code DESCENDANTS} is excluded: a subgroup member is no member of the group.
     */
    public static final List<String> EFFECTIVE_RELATIONS = List.of("DIRECT", "INHERITED", "SHARED_FROM_GROUPS");

    /**
     * A subgroup team's own grants: its members and the members of groups invited to it. Not {@code INHERITED}, since
     * the team hierarchy already carries what a subgroup inherits.
     */
    public static final List<String> TEAM_RELATIONS = List.of("DIRECT", "SHARED_FROM_GROUPS");

    /**
     * User data from a group membership node.
     *
     * @param id        GitLab Global ID (e.g., {@code gid://gitlab/User/42})
     * @param username  the user's login name
     * @param name      the user's display name (nullable)
     * @param avatarUrl the user's avatar URL (nullable)
     * @param webUrl    the user's profile URL (nullable)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GitLabMemberUser(
            String id,
            String username,
            @Nullable String name,
            @Nullable String avatarUrl,
            @Nullable String webUrl) {}

    /**
     * Access level for a group member.
     * <p>
     * GitLab access levels: MINIMAL_ACCESS(5), GUEST(10), PLANNER(15), REPORTER(20),
     * DEVELOPER(30), MAINTAINER(40), OWNER(50).
     *
     * @param stringValue  human-readable access level (e.g., "DEVELOPER")
     * @param integerValue numeric access level (e.g., 30)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GitLabAccessLevel(
            @Nullable String stringValue, @Nullable Integer integerValue) {}
}
