package de.tum.cit.aet.hephaestus.core.privacy.spi;

import de.tum.cit.aet.hephaestus.core.security.ScmOrigin;
import org.jspecify.annotations.Nullable;

/** Exact provider instance and native subject; no display attribution or account-subject inference. */
public record PersonCopyIdentity(
        String providerType,
        String providerOrigin,
        String subject,
        @Nullable String teamId) {
    public PersonCopyIdentity {
        if (!java.util.Set.of("GITHUB", "GITLAB", "SLACK", "OUTLINE").contains(providerType)
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
