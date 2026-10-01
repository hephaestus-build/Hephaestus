package de.tum.cit.aet.hephaestus.core.privacy.spi;

import org.jspecify.annotations.Nullable;

/** An exact native subject in one provider instance; team scopes Slack subjects. */
public record PersonIdentity(
        long providerId, String subject, @Nullable String teamId) {
    public PersonIdentity {
        if (providerId <= 0
                || subject.isBlank()
                || subject.length() > 255
                || (teamId != null && (teamId.isBlank() || teamId.length() > 255))) {
            throw new IllegalArgumentException("An exact provider id and native subject are required");
        }
    }
}
