package de.tum.cit.aet.hephaestus.integration.scm.domain.organization;

import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrganizationService {

    private final OrganizationRepository organizations;

    public OrganizationService(OrganizationRepository organizations) {
        this.organizations = organizations;
    }

    /** The id of the organization with this immutable native id at a provider, if Hephaestus mirrors it. */
    @Transactional(readOnly = true)
    public Optional<Long> findId(long nativeId, Long providerId) {
        return organizations.findByNativeIdAndProviderId(nativeId, providerId).map(Organization::getId);
    }

    /**
     * Ensure an organization row exists (by stable native id + provider) and keep its login up to date (rename-safe).
     *
     * @param nativeId   the provider's native numeric ID for the organization
     * @param login      the organization login name
     * @param providerId the FK ID of the IdentityProvider entity
     */
    @Transactional
    public Organization upsertIdentity(long nativeId, String login, Long providerId) {
        if (login == null || login.isBlank()) {
            throw new IllegalArgumentException("login required");
        }

        organizations.insertIfAbsent(nativeId, providerId, login);
        Organization organization =
                organizations.findByNativeIdAndProviderId(nativeId, providerId).orElseThrow();
        if (!login.equals(organization.getLogin())) {
            organization.setLogin(login);
        }
        return organization;
    }
}
