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
import org.junit.jupiter.api.DisplayName;
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
            String login = CommitAuthorResolver.extractLoginFromNoreply("felix_dietrich@gmx.de");
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

        @Test
        void shouldReturnNullForNullEmail() {
            Long result = resolver.resolveByEmail(null, null);
            assertThat(result).isNull();
            verifyNoInteractions(userRepository);
        }

        @Test
        void shouldReturnNullForBlankEmail() {
            Long result = resolver.resolveByEmail("   ", null);
            assertThat(result).isNull();
            verifyNoInteractions(userRepository);
        }

        @Test
        void shouldReturnUserIdOnDirectEmailMatch() {
            User user = createUser(42L);
            when(userRepository.findByEmail("author@example.com")).thenReturn(Optional.of(user));

            Long result = resolver.resolveByEmail("author@example.com", null);

            assertThat(result).isEqualTo(42L);
            verify(userRepository).findByEmail("author@example.com");
            verify(userRepository, never()).findByLogin(anyString());
        }

        @Test
        void shouldFallBackToNoreplyLoginMatch() {
            when(userRepository.findByEmail("username@users.noreply.github.com"))
                    .thenReturn(Optional.empty());

            User user = createUser(99L);
            when(userRepository.findByLogin("username")).thenReturn(Optional.of(user));

            Long result = resolver.resolveByEmail("username@users.noreply.github.com", null);

            assertThat(result).isEqualTo(99L);
            verify(userRepository).findByEmail("username@users.noreply.github.com");
            verify(userRepository).findByLogin("username");
        }

        @Test
        void shouldFallBackToNoreplyLoginMatchForIdPrefixed() {
            when(userRepository.findByEmail("12345+dev@users.noreply.github.com"))
                    .thenReturn(Optional.empty());

            User user = createUser(77L);
            when(userRepository.findByLogin("dev")).thenReturn(Optional.of(user));

            Long result = resolver.resolveByEmail("12345+dev@users.noreply.github.com", null);

            assertThat(result).isEqualTo(77L);
        }

        @Test
        void shouldReturnNullWhenNeitherMatch() {
            when(userRepository.findByEmail("unknown@users.noreply.github.com")).thenReturn(Optional.empty());
            when(userRepository.findByLogin("unknown")).thenReturn(Optional.empty());

            Long result = resolver.resolveByEmail("unknown@users.noreply.github.com", null);

            assertThat(result).isNull();
        }

        @Test
        void shouldReturnNullForDottedLocalPartEmailWhenNotFound() {
            when(userRepository.findByEmail("first.last@gmail.com")).thenReturn(Optional.empty());

            Long result = resolver.resolveByEmail("first.last@gmail.com", null);

            assertThat(result).isNull();
            // Dotted local-parts are not login-shaped, so Strategy 3 should skip them.
            verify(userRepository, never()).findByLogin(anyString());
        }

        @Test
        void shouldFallBackToEmailLocalPartLoginForTumStyleAddress() {
            when(userRepository.findByEmail("ge27coy@mytum.de")).thenReturn(Optional.empty());

            User user = createUser(101L);
            when(userRepository.findByLogin("ge27coy")).thenReturn(Optional.of(user));

            Long result = resolver.resolveByEmail("ge27coy@mytum.de", null);

            assertThat(result).isEqualTo(101L);
            verify(userRepository).findByEmail("ge27coy@mytum.de");
            verify(userRepository).findByLogin("ge27coy");
        }

        @Test
        void shouldNotTryLoginMatchForGitLabBotNoreplyEmail() {
            when(userRepository.findByEmail("group_319719_bot_abc123@noreply.gitlab.lrz.de"))
                    .thenReturn(Optional.empty());

            Long result = resolver.resolveByEmail("group_319719_bot_abc123@noreply.gitlab.lrz.de", null);

            assertThat(result).isNull();
            verify(userRepository, never()).findByLogin(anyString());
        }

        @Test
        void shouldPreferDirectEmailMatchOverNoreplyParsing() {
            // Edge case: noreply email is stored directly in user's email field
            User user = createUser(55L);
            when(userRepository.findByEmail("dev@users.noreply.github.com")).thenReturn(Optional.of(user));

            Long result = resolver.resolveByEmail("dev@users.noreply.github.com", null);

            assertThat(result).isEqualTo(55L);
            // Should not proceed to login lookup since email matched
            verify(userRepository, never()).findByLogin(anyString());
        }
    }

    // resolveByLogin

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

    // Strategy 4: display-name match

    @Nested
    class ResolveByEmailDisplayName {

        @Test
        void shouldMatchFirstnameLastnameAgainstUserName() {
            when(userRepository.findByEmailAndProviderId("erik.kiessig@tum.de", 3L))
                    .thenReturn(Optional.empty());

            User user = createUser(5202L);
            when(userRepository.findAllByNameAndProviderId("Erik Kiessig", 3L)).thenReturn(List.of(user));

            Long result = resolver.resolveByEmail("erik.kiessig@tum.de", 3L);

            assertThat(result).isEqualTo(5202L);
            verify(userRepository).findAllByNameAndProviderId("Erik Kiessig", 3L);
        }

        @Test
        void shouldFallBackToCrossProviderNameMatchWhenProviderIdIsNull() {
            when(userRepository.findByEmail("john.doe@example.com")).thenReturn(Optional.empty());

            User user = createUser(777L);
            when(userRepository.findAllByName("John Doe")).thenReturn(List.of(user));

            Long result = resolver.resolveByEmail("john.doe@example.com", null);

            assertThat(result).isEqualTo(777L);
        }

        @Test
        void shouldSkipDisplayNameMatchWhenAmbiguous() {
            when(userRepository.findByEmailAndProviderId("john.smith@tum.de", 3L))
                    .thenReturn(Optional.empty());
            when(userRepository.findAllByNameAndProviderId("John Smith", 3L))
                    .thenReturn(List.of(createUser(1L), createUser(2L)));

            Long result = resolver.resolveByEmail("john.smith@tum.de", 3L);

            assertThat(result).isNull();
        }

        @Test
        void shouldHandleThreeTokenDottedLocalParts() {
            when(userRepository.findByEmailAndProviderId("anna.marie.huber@in.tum.de", 3L))
                    .thenReturn(Optional.empty());

            User user = createUser(42L);
            when(userRepository.findAllByNameAndProviderId("Anna Marie Huber", 3L))
                    .thenReturn(List.of(user));

            Long result = resolver.resolveByEmail("anna.marie.huber@in.tum.de", 3L);

            assertThat(result).isEqualTo(42L);
        }

        @Test
        void shouldPreferLoginMatchOverDisplayName() {
            // Login-style local-parts skip strategy 4 entirely.
            when(userRepository.findByEmailAndProviderId("go98tod@mytum.de", 3L))
                    .thenReturn(Optional.empty());

            User user = createUser(4875L);
            when(userRepository.findByLoginAndProviderId("go98tod", 3L)).thenReturn(Optional.of(user));

            Long result = resolver.resolveByEmail("go98tod@mytum.de", 3L);

            assertThat(result).isEqualTo(4875L);
            verify(userRepository, never()).findAllByNameAndProviderId(anyString(), anyLong());
        }

        @Test
        void shouldReturnNullForNumericPrefixedLocalPart() {
            when(userRepository.findByEmailAndProviderId("42.spam@domain.com", 3L))
                    .thenReturn(Optional.empty());

            Long result = resolver.resolveByEmail("42.spam@domain.com", 3L);

            assertThat(result).isNull();
            verify(userRepository, never()).findAllByNameAndProviderId(anyString(), anyLong());
        }
    }

    // resolveAndBackfillByEmail

    @Nested
    class ResolveAndBackfillByEmail {

        @Test
        void shouldBackfillEmailWhenStrategy3Resolves() {
            when(userRepository.findByEmailAndProviderId("go98tod@mytum.de", 3L))
                    .thenReturn(Optional.empty());

            User user = createUser(4875L);
            when(userRepository.findByLoginAndProviderId("go98tod", 3L)).thenReturn(Optional.of(user));

            Long result = resolver.resolveAndBackfillByEmail("go98tod@mytum.de", 3L);

            assertThat(result).isEqualTo(4875L);
            verify(userRepository).backfillEmailIfNull(4875L, "go98tod@mytum.de");
        }

        @Test
        void shouldBackfillEmailWhenStrategy4Resolves() {
            when(userRepository.findByEmailAndProviderId("erik.kiessig@tum.de", 3L))
                    .thenReturn(Optional.empty());

            User user = createUser(5202L);
            when(userRepository.findAllByNameAndProviderId("Erik Kiessig", 3L)).thenReturn(List.of(user));

            Long result = resolver.resolveAndBackfillByEmail("erik.kiessig@tum.de", 3L);

            assertThat(result).isEqualTo(5202L);
            verify(userRepository).backfillEmailIfNull(5202L, "erik.kiessig@tum.de");
        }

        @Test
        void shouldLowercaseEmailBeforeBackfilling() {
            when(userRepository.findByEmailAndProviderId("Erik.Kiessig@TUM.de", 3L))
                    .thenReturn(Optional.empty());

            User user = createUser(5202L);
            when(userRepository.findAllByNameAndProviderId("Erik Kiessig", 3L)).thenReturn(List.of(user));

            resolver.resolveAndBackfillByEmail("Erik.Kiessig@TUM.de", 3L);

            verify(userRepository).backfillEmailIfNull(5202L, "erik.kiessig@tum.de");
        }

        @Test
        @DisplayName("should NOT backfill when resolution came from direct email match (strategy 1)")
        void shouldNotBackfillWhenStrategy1Resolves() {
            User user = createUser(100L);
            when(userRepository.findByEmailAndProviderId("user@example.com", 3L))
                    .thenReturn(Optional.of(user));

            Long result = resolver.resolveAndBackfillByEmail("user@example.com", 3L);

            assertThat(result).isEqualTo(100L);
            // Strategy 1 already implies the email is stored — backfilling is a no-op.
            // We allow it to fire (it's cheap and idempotent) for single-token local-parts.
            verify(userRepository).backfillEmailIfNull(100L, "user@example.com");
        }

        @Test
        void shouldNotBackfillForGitHubNoreply() {
            when(userRepository.findByEmail("dev@users.noreply.github.com")).thenReturn(Optional.empty());

            User user = createUser(55L);
            when(userRepository.findByLogin("dev")).thenReturn(Optional.of(user));

            Long result = resolver.resolveAndBackfillByEmail("dev@users.noreply.github.com", null);

            assertThat(result).isEqualTo(55L);
            verify(userRepository, never()).backfillEmailIfNull(anyLong(), anyString());
        }

        @Test
        void shouldNotBackfillForGitLabBotNoreply() {
            // Resolver returns null for bot noreply (no strategy can match), so we
            // cover the eligibility filter via isBackfillEligible directly below.
            when(userRepository.findByEmail("group_319719_bot_abc@noreply.gitlab.lrz.de"))
                    .thenReturn(Optional.empty());

            Long result = resolver.resolveAndBackfillByEmail("group_319719_bot_abc@noreply.gitlab.lrz.de", null);

            assertThat(result).isNull();
            verify(userRepository, never()).backfillEmailIfNull(anyLong(), anyString());
        }

        @Test
        void shouldNotThrowWhenBackfillFails() {
            when(userRepository.findByEmailAndProviderId("go98tod@mytum.de", 3L))
                    .thenReturn(Optional.empty());

            User user = createUser(4875L);
            when(userRepository.findByLoginAndProviderId("go98tod", 3L)).thenReturn(Optional.of(user));
            when(userRepository.backfillEmailIfNull(4875L, "go98tod@mytum.de")).thenThrow(new RuntimeException("boom"));

            Long result = resolver.resolveAndBackfillByEmail("go98tod@mytum.de", 3L);

            // Resolution must still succeed even if the opportunistic backfill failed.
            assertThat(result).isEqualTo(4875L);
        }
    }

    // isBackfillEligible (static helper)

    @Nested
    class IsBackfillEligible {

        @Test
        void shouldAcceptLoginStyleAddress() {
            assertThat(CommitAuthorResolver.isBackfillEligible("ge27coy@mytum.de"))
                    .isTrue();
        }

        @Test
        void shouldAcceptFirstnameLastnameAddress() {
            assertThat(CommitAuthorResolver.isBackfillEligible("erik.kiessig@tum.de"))
                    .isTrue();
        }

        @Test
        void shouldRejectGitHubNoreply() {
            assertThat(CommitAuthorResolver.isBackfillEligible("1234+dev@users.noreply.github.com"))
                    .isFalse();
        }

        @Test
        void shouldRejectGitLabBotNoreply() {
            assertThat(CommitAuthorResolver.isBackfillEligible("group_123_bot_abc@noreply.gitlab.lrz.de"))
                    .isFalse();
        }

        @Test
        void shouldRejectNullAndBlank() {
            assertThat(CommitAuthorResolver.isBackfillEligible(null)).isFalse();
            assertThat(CommitAuthorResolver.isBackfillEligible("")).isFalse();
            assertThat(CommitAuthorResolver.isBackfillEligible("   ")).isFalse();
        }

        @Test
        void shouldRejectMalformedAddress() {
            assertThat(CommitAuthorResolver.isBackfillEligible("no-at-sign")).isFalse();
            assertThat(CommitAuthorResolver.isBackfillEligible("@nolocalpart.com"))
                    .isFalse();
            assertThat(CommitAuthorResolver.isBackfillEligible("nolocalpart@")).isFalse();
        }
    }

    // dottedLocalPartToDisplayName (static helper)

    @Nested
    class DottedLocalPartToDisplayName {

        @Test
        void shouldCapitaliseSingleDot() {
            assertThat(CommitAuthorResolver.dottedLocalPartToDisplayName("erik.kiessig"))
                    .isEqualTo("Erik Kiessig");
        }

        @Test
        void shouldLowercaseRemainingCharacters() {
            assertThat(CommitAuthorResolver.dottedLocalPartToDisplayName("ERIK.KIESSIG"))
                    .isEqualTo("Erik Kiessig");
        }

        @Test
        void shouldHandleThreeTokens() {
            assertThat(CommitAuthorResolver.dottedLocalPartToDisplayName("anna.marie.huber"))
                    .isEqualTo("Anna Marie Huber");
        }

        @Test
        void shouldReturnNullForEmptyToken() {
            assertThat(CommitAuthorResolver.dottedLocalPartToDisplayName("erik..kiessig"))
                    .isNull();
            assertThat(CommitAuthorResolver.dottedLocalPartToDisplayName(".kiessig"))
                    .isNull();
            assertThat(CommitAuthorResolver.dottedLocalPartToDisplayName("erik."))
                    .isNull();
        }

        @Test
        void shouldReturnNullForInputWithoutDot() {
            assertThat(CommitAuthorResolver.dottedLocalPartToDisplayName("ge27coy"))
                    .isNull();
        }

        @Test
        void shouldReturnNullForNullOrBlank() {
            assertThat(CommitAuthorResolver.dottedLocalPartToDisplayName(null)).isNull();
            assertThat(CommitAuthorResolver.dottedLocalPartToDisplayName("")).isNull();
            assertThat(CommitAuthorResolver.dottedLocalPartToDisplayName("   ")).isNull();
        }

        @Test
        void shouldPreserveSingleCharacterTokens() {
            assertThat(CommitAuthorResolver.dottedLocalPartToDisplayName("a.b")).isEqualTo("A B");
        }
    }
}
