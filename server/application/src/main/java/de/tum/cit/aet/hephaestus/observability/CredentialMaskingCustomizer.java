package de.tum.cit.aet.hephaestus.observability;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;

/**
 * Masks credential-shaped values in every structured log line, as {@code logging.structured.json.customizer}.
 *
 * <p>This is a last line of defence for the credential formats below, not redaction. A name, an email address, a
 * prompt or a provider body passes through unchanged; the call site must not log it. The value patterns are the ones
 * the earlier Logstash encoder applied, so moving to Boot's formatter removes none of them.
 */
public class CredentialMaskingCustomizer implements StructuredLoggingJsonMembersCustomizer<Object> {

    private static final String MASK = "***";

    private static final Set<String> SECRET_FIELDS = Set.of(
            "password",
            "secret",
            "token",
            "access_token",
            "accessToken",
            "refresh_token",
            "refreshToken",
            "authorization",
            "Authorization",
            "api_key",
            "apiKey",
            "client_secret",
            "clientSecret",
            "cookie",
            "Cookie",
            "set-cookie",
            "Set-Cookie",
            "x-api-key",
            "X-Api-Key",
            "private-token",
            "PRIVATE-TOKEN",
            "Private-Token",
            "x-gitlab-token",
            "X-Gitlab-Token",
            "privateKey",
            "private_key");

    // Order matters where formats overlap: "Bearer ghp_…" must lose the whole credential to the first rule.
    private static final List<Mask> VALUE_MASKS = List.of(
            new Mask(Pattern.compile("(?i)(Bearer|Basic)\\s+[A-Za-z0-9._~+/=-]+"), "$1 " + MASK),
            new Mask(Pattern.compile("glpat-[A-Za-z0-9_-]+"), "glpat-" + MASK),
            new Mask(Pattern.compile("(github_pat_|gh[pousr]_)[A-Za-z0-9_]+"), "$1" + MASK),
            new Mask(Pattern.compile("(xox[abeprs]-|xapp-)[A-Za-z0-9-]+"), "$1" + MASK),
            // A JWT: three base64url segments, the first a JSON header ("eyJ" is "{\"" encoded).
            new Mask(Pattern.compile("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]*"), MASK),
            // Credentials in a URL's userinfo, as in https://user:secret@host/.
            new Mask(Pattern.compile("([a-zA-Z][a-zA-Z0-9+.-]*://)[^/\\s:@\"']+:[^/\\s@\"']+@"), "$1" + MASK + "@"),
            new Mask(
                    Pattern.compile("(?i)([?&](?:token|access_token|refresh_token|client_secret|api_key"
                            + "|X-Amz-Credential|X-Amz-Signature|X-Amz-Security-Token)=)[^&\\s\"']+"),
                    "$1" + MASK));

    @Override
    public void customize(JsonWriter.Members<Object> members) {
        JsonWriter.ValueProcessor<Object> masking = CredentialMaskingCustomizer::mask;
        members.applyingValueProcessor(masking);
    }

    private static @Nullable Object mask(JsonWriter.MemberPath path, @Nullable Object value) {
        if (value == null) return null;
        String name = path.name();
        if (name != null && SECRET_FIELDS.contains(name)) return MASK;
        if (!(value instanceof CharSequence text)) return value;
        return maskCredentials(text.toString());
    }

    /** The text with every credential format this customizer knows replaced by its mask. */
    public static String maskCredentials(String text) {
        String masked = text;
        for (Mask rule : VALUE_MASKS) {
            masked = rule.pattern().matcher(masked).replaceAll(rule.replacement());
        }
        return masked;
    }

    private record Mask(Pattern pattern, String replacement) {}
}
