package de.tum.cit.aet.hephaestus.core.auth.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.AccountService;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEvent;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventLogger;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountFeatureRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityLink;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountPreferencesQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountWorkspaceMembershipQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountWorkspaceMembershipQuery.WorkspaceMembershipView;
import de.tum.cit.aet.hephaestus.core.auth.spi.GitProviderRegistry;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

class AccountExportServiceTest extends BaseUnitTest {

    private static final Long ACCOUNT_ID = 42L;
    private static final Long OTHER_ACCOUNT_ID = 99L;

    private final Clock clock = Clock.fixed(Instant.parse("2026-05-29T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void assemble_includesOwnData_andExcludesTokensAndOtherUsers() {
        AccountService accountService = mock(AccountService.class);
        AccountFeatureRepository featureRepo = mock(AccountFeatureRepository.class);
        AuthEventRepository authEventRepo = mock(AuthEventRepository.class);
        AccountWorkspaceMembershipQuery membershipQuery = mock(AccountWorkspaceMembershipQuery.class);
        AccountPreferencesQuery preferencesQuery = mock(AccountPreferencesQuery.class);
        GitProviderRegistry gitProviderRegistry = mock(GitProviderRegistry.class);

        Account account = new Account("Ada Lovelace");
        setId(account, ACCOUNT_ID);
        account.setPrimaryEmail("ada@example.com");
        account.setAppRole(Account.AppRole.USER);
        account.setStatus(Account.Status.ACTIVE);
        account.setCreatedAt(clock.instant());

        IdentityLink link = new IdentityLink();
        link.setProviderId(55L);
        when(gitProviderRegistry.providerTypeName(55L)).thenReturn("GITLAB");
        link.setSubject("123");
        link.setUsernameAtSignup("ada");
        link.setEmailAtSignup("ada@signup.example.com");
        link.setDisplayName("Ada");

        when(accountService.requireById(ACCOUNT_ID)).thenReturn(account);
        when(accountService.activeIdentities(ACCOUNT_ID)).thenReturn(List.of(link));
        when(featureRepo.findFlagsByAccountId(ACCOUNT_ID)).thenReturn(List.of("mentor_access"));
        when(authEventRepo.findByAccountSince(eq(ACCOUNT_ID), any())).thenReturn(List.of());
        when(membershipQuery.membershipsForAccount(ACCOUNT_ID))
                .thenReturn(List.of(new WorkspaceMembershipView(7L, "tum-ase", "TUM ASE", "MEMBER", 314L)));
        when(preferencesQuery.preferencesForAccount(ACCOUNT_ID))
                .thenReturn(Optional.of(new AccountPreferencesQuery.PreferencesView(true, false)));

        ExportBundleAssembler assembler = new ExportBundleAssembler(
                accountService,
                featureRepo,
                authEventRepo,
                membershipQuery,
                preferencesQuery,
                gitProviderRegistry,
                clock);

        ExportBundle bundle = assembler.assemble(ACCOUNT_ID);

        assertThat(bundle.schemaVersion()).isEqualTo(ExportBundle.SCHEMA_VERSION);
        assertThat(bundle.account().id()).isEqualTo(ACCOUNT_ID);
        assertThat(bundle.account().primaryEmail()).isEqualTo("ada@example.com");
        assertThat(bundle.identities()).singleElement().satisfies(i -> {
            assertThat(i.provider()).isEqualTo("GITLAB");
            assertThat(i.usernameAtSignup()).isEqualTo("ada");
        });
        assertThat(bundle.workspaceMemberships()).singleElement().satisfies(m -> {
            assertThat(m.slug()).isEqualTo("tum-ase");
            assertThat(m.role()).isEqualTo("MEMBER");
        });
        assertThat(bundle.featureFlags()).containsExactly("mentor_access");
        assertNotNull(bundle.preferences());
        assertThat(bundle.preferences().participateInResearch()).isTrue();
        assertThat(bundle.preferences().practiceFeedbackDeliveryEnabled()).isFalse();

        String json = new ObjectMapper().writeValueAsString(bundle);
        assertThat(json).contains("\"ada@example.com\"", "tum-ase", "mentor_access");
        assertThat(json.toLowerCase())
                .as("export bundle must never disclose tokens / credentials / signing keys")
                .doesNotContain("access_token")
                .doesNotContain("refresh_token")
                .doesNotContain("private_key")
                .doesNotContain("client_secret")
                .doesNotContain("password");
    }

    @Test
    void status_foreignId_returnsEmpty_soControllerAnswers404() {
        AccountExportRepository repo = mock(AccountExportRepository.class);
        AccountExportService service = newService(repo);

        when(repo.findByIdAndAccountId(1000L, ACCOUNT_ID)).thenReturn(Optional.empty());

        Optional<?> result = service.status(1000L, ACCOUNT_ID);

        assertThat(result).isEmpty();
        verify(repo).findByIdAndAccountId(1000L, ACCOUNT_ID);
    }

    @Test
    void download_foreignId_returnsEmpty() {
        AccountExportRepository repo = mock(AccountExportRepository.class);
        AccountExportService service = newService(repo);
        when(repo.findByIdAndAccountId(1000L, OTHER_ACCOUNT_ID)).thenReturn(Optional.empty());

        assertThat(service.downloadPayload(1000L, OTHER_ACCOUNT_ID)).isEmpty();
    }

    @Test
    void download_notReady_returnsEmpty() {
        AccountExportRepository repo = mock(AccountExportRepository.class);
        AccountExportService service = newService(repo);

        AccountExport pending = new AccountExport(ACCOUNT_ID);
        pending.setStatus(AccountExport.Status.PROCESSING);
        when(repo.findByIdAndAccountId(5L, ACCOUNT_ID)).thenReturn(Optional.of(pending));

        assertThat(service.downloadPayload(5L, ACCOUNT_ID)).isEmpty();
    }

    @Test
    void download_expired_returnsEmpty() {
        AccountExportRepository repo = mock(AccountExportRepository.class);
        AccountExportService service = newService(repo);

        AccountExport ready = new AccountExport(ACCOUNT_ID);
        ready.setStatus(AccountExport.Status.READY);
        ready.setPayload("{}".getBytes());
        ready.setExpiresAt(Instant.parse("2026-05-29T11:00:00Z")); // before fixed clock now
        when(repo.findByIdAndAccountId(6L, ACCOUNT_ID)).thenReturn(Optional.of(ready));

        assertThat(service.downloadPayload(6L, ACCOUNT_ID)).isEmpty();
    }

    @Test
    void download_readyAndOwned_returnsPayload() {
        AccountExportRepository repo = mock(AccountExportRepository.class);
        AccountExportService service = newService(repo);

        AccountExport ready = new AccountExport(ACCOUNT_ID);
        ready.setStatus(AccountExport.Status.READY);
        ready.setPayload("{\"ok\":true}".getBytes());
        ready.setExpiresAt(Instant.parse("2026-05-31T12:00:00Z")); // after fixed clock now
        when(repo.findByIdAndAccountId(7L, ACCOUNT_ID)).thenReturn(Optional.of(ready));

        assertThat(service.downloadPayload(7L, ACCOUNT_ID)).contains("{\"ok\":true}".getBytes());
    }

    @Test
    void requestExport_persistsPendingRow_auditsAndHandsOff() {
        AccountExportRepository repo = mock(AccountExportRepository.class);
        ExportGenerationWorker worker = mock(ExportGenerationWorker.class);
        AuthEventLogger logger = mock(AuthEventLogger.class);
        AuthEventLogger.Draft draft = mock(AuthEventLogger.Draft.class);
        lenient().when(logger.event(any(), any())).thenReturn(draft);
        lenient().when(draft.account(any())).thenReturn(draft);
        lenient().when(draft.details(any())).thenReturn(draft);

        when(repo.saveAndFlush(any(AccountExport.class))).thenAnswer(inv -> {
            AccountExport e = inv.getArgument(0);
            setId(e, 1234L);
            return e;
        });

        // No active transaction in a unit test → TransactionCallbacks.afterCommit runs the handoff inline.
        AccountExportService service = new AccountExportService(repo, worker, logger, clock);

        AccountExport created = service.requestExport(ACCOUNT_ID);

        assertThat(created.getId()).isEqualTo(1234L);
        assertThat(created.getStatus()).isEqualTo(AccountExport.Status.PENDING);
        verify(logger).event(AuthEvent.EventType.EXPORT_REQUESTED, AuthEvent.Result.SUCCESS);
        verify(worker).generate(1234L, ACCOUNT_ID);
    }

    @Test
    void requestExport_rejectsWhenInFlightExportExists() {
        AccountExportRepository repo = mock(AccountExportRepository.class);
        ExportGenerationWorker worker = mock(ExportGenerationWorker.class);
        AuthEventLogger logger = mock(AuthEventLogger.class);
        when(repo.existsByAccountIdAndStatusIn(eq(ACCOUNT_ID), any())).thenReturn(true);

        AccountExportService service = new AccountExportService(repo, worker, logger, clock);

        assertThatExceptionOfType(ResponseStatusException.class)
                .isThrownBy(() -> service.requestExport(ACCOUNT_ID))
                .satisfies(ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        verify(repo, never()).save(any());
        verify(worker, never()).generate(any(), any());
    }

    private AccountExportService newService(AccountExportRepository repo) {
        return new AccountExportService(repo, mock(ExportGenerationWorker.class), mock(AuthEventLogger.class), clock);
    }

    private static void setId(Object entity, Long id) {
        try {
            var field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not set id via reflection", e);
        }
    }
}
