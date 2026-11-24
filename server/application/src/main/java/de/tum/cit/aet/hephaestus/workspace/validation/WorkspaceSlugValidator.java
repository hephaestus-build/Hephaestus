package de.tum.cit.aet.hephaestus.workspace.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates workspace slugs for controller path variables and DTO fields.
 */
public class WorkspaceSlugValidator implements ConstraintValidator<WorkspaceSlug, String> {

    public static final int MIN_LENGTH = 3;
    public static final int MAX_LENGTH = 51;
    public static final String LABEL_PATTERN = "^(?!.*--)[a-z0-9][a-z0-9-]{1,49}[a-z0-9]$";
    private static final Pattern LABEL = Pattern.compile(LABEL_PATTERN);

    /**
     * The slug of any workspace that a path can name: a team slug, or a personal space's {@code me--} and 16 base32
     * characters. A team slug never contains {@code --}, so the two never meet, and a label with {@code --} in its
     * third and fourth place is reserved in DNS, so a personal space never gets a host name.
     */
    public static final String ADDRESS_PATTERN = "^(?:me--[a-z2-7]{16}|(?!.*--)[a-z0-9][a-z0-9-]{1,49}[a-z0-9])$";

    private static final Set<String> RESERVED = Set.of(
            "www",
            "api",
            "docs",
            "admin",
            "auth",
            "login",
            "mail",
            "status",
            "staging",
            "preview",
            "info",
            "marketing",
            "sales",
            "support",
            "abuse",
            "noc",
            "security",
            "postmaster",
            "hostmaster",
            "usenet",
            "news",
            "webmaster",
            "uucp",
            "ftp",
            "w",
            "settings",
            "integrations",
            "consent",
            "legal",
            "privacy",
            "terms",
            "unsubscribe",
            "landing",
            "oauth",
            "oauth2",
            "webhooks",
            "actuator",
            "livez",
            "readyz",
            "error",
            "swagger-ui",
            "v3",
            "identity-providers",
            "workspaces",
            "workers",
            "mentor",
            "conversation",
            "feedback",
            "practices",
            "activity",
            "about",
            "imprint",
            "audit",
            "catalog",
            "login-providers",
            "models",
            "person-data",
            "surveys",
            "usage",
            "users",
            "onboarding",
            "practice-profile",
            "practices-across-the-workspace",
            "workspace-activity",
            "new",
            "github",
            "gitlab",
            "members",
            "teams",
            "outline",
            "scm",
            "slack",
            "releases",
            "review",
            "reviews",
            "user",
            "observations",
            "runs",
            "work",
            "practice-groups",
            "assets",
            "static",
            "cdn",
            "traefik",
            "grafana",
            "prometheus",
            "nats",
            "postgres",
            "registry",
            "agents",
            "config-audit",
            "connections",
            "contributors",
            "internal",
            "llm",
            "practice-catalog",
            "product-feedback",
            "sync",
            "team",
            "user-view",
            "notifications",
            "providers");

    static Set<String> reservedLabels() {
        return RESERVED;
    }

    public static boolean isAssignable(String value) {
        return LABEL.matcher(value).matches() && !RESERVED.contains(value) && !value.matches("pr[0-9]+");
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true; // validation annotations like @NotBlank should handle nullability
        }
        return isAssignable(value);
    }
}
