package de.tum.cit.aet.hephaestus.core.auth.oauth;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;

/**
 * Adapts GitLab's authenticated /user email attestation to the common verified-contact contract.
 *
 * @see <a href="https://docs.gitlab.com/api/users/#retrieve-the-current-user">GitLab current-user response</a>
 * @see <a href="https://github.com/gitlabhq/gitlabhq/blob/master/app/models/user.rb">GitLab primary_email_verified?</a>
 */
public final class GitLabEmailAttributes {
    private GitLabEmailAttributes() {}

    public static Map<String, Object> withVerification(Map<String, Object> attributes) {
        var result = new HashMap<>(attributes);
        result.put("email_verified", hasConfirmedPrimaryEmail(attributes));
        return result;
    }

    private static boolean hasConfirmedPrimaryEmail(Map<String, Object> attributes) {
        if (!(attributes.get("email") instanceof String email)
                || email.isBlank()
                // GitLab excludes its synthetic OAuth placeholder even when the account is confirmed.
                || email.startsWith("temp-email-for-oauth")
                || !(attributes.get("confirmed_at") instanceof String confirmedAt)) return false;
        try {
            Instant.parse(confirmedAt);
            return true;
        } catch (DateTimeParseException invalid) {
            return false;
        }
    }
}
