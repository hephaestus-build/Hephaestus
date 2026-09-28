package de.tum.cit.aet.hephaestus.integration.scm.gitlab.user;

import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Classifies GitLab users by login pattern.
 * <p>
 * GitLab group and project access tokens materialise as users with deterministic
 * logins of the form {@code group_<id>_bot_<hash>} or {@code project_<id>_bot_<hash>}
 * (hex-encoded). These are service identities, not humans — marking them as
 * {@link User.Type#BOT} so that downstream filters (team rosters,
 * activity) can exclude them uniformly.
 */
public final class GitLabUserClassifier {

    private static final Pattern BOT_LOGIN_PATTERN = Pattern.compile("^(group|project)_\\d+_bot_[0-9a-f]+$");

    private GitLabUserClassifier() {}

    public static boolean isBot(@Nullable String login) {
        return login != null && BOT_LOGIN_PATTERN.matcher(login).matches();
    }

    public static User.Type classify(@Nullable String login) {
        return isBot(login) ? User.Type.BOT : User.Type.USER;
    }
}
