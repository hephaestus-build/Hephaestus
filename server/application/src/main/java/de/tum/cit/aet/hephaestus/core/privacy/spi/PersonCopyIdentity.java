package de.tum.cit.aet.hephaestus.core.privacy.spi;

import de.tum.cit.aet.hephaestus.core.security.ScmOrigin;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** Exact provider instance and native subject; no display attribution or account-subject inference. */
public record PersonCopyIdentity(
        String providerType,
        String providerOrigin,
        String subject,
        @Nullable String teamId) {
    /** The provider types a capture copies words from. An OIDC sign-in issuer is none of them. */
    public static final Set<String> PROVIDER_TYPES = Set.of("GITHUB", "GITLAB", "SLACK", "OUTLINE");

    public PersonCopyIdentity {
        if (!PROVIDER_TYPES.contains(providerType)
                || providerOrigin.isBlank()
                || subject.isBlank()
                || subject.length() > 255
                || (teamId != null && teamId.isBlank())) {
            throw new IllegalArgumentException("A copy requires an exact provider instance and native subject");
        }
        providerOrigin = ScmOrigin.of(providerOrigin)
                .orElseThrow(() -> new IllegalArgumentException("A copy requires a valid provider origin"));
        if ((providerType.equals("SLACK") && teamId == null)
                || (!providerType.equals("SLACK") && !providerType.equals("OUTLINE") && teamId != null)) {
            throw new IllegalArgumentException("A Slack copy requires its native workspace key");
        }
    }
}
