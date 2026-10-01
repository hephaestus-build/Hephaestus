package de.tum.cit.aet.hephaestus.integration.scm.gitlab.user;

import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The account type of a GitLab user. GitLab's own {@code bot} flag decides it: service accounts are bots whatever their
 * username. Without the flag the type is unknown and a stored one stays; only a user seen for the first time needs a
 * guess, and the deterministic logins GitLab gives group and project access tokens, {@code group_<id>_bot_<hash>} or
 * {@code project_<id>_bot_<hash>}, are then taken for bots. No username shows that an account is human.
 */
public final class GitLabUserClassifier {

    private static final Pattern BOT_LOGIN_PATTERN = Pattern.compile("^(group|project)_\\d+_bot_[0-9a-f]+$");

    private GitLabUserClassifier() {}

    /** The type GitLab states, or {@code null} when the read did not carry its {@code bot} flag. */
    public static @Nullable String nativeType(@Nullable Boolean bot) {
        return bot == null ? null : (bot ? User.Type.BOT : User.Type.USER).name();
    }

    /** The type to record for a user seen for the first time: GitLab's, or else a guess from the login. */
    public static String insertionType(@Nullable String login, @Nullable Boolean bot) {
        String stated = nativeType(bot);
        if (stated != null) {
            return stated;
        }
        return (login != null && BOT_LOGIN_PATTERN.matcher(login).matches() ? User.Type.BOT : User.Type.USER).name();
    }
}
