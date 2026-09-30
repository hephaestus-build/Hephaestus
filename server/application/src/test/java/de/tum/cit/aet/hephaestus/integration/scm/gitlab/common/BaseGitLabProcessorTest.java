package de.tum.cit.aet.hephaestus.integration.scm.gitlab.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.RepositoryScopeFilter;
import de.tum.cit.aet.hephaestus.integration.core.spi.ScopeIdResolver;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.Label;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.LabelRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.dto.GitLabWebhookLabel;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.dto.GitLabWebhookUser;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.user.GitLabUserService;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;

@Tag("unit")
class BaseGitLabProcessorTest extends BaseUnitTest {

    @Mock
    private GitLabUserService gitLabUserService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private LabelRepository labelRepository;

    @Mock
    private RepositoryRepository repositoryRepository;

    @Mock
    private ScopeIdResolver scopeIdResolver;

    @Mock
    private RepositoryScopeFilter repositoryScopeFilter;

    private TestProcessor processor;
    private Repository testRepo;

    @BeforeEach
    void setUp() {
        GitLabProperties properties = new GitLabProperties(
                "https://gitlab.lrz.de",
                Duration.ofSeconds(30),
                Duration.ofSeconds(60),
                Duration.ofMillis(200),
                Duration.ofMinutes(5));

        processor = new TestProcessor(
                gitLabUserService,
                userRepository,
                labelRepository,
                repositoryRepository,
                scopeIdResolver,
                repositoryScopeFilter,
                properties);

        IdentityProvider gitLabProvider = new IdentityProvider();
        gitLabProvider.setId(2L);
        gitLabProvider.setType(IdentityProviderType.GITLAB);
        gitLabProvider.setServerUrl("https://gitlab.lrz.de");

        testRepo = new Repository();
        testRepo.setId(-246765L);
        testRepo.setNameWithOwner("hephaestustest/demo-repository");
        testRepo.setProvider(gitLabProvider);
    }

    // Timestamp Parsing

    @Nested
    class TimestampParsing {

        @Test
        void parsesIso8601() {
            Instant result = processor.callParseGitLabTimestamp("2026-01-31T18:03:35Z");

            assertThat(result).isNotNull();
            assertThat(result.toString()).isEqualTo("2026-01-31T18:03:35Z");
        }

        @Test
        void parsesIso8601WithOffset() {
            Instant result = processor.callParseGitLabTimestamp("2026-01-31T19:03:35+01:00");

            assertThat(result).isNotNull();
            assertThat(result.toString()).isEqualTo("2026-01-31T18:03:35Z");
        }

        @Test
        void parsesWebhookFormat() {
            Instant result = processor.callParseGitLabTimestamp("2026-01-31 19:03:35 +0100");

            assertThat(result).isNotNull();
            assertThat(result.toString()).isEqualTo("2026-01-31T18:03:35Z");
        }

        @Test
        void parsesWebhookFormatNoTimezone() {
            Instant result = processor.callParseGitLabTimestamp("2026-01-31 19:03:35");

            assertThat(result).isNotNull();
            // Defaults to UTC offset 0
            assertThat(result.toString()).isEqualTo("2026-01-31T19:03:35Z");
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"  ", "\t"})
        void returnsNullForNullOrBlank(String input) {
            Instant result = processor.callParseGitLabTimestamp(input);
            assertThat(result).isNull();
        }

        @Test
        void returnsNullForUnparseable() {
            Instant result = processor.callParseGitLabTimestamp("not-a-timestamp");
            assertThat(result).isNull();
        }
    }

    // User Resolution

    @Nested
    class WebhookUserResolution {

        @Test
        void findsOrCreatesUserFromWebhook() {
            User user = new User();
            user.setId(-18024L);
            user.setLogin("ga84xah");

            GitLabWebhookUser dto =
                    new GitLabWebhookUser(18024L, "ga84xah", "Felix Dietrich", "https://avatar.url", null);

            when(gitLabUserService.findOrCreateUser(dto, 1L)).thenReturn(user);

            User result = processor.callFindOrCreateUser(dto);

            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(-18024L);
            assertThat(result.getLogin()).isEqualTo("ga84xah");
        }

        @Test
        void returnsNullForNullUser() {
            User result = processor.callFindOrCreateUser((GitLabWebhookUser) null);
            assertThat(result).isNull();
        }

        @Test
        void returnsNullForNullUserId() {
            GitLabWebhookUser dto = new GitLabWebhookUser(null, "ga84xah", "Felix", null, null);
            User result = processor.callFindOrCreateUser(dto);
            assertThat(result).isNull();
        }

        @Test
        void returnsNullForNullUsername() {
            GitLabWebhookUser dto = new GitLabWebhookUser(18024L, null, "Felix", null, null);
            User result = processor.callFindOrCreateUser(dto);
            assertThat(result).isNull();
        }
    }

    @Nested
    class GraphQLUserResolution {

        @Test
        void findsOrCreatesUserFromGraphQL() {
            User user = new User();
            user.setId(-18024L);

            GitLabUserLookup expectedLookup = GitLabUserLookup.of(
                    "gid://gitlab/User/18024",
                    "ga84xah",
                    "Felix Dietrich",
                    "https://avatar.url",
                    "https://gitlab.lrz.de/ga84xah",
                    null);
            when(gitLabUserService.findOrCreateUser(expectedLookup, 1L)).thenReturn(user);

            User result = processor.callFindOrCreateUser(
                    "gid://gitlab/User/18024",
                    "ga84xah",
                    "Felix Dietrich",
                    "https://avatar.url",
                    "https://gitlab.lrz.de/ga84xah");

            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(-18024L);
        }

        @Test
        void returnsNullForInvalidGlobalId() {
            User result = processor.callFindOrCreateUser("invalid", "ga84xah", "Felix", null, null);
            assertThat(result).isNull();
        }

        @Test
        void returnsNullForNullGlobalId() {
            User result = processor.callFindOrCreateUser(null, "ga84xah", "Felix", null, null);
            assertThat(result).isNull();
        }
    }

    // Label Resolution

    @Nested
    class LabelResolution {

        @Test
        void returnsExistingLabel() {
            Label existing = new Label();
            existing.setId(-85907L);
            existing.setName("enhancement");

            when(labelRepository.findByRepositoryIdAndName(testRepo.getId(), "enhancement"))
                    .thenReturn(Optional.of(existing));

            GitLabWebhookLabel dto = new GitLabWebhookLabel(85907L, "enhancement", "#a2eeef");
            Label result = processor.callFindOrCreateLabel(dto, testRepo);

            assertThat(result).isSameAs(existing);
            verify(labelRepository, never()).insertIfAbsent(anyLong(), anyLong(), anyString(), anyString(), anyLong());
        }

        @Test
        void createsNewLabelWithNativeId() {
            Label created = new Label();
            created.setId(85907L);
            created.setName("enhancement");

            when(labelRepository.findByRepositoryIdAndName(testRepo.getId(), "enhancement"))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(created));
            when(labelRepository.insertIfAbsent(85907L, 2L, "enhancement", "#a2eeef", testRepo.getId()))
                    .thenReturn(1);

            GitLabWebhookLabel dto = new GitLabWebhookLabel(85907L, "enhancement", "#a2eeef");
            Label result = processor.callFindOrCreateLabel(dto, testRepo);

            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(85907L);
        }

        @Test
        void returnsNullForNullLabel() {
            Label result = processor.callFindOrCreateLabel((GitLabWebhookLabel) null, testRepo);
            assertThat(result).isNull();
        }

        @Test
        void returnsNullForBlankTitle() {
            GitLabWebhookLabel dto = new GitLabWebhookLabel(85907L, "  ", "#a2eeef");
            Label result = processor.callFindOrCreateLabel(dto, testRepo);
            assertThat(result).isNull();
        }
    }

    // Context Resolution

    @Nested
    class ContextResolution {

        @Test
        void returnsNullWhenFiltered() {
            when(repositoryScopeFilter.isRepositoryAllowed("org/repo")).thenReturn(false);
            var ctx = processor.callResolveContext("org/repo", "open");
            assertThat(ctx).isNull();
        }

        @Test
        void returnsNullWhenNotFound() {
            when(repositoryScopeFilter.isRepositoryAllowed("org/repo")).thenReturn(true);
            when(repositoryRepository.findByNameWithOwnerWithOrganization("org/repo"))
                    .thenReturn(Optional.empty());
            var ctx = processor.callResolveContext("org/repo", "open");
            assertThat(ctx).isNull();
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"  "})
        void returnsNullForNullOrBlankPath(String path) {
            var ctx = processor.callResolveContext(path, "open");
            assertThat(ctx).isNull();
        }
    }

    // ID Mapping (delegated to GitLabSyncConstants)

    @Nested
    class IdMapping {

        @ParameterizedTest(name = "toEntityId({0}) = {1}")
        @CsvSource({"1, 1", "42, 42", "18024, 18024", "422296, 422296"})
        void returnsRawIds(long rawId, long expectedEntityId) {
            assertThat(GitLabSyncConstants.toEntityId(rawId)).isEqualTo(expectedEntityId);
        }

        @ParameterizedTest(name = "extractEntityId(\"{0}\") = {1}")
        @CsvSource({
            "gid://gitlab/User/18024, 18024",
            "gid://gitlab/Issue/422296, 422296",
            "gid://gitlab/Project/246765, 246765",
            "gid://gitlab/Label/85907, 85907",
        })
        void extractsNumericIds(String globalId, long expectedEntityId) {
            assertThat(GitLabSyncConstants.extractEntityId(globalId)).isEqualTo(expectedEntityId);
        }
    }

    // Test Processor (exposes protected methods)

    private static class TestProcessor extends BaseGitLabProcessor {

        TestProcessor(
                GitLabUserService gitLabUserService,
                UserRepository userRepository,
                LabelRepository labelRepository,
                RepositoryRepository repositoryRepository,
                ScopeIdResolver scopeIdResolver,
                RepositoryScopeFilter repositoryScopeFilter,
                GitLabProperties gitLabProperties) {
            super(
                    gitLabUserService,
                    userRepository,
                    labelRepository,
                    repositoryRepository,
                    scopeIdResolver,
                    repositoryScopeFilter,
                    gitLabProperties);
        }

        @Nullable
        Instant callParseGitLabTimestamp(String timestamp) {
            return parseGitLabTimestamp(timestamp);
        }

        @Nullable
        User callFindOrCreateUser(@Nullable GitLabWebhookUser dto) {
            return findOrCreateUser(dto, 1L);
        }

        @Nullable
        User callFindOrCreateUser(
                @Nullable String globalId,
                String username,
                String name,
                @Nullable String avatarUrl,
                @Nullable String webUrl) {
            return findOrCreateUser(GitLabUserLookup.of(globalId, username, name, avatarUrl, webUrl, null), 1L);
        }

        @Nullable
        Label callFindOrCreateLabel(@Nullable GitLabWebhookLabel dto, Repository repository) {
            return findOrCreateLabel(dto, repository);
        }

        @Nullable
        ProcessingContext callResolveContext(String path, String action) {
            return resolveContext(path, action);
        }
    }
}
