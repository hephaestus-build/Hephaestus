package de.tum.cit.aet.hephaestus.agent.mentor.turn;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.catalog.ReasoningEffort;
import de.tum.cit.aet.hephaestus.agent.usage.FundingSource;
import de.tum.cit.aet.hephaestus.agent.usage.LlmPriceSnapshot;
import de.tum.cit.aet.hephaestus.agent.usage.PricingState;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * An admission is read inside the reaper's per-turn transaction, so anything thrown there fails the same row on every
 * later tick: the turn is never billed and its conversation never takes another turn.
 */
class MentorTurnAdmissionTest extends BaseUnitTest {

    private static final LlmPriceSnapshot PRICED = new LlmPriceSnapshot(
            FundingSource.WORKSPACE,
            PricingState.PRICED,
            7L,
            11L,
            new BigDecimal("3.500000"),
            new BigDecimal("15.000000"),
            new BigDecimal("0.123456789012345678"),
            new BigDecimal("3.750000"));

    private static final MentorTurnAdmission ADMITTED = new MentorTurnAdmission(
            "gpt-5-mini",
            PRICED,
            new MentorTurnAdmission.Route("openai-responses", "https://api.example", FundingSource.WORKSPACE, 3L, 5L),
            200_000,
            32_000,
            ReasoningEffort.HIGH);

    @Test
    void shouldReadBackEveryFieldWhenAnAdmissionIsStoredAsJson() {
        JsonNode stored =
                JsonMapper.builder().build().readTree(ADMITTED.toJson().toString());

        assertThat(MentorTurnAdmission.read(stored)).contains(ADMITTED);
    }

    @Test
    void shouldWriteRatesAsStringsWhenAnAdmissionIsWritten() {
        JsonNode price = ADMITTED.toJson().path("price");

        assertThat(price.path("per1mCacheReadUsd").isString()).isTrue();
        assertThat(price.path("per1mCacheReadUsd").asString()).isEqualTo("0.123456789012345678");
    }

    @Test
    void shouldBillUnpricedWhenTheAdmissionCannotBeRead() {
        ObjectNode broken = (ObjectNode) ADMITTED.toJson();
        ((ObjectNode) broken.path("price")).remove("fundingSource");
        ((ObjectNode) broken.path("price")).put("pricingState", "NOT_A_STATE");

        assertThat(MentorTurnAdmission.read(broken)).isEmpty();
        assertThat(MentorTurnAdmission.priceOf(broken)).isEqualTo(LlmPriceSnapshot.unpricedInstance());
    }

    @Test
    void shouldBillUnpricedWhenTheTurnCarriesNoAdmission() {
        assertThat(MentorTurnAdmission.priceOf(null)).isEqualTo(LlmPriceSnapshot.unpricedInstance());
        assertThat(MentorTurnAdmission.priceOf(JsonMapper.builder().build().createObjectNode()))
                .isEqualTo(LlmPriceSnapshot.unpricedInstance());
    }
}
