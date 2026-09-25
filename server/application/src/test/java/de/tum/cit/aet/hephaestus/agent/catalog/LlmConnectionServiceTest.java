package de.tum.cit.aet.hephaestus.agent.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.spi.LlmConnectionAudit;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.spi.LlmConnectionPlatform;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;

class LlmConnectionServiceTest extends BaseUnitTest {

    @Mock
    private LlmConnectionRepository connectionRepository;

    @Mock
    private LlmModelRepository modelRepository;

    @Mock
    private EgressPolicy egressPolicy;

    @Mock
    private LlmConnectionAudit llmConnectionAudit;

    @InjectMocks
    private LlmConnectionService connectionService;

    private CreateLlmConnectionRequestDTO createRequest() {
        return new CreateLlmConnectionRequestDTO(
                "openai-prod",
                "OpenAI",
                "https://api.openai.com",
                "openai-completions",
                LlmAuthMode.BEARER,
                "sk-abc",
                null,
                null);
    }

    @Nested
    class Create {

        @Test
        void createdConnectionIsAuditedWithItsIdAndSlug() {
            when(connectionRepository.findBySlug("openai-prod")).thenReturn(Optional.empty());
            when(connectionRepository.save(any(LlmConnection.class))).thenAnswer(inv -> {
                LlmConnection saved = inv.getArgument(0);
                saved.setId(9L);
                return saved;
            });

            LlmConnection result = connectionService.create(createRequest());

            assertThat(result.getId()).isEqualTo(9L);
            verify(llmConnectionAudit).connectionCreated(9L, "openai-prod");
        }

        @Test
        void rejectsDuplicateSlugWithoutAuditing() {
            when(connectionRepository.findBySlug("openai-prod")).thenReturn(Optional.of(new LlmConnection()));

            assertThatThrownBy(() -> connectionService.create(createRequest()))
                    .isInstanceOf(LlmConnectionSlugConflictException.class);

            verify(connectionRepository, never()).save(any());
            verifyNoInteractions(llmConnectionAudit);
        }

        @Test
        void doesNotCreateAConnectionWhoseBaseUrlTheEgressPolicyRejects() {
            when(connectionRepository.findBySlug("openai-prod")).thenReturn(Optional.empty());
            doThrow(new IllegalArgumentException("Provider host must be a public HTTPS URL"))
                    .when(egressPolicy)
                    .validate("https://api.openai.com");

            assertThatThrownBy(() -> connectionService.create(createRequest()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("public HTTPS URL");

            verify(connectionRepository, never()).save(any());
            verifyNoInteractions(llmConnectionAudit);
        }

        @Test
        void generatesCollisionSafeSlugWhenSlugIsOmitted() {
            CreateLlmConnectionRequestDTO request = new CreateLlmConnectionRequestDTO(
                    null,
                    "OpenAI",
                    "https://api.openai.com",
                    "openai-completions",
                    LlmAuthMode.BEARER,
                    null,
                    null,
                    null);
            when(connectionRepository.findBySlug("openai")).thenReturn(Optional.of(new LlmConnection()));
            when(connectionRepository.findBySlug("openai-2")).thenReturn(Optional.empty());
            when(connectionRepository.save(any(LlmConnection.class))).thenAnswer(inv -> inv.getArgument(0));

            LlmConnection result = connectionService.create(request);

            assertThat(result.getSlug()).isEqualTo("openai-2");
        }
    }

    @Nested
    class Delete {

        @Test
        void deletingAnUnreferencedConnectionAuditsTheSameRowItRemoved() {
            LlmConnection connection = new LlmConnection();
            connection.setId(5L);
            connection.setSlug("openai-prod");
            when(connectionRepository.findById(5L)).thenReturn(Optional.of(connection));
            when(modelRepository.existsByConnectionId(5L)).thenReturn(false);

            connectionService.delete(5L);

            ArgumentCaptor<LlmConnection> deleted = ArgumentCaptor.forClass(LlmConnection.class);
            verify(connectionRepository).delete(deleted.capture());
            assertThat(deleted.getValue().getId()).isEqualTo(5L);
            assertThat(deleted.getValue().getSlug()).isEqualTo("openai-prod");
            verify(llmConnectionAudit)
                    .connectionDeleted(
                            deleted.getValue().getId(), deleted.getValue().getSlug());
        }

        @Test
        void deletionInUseIsRejectedWithoutAuditing() {
            LlmConnection connection = new LlmConnection();
            connection.setId(5L);
            when(connectionRepository.findById(5L)).thenReturn(Optional.of(connection));
            when(modelRepository.existsByConnectionId(5L)).thenReturn(true);

            assertThatThrownBy(() -> connectionService.delete(5L)).isInstanceOf(LlmConnectionInUseException.class);

            verify(connectionRepository, never()).delete(any());
            verifyNoInteractions(llmConnectionAudit);
        }

        @Test
        void unknownConnectionRaisesNotFound() {
            when(connectionRepository.findById(404L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> connectionService.delete(404L)).isInstanceOf(EntityNotFoundException.class);
            verifyNoInteractions(llmConnectionAudit);
        }
    }

    @Nested
    class Update {

        private LlmConnection stored() {
            LlmConnection connection = new LlmConnection();
            connection.setId(5L);
            connection.setSlug("openai-prod");
            connection.setDisplayName("Old name");
            connection.setApiKey("sk-stored");
            connection.setEnabled(true);
            when(connectionRepository.findById(5L)).thenReturn(Optional.of(connection));
            when(connectionRepository.save(any(LlmConnection.class))).thenAnswer(inv -> inv.getArgument(0));
            return connection;
        }

        @Test
        void appliesTheSuppliedFieldsAndAuditsTheUpdate() {
            LlmConnection connection = stored();

            LlmConnection saved = connectionService.update(
                    5L, new UpdateLlmConnectionRequestDTO("New name", null, null, false, null, null));

            assertThat(saved.getDisplayName()).isEqualTo("New name");
            assertThat(saved.isEnabled()).isFalse();
            assertThat(saved).isSameAs(connection);
            verify(llmConnectionAudit).connectionUpdated(5L, "openai-prod");
        }

        @Test
        void leavesOmittedFieldsUntouched() {
            LlmConnection connection = stored();

            connectionService.update(5L, new UpdateLlmConnectionRequestDTO(null, null, null, null, null, null));

            assertThat(connection.getDisplayName()).isEqualTo("Old name");
            assertThat(connection.getApiKey()).isEqualTo("sk-stored");
            assertThat(connection.isEnabled()).isTrue();
        }

        @Test
        void clearingTheApiKeyBeatsASuppliedOne() {
            LlmConnection connection = stored();

            connectionService.update(5L, new UpdateLlmConnectionRequestDTO(null, "sk-new", true, null, null, null));

            assertThat(connection.getApiKey()).isNull();
        }

        @Test
        void aSuppliedApiKeyReplacesTheStoredOne() {
            LlmConnection connection = stored();

            connectionService.update(5L, new UpdateLlmConnectionRequestDTO(null, "sk-new", false, null, null, null));

            assertThat(connection.getApiKey()).isEqualTo("sk-new");
        }

        @Test
        void declaredConnectionPlatformCanBeChangedAndCleared() {
            LlmConnection connection = stored();

            connectionService.update(
                    5L, new UpdateLlmConnectionRequestDTO(null, null, null, null, LlmConnectionPlatform.AZURE, null));
            assertThat(connection.getConnectionPlatform()).isEqualTo(LlmConnectionPlatform.AZURE);
            assertThat(LlmConnectionDTO.from(connection).connectionPlatform()).isEqualTo(LlmConnectionPlatform.AZURE);

            connectionService.update(
                    5L, new UpdateLlmConnectionRequestDTO(null, null, null, null, LlmConnectionPlatform.OPENAI, true));
            assertThat(connection.getConnectionPlatform()).isNull();
        }

        @Test
        void unknownConnectionRaisesNotFoundWithoutAuditing() {
            when(connectionRepository.findById(404L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> connectionService.update(
                            404L, new UpdateLlmConnectionRequestDTO("New name", null, null, null, null, null)))
                    .isInstanceOf(EntityNotFoundException.class);
            verifyNoInteractions(llmConnectionAudit);
        }
    }
}
