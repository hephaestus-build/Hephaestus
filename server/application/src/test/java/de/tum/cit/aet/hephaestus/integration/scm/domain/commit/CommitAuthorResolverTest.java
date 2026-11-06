package de.tum.cit.aet.hephaestus.integration.scm.domain.commit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class CommitAuthorResolverTest extends BaseUnitTest {

    @Mock
    private UserRepository userRepository;

    private CommitAuthorResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new CommitAuthorResolver(userRepository);
    }

    // Helper

    private User createUser(Long id) {
        User user = new User();
        user.setId(id);
        return user;
    }

    // extractLoginFromNoreply (static, no mocks needed)

    @Nested
    class ExtractLoginFromNoreply {

        @Test
        void shouldExtractLoginFromSimpleNoreply() {
            String login = CommitAuthorResolver.extractLoginFromNoreply("username@users.noreply.github.com");
            assertThat(login).isEqualTo("username");
        }

        @Test
        void shouldExtractLoginFromIdPrefixedNoreply() {
            String login = CommitAuthorResolver.extractLoginFromNoreply("12345+username@users.noreply.github.com");
            assertThat(login).isEqualTo("username");
        }

        @Test
        void shouldHandleLargeNumericIdPrefix() {
            String login =
                    CommitAuthorResolver.extractLoginFromNoreply("123456789+FelixTJDietrich@users.noreply.github.com");
            assertThat(login).isEqualTo("FelixTJDietrich");
        }

        @Test
        void shouldReturnNullForRegularEmail() {
            String login = CommitAuthorResolver.extractLoginFromNoreply("user@example.com");
            assertThat(login).isNull();
        }

        @Test
        void shouldReturnNullForPersonalEmail() {
            String login = CommitAuthorResolver.extractLoginFromNoreply("max_muster@example.com");
            assertThat(login).isNull();
        }

        @Test
        void shouldBeCaseInsensitiveForDomain() {
            String login = CommitAuthorResolver.extractLoginFromNoreply("user@Users.Noreply.GitHub.com");
            assertThat(login).isEqualTo("user");
        }

        @Test
        void shouldReturnNullForPartialNoreplyMatch() {
            String login = CommitAuthorResolver.extractLoginFromNoreply("user@noreply.github.com");
            assertThat(login).isNull();
        }
    }

    // resolveByEmail

    @Nested
    class ResolveByEmail {

        private static final Long PROVIDER = 7L;

        @Test
        void shouldReturnNullWhenTheEmailIsNull() {
            assertThat(resolver.resolveByEmail(null, PROVIDER)).isNull();
            verifyNoInteractions(userRepository);
        }

        @Test
        void shouldReturnNullWhenTheEmailIsBlank() {
            assertThat(resolver.resolveByEmail("  ", PROVIDER)).isNull();
            verifyNoInteractions(userRepository);
        }

        @Test
        void shouldReturnTheUserWhenExactlyOneUserHasTheEmail() {
            when(userRepository.findAllByEmailAndProviderId("a@example.com", PROVIDER))
                    .thenReturn(List.of(createUser(1L)));

            assertThat(resolver.resolveByEmail("a@example.com", PROVIDER)).isEqualTo(1L);
        }

        @Test
        void shouldReturnNullWhenSeveralUsersHaveTheEmail() {
            when(userRepository.findAllByEmailAndProviderId("a@example.com", PROVIDER))
                    .thenReturn(List.of(createUser(1L), createUser(2L)));

            assertThat(resolver.resolveByEmail("a@example.com", PROVIDER)).isNull();
        }

        @Test
        void shouldResolveTheLoginWhenTheEmailIsAGitHubNoreplyAddress() {
            String email = "12345+octocat@users.noreply.github.com";
            when(userRepository.findAllByEmailAndProviderId(email, PROVIDER)).thenReturn(List.of());
            when(userRepository.findGitHubUserIdByLogin("octocat", PROVIDER)).thenReturn(Optional.of(3L));

            assertThat(resolver.resolveByEmail(email, PROVIDER)).isEqualTo(3L);
        }

        @Test
        void shouldReturnNullWhenNoUserHasTheEmailAndItIsNoNoreplyAddress() {
            when(userRepository.findAllByEmailAndProviderId("ab12cde@mytum.de", PROVIDER))
                    .thenReturn(List.of());

            assertThat(resolver.resolveByEmail("ab12cde@mytum.de", PROVIDER)).isNull();
            verify(userRepository, never()).findGitHubUserIdByLogin(anyString(), anyLong());
        }
    }

    @Nested
    class ResolveByLogin {

        @Test
        void shouldReturnNullForNullLogin() {
            Long result = resolver.resolveByLogin(null, null);
            assertThat(result).isNull();
            verifyNoInteractions(userRepository);
        }

        @Test
        void shouldReturnNullForBlankLogin() {
            Long result = resolver.resolveByLogin("  ", null);
            assertThat(result).isNull();
            verifyNoInteractions(userRepository);
        }

        @Test
        void shouldReturnUserIdWhenLoginFound() {
            User user = createUser(33L);
            when(userRepository.findByLogin("testuser")).thenReturn(Optional.of(user));

            Long result = resolver.resolveByLogin("testuser", null);

            assertThat(result).isEqualTo(33L);
        }

        @Test
        void shouldReturnNullWhenLoginNotFound() {
            when(userRepository.findByLogin("unknown")).thenReturn(Optional.empty());

            Long result = resolver.resolveByLogin("unknown", null);

            assertThat(result).isNull();
        }
    }
}
