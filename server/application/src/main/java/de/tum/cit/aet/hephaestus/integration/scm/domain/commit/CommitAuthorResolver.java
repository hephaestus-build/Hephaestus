package de.tum.cit.aet.hephaestus.integration.scm.domain.commit;

import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Resolves git commit author and committer identities to database {@link User} records, on one provider.
 * <p>
 * A commit's email is whatever its author typed, so this class never infers a person from it. It accepts only:
 * <ol>
 *   <li>The email stored on exactly one user of the provider.</li>
 *   <li>A GitHub noreply address that holds an account id, on a GitHub provider. GitHub never reuses that id, but it
 *       gives a released login to a new account, so the login in an address proves nothing.</li>
 *   <li>A login that the provider itself reported, through {@link #resolveByLogin}.</li>
 * </ol>
 * Anything else stays unlinked: an unlinked commit is safe, a wrong link is a false claim about a person.
 * <p>
 * This component is shared by the GitHub and GitLab commit ingestion paths.
 */
@Component
@RequiredArgsConstructor
public class CommitAuthorResolver {

    /** Matches {@code 12345+username@users.noreply.github.com}. Eighteen digits always fit a {@code long}. */
    private static final Pattern GITHUB_NOREPLY_PATTERN =
            Pattern.compile("^([1-9]\\d{0,17})\\+[^@]+@users\\.noreply\\.github\\.com$", Pattern.CASE_INSENSITIVE);

    private final UserRepository userRepository;

    /**
     * Resolve a user's database ID by email, with GitHub noreply fallback.
     * Scoped to a specific provider to avoid cross-provider ambiguity.
     *
     * @param email      the git author/committer email
     * @param providerId the provider to scope the lookup to
     * @return the user's database ID, or {@code null} if no match is found
     */
    @Nullable
    public Long resolveByEmail(@Nullable String email, Long providerId) {
        if (email == null || email.isBlank()) {
            return null;
        }
        List<User> users = userRepository.findAllByEmailAndProviderId(email, providerId);
        if (users.size() == 1) {
            return users.get(0).getId();
        }
        if (!users.isEmpty()) {
            return null;
        }
        Long nativeId = extractNativeIdFromNoreply(email);
        return nativeId == null
                ? null
                : userRepository
                        .findGitHubUserIdByNativeId(nativeId, providerId)
                        .orElse(null);
    }

    /**
     * Resolve a user's database ID by login (username).
     * Scoped to a specific provider to avoid cross-provider ambiguity.
     *
     * @param login      the username (e.g., from webhook {@code CommitUser.username})
     * @param providerId the provider to scope the lookup to
     * @return the user's database ID, or {@code null} if not found
     */
    @Nullable
    public Long resolveByLogin(@Nullable String login, @Nullable Long providerId) {
        if (login == null || login.isBlank()) {
            return null;
        }
        if (providerId != null) {
            return userRepository
                    .findByLoginAndProviderId(login, providerId)
                    .map(User::getId)
                    .orElse(null);
        }
        return userRepository.findByLogin(login).map(User::getId).orElse(null);
    }

    /**
     * Extracts the GitHub account id from a noreply email address.
     *
     * @param email the email to parse
     * @return the account id, or {@code null} if the email is no noreply address with an account id
     */
    @Nullable
    static Long extractNativeIdFromNoreply(String email) {
        Matcher matcher = GITHUB_NOREPLY_PATTERN.matcher(email);
        return matcher.matches() ? Long.valueOf(matcher.group(1)) : null;
    }
}
