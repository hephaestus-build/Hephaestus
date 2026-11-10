package de.tum.cit.aet.hephaestus.integration.chat.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountDirectory;
import de.tum.cit.aet.hephaestus.core.auth.spi.GitProviderRegistry;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentityResolver;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonProcessingSuppression;
import de.tum.cit.aet.hephaestus.core.security.OutlineOriginPolicy;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.docs.item.DocumentContentSource;
import de.tum.cit.aet.hephaestus.integration.docs.item.DocumentItemDescriptor;
import de.tum.cit.aet.hephaestus.integration.outline.OutlinePersonSourceIdentityContributor;
import de.tum.cit.aet.hephaestus.integration.outline.documentation.OutlineDocumentPermission;
import de.tum.cit.aet.hephaestus.integration.outline.documentation.OutlineDocumentProjector;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineDocumentRepository;
import de.tum.cit.aet.hephaestus.integration.outline.item.DocumentItemSource;
import de.tum.cit.aet.hephaestus.integration.slack.capture.ConversationCaptureSource;
import de.tum.cit.aet.hephaestus.integration.slack.capture.ThreadCaptureSource;
import de.tum.cit.aet.hephaestus.integration.slack.conversation.SlackConversationProjector;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMessageRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackThreadRepository;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackPersonErasureService;
import de.tum.cit.aet.hephaestus.integration.slack.item.ThreadItemSource;
import de.tum.cit.aet.hephaestus.practices.spi.ConversationFeedbackErasure;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import jakarta.persistence.EntityManagerFactory;
import java.time.Clock;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class ChatDocsRoleStartupTest extends BaseUnitTest {

    @Test
    void shouldPublishCaptureAndErasureWhenTheServerRoleStarts() {
        verifyCaptureRole();
    }

    @Test
    void shouldRetainPrivacyAttributionWhenOutlineCaptureIsDisabled() {
        new ApplicationContextRunner()
                .withUserConfiguration(
                        OutlinePersonSourceIdentityContributor.class,
                        OutlineDocumentPermission.class,
                        DocumentItemSource.class)
                .withPropertyValues(
                        "hephaestus.runtime.server.enabled=true", "hephaestus.integration.outline.enabled=false")
                .withBean(OutlineDocumentRepository.class, () -> mock(OutlineDocumentRepository.class))
                .withBean(ConnectionService.class, () -> mock(ConnectionService.class))
                .withBean(EntityManagerFactory.class, () -> mock(EntityManagerFactory.class))
                .withBean(GitProviderRegistry.class, () -> mock(GitProviderRegistry.class))
                .withBean(AccountDirectory.class, () -> mock(AccountDirectory.class))
                .withBean(PersonIdentityResolver.class, () -> mock(PersonIdentityResolver.class))
                .withBean(PersonProcessingSuppression.class, () -> mock(PersonProcessingSuppression.class))
                .withBean(OutlineOriginPolicy.class, () -> new OutlineOriginPolicy(Set.of()))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context)
                            .hasSingleBean(OutlinePersonSourceIdentityContributor.class)
                            .hasSingleBean(OutlineDocumentPermission.class)
                            .doesNotHaveBean(DocumentItemSource.class);
                });
    }

    private static void verifyCaptureRole() {
        var runner = new ApplicationContextRunner()
                .withUserConfiguration(
                        ThreadItemDescriptor.class,
                        DocumentItemDescriptor.class,
                        ThreadItemSource.class,
                        DocumentItemSource.class,
                        ThreadCaptureSource.class,
                        OutlineDocumentPermission.class,
                        OutlinePersonSourceIdentityContributor.class,
                        ConversationCaptureSource.class,
                        SlackPersonErasureService.class)
                .withPropertyValues(
                        "hephaestus.runtime.server.enabled=true",
                        "hephaestus.integration.slack.enabled=true",
                        "hephaestus.integration.outline.enabled=true")
                .withBean(SlackThreadRepository.class, () -> mock(SlackThreadRepository.class))
                .withBean(SlackMessageRepository.class, () -> mock(SlackMessageRepository.class))
                .withBean(SlackConversationProjector.class, () -> mock(SlackConversationProjector.class))
                .withBean(OutlineDocumentRepository.class, () -> mock(OutlineDocumentRepository.class))
                .withBean(OutlineDocumentProjector.class, () -> mock(OutlineDocumentProjector.class))
                .withBean(ConnectionService.class, () -> mock(ConnectionService.class))
                .withBean(EntityManagerFactory.class, () -> mock(EntityManagerFactory.class))
                .withBean(GitProviderRegistry.class, () -> mock(GitProviderRegistry.class))
                .withBean(AccountDirectory.class, () -> mock(AccountDirectory.class))
                .withBean(PersonIdentityResolver.class, () -> mock(PersonIdentityResolver.class))
                .withBean(PersonProcessingSuppression.class, () -> mock(PersonProcessingSuppression.class))
                .withBean(OutlineOriginPolicy.class, () -> new OutlineOriginPolicy(Set.of()))
                .withBean(ObjectMapper.class, JsonMapper::new)
                .withBean(
                        DocumentContentSource.class,
                        () -> new DocumentContentSource(JsonMapper.builder().build(), Clock.systemUTC()));
        runner.withBean(ConversationFeedbackErasure.class, () -> mock(ConversationFeedbackErasure.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context)
                            .hasSingleBean(ThreadItemSource.class)
                            .hasSingleBean(DocumentItemSource.class)
                            .hasSingleBean(OutlineDocumentPermission.class)
                            .hasSingleBean(OutlinePersonSourceIdentityContributor.class)
                            .hasSingleBean(ThreadCaptureSource.class)
                            .hasSingleBean(ConversationCaptureSource.class);
                    assertThat(context).hasSingleBean(SlackPersonErasureService.class);
                });
    }
}
