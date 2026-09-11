package de.tum.cit.aet.hephaestus.core.auth.consent;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound to {@code hephaestus.consent.*}.
 *
 * <p>{@code researchOrganization} names the organisation that runs a study on this deployment, and
 * its presence is what decides whether there is a study at all. Consent has to be informed, and the
 * identity of the controller is part of that ([EDPB 05/2020] § 3.3), so an instance that cannot name
 * one does not ask: first-login setup omits the question and account settings omit the switch.
 *
 * <p>It is unset by default. Only the deployment that actually runs the study sets it, which is also
 * what keeps the question off the screens of every self-hoster who does not.
 */
@ConfigurationProperties(prefix = "hephaestus.consent")
public record ConsentProperties(@Nullable String researchOrganization) {

    /** The configured name, or {@code null} when this deployment runs no study. */
    public @Nullable String researchProgramme() {
        return researchOrganization == null || researchOrganization.isBlank() ? null : researchOrganization.strip();
    }
}
