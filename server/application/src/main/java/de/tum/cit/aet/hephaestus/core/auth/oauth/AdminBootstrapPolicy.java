package de.tum.cit.aet.hephaestus.core.auth.oauth;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.AuthProperties;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Decides whether a federated login should be promoted to the instance super-admin role
 * ({@code APP_ADMIN}) based on the {@code hephaestus.auth.bootstrap-admins} allowlist.
 *
 * <p>The allowlist is the operator-scoped source of truth for "who runs this instance" — it is
 * deliberately NOT the per-workspace role model (those derive from SCM team / WorkspaceMembership).
 * Each entry is {@code <registrationId>:<who>}, where {@code who} is either:
 *
 * <ul>
 *   <li><b>{@code @username}</b> (recommended, e.g. {@code gitlab:@m.mustermann}) — matched
 *       against the git login the user authenticates with. The {@code registrationId} prefix must
 *       equal a real provider's id (the env-seeded GitLab provider is {@code gitlab}, GitHub is
 *       {@code github}). Readable and the right default on institutional providers (managed GitLab
 *       handles are bound to a directory identity and not recycled).
 *       This is NOT email-matching / nOAuth: the operator must complete OAuth and authenticate as the
 *       account that currently holds that handle. The only residual risk is <em>username reclaim</em>
 *       (a relinquished public-provider handle later re-registered by someone else), which is why —</li>
 *   <li><b>{@code subject}</b> (e.g. {@code github:1234567}) — the IdP-stable numeric provider id, the
 *       reclaim-proof option, recommended on public providers like github.com.</li>
 * </ul>
 *
 * <p>Pure decision only — it never touches the DB. The caller ({@link AccountProvisioningService})
 * owns the (idempotent, promote-only) mutation inside the login transaction, so the role lands before
 * the JWT is minted. Empty/unset allowlist → fail-closed (no admin path exists).
 */
@ConditionalOnServerRole
@Component
@WorkspaceAgnostic("Instance-admin bootstrap is keyed by (provider, identity); not workspace-scoped")
public class AdminBootstrapPolicy {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrapPolicy.class);

    /** {@code registrationId:subject} entries (stable numeric id match). */
    private final Set<String> subjectKeys;

    /** {@code registrationId:username} entries, username lower-cased for case-insensitive match. */
    private final Set<String> usernameKeys;

    public AdminBootstrapPolicy(AuthProperties properties) {
        Set<String> subjects = new LinkedHashSet<>();
        Set<String> usernames = new LinkedHashSet<>();
        parse(properties.bootstrapAdmins(), subjects, usernames);
        this.subjectKeys = Set.copyOf(subjects);
        this.usernameKeys = Set.copyOf(usernames);
        if (!subjects.isEmpty() || !usernames.isEmpty()) {
            log.info(
                    "auth.bootstrap: instance-admin allowlist configured ({} by-username, {} by-subject)",
                    usernameKeys.size(),
                    subjectKeys.size());
        }
    }

    /** Whether any allowlist entry is configured. */
    public boolean isConfigured() {
        return !(subjectKeys.isEmpty() && usernameKeys.isEmpty());
    }

    /**
     * @return {@code true} iff this login matches the allowlist by stable subject OR by git login.
     *     Empty allowlist or a null registration returns {@code false} (fail-closed).
     */
    public boolean shouldPromote(@Nullable String registrationId, @Nullable String subject, @Nullable String login) {
        if (!isConfigured() || registrationId == null) {
            return false;
        }
        if (subject != null && subjectKeys.contains(registrationId + ":" + subject)) {
            return true;
        }
        return (login != null
                && !login.isBlank()
                && usernameKeys.contains(registrationId + ":" + login.toLowerCase(Locale.ROOT)));
    }

    private static void parse(List<String> raw, Set<String> subjects, Set<String> usernames) {
        if (raw == null) {
            return;
        }
        for (String entry : raw) {
            if (entry == null) {
                continue;
            }
            String trimmed = entry.trim();
            int sep = trimmed.indexOf(':');
            // Require a non-blank provider AND a non-blank id/handle; drop malformed entries (blank,
            // no colon, "provider:" or ":who") rather than fail boot — but log it.
            if (sep <= 0 || sep == trimmed.length() - 1) {
                if (!trimmed.isEmpty()) {
                    log.warn(
                            "auth.bootstrap: ignoring malformed bootstrap-admins entry (expected 'provider:@username' or 'provider:subject')");
                }
                continue;
            }
            String provider = trimmed.substring(0, sep);
            String who = trimmed.substring(sep + 1);
            if (who.startsWith("@")) {
                String username = who.substring(1);
                if (!username.isBlank()) {
                    usernames.add(provider + ":" + username.toLowerCase(Locale.ROOT));
                }
            } else {
                subjects.add(provider + ":" + who);
            }
        }
    }
}
