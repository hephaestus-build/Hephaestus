package de.tum.cit.aet.hephaestus.integration.scm.gitlab.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.OrganizationMembershipListener;
import de.tum.cit.aet.hephaestus.integration.core.spi.OrganizationMembershipListener.OrganizationSyncedEvent;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationMemberRole;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationMembershipRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlResponseHandler;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlResponseHandler.HandleResult;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabProperties;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.graphql.GitLabGroupMemberResponse;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.graphql.GitLabGroupMemberResponse.GitLabAccessLevel;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.graphql.GitLabGroupMemberResponse.GitLabMemberUser;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.graphql.GitLabPageInfo;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.ClientResponseField;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;

class GitLabGroupMemberSyncServiceTest extends BaseUnitTest {

    private static final Long TEST_PROVIDER_ID = 100L;
    private static final Long SCOPE_ID = 1L;
    private static final String GROUP_PATH = "my-org";

    @Mock
    private GitLabGraphQlClientProvider graphQlClientProvider;

    @Mock
    private GitLabGraphQlResponseHandler responseHandler;

    @Mock
    private OrganizationMembershipRepository organizationMembershipRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private IdentityProviderRepository gitProviderRepository;

    @Mock
    private OrganizationMembershipListener organizationMembershipListener;

    private final GitLabProperties gitLabProperties = new GitLabProperties(
            "https://gitlab.com",
            Duration.ofSeconds(30),
            Duration.ofSeconds(60),
            Duration.ofMillis(10), // fast throttle for tests
            Duration.ofMinutes(5));

    private GitLabGroupMemberSyncService service;
    private Organization testOrg;

    @BeforeEach
    void setUp() {
        IdentityProvider gitLabProvider = TestEntities.gitProvider(TEST_PROVIDER_ID, IdentityProviderType.GITLAB);
        lenient()
                .when(gitProviderRepository.findByTypeAndServerUrl(IdentityProviderType.GITLAB, "https://gitlab.com"))
                .thenReturn(Optional.of(gitLabProvider));

        // TransactionTemplate that executes callbacks directly (no real transactions needed)
        PlatformTransactionManager mockTxManager = mock(PlatformTransactionManager.class);
        lenient().when(mockTxManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        TransactionTemplate txTemplate = new TransactionTemplate(mockTxManager);

        // Default: advisory lock always succeeds
        lenient()
                .when(userRepository.tryAcquireLoginLock(anyString(), anyLong()))
                .thenReturn(true);

        // Default: responseHandler.handle() returns CONTINUE (valid response)
        lenient()
                .when(responseHandler.handle(any(), anyString(), any()))
                .thenReturn(new HandleResult(HandleResult.Action.CONTINUE, null));

        service = new GitLabGroupMemberSyncService(
                graphQlClientProvider,
                responseHandler,
                organizationMembershipRepository,
                userRepository,
                gitProviderRepository,
                gitLabProperties,
                organizationMembershipListener,
                txTemplate);

        testOrg = new Organization();
        testOrg.setId(42L);
        testOrg.setLogin("my-org");
    }

    /**
     * Helper to set up a user that can be found after upsert.
     * Registers findByNativeIdAndProviderId to return a user with the given nativeId.
     */
    private void stubUserLookup(long nativeId, long userId) {
        User user = new User();
        user.setId(userId);
        user.setNativeId(nativeId);
        user.setType(User.Type.USER);
        when(userRepository.findByNativeIdAndProviderId(nativeId, TEST_PROVIDER_ID))
                .thenReturn(Optional.of(user));
    }

    @Nested
    class ArgumentValidation {

        @Test
        void nullOrganization_returnsNegative() {
            int result = service.syncGroupMemberships(SCOPE_ID, GROUP_PATH, null);
            assertThat(result).isEqualTo(-1);
            verifyNoInteractions(graphQlClientProvider);
        }

        @Test
        void nullGroupPath_returnsNegative() {
            int result = service.syncGroupMemberships(SCOPE_ID, null, testOrg);
            assertThat(result).isEqualTo(-1);
            verifyNoInteractions(graphQlClientProvider);
        }

        @Test
        void blankGroupPath_returnsNegative() {
            int result = service.syncGroupMemberships(SCOPE_ID, "   ", testOrg);
            assertThat(result).isEqualTo(-1);
            verifyNoInteractions(graphQlClientProvider);
        }
    }

    @Nested
    class ProviderResolution {

        @Test
        void providerNotFound_throwsIllegalState() {
            when(gitProviderRepository.findByTypeAndServerUrl(IdentityProviderType.GITLAB, "https://gitlab.com"))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.syncGroupMemberships(SCOPE_ID, GROUP_PATH, testOrg))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("IdentityProvider not found");
        }
    }

    @Nested
    class SinglePage {

        @Test
        void syncsAllMembers() throws InterruptedException {
            var member1 = createMember("gid://gitlab/User/10", "alice", "Alice", 30); // DEVELOPER → MEMBER
            var member2 = createMember("gid://gitlab/User/20", "bob", "Bob", 50); // OWNER → ADMIN

            ClientGraphQlResponse response = mockMembersPage(List.of(member1, member2), null);
            HttpGraphQlClient client = mockClient();
            mockSequentialExecute(client, response);
            when(graphQlClientProvider.getRateLimitRemaining(SCOPE_ID)).thenReturn(100);

            stubUserLookup(10L, 1010L);
            stubUserLookup(20L, 1020L);
            when(organizationMembershipRepository.findUserIdsByOrganizationId(42L))
                    .thenReturn(List.of());

            int result = service.syncGroupMemberships(SCOPE_ID, GROUP_PATH, testOrg);

            assertThat(result).isEqualTo(2);

            // Verify upserts with correct user IDs (not anyLong)
            verify(organizationMembershipRepository).upsertMembership(42L, 1010L, OrganizationMemberRole.MEMBER);
            verify(organizationMembershipRepository).upsertMembership(42L, 1020L, OrganizationMemberRole.ADMIN);

            // Verify native SQL upsert was used (not JPA save)
            verify(userRepository, times(2))
                    .upsertUser(
                            anyLong(),
                            eq(TEST_PROVIDER_ID),
                            anyString(),
                            any(),
                            anyString(),
                            anyString(),
                            eq("USER"),
                            any(),
                            any(),
                            any());
            verify(userRepository, never()).save(any(User.class));

            // Verify login conflict resolution
            verify(userRepository).freeLoginConflicts("alice", 10L, TEST_PROVIDER_ID);
            verify(userRepository).freeLoginConflicts("bob", 20L, TEST_PROVIDER_ID);

            // Verify circuit breaker + rate limit per page
            verify(graphQlClientProvider).acquirePermission();
            verify(graphQlClientProvider).waitIfRateLimitLow(SCOPE_ID);

            // Verify success recorded
            verify(graphQlClientProvider).recordSuccess();

            // Verify listener event
            ArgumentCaptor<OrganizationSyncedEvent> eventCaptor =
                    ArgumentCaptor.forClass(OrganizationSyncedEvent.class);
            verify(organizationMembershipListener).onOrganizationMembershipsSynced(eventCaptor.capture());
            assertThat(eventCaptor.getValue().organizationId()).isEqualTo(42L);
            assertThat(eventCaptor.getValue().organizationLogin()).isEqualTo("my-org");
        }

        @Test
        void duplicateMember_countedOnce() {
            var member1 = createMember("gid://gitlab/User/10", "alice", "Alice", 30);
            var member2 = createMember("gid://gitlab/User/10", "alice", "Alice", 30); // duplicate

            ClientGraphQlResponse response = mockMembersPage(List.of(member1, member2), null);
            HttpGraphQlClient client = mockClient();
            mockSequentialExecute(client, response);
            when(graphQlClientProvider.getRateLimitRemaining(SCOPE_ID)).thenReturn(100);

            stubUserLookup(10L, 1010L);
            when(organizationMembershipRepository.findUserIdsByOrganizationId(42L))
                    .thenReturn(List.of());

            int result = service.syncGroupMemberships(SCOPE_ID, GROUP_PATH, testOrg);

            // Returns unique count, not total appearances
            assertThat(result).isEqualTo(1);
        }
    }

    @Nested
    class MultiPage {

        @Test
        void fetchesAllPages() throws InterruptedException {
            var member1 = createMember("gid://gitlab/User/10", "alice", "Alice", 30);
            var member2 = createMember("gid://gitlab/User/20", "bob", "Bob", 40);

            ClientGraphQlResponse page1 = mockMembersPage(List.of(member1), new GitLabPageInfo(true, "cursor1"));
            ClientGraphQlResponse page2 = mockMembersPage(List.of(member2), new GitLabPageInfo(false, null));

            HttpGraphQlClient client = mockClient();
            mockSequentialExecute(client, page1, page2);
            when(graphQlClientProvider.getRateLimitRemaining(SCOPE_ID)).thenReturn(100);

            stubUserLookup(10L, 1010L);
            stubUserLookup(20L, 1020L);
            when(organizationMembershipRepository.findUserIdsByOrganizationId(42L))
                    .thenReturn(List.of());

            int result = service.syncGroupMemberships(SCOPE_ID, GROUP_PATH, testOrg);

            assertThat(result).isEqualTo(2);

            // acquirePermission called per page (2 pages = 2 calls)
            verify(graphQlClientProvider, times(2)).acquirePermission();
            verify(graphQlClientProvider, times(2)).waitIfRateLimitLow(SCOPE_ID);
        }

        @Test
        void nullCursor_stopsPagination() {
            var member = createMember("gid://gitlab/User/10", "alice", "Alice", 30);
            // hasNextPage=true but endCursor=null
            ClientGraphQlResponse response = mockMembersPage(List.of(member), new GitLabPageInfo(true, null));

            HttpGraphQlClient client = mockClient();
            mockSequentialExecute(client, response);
            when(graphQlClientProvider.getRateLimitRemaining(SCOPE_ID)).thenReturn(100);

            stubUserLookup(10L, 1010L);

            int result = service.syncGroupMemberships(SCOPE_ID, GROUP_PATH, testOrg);

            // Sync incomplete → event NOT fired, stale removal skipped
            assertThat(result).isEqualTo(1);
            verify(organizationMembershipListener, never()).onOrganizationMembershipsSynced(any());
            verify(organizationMembershipRepository, never()).deleteByOrganizationIdAndUserIdIn(anyLong(), any());
        }
    }

    @Nested
    class StaleMemberRemoval {

        @Test
        void removesStaleMembers() {
            var member = createMember("gid://gitlab/User/10", "alice", "Alice", 30);

            ClientGraphQlResponse response = mockMembersPage(List.of(member), null);
            HttpGraphQlClient client = mockClient();
            mockSequentialExecute(client, response);
            when(graphQlClientProvider.getRateLimitRemaining(SCOPE_ID)).thenReturn(100);

            stubUserLookup(10L, 1010L);

            // Existing memberships include a stale user (id=9999)
            when(organizationMembershipRepository.findUserIdsByOrganizationId(42L))
                    .thenReturn(List.of(1010L, 9999L));

            int result = service.syncGroupMemberships(SCOPE_ID, GROUP_PATH, testOrg);

            assertThat(result).isEqualTo(1);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Collection<Long>> staleCaptor = ArgumentCaptor.forClass(Collection.class);
            verify(organizationMembershipRepository).deleteByOrganizationIdAndUserIdIn(eq(42L), staleCaptor.capture());
            assertThat(staleCaptor.getValue()).containsExactly(9999L);
        }

        @Test
        void staleRemovalSkipped_whenIncomplete() {
            HttpGraphQlClient client = mockClient();
            ClientGraphQlResponse invalidResp = mock(ClientGraphQlResponse.class);
            lenient().when(invalidResp.isValid()).thenReturn(false);
            lenient().when(invalidResp.getErrors()).thenReturn(List.of());

            // Invalid response → handler returns ABORT
            when(responseHandler.handle(eq(invalidResp), anyString(), any()))
                    .thenReturn(new HandleResult(HandleResult.Action.ABORT, null));

            HttpGraphQlClient.RequestSpec requestSpec = mock(HttpGraphQlClient.RequestSpec.class);
            when(client.documentName(anyString())).thenReturn(requestSpec);
            when(requestSpec.variable(anyString(), any())).thenReturn(requestSpec);
            when(requestSpec.execute()).thenReturn(Mono.just(invalidResp));
            when(graphQlClientProvider.getRateLimitRemaining(SCOPE_ID)).thenReturn(100);

            int result = service.syncGroupMemberships(SCOPE_ID, GROUP_PATH, testOrg);

            assertThat(result).isEqualTo(0);
            verify(organizationMembershipRepository, never()).deleteByOrganizationIdAndUserIdIn(anyLong(), any());
            // Event NOT fired on incomplete sync
            verify(organizationMembershipListener, never()).onOrganizationMembershipsSynced(any());
            // Failure recorded
            verify(graphQlClientProvider).recordFailure(any());
        }
    }

    @Nested
    class ErrorHandling {

        @Test
        void nullMemberUser_skipped() {
            var nullUserMember = new GitLabGroupMemberResponse(null, new GitLabAccessLevel("DEVELOPER", 30));
            var validMember = createMember("gid://gitlab/User/10", "alice", "Alice", 30);

            ClientGraphQlResponse response = mockMembersPage(List.of(nullUserMember, validMember), null);
            HttpGraphQlClient client = mockClient();
            mockSequentialExecute(client, response);
            when(graphQlClientProvider.getRateLimitRemaining(SCOPE_ID)).thenReturn(100);

            stubUserLookup(10L, 1010L);
            when(organizationMembershipRepository.findUserIdsByOrganizationId(42L))
                    .thenReturn(List.of());

            int result = service.syncGroupMemberships(SCOPE_ID, GROUP_PATH, testOrg);

            assertThat(result).isEqualTo(1);
        }

        @Test
        void invalidGid_skippedGracefully() {
            var badMember = createMember("invalid-gid", "baduser", "Bad User", 30);
            var goodMember = createMember("gid://gitlab/User/10", "alice", "Alice", 30);

            ClientGraphQlResponse response = mockMembersPage(List.of(badMember, goodMember), null);
            HttpGraphQlClient client = mockClient();
            mockSequentialExecute(client, response);
            when(graphQlClientProvider.getRateLimitRemaining(SCOPE_ID)).thenReturn(100);

            stubUserLookup(10L, 1010L);
            when(organizationMembershipRepository.findUserIdsByOrganizationId(42L))
                    .thenReturn(List.of());

            int result = service.syncGroupMemberships(SCOPE_ID, GROUP_PATH, testOrg);

            assertThat(result).isEqualTo(1);
            // upsertUser called only for the valid member
            verify(userRepository, times(1))
                    .upsertUser(
                            eq(10L),
                            eq(TEST_PROVIDER_ID),
                            eq("alice"),
                            any(),
                            anyString(),
                            anyString(),
                            eq("USER"),
                            any(),
                            any(),
                            any());
        }

        @Test
        void interruptedDuringSleep_returnsNegative() {
            var member = createMember("gid://gitlab/User/10", "alice", "Alice", 30);

            // First page has more pages; second page will trigger Thread.sleep which throws
            ClientGraphQlResponse page1 = mockMembersPage(List.of(member), new GitLabPageInfo(true, "cursor1"));

            HttpGraphQlClient client = mockClient();
            HttpGraphQlClient.RequestSpec requestSpec = mock(HttpGraphQlClient.RequestSpec.class);
            when(client.documentName(anyString())).thenReturn(requestSpec);
            when(requestSpec.variable(anyString(), any())).thenReturn(requestSpec);
            when(requestSpec.execute()).thenReturn(Mono.just(page1));
            when(graphQlClientProvider.getRateLimitRemaining(SCOPE_ID)).thenReturn(100);

            stubUserLookup(10L, 1010L);

            // Interrupt the current thread before the sleep
            Thread.currentThread().interrupt();

            int result = service.syncGroupMemberships(SCOPE_ID, GROUP_PATH, testOrg);

            assertThat(result).isEqualTo(-1);
            // Clear interrupted flag for other tests
            Thread.interrupted();
        }

        @Test
        void interruptedDuringRateLimitWait_breaksLoop() throws InterruptedException {
            doThrow(new InterruptedException("rate limit wait interrupted"))
                    .when(graphQlClientProvider)
                    .waitIfRateLimitLow(SCOPE_ID);

            int result = service.syncGroupMemberships(SCOPE_ID, GROUP_PATH, testOrg);

            // Incomplete sync → returns 0 (no members synced), no event
            assertThat(result).isEqualTo(0);
            verify(organizationMembershipListener, never()).onOrganizationMembershipsSynced(any());
            // Clear interrupted flag
            Thread.interrupted();
        }

        @Test
        void graphQlException_returnsNegative() {
            HttpGraphQlClient client = mockClient();
            HttpGraphQlClient.RequestSpec requestSpec = mock(HttpGraphQlClient.RequestSpec.class);
            when(client.documentName(anyString())).thenReturn(requestSpec);
            when(requestSpec.variable(anyString(), any())).thenReturn(requestSpec);
            when(requestSpec.execute()).thenReturn(Mono.error(new RuntimeException("network error")));
            when(graphQlClientProvider.getRateLimitRemaining(SCOPE_ID)).thenReturn(100);

            int result = service.syncGroupMemberships(SCOPE_ID, GROUP_PATH, testOrg);

            assertThat(result).isEqualTo(-1);
            verify(graphQlClientProvider).recordFailure(any(Exception.class));
        }

        @Test
        void emptyMemberList_doesNotDeleteAll() {
            ClientGraphQlResponse response = mockMembersPage(List.of(), null);
            HttpGraphQlClient client = mockClient();
            mockSequentialExecute(client, response);
            when(graphQlClientProvider.getRateLimitRemaining(SCOPE_ID)).thenReturn(100);

            // Existing memberships
            when(organizationMembershipRepository.findUserIdsByOrganizationId(42L))
                    .thenReturn(List.of(1010L, 1020L));

            int result = service.syncGroupMemberships(SCOPE_ID, GROUP_PATH, testOrg);

            // Zero members synced
            assertThat(result).isEqualTo(0);

            // All existing memberships are stale — they get removed
            // (this is correct behavior: the group is now empty)
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Collection<Long>> staleCaptor = ArgumentCaptor.forClass(Collection.class);
            verify(organizationMembershipRepository).deleteByOrganizationIdAndUserIdIn(eq(42L), staleCaptor.capture());
            assertThat(staleCaptor.getValue()).containsExactlyInAnyOrder(1010L, 1020L);
        }
    }

    @Nested
    @DisplayName("syncGroupMemberships — listener")
    class ListenerTests {

        @Test
        void noListener_doesNotThrow() {
            PlatformTransactionManager mockTxManager = mock(PlatformTransactionManager.class);
            lenient().when(mockTxManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
            TransactionTemplate txTemplate = new TransactionTemplate(mockTxManager);

            var serviceNoListener = new GitLabGroupMemberSyncService(
                    graphQlClientProvider,
                    responseHandler,
                    organizationMembershipRepository,
                    userRepository,
                    gitProviderRepository,
                    gitLabProperties,
                    null,
                    txTemplate);

            var member = createMember("gid://gitlab/User/10", "alice", "Alice", 30);
            ClientGraphQlResponse response = mockMembersPage(List.of(member), null);
            HttpGraphQlClient client = mockClient();
            mockSequentialExecute(client, response);
            when(graphQlClientProvider.getRateLimitRemaining(SCOPE_ID)).thenReturn(100);

            stubUserLookup(10L, 1010L);
            when(organizationMembershipRepository.findUserIdsByOrganizationId(42L))
                    .thenReturn(List.of());

            int result = serviceNoListener.syncGroupMemberships(SCOPE_ID, GROUP_PATH, testOrg);

            assertThat(result).isEqualTo(1);
        }
    }

    @Nested
    class UpsertUser {

        @Test
        void upsertsViaNativeSql() {
            stubUserLookup(42L, 1042L);

            var memberUser = new GitLabMemberUser(
                    "gid://gitlab/User/42",
                    "alice",
                    "Alice A.",
                    "https://gitlab.com/avatar.png",
                    "https://gitlab.com/alice");

            Long userId = service.upsertUser(memberUser, TEST_PROVIDER_ID);

            assertThat(userId).isEqualTo(1042L);

            // Verify advisory lock acquired
            verify(userRepository).tryAcquireLoginLock("alice", TEST_PROVIDER_ID);

            // Verify login conflicts freed
            verify(userRepository).freeLoginConflicts("alice", 42L, TEST_PROVIDER_ID);

            // Verify native SQL upsert (not JPA save)
            verify(userRepository)
                    .upsertUser(
                            42L,
                            TEST_PROVIDER_ID,
                            "alice",
                            "Alice A.",
                            "https://gitlab.com/avatar.png",
                            "https://gitlab.com/alice",
                            "USER",
                            null,
                            null,
                            null);
            verify(userRepository, never()).save(any(User.class));
        }

        @Test
        void lockNotAcquired_proceedsWithoutFreeConflicts() {
            when(userRepository.tryAcquireLoginLock("alice", TEST_PROVIDER_ID)).thenReturn(false);
            stubUserLookup(42L, 1042L);

            var memberUser =
                    new GitLabMemberUser("gid://gitlab/User/42", "alice", "Alice", null, "https://gitlab.com/alice");

            Long userId = service.upsertUser(memberUser, TEST_PROVIDER_ID);

            assertThat(userId).isEqualTo(1042L);
            verify(userRepository, never()).freeLoginConflicts(anyString(), anyLong(), anyLong());
            verify(userRepository)
                    .upsertUser(
                            eq(42L),
                            eq(TEST_PROVIDER_ID),
                            eq("alice"),
                            eq("Alice"),
                            eq(""),
                            eq("https://gitlab.com/alice"),
                            eq("USER"),
                            any(),
                            any(),
                            any());
        }

        @Test
        void nullAvatarUrl_defaultsToEmpty() {
            stubUserLookup(42L, 1042L);

            var memberUser =
                    new GitLabMemberUser("gid://gitlab/User/42", "alice", null, null, "https://gitlab.com/alice");

            Long userId = service.upsertUser(memberUser, TEST_PROVIDER_ID);

            assertThat(userId).isEqualTo(1042L);
            verify(userRepository)
                    .upsertUser(
                            eq(42L),
                            eq(TEST_PROVIDER_ID),
                            eq("alice"),
                            any(),
                            eq(""),
                            eq("https://gitlab.com/alice"),
                            eq("USER"),
                            any(),
                            any(),
                            any());
        }

        @Test
        void invalidGid_returnsNull() {
            var memberUser =
                    new GitLabMemberUser("not-a-valid-gid", "alice", "Alice", null, "https://gitlab.com/alice");

            Long userId = service.upsertUser(memberUser, TEST_PROVIDER_ID);

            assertThat(userId).isNull();
            verify(userRepository, never())
                    .upsertUser(
                            anyLong(),
                            anyLong(),
                            anyString(),
                            any(),
                            anyString(),
                            anyString(),
                            anyString(),
                            any(),
                            any(),
                            any());
        }

        @Test
        void userNotFoundAfterUpsert_returnsNull() {
            when(userRepository.findByNativeIdAndProviderId(42L, TEST_PROVIDER_ID))
                    .thenReturn(Optional.empty());

            var memberUser =
                    new GitLabMemberUser("gid://gitlab/User/42", "alice", "Alice", null, "https://gitlab.com/alice");

            Long userId = service.upsertUser(memberUser, TEST_PROVIDER_ID);

            assertThat(userId).isNull();
            // Upsert was still attempted
            verify(userRepository)
                    .upsertUser(
                            eq(42L),
                            eq(TEST_PROVIDER_ID),
                            anyString(),
                            any(),
                            anyString(),
                            anyString(),
                            eq("USER"),
                            any(),
                            any(),
                            any());
        }
    }

    @Nested
    class MapAccessLevel {

        @Test
        void owner_mapsToAdmin() {
            assertThat(GitLabGroupMemberSyncService.mapAccessLevel(new GitLabAccessLevel("OWNER", 50)))
                    .isEqualTo(OrganizationMemberRole.ADMIN);
        }

        @Test
        void maintainer_mapsToAdmin() {
            assertThat(GitLabGroupMemberSyncService.mapAccessLevel(new GitLabAccessLevel("MAINTAINER", 40)))
                    .isEqualTo(OrganizationMemberRole.ADMIN);
        }

        @Test
        void developer_mapsToMember() {
            assertThat(GitLabGroupMemberSyncService.mapAccessLevel(new GitLabAccessLevel("DEVELOPER", 30)))
                    .isEqualTo(OrganizationMemberRole.MEMBER);
        }

        @Test
        void reporter_mapsToMember() {
            assertThat(GitLabGroupMemberSyncService.mapAccessLevel(new GitLabAccessLevel("REPORTER", 20)))
                    .isEqualTo(OrganizationMemberRole.MEMBER);
        }

        @Test
        void planner_mapsToMember() {
            assertThat(GitLabGroupMemberSyncService.mapAccessLevel(new GitLabAccessLevel("PLANNER", 15)))
                    .isEqualTo(OrganizationMemberRole.MEMBER);
        }

        @Test
        void guest_mapsToMember() {
            assertThat(GitLabGroupMemberSyncService.mapAccessLevel(new GitLabAccessLevel("GUEST", 10)))
                    .isEqualTo(OrganizationMemberRole.MEMBER);
        }

        @Test
        void minimalAccess_mapsToMember() {
            assertThat(GitLabGroupMemberSyncService.mapAccessLevel(new GitLabAccessLevel("MINIMAL_ACCESS", 5)))
                    .isEqualTo(OrganizationMemberRole.MEMBER);
        }

        @Test
        void nullAccessLevel_mapsToMember() {
            assertThat(GitLabGroupMemberSyncService.mapAccessLevel(null)).isEqualTo(OrganizationMemberRole.MEMBER);
        }

        @Test
        void nullIntegerValue_mapsToMember() {
            assertThat(GitLabGroupMemberSyncService.mapAccessLevel(new GitLabAccessLevel("UNKNOWN", null)))
                    .isEqualTo(OrganizationMemberRole.MEMBER);
        }
    }

    // Helpers

    private GitLabGroupMemberResponse createMember(String gid, String username, String name, int accessLevel) {
        return new GitLabGroupMemberResponse(
                new GitLabMemberUser(gid, username, name, null, "https://gitlab.com/" + username),
                new GitLabAccessLevel(accessLevelName(accessLevel), accessLevel));
    }

    private static String accessLevelName(int level) {
        return switch (level) {
            case 5 -> "MINIMAL_ACCESS";
            case 10 -> "GUEST";
            case 15 -> "PLANNER";
            case 20 -> "REPORTER";
            case 30 -> "DEVELOPER";
            case 40 -> "MAINTAINER";
            case 50 -> "OWNER";
            default -> "UNKNOWN";
        };
    }

    private HttpGraphQlClient mockClient() {
        HttpGraphQlClient client = mock(HttpGraphQlClient.class);
        when(graphQlClientProvider.forScope(any())).thenReturn(client);
        return client;
    }

    @SuppressWarnings("unchecked")
    private ClientGraphQlResponse mockMembersPage(
            List<GitLabGroupMemberResponse> members, @Nullable GitLabPageInfo pageInfo) {
        ClientGraphQlResponse resp = mock(ClientGraphQlResponse.class);
        lenient().when(resp.isValid()).thenReturn(true);

        ClientResponseField nodesField = mock(ClientResponseField.class);
        when(nodesField.<GitLabGroupMemberResponse>toEntityList(any(Class.class)))
                .thenReturn(members);
        when(resp.field("group.groupMembers.nodes")).thenReturn(nodesField);

        ClientResponseField pageInfoField = mock(ClientResponseField.class);
        when(pageInfoField.<GitLabPageInfo>toEntity(any(Class.class))).thenReturn(pageInfo);
        when(resp.field("group.groupMembers.pageInfo")).thenReturn(pageInfoField);

        return resp;
    }

    private void mockSequentialExecute(
            HttpGraphQlClient client, ClientGraphQlResponse first, ClientGraphQlResponse... rest) {
        HttpGraphQlClient.RequestSpec requestSpec = mock(HttpGraphQlClient.RequestSpec.class);
        when(client.documentName(anyString())).thenReturn(requestSpec);
        when(requestSpec.variable(anyString(), any())).thenReturn(requestSpec);

        var responses = when(requestSpec.execute()).thenReturn(Mono.just(first));

        for (ClientGraphQlResponse response : rest) {

            responses = responses.thenReturn(Mono.just(response));
        }
    }
}
