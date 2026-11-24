package de.tum.cit.aet.hephaestus.core.auth.consent;

import static org.mockito.Mockito.mock;

import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PrivacyJournal;
import org.springframework.context.ApplicationContext;

/**
 * A {@link ConsentService} for a named study, built over the context's real ledger so tests in other
 * packages share the cached application context instead of configuring one with a study.
 */
public final class ResearchConsentTestSupport {

    private ResearchConsentTestSupport() {}

    public static ConsentService forOrganization(ApplicationContext context, String organization) {
        return new ConsentService(
                context.getBean(ConsentDecisionRepository.class),
                context.getBean(AccountRepository.class),
                new ConsentProperties(organization),
                mock(PrivacyJournal.class));
    }

    public static String wordingVersion() {
        return ConsentService.WORDING_VERSION;
    }
}
