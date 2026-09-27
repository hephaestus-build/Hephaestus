package de.tum.cit.aet.hephaestus.integration.scm.domain.user;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/** Information about a user from the git provider. */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@Schema(description = "Information about a user from the git provider")
public record UserInfoDTO(
        @NonNull @Schema(description = "Unique identifier of the user")
        Long id,

        @NonNull @Schema(description = "Login/username of the user")
        String login,

        @Schema(description = "Email address of the user, if public")
        String email,

        @NonNull @Schema(description = "URL to the user's avatar image")
        String avatarUrl,

        @NonNull @Schema(description = "Display name of the user")
        String name,

        @NonNull @Schema(description = "URL to the user's profile on the git provider")
        String htmlUrl) {
    @Nullable
    public static UserInfoDTO fromUser(@Nullable User user) {
        if (user == null) {
            return null;
        }
        return new UserInfoDTO(
                user.getId(),
                user.getLogin(),
                user.getEmail(),
                user.getAvatarUrl(),
                user.getName() != null ? user.getName() : user.getLogin(),
                user.getHtmlUrl());
    }
}
