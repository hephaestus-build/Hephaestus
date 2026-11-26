package de.tum.cit.aet.hephaestus.practices.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataContributor;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentityResolver;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackCopyCleanupCustody;
import de.tum.cit.aet.hephaestus.practices.review.report.ScanReportService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * A copied identity authorizes reads of what its review item kept, so erasure removes it after every review item,
 * result and copy of the practices. An erasure that stops early leaves the authorization in place.
 */
@Tag("unit")
class CopiedIdentityErasureOrderTest {

    @Test
    void shouldEraseCopiedIdentitiesAfterEveryOtherPracticesStore() {
        var stores = new PracticesPersonDataCatalog(
                        mock(NamedParameterJdbcTemplate.class),
                        JsonMapper.builder().build(),
                        mock(FeedbackCopyCleanupCustody.class),
                        mock(PersonIdentityResolver.class),
                        mock(ScanReportService.class))
                .contributors();
        int identities = stores.stream()
                .filter(store -> store.store().equals("review_item_copied_identity"))
                .mapToInt(PersonDataContributor::getOrder)
                .findFirst()
                .orElseThrow();

        assertThat(stores.stream()
                        .filter(store -> !store.store().equals("review_item_copied_identity"))
                        .mapToInt(PersonDataContributor::getOrder))
                .allMatch(order -> order < identities);
    }
}
