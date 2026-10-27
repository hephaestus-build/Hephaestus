package de.tum.cit.aet.hephaestus.integration.scm.domain.commit;

import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Resolves git commit author/committer identities to database {@link User} records.
 * <p>
 * Resolution strategy (in order):
 * <ol>
 *   <li>Direct email match via {@link UserRepository#findByEmail} (case-insensitive)</li>
 *   <li>GitHub noreply email parsing: extracts login from {@code username@users.noreply.github.com}
 *       or {@code 12345+username@users.noreply.github.com} patterns, then matches via
 *       {@link UserRepository#findByLogin}</li>
 *   <li>Email local-part match: extracts the portion before {@code @} and matches via
 *       {@link UserRepository#findByLoginAndProviderId}. Covers TUM/LRZ conventions
 *       like {@code ef56ghi@mytum.de} or {@code ab12cde@mytum.de} where the local-part
 *       equals the GitLab login. Skipped for {@code firstname.lastname@domain} style
 *       addresses (any local-part containing {@code .} or {@code +} is ignored).</li>
 *   <li>Display-name match for {@code firstname.lastname@tum.de}-style addresses:
 *       splits the dotted local-part into tokens, concatenates them with a single
 *       space, and looks up a {@link User} whose {@code name} equals that value
 *       (case-insensitive). Only acts when exactly one candidate is returned so
 *       the match is deterministic and high-confidence.</li>
 *   <li>Login-based match via {@link UserRepository#findByLogin} (for webhook data that provides
 *       the username directly)</li>
 * </ol>
 * <p>
 * This component is shared between {@code GitHubCommitBackfillService} (sync-cycle backfill),
 * {@code GitHubPushMessageHandler} (webhook push handling), and the equivalent GitLab paths
 * to ensure consistent author resolution across all commit ingestion paths.
 * <p>
 * {@link #resolveAndBackfillByEmail(String, Long)} is the GitLab-oriented convenience that
 * pairs resolution with an email backfill onto the matched {@link User}. Use it from the
 * GitLab commit-contributor sync paths where commit email fingerprints (e.g.
 * {@code firstname.lastname@tum.de}) are trusted institutional identifiers.
 */
@Component
@RequiredArgsConstructor
public class CommitAuthorResolver {

    /**
     * Matches GitHub noreply email patterns:
     * <ul>
     *   <li>{@code username@users.noreply.github.com}</li>
     *   <li>{@code 12345+username@users.noreply.github.com} (ID-prefixed)</li>
     * </ul>
     */
    private static final Pattern GITHUB_NOREPLY_PATTERN =
            Pattern.compile("^(?:\\d+\\+)?([^@]+)@users\\.noreply\\.github\\.com$", Pattern.CASE_INSENSITIVE);

    /**
     * Matches email local-parts that look like a single login (no dot, no plus).
     * Used for Strategy 3 to avoid false positives on {@code firstname.lastname@tum.de}
     * style addresses where the local-part is not a login.
     */
    private static final Pattern LOGIN_LIKE_LOCAL_PART = Pattern.compile("^[A-Za-z0-9_-]+$");

    /**
     * Matches GitLab bot noreply email patterns of the form
     * {@code group_{id}_bot_{hash}@noreply.<host>} or
     * {@code project_{id}_bot_{hash}@noreply.<host>}. These cannot be resolved to a user.
     */
    private static final Pattern GITLAB_BOT_NOREPLY_PATTERN =
            Pattern.compile("^(?:group|project)_\\d+_bot[_a-z0-9-]*@noreply\\..+$", Pattern.CASE_INSENSITIVE);

    /**
     * Matches dotted local-parts consisting of two or more tokens separated by dots,
     * where every token starts with a letter. Used for Strategy 4 to whitelist
     * {@code firstname.lastname@tum.de} / {@code first.middle.last@example.com} style
     * institutional addresses while filtering out {@code 42.spam@domain} style noise.
     */
    private static final Pattern FIRSTNAME_LASTNAME_LOCAL_PART =
            Pattern.compile("^[A-Za-z][A-Za-z-]*(?:\\.[A-Za-z][A-Za-z-]*)+$");

    private static final Logger log = LoggerFactory.getLogger(CommitAuthorResolver.class);

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
    public Long resolveByEmail(@Nullable String email, @Nullable Long providerId) {
        if (email == null || email.isBlank()) {
            return null;
        }

        // Strategy 1: direct email match (provider-scoped if available)
        Long id;
        if (providerId != null) {
            id = userRepository
                    .findByEmailAndProviderId(email, providerId)
                    .map(User::getId)
                    .orElse(null);
        } else {
            id = userRepository.findByEmail(email).map(User::getId).orElse(null);
        }
        if (id != null) {
            return id;
        }

        // Strategy 2: parse GitHub noreply email → extract login → match by login
        String login = extractLoginFromNoreply(email);
        if (login != null) {
            if (providerId != null) {
                return userRepository
                        .findByLoginAndProviderId(login, providerId)
                        .map(User::getId)
                        .orElse(null);
            }
            return userRepository.findByLogin(login).map(User::getId).orElse(null);
        }

        // Strategy 3: email local-part as login (TUM/LRZ pattern ef56ghi@mytum.de → "ef56ghi").
        // Skips bot noreply addresses and local-parts containing dots/plus so that
        // firstname.lastname@tum.de or user+tag@domain do not produce false matches.
        if (GITLAB_BOT_NOREPLY_PATTERN.matcher(email).matches()) {
            return null;
        }
        int atIndex = email.indexOf('@');
        if (atIndex > 0) {
            String localPart = email.substring(0, atIndex);
            if (LOGIN_LIKE_LOCAL_PART.matcher(localPart).matches()) {
                if (providerId != null) {
                    Long resolved = userRepository
                            .findByLoginAndProviderId(localPart, providerId)
                            .map(User::getId)
                            .orElse(null);
                    if (resolved != null) {
                        return resolved;
                    }
                } else {
                    Long resolved = userRepository
                            .findByLogin(localPart)
                            .map(User::getId)
                            .orElse(null);
                    if (resolved != null) {
                        return resolved;
                    }
                }
            }

            // Strategy 4: firstname.lastname@tum.de → match against User.name
            // ("Firstname Lastname"). Only acts when exactly one candidate matches
            // to avoid false positives across students with similar names.
            if (FIRSTNAME_LASTNAME_LOCAL_PART.matcher(localPart).matches()) {
                String displayName = dottedLocalPartToDisplayName(localPart);
                if (displayName != null) {
                    List<User> candidates = providerId != null
                            ? userRepository.findAllByNameAndProviderId(displayName, providerId)
                            : userRepository.findAllByName(displayName);
                    if (candidates.size() == 1) {
                        return candidates.get(0).getId();
                    }
                    if (candidates.size() > 1) {
                        log.debug(
                                "Skipped display-name match: ambiguous candidates for email={}, candidates={}",
                                email,
                                candidates.size());
                    }
                }
            }
        }

        return null;
    }

    /**
     * Resolve by email and, when the resolution reaches strategy 3 or 4, opportunistically
     * backfill {@link User#getEmail()} with the supplied address if the user record currently
     * has no email set.
     * <p>
     * High-confidence path only: the backfill fires only for
     * <ul>
     *   <li>non-noreply, non-bot addresses whose local-part is either a login
     *       (e.g. {@code cd34efg@mytum.de}) or a firstname.lastname pattern
     *       (e.g. {@code jonas.beispiel@tum.de}); and</li>
     *   <li>resolutions obtained through the heuristic strategies — direct email
     *       match (strategy 1) already implies the email is stored, and GitHub
     *       noreply matches (strategy 2) intentionally do not leak the noreply
     *       address into the user record.</li>
     * </ul>
     * Idempotent: {@link UserRepository#backfillEmailIfNull} only writes when the
     * current email is {@code NULL}, so repeated sync runs cannot corrupt data.
     *
     * @param email      the commit author/committer email
     * @param providerId the provider to scope the lookup to
     * @return the user's database ID, or {@code null} if no match is found
     */
    @Nullable
    public Long resolveAndBackfillByEmail(@Nullable String email, @Nullable Long providerId) {
        Long userId = resolveByEmail(email, providerId);
        if (userId == null) {
            return null;
        }
        if (email == null || email.isBlank()) {
            return userId;
        }
        if (!isBackfillEligible(email)) {
            return userId;
        }
        try {
            userRepository.backfillEmailIfNull(userId, email.toLowerCase(Locale.ROOT));
        } catch (RuntimeException e) {
            // Never let an email backfill failure abort commit sync: the authoritative
            // work (user_id resolution) is already done. Log and move on.
            log.debug(
                    "Failed to backfill user email: userId={}, error={}",
                    userId,
                    e.getClass().getSimpleName());
        }
        return userId;
    }

    /**
     * Decides whether {@code email} is safe to persist into {@link User#getEmail()}.
     * Excludes noreply / bot addresses and anything that is not a plausible
     * institutional identifier. Kept package-private for testability.
     */
    static boolean isBackfillEligible(@Nullable String email) {
        if (email == null || email.isBlank()) {
            return false;
        }
        int atIndex = email.indexOf('@');
        if (atIndex <= 0 || atIndex == email.length() - 1) {
            return false;
        }
        // GitHub noreply (Strategy 2): never leak a synthetic noreply address into the user record.
        if (GITHUB_NOREPLY_PATTERN.matcher(email).matches()) {
            return false;
        }
        // GitLab bot noreply.
        if (GITLAB_BOT_NOREPLY_PATTERN.matcher(email).matches()) {
            return false;
        }
        String localPart = email.substring(0, atIndex);
        // Only backfill when local-part is a login-shape (strategy 3 territory)
        // or firstname.lastname (strategy 4 territory). Anything else is too
        // noisy (marketing list addresses, shared mailboxes, etc.).
        return (LOGIN_LIKE_LOCAL_PART.matcher(localPart).matches()
                || FIRSTNAME_LASTNAME_LOCAL_PART.matcher(localPart).matches());
    }

    /**
     * Convert a dotted email local-part such as {@code jonas.beispiel} into a
     * display-name candidate {@code "Jonas Beispiel"}. Capitalises each dot-separated
     * token, lower-cases the remainder, and joins with a single space. Returns
     * {@code null} for inputs that are blank or contain empty tokens.
     */
    @Nullable
    static String dottedLocalPartToDisplayName(@Nullable String localPart) {
        if (localPart == null || localPart.isBlank() || localPart.indexOf('.') < 0) {
            return null;
        }
        // Reject leading/trailing dots outright — String.split drops trailing empty
        // tokens, so we have to check explicitly.
        if (localPart.charAt(0) == '.' || localPart.charAt(localPart.length() - 1) == '.') {
            return null;
        }
        // Use split with limit=-1 to keep any internal empty tokens (e.g. "a..b").
        String[] tokens = localPart.split("\\.", -1);
        StringBuilder out = new StringBuilder(localPart.length() + tokens.length);
        for (int i = 0; i < tokens.length; i++) {
            String token = tokens[i];
            if (token.isEmpty()) {
                return null;
            }
            if (i > 0) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(token.charAt(0)));
            if (token.length() > 1) {
                out.append(token.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return out.toString();
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
     * Extracts the GitHub login from a noreply email address.
     *
     * @param email the email to parse
     * @return the extracted login, or {@code null} if the email doesn't match noreply patterns
     */
    @Nullable
    static String extractLoginFromNoreply(String email) {
        Matcher matcher = GITHUB_NOREPLY_PATTERN.matcher(email);
        return matcher.matches() ? matcher.group(1) : null;
    }
}
