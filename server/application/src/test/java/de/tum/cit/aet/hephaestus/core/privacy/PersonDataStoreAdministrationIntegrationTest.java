package de.tum.cit.aet.hephaestus.core.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonScope;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.ObjectMapper;

class PersonDataStoreAdministrationIntegrationTest extends BaseIntegrationTest {
    @Autowired
    private PersonDataRequestRepository requests;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private PersonDataService service;

    @Test
    void erasesOnlyTheExactStepActorAndKeepsCountsTimesAndAnotherAdministratorsAttribution() {
        databaseTestUtils.cleanDatabase();
        var time = Instant.parse("2026-10-02T12:00:00Z");
        var request = new PersonDataRequest();
        request.startErasure(null);
        request.complete(time);
        request.setCompletedJson(mapper.writeValueAsString(Map.of(
                "first", new PersonDataStoreReceipt(12, time, 42L),
                "second", new PersonDataStoreReceipt(7, time, 84L))));
        requests.saveAndFlush(request);
        var contributor = new PersonDataStoreAdministrationContributor(jdbc, mapper);
        var selectedScope = new PersonScope(42L, List.of(), List.of());
        var selected = contributor.select(selectedScope);
        assertThat(selected.rows()).hasSize(1);
        var exported = contributor.export(selected, selectedScope);
        assertThat(exported).hasSize(1);
        assertThat(exported.getFirst().path("receipt").path("count").asLong()).isEqualTo(12);
        assertThat(contributor.erase(selected, selectedScope)).isEqualTo(1);
        assertThat(contributor.erase(selected, selectedScope)).isZero();
        var receipts = PersonDataStoreReceipt.read(
                mapper, requests.findById(request.getId()).orElseThrow().getCompletedJson());
        assertThat(receipts.get("first")).isEqualTo(new PersonDataStoreReceipt(12, time, null));
        assertThat(receipts.get("second")).isEqualTo(new PersonDataStoreReceipt(7, time, 84L));
    }

    @Test
    void shouldExpireAnExpiredPreviewAndDropItsDataWhenPreviewsExpire() {
        databaseTestUtils.cleanDatabase();
        var expired = new PersonDataRequest();
        expired.setScopeJson("{\"accountId\":5}");
        expired.setSelectionsJson("{}");
        expired.setExpiresAt(Instant.now().minusSeconds(60));
        var open = new PersonDataRequest();
        open.setScopeJson("{\"accountId\":6}");
        open.setSelectionsJson("{}");
        requests.saveAndFlush(expired);
        requests.saveAndFlush(open);

        service.expirePreviews();

        var after = requests.findById(expired.getId()).orElseThrow();
        assertThat(after.getState()).isEqualTo(PersonDataRequest.State.EXPIRED);
        assertThat(after.getScopeJson()).isNull();
        assertThat(after.getSelectionsJson()).isNull();
        var untouched = requests.findById(open.getId()).orElseThrow();
        assertThat(untouched.getState()).isEqualTo(PersonDataRequest.State.PREVIEW);
        assertThat(untouched.getScopeJson()).isNotNull();
    }
}
