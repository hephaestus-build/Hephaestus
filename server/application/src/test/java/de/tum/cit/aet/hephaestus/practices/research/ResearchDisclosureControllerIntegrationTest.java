package de.tum.cit.aet.hephaestus.practices.research;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.auth.consent.ConsentService;
import de.tum.cit.aet.hephaestus.core.auth.consent.ResearchConsentTestSupport;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.jwt.HephaestusJwtIssuer;
import de.tum.cit.aet.hephaestus.core.auth.jwt.TokenConstraints;
import de.tum.cit.aet.hephaestus.core.exception.ProblemType;
import de.tum.cit.aet.hephaestus.core.security.UserViewContextHolder;
import de.tum.cit.aet.hephaestus.practices.research.ResearchDisclosureController.CloseResearchDisclosureDTO;
import de.tum.cit.aet.hephaestus.practices.research.ResearchDisclosureController.RecordResearchRemovalsDTO;
import de.tum.cit.aet.hephaestus.practices.research.ResearchDisclosureController.ReleaseResearchDatasetDTO;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData.Scenario;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.testconfig.RealAuthIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Import;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.json.JsonMapper;

/** Real signed browser/client tokens, native assurance and the actual disclosure/consent database. */
@Import(ReviewIntegrationData.class)
@TestPropertySource(
        properties = {
            "hephaestus.consent.research-organization=AET",
            "hephaestus.auth.passkeys.instance-admin-required=false"
        })
class ResearchDisclosureControllerIntegrationTest extends RealAuthIntegrationTest {
    private static final String BASE = "/admin/research-disclosures";

    @Autowired
    private WebTestClient client;

    @Autowired
    private HephaestusJwtIssuer issuer;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private ConsentService consent;

    @Autowired
    private ReviewIntegrationData data;

    @Autowired
    private ResearchDisclosureService service;

    @Autowired
    private ResearchDisclosureRepository disclosures;

    @Autowired
    private ResearchDisclosureSubjectRepository subjects;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JsonMapper mapper;

    @Value("${hephaestus.auth.cookie-name:__Host-HEPHAESTUS_AT}")
    private String cookieName;

    private Scenario course;
    private Account admin;
    private LocalDate day;
    private UUID existingDataset;
    private String participantKey;

    enum Entry {
        RELEASE,
        CHECK_REMOVALS,
        RECORD_REMOVED,
        CLOSE
    }

    @BeforeEach
    void createDisclosureFixtures() {
        course = data.scenario();
        admin = new Account("Research administrator");
        admin.setAppRole(Account.AppRole.APP_ADMIN);
        admin = accounts.saveAndFlush(admin);
        grant(course.account(), true);
        Instant recorded = Instant.now().minus(2, ChronoUnit.DAYS);
        day = recorded.atOffset(ZoneOffset.UTC).toLocalDate();
        data.result(course, ResultOutcome.NOT_MET, recorded);
        // This is domain fixture setup, not an HTTP allowed-path assurance stand-in.
        existingDataset =
                service.release(workspaceId(), day, day, useUntil(), adminId()).datasetId();
        participantKey = subjects.findForDisclosure(workspaceId(), existingDataset)
                .getFirst()
                .getParticipantKey();
    }

    @ParameterizedTest
    @EnumSource(Entry.class)
    void shouldRequireTheOperatorsOwnFreshPasskeyOnEveryEntry(Entry entry) {
        withdraw();
        long before = researchAuditCount();
        for (String token :
                List.of(token(admin, null), token(admin, Instant.now().minusSeconds(600)))) {
            send(entry, request(entry, token))
                    .expectStatus()
                    .isForbidden()
                    .expectHeader()
                    .doesNotExist(HttpHeaders.CONTENT_DISPOSITION)
                    .expectHeader()
                    .value(HttpHeaders.CACHE_CONTROL, v -> assertThat(v).contains("no-store"))
                    .expectBody()
                    .jsonPath("$.type")
                    .isEqualTo(ProblemType.PASSKEY_REQUIRED.uri().toString());
        }
        assertUnchanged(before);
    }

    @ParameterizedTest
    @EnumSource(Entry.class)
    void shouldRejectAnUnauthenticatedCallerBeforeAnyResearchWrite(Entry entry) {
        withdraw();
        long before = researchAuditCount();
        String csrf = csrf();
        send(
                        entry,
                        client.post()
                                .uri(path(entry))
                                .contentType(MediaType.APPLICATION_JSON)
                                .cookie("__Host-XSRF-TOKEN", csrf)
                                .header("X-XSRF-TOKEN", csrf))
                .expectStatus()
                .isUnauthorized()
                .expectHeader()
                .doesNotExist(HttpHeaders.CONTENT_DISPOSITION)
                .expectBody(Void.class);
        assertUnchanged(before);
    }

    @ParameterizedTest
    @EnumSource(Entry.class)
    void shouldRejectAWorkspaceOwnerWhoIsNotAnInstanceAdmin(Entry entry) {
        withdraw();
        long before = researchAuditCount();
        // The fixture participant owns this workspace but has only the USER instance role.
        send(entry, request(entry, token(course.account(), Instant.now())))
                .expectStatus()
                .isForbidden()
                .expectHeader()
                .doesNotExist(HttpHeaders.CONTENT_DISPOSITION)
                .expectBody(Void.class);
        assertUnchanged(before);
    }

    @ParameterizedTest
    @EnumSource(Entry.class)
    void shouldRejectAnInstalledClientEvenWithAnIssuerSignedPasskeyClaim(Entry entry) {
        withdraw();
        long before = researchAuditCount();
        Instant now = Instant.now();
        UUID sid = UUID.randomUUID();
        // Retain a real client-session parent for the native issued_jwt foreign key.
        jdbc.update(
                """
                INSERT INTO client_session(id,account_id,client_kind,client_id,session_expires_at,auth_time,created_at)
                VALUES (?,?,'BROWSER_EXTENSION','research-test-client',?,?,?)
                """, sid, adminId(), Timestamp.from(now.plusSeconds(3600)), Timestamp.from(now), Timestamp.from(now));
        String installed = issuer.issueForClientSession(
                        adminId(), new TokenConstraints(now.plusSeconds(3600), now, sid, now), "a".repeat(64), null)
                .value();
        send(entry, request(entry, installed))
                .expectStatus()
                .isForbidden()
                .expectHeader()
                .doesNotExist(HttpHeaders.CONTENT_DISPOSITION)
                .expectBody()
                .jsonPath("$.type")
                .isEqualTo(ProblemType.PASSKEY_REQUIRED.uri().toString());
        assertUnchanged(before);
    }

    @ParameterizedTest
    @EnumSource(Entry.class)
    void shouldRejectUserViewHeadersBeforeAnyResearchWrite(Entry entry) {
        withdraw();
        long before = researchAuditCount();
        var request = request(entry, token(admin, Instant.now()))
                .header(
                        UserViewContextHolder.WORKSPACE_HEADER,
                        course.workspace().getWorkspaceSlug())
                .header(
                        UserViewContextHolder.ACCOUNT_HEADER,
                        String.valueOf(course.account().getId()))
                .header(UserViewContextHolder.REASON_HEADER, "Research export is not a support view");
        send(entry, request)
                .expectStatus()
                .isForbidden()
                .expectHeader()
                .doesNotExist(HttpHeaders.CONTENT_DISPOSITION)
                .expectBody(Void.class);
        assertUnchanged(before);
    }

    @Test
    void shouldDownloadOnlyTheSelectedWorkspacesConsentedDataWithAnAtomicReceiptAndAudit() {
        Account nonParticipant = data.member(workspaceId());
        grant(nonParticipant, false);
        var unconsented = data.result(
                new Scenario(course.workspace(), nonParticipant, course.practice(), course.item()),
                ResultOutcome.NOT_MET,
                day.atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(60));
        Scenario other = data.scenario();
        grant(other.account(), true);
        var foreign = data.result(
                other,
                ResultOutcome.NOT_MET,
                day.atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(60));

        var response = send(Entry.RELEASE, request(Entry.RELEASE, token(admin, Instant.now())))
                .expectStatus()
                .isOk()
                .expectHeader()
                .contentType(MediaType.APPLICATION_JSON)
                .expectHeader()
                .value(HttpHeaders.CACHE_CONTROL, v -> assertThat(v).contains("no-store", "private"))
                .expectHeader()
                .valueEquals(HttpHeaders.PRAGMA, "no-cache")
                .expectBody()
                .returnResult();
        byte[] bytes = Objects.requireNonNull(response.getResponseBody());
        ResearchDataset dataset = mapper.readValue(bytes, ResearchDataset.class);
        UUID datasetId = UUID.fromString(dataset.datasetId());
        assertThat(ContentDisposition.parse(Objects.requireNonNull(
                                response.getResponseHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)))
                        .getFilename())
                .isEqualTo("hephaestus-research-" + datasetId + ".json");
        assertThat(dataset.schema()).isEqualTo(ResearchDataset.SCHEMA);
        assertThat(dataset.research())
                .isEqualTo(new ResearchDataset.Research("AET", ResearchConsentTestSupport.wordingVersion()));
        assertThat(dataset.results()).hasSize(1);
        assertThat(dataset.problems()).hasSize(1);
        assertThat(dataset.participants()).hasSize(1);
        assertThat(new String(bytes, StandardCharsets.UTF_8))
                .doesNotContain(
                        "The captured change supports this result.",
                        "Adjust the changed branch.",
                        unconsented.getId().toString(),
                        foreign.getId().toString(),
                        course.workspace().getWorkspaceSlug(),
                        other.workspace().getWorkspaceSlug());
        var receipt = disclosures.findById(datasetId).orElseThrow();
        assertThat(receipt.getWorkspaceId()).isEqualTo(workspaceId());
        assertThat(receipt.getCreatedByAccountId()).isEqualTo(adminId());
        assertThat(receipt.getContentSha256()).isEqualTo(ResearchDisclosureService.sha256(bytes));
        assertThat(subjects.findForDisclosure(workspaceId(), datasetId))
                .singleElement()
                .satisfies(s ->
                        assertThat(s.getAccountId()).isEqualTo(course.account().getId()));
        assertThat(auditCount(datasetId, "RESEARCH_DATASET_RELEASED")).isOne();
    }

    @Test
    void shouldRequireCsrfForAFreshCookieSessionAndAllowTheProtectedDownload() {
        String token = token(admin, Instant.now());
        long before = disclosureCount();
        var noCsrf = client.post()
                .uri(BASE)
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.COOKIE, cookieName + "=" + token);
        send(Entry.RELEASE, noCsrf).expectStatus().isForbidden().expectBody(Void.class);
        assertThat(disclosureCount()).isEqualTo(before);

        String csrf = csrf();
        var protectedRequest = client.post()
                .uri(BASE)
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.COOKIE, cookieName + "=" + token + "; __Host-XSRF-TOKEN=" + csrf)
                .header("X-XSRF-TOKEN", csrf);
        send(Entry.RELEASE, protectedRequest)
                .expectStatus()
                .isOk()
                .expectHeader()
                .value(HttpHeaders.CONTENT_DISPOSITION, v -> assertThat(v).contains("attachment"))
                .expectBody(byte[].class);
        assertThat(disclosureCount()).isEqualTo(before + 1);
    }

    @Test
    void shouldDetectRemovalsOnlyByPostThenRecordTheOperatorsAttestations() {
        withdraw();
        String fresh = token(admin, Instant.now());
        client.get()
                .uri(BASE + "/removal-checks")
                .headers(h -> h.setBearerAuth(fresh))
                .exchange()
                .expectStatus()
                .isEqualTo(405)
                .expectBody(Void.class);
        assertThat(subjects.findForDisclosure(workspaceId(), existingDataset)
                        .getFirst()
                        .getRemovalReason())
                .isNull();

        send(Entry.CHECK_REMOVALS, request(Entry.CHECK_REMOVALS, fresh))
                .expectStatus()
                .isOk()
                .expectHeader()
                .value(HttpHeaders.CACHE_CONTROL, v -> assertThat(v).contains("no-store", "private"))
                .expectBody()
                .jsonPath("$[0].datasetId")
                .isEqualTo(existingDataset.toString())
                .jsonPath("$[0].workspaceId")
                .isEqualTo(Long.toString(workspaceId()))
                .jsonPath("$[0].removals[0].participantKey")
                .isEqualTo(participantKey)
                .jsonPath("$[0].removals[0].reason")
                .isEqualTo("CONSENT_WITHDRAWN")
                .jsonPath("$[0].removals[0].accountId")
                .doesNotExist()
                .jsonPath("$[0].removals[0].grantDecisionId")
                .doesNotExist();

        send(Entry.RECORD_REMOVED, request(Entry.RECORD_REMOVED, fresh))
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.recorded")
                .isEqualTo(1);
        var removed = subjects.findForDisclosure(workspaceId(), existingDataset).getFirst();
        assertThat(removed.getRemovedAt()).isNotNull();
        assertThat(removed.getAccountId()).isNull();
        assertThat(removed.getGrantDecisionId()).isNull();
        assertThat(auditCount(existingDataset, "RESEARCH_REMOVAL_RECORDED")).isOne();

        send(Entry.CLOSE, request(Entry.CLOSE, fresh))
                .expectStatus()
                .isNoContent()
                .expectHeader()
                .value(HttpHeaders.CACHE_CONTROL, v -> assertThat(v).contains("no-store", "private"))
                .expectBody(Void.class);
        assertThat(disclosures.findById(existingDataset).orElseThrow().getClosure())
                .isEqualTo(ResearchDisclosure.Closure.DESTROYED);
        assertThat(subjects.findForDisclosure(workspaceId(), existingDataset)).isEmpty();
        assertThat(auditCount(existingDataset, "RESEARCH_REMOVAL_RECORDED")).isEqualTo(2);
        send(Entry.CLOSE, request(Entry.CLOSE, fresh))
                .expectStatus()
                .isEqualTo(409)
                .expectBody(Void.class);
        assertThat(auditCount(existingDataset, "RESEARCH_REMOVAL_RECORDED")).isEqualTo(2);
    }

    @Test
    void shouldRefuseAnUnknownParticipantKeyWithoutPartiallyRecordingRemovals() {
        withdraw();
        String fresh = token(admin, Instant.now());
        send(Entry.CHECK_REMOVALS, request(Entry.CHECK_REMOVALS, fresh))
                .expectStatus()
                .isOk()
                .expectBody(Void.class);
        request(Entry.RECORD_REMOVED, fresh)
                .bodyValue(new RecordResearchRemovalsDTO(List.of(participantKey, "not-a-participant")))
                .exchange()
                .expectStatus()
                .isEqualTo(409)
                .expectBody(Void.class);
        assertThat(subjects.findForDisclosure(workspaceId(), existingDataset)
                        .getFirst()
                        .getRemovedAt())
                .isNull();
        assertThat(auditCount(existingDataset, "RESEARCH_REMOVAL_RECORDED")).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "not-an-id", "9999999999999999999"})
    void shouldRefuseInvalidWorkspaceIdsWithoutCreatingAnAttachmentOrReceipt(String workspaceId) {
        long before = disclosureCount();
        request(Entry.RELEASE, token(admin, Instant.now()))
                .bodyValue(new ReleaseResearchDatasetDTO(workspaceId, day, day, useUntil()))
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectHeader()
                .doesNotExist(HttpHeaders.CONTENT_DISPOSITION)
                .expectBody(Void.class);
        assertThat(disclosureCount()).isEqualTo(before);
    }

    @Test
    void shouldRefuseMissingWorkspacesAndInvalidWindowsWithoutAnAttachment() {
        String fresh = token(admin, Instant.now());
        request(Entry.RELEASE, fresh)
                .bodyValue(new ReleaseResearchDatasetDTO(Long.toString(Long.MAX_VALUE), day, day, useUntil()))
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectHeader()
                .doesNotExist(HttpHeaders.CONTENT_DISPOSITION)
                .expectBody(Void.class);
        request(Entry.RELEASE, fresh)
                .bodyValue(
                        new ReleaseResearchDatasetDTO(Long.toString(workspaceId()), day, day.minusDays(1), useUntil()))
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectHeader()
                .doesNotExist(HttpHeaders.CONTENT_DISPOSITION)
                .expectBody(Void.class);
        assertThat(disclosureCount()).isOne();
    }

    @Test
    void shouldRejectAnUnknownClosureAndEmptyRemovalReceiptWithoutAWrite() {
        String fresh = token(admin, Instant.now());
        long before = researchAuditCount();
        request(Entry.CLOSE, fresh)
                .bodyValue(Map.of("closure", "NOT_A_CLOSURE"))
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody(Void.class);
        request(Entry.RECORD_REMOVED, fresh)
                .bodyValue(new RecordResearchRemovalsDTO(List.of()))
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody(Void.class);
        assertUnchanged(before);
    }

    private WebTestClient.RequestBodySpec request(Entry entry, String token) {
        return client.post()
                .uri(path(entry))
                .contentType(MediaType.APPLICATION_JSON)
                .headers(h -> h.setBearerAuth(token));
    }

    private WebTestClient.ResponseSpec send(Entry entry, WebTestClient.RequestBodySpec request) {
        return switch (entry) {
            case RELEASE ->
                request.bodyValue(new ReleaseResearchDatasetDTO(Long.toString(workspaceId()), day, day, useUntil()))
                        .exchange();
            case CHECK_REMOVALS -> request.exchange();
            case RECORD_REMOVED ->
                request.bodyValue(new RecordResearchRemovalsDTO(List.of(participantKey)))
                        .exchange();
            case CLOSE ->
                request.bodyValue(new CloseResearchDisclosureDTO(ResearchDisclosure.Closure.DESTROYED))
                        .exchange();
        };
    }

    private String path(Entry entry) {
        return switch (entry) {
            case RELEASE -> BASE;
            case CHECK_REMOVALS -> BASE + "/removal-checks";
            case RECORD_REMOVED -> BASE + "/" + existingDataset + "/removal-receipts";
            case CLOSE -> BASE + "/" + existingDataset + "/closure";
        };
    }

    private String token(Account account, @Nullable Instant proof) {
        Instant now = Instant.now();
        return issuer.issue(
                        Objects.requireNonNull(account.getId()),
                        new TokenConstraints(now.plus(Duration.ofHours(1)), now, null, proof),
                        null)
                .value();
    }

    private String csrf() {
        return Objects.requireNonNull(client.get()
                        .uri("/identity-providers")
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .returnResult(Void.class)
                        .getResponseCookies()
                        .getFirst("__Host-XSRF-TOKEN"))
                .getValue();
    }

    private void grant(Account account, boolean agreed) {
        consent.completeFirstLogin(
                Objects.requireNonNull(account.getId()),
                new ConsentService.FirstLoginConsentDTO(
                        ResearchConsentTestSupport.wordingVersion(), true, agreed, "AET"));
    }

    private void withdraw() {
        consent.setResearchParticipation(
                Objects.requireNonNull(course.account().getId()),
                new ConsentService.ResearchConsentDTO(ResearchConsentTestSupport.wordingVersion(), false, "AET"));
    }

    private long workspaceId() {
        return Objects.requireNonNull(course.workspace().getId());
    }

    private long adminId() {
        return Objects.requireNonNull(admin.getId());
    }

    private static Instant useUntil() {
        return Instant.now().plus(30, ChronoUnit.DAYS);
    }

    private long disclosureCount() {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT count(*) FROM research_disclosure WHERE workspace_id=?", Long.class, workspaceId()));
    }

    private long researchAuditCount() {
        return Objects.requireNonNull(jdbc.queryForObject("""
                SELECT count(*) FROM auth_event WHERE workspace_id=?
                 AND event_type IN ('RESEARCH_DATASET_RELEASED','RESEARCH_REMOVAL_RECORDED')
                """, Long.class, workspaceId()));
    }

    private long auditCount(UUID datasetId, String type) {
        return Objects.requireNonNull(jdbc.queryForObject("""
                SELECT count(*) FROM auth_event WHERE workspace_id=? AND event_type=?
                 AND CAST(details AS jsonb)->>'disclosureId'=?
                """, Long.class, workspaceId(), type, datasetId.toString()));
    }

    private void assertUnchanged(long researchAudits) {
        assertThat(disclosureCount()).isOne();
        assertThat(researchAuditCount()).isEqualTo(researchAudits);
        assertThat(disclosures.findById(existingDataset).orElseThrow().isClosed())
                .isFalse();
        assertThat(subjects.findForDisclosure(workspaceId(), existingDataset))
                .singleElement()
                .satisfies(s -> {
                    assertThat(s.getRemovalReason()).isNull();
                    assertThat(s.getRemovedAt()).isNull();
                    assertThat(s.getAccountId()).isEqualTo(course.account().getId());
                });
    }
}
