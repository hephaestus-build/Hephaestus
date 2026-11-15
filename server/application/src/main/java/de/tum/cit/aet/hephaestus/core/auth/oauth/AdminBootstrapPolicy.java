package de.tum.cit.aet.hephaestus.core.auth.oauth;

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

/** Operator-selected provider subjects are stable; handle allowlist entries can be reclaimed when a provider recycles usernames. */
@ConditionalOnServerRole
@Component
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
                    "auth.bootstrap: instance-admin allowlist configured ({} by-username, {}" + " by-subject)",
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
            // Require a non-blank provider AND a non-blank id/handle; drop malformed entries
            // (blank,
            // no colon, "provider:" or ":who") rather than fail boot — but log it.
            if (sep <= 0 || sep == trimmed.length() - 1) {
                if (!trimmed.isEmpty()) {
                    log.warn("auth.bootstrap: ignoring malformed bootstrap-admins entry (expected"
                            + " 'provider:@username' or 'provider:subject')");
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
