package de.tum.cit.aet.hephaestus.agent.usage.fx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.LlmProperties;
import de.tum.cit.aet.hephaestus.agent.usage.fx.EcbFxRateClient.EcbDailyRate;
import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * The worker and webhook tiers must not acquire an outbound dependency on ecb.europa.eu; an ungated
 * bean in this group has crash-looped them before.
 */
class FxRateFetchSchedulerTest extends BaseUnitTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 7, 24);
    private static final Clock CLOCK =
            Clock.fixed(TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
    private static final EcbDailyRate FETCHED = new EcbDailyRate(TODAY, new BigDecimal("1.1377"));

    @Mock
    private EcbFxRateClient client;

    @Mock
    private FxRateLookup lookup;

    /**
     * Stands in for the {@code fx_rate} table: "no row was written" and "the old row is still the old
     * row" are different facts, and a {@code verify(never()).save(any())} cannot tell them apart.
     */
    private final List<FxRate> table = new ArrayList<>();

    private final FxRateRepository repository = mock(FxRateRepository.class, invocation -> switch (invocation
            .getMethod()
            .getName()) {
        case "save", "saveAndFlush" -> {
            FxRate row = invocation.getArgument(0);
            // rate_date is unique, so the saved row replaces whichever row held its date.
            table.removeIf(existing -> existing.getRateDate().equals(row.getRateDate()));
            table.add(row);
            yield row;
        }
        case "findByRateDate" -> {
            LocalDate date = invocation.getArgument(0);
            yield table.stream().filter(r -> r.getRateDate().equals(date)).findFirst();
        }
        case "findAll" -> List.copyOf(table);
        default -> Optional.empty();
    });

    private FxRate storedRate(LocalDate date, String usdPerEur) {
        FxRate rate = new FxRate();
        rate.setRateDate(date);
        rate.setUsdPerEur(new BigDecimal(usdPerEur));
        rate.setFetchedAt(Instant.EPOCH);
        table.add(rate);
        return rate;
    }

    private FxRateFetchScheduler scheduler() {
        return new FxRateFetchScheduler(client, repository, lookup, CLOCK);
    }

    @ParameterizedTest(name = "{0} stores a freshly published rate")
    @MethodSource("everyFetchEntryPoint")
    void shouldStoreRateWhenFetchSucceeds(String entryPoint, Consumer<FxRateFetchScheduler> fetch) {
        when(lookup.isEnabled()).thenReturn(true);
        // Only the boot catch-up asks whether a usable rate is already stored.
        lenient().when(lookup.latest()).thenReturn(Optional.empty());
        when(client.fetchLatestUsdRate()).thenReturn(Optional.of(FETCHED));

        fetch.accept(scheduler());

        assertThat(table).hasSize(1);
        FxRate stored = table.getFirst();
        assertThat(stored.getRateDate()).isEqualTo(TODAY);
        // Stored in the ECB's own direction — inverting on write would bake a rounding step into history.
        assertThat(stored.getUsdPerEur()).isEqualByComparingTo("1.1377");
        assertThat(stored.getFetchedAt()).isEqualTo(Instant.now(CLOCK));
    }

    @Test
    @DisplayName("should overwrite the same day's row when the rate is republished")
    void shouldOverwriteTheSameDaysRowWhenTheRateIsRepublished() {
        FxRate existing = storedRate(TODAY, "1.1000");
        when(lookup.isEnabled()).thenReturn(true);
        when(client.fetchLatestUsdRate()).thenReturn(Optional.of(FETCHED));

        scheduler().fetchDaily();

        assertThat(table).containsExactly(existing);
        assertThat(existing.getUsdPerEur()).isEqualByComparingTo("1.1377");
        assertThat(existing.getFetchedAt()).isEqualTo(Instant.now(CLOCK));
    }

    @Test
    @DisplayName("should leave the stored rate untouched when the fetch fails")
    void shouldLeaveTheStoredRateUntouchedWhenTheFetchFails() {
        FxRate yesterday = storedRate(TODAY.minusDays(1), "1.1000");
        when(lookup.isEnabled()).thenReturn(true);
        when(client.fetchLatestUsdRate()).thenReturn(Optional.empty());

        scheduler().fetchDaily();

        assertThat(table).containsExactly(yesterday);
        assertThat(yesterday.getUsdPerEur()).isEqualByComparingTo("1.1000");
        assertThat(yesterday.getFetchedAt()).isEqualTo(Instant.EPOCH);
    }

    @Test
    @DisplayName("should ignore a rate dated in the future")
    void shouldIgnoreRateWhenDatedAfterToday() {
        FxRate yesterday = storedRate(TODAY.minusDays(1), "1.1000");
        when(lookup.isEnabled()).thenReturn(true);
        when(client.fetchLatestUsdRate())
                .thenReturn(Optional.of(new EcbDailyRate(TODAY.plusDays(5), new BigDecimal("1.1377"))));

        scheduler().fetchDaily();

        // A future-dated rate would otherwise become the "latest" and silently reprice every month
        // between now and then.
        assertThat(table).containsExactly(yesterday);
        assertThat(yesterday.getUsdPerEur()).isEqualByComparingTo("1.1000");
    }

    @Test
    @DisplayName("should not fetch at startup when a usable rate is already stored")
    void shouldMakeNoOutboundRequestOnStartupWhenAUsableRateIsAlreadyStored() {
        FxRate existing = storedRate(TODAY, "1.1377");
        when(lookup.isEnabled()).thenReturn(true);
        when(lookup.latest())
                .thenReturn(Optional.of(FxRateInfoDTO.fromEcbRate("EUR", new BigDecimal("1.1377"), TODAY)));

        scheduler().fetchOnStartupIfMissing();

        verifyNoInteractions(client);
        assertThat(table).containsExactly(existing);
    }

    /**
     * An {@code ApplicationReadyEvent} listener that throws aborts {@code SpringApplication.run}. A
     * display-only currency estimate must never be able to do that, so the startup catch-up swallows
     * whatever the lookup throws — an unreachable database here would otherwise crash-loop the server.
     */
    @Test
    @DisplayName("should still finish booting when the startup lookup blows up")
    void shouldNotFailStartupWhenLookupThrows() {
        when(lookup.isEnabled()).thenReturn(true);
        when(lookup.latest()).thenThrow(new DataAccessResourceFailureException("database unreachable"));

        FxRate untouched = storedRate(TODAY.minusDays(1), "1.1000");

        assertThatCode(() -> scheduler().fetchOnStartupIfMissing()).doesNotThrowAnyException();

        assertThat(table).containsExactly(untouched);
        assertThat(untouched.getUsdPerEur()).isEqualByComparingTo("1.1000");
    }

    static Stream<Arguments> everyFetchEntryPoint() {
        return Stream.of(
                Arguments.of("the daily tick", (Consumer<FxRateFetchScheduler>) FxRateFetchScheduler::fetchDaily),
                Arguments.of("the boot catch-up", (Consumer<FxRateFetchScheduler>)
                        FxRateFetchScheduler::fetchOnStartupIfMissing));
    }

    /**
     * Both entry points run against the REAL {@link FxRateLookup} built from the real default
     * {@link LlmProperties}, so what is asserted is the production predicate — an unset property
     * resolves to "off" — and not a stubbed boolean. A {@code isEnabled()} that started returning
     * true, or a {@code freshLatest()} that read the table before checking the currency, fails here.
     */
    @ParameterizedTest(name = "{0} makes no outbound request under the default configuration")
    @MethodSource("everyFetchEntryPoint")
    void shouldStayInertUnderTheDefaultConfiguration(String entryPoint, Consumer<FxRateFetchScheduler> fetch) {
        FxRateLookup realLookup = new FxRateLookup(repository, CLOCK, defaultProperties());
        assertThat(realLookup.isEnabled())
                .as("the shipped default must leave the feature off")
                .isFalse();

        fetch.accept(new FxRateFetchScheduler(client, repository, realLookup, CLOCK));

        verifyNoInteractions(client);
        verifyNoInteractions(repository);
    }

    /** Exactly what Spring binds when {@code hephaestus.llm} is absent from the configuration. */
    private static LlmProperties defaultProperties() {
        return new LlmProperties(
                "", new LlmProperties.Egress(false), new LlmProperties.Fx(LlmProperties.ECB_DAILY_URL));
    }

    private ApplicationContextRunner contextRunner() {
        return new ApplicationContextRunner()
                .withBean(EcbFxRateClient.class, () -> mock(EcbFxRateClient.class))
                .withBean(FxRateRepository.class, () -> mock(FxRateRepository.class))
                .withBean(FxRateLookup.class, () -> mock(FxRateLookup.class))
                .withBean(Clock.class, () -> CLOCK)
                .withUserConfiguration(FxRateFetchScheduler.class);
    }

    static Stream<Arguments> roleGating() {
        return Stream.of(
                Arguments.of(
                        new String[] {RuntimeRole.SERVER_PROPERTY + "=false"}, false, "server role explicitly off"),
                // matchIfMissing=true — zero env vars still boots a full monolith (ADR 0005).
                Arguments.of(new String[0], true, "default single-JVM deployment"));
    }

    @ParameterizedTest(name = "{2}")
    @MethodSource("roleGating")
    void shouldRegisterTheSchedulerOnlyOnTheServerRole(String[] properties, boolean expectBean, String why) {
        contextRunner().withPropertyValues(properties).run(context -> {
            if (expectBean) {
                assertThat(context).as(why).hasSingleBean(FxRateFetchScheduler.class);
            } else {
                assertThat(context).as(why).doesNotHaveBean(FxRateFetchScheduler.class);
            }
        });
    }
}
