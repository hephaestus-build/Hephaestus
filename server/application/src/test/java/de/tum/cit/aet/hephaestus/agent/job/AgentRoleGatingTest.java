package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.tum.cit.aet.hephaestus.agent.handler.PublicReviewEligibility;
import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService;
import de.tum.cit.aet.hephaestus.agent.mentor.web.MentorConversationController;
import de.tum.cit.aet.hephaestus.agent.mentor.web.MentorLiveController;
import de.tum.cit.aet.hephaestus.agent.scan.ScanDriver;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventLogger;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import de.tum.cit.aet.hephaestus.core.settings.InstanceSettingsService;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.lang.reflect.ParameterizedType;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotatedBeanDefinitionReader;
import org.springframework.mock.env.MockEnvironment;

class AgentRoleGatingTest extends BaseUnitTest {

    private static final String AGENT_ON = RuntimeRole.AGENT_ENABLED_PROPERTY + "=true";

    @Test
    void shouldPollJobsOnlyWhenAgentJobsAndTheWorkerRoleAreOn() {
        assertThat(registers(ReviewJobExecutor.class)).isFalse();
        assertThat(registers(ReviewJobExecutor.class, AGENT_ON)).isTrue();
        assertThat(registers(ReviewJobExecutor.class, AGENT_ON, RuntimeRole.WORKER_PROPERTY + "=false"))
                .isFalse();
        assertThat(registers(ReviewJobExecutor.class, AGENT_ON, RuntimeRole.SERVER_PROPERTY + "=false"))
                .isTrue();
    }

    /** A worker that runs no practice reviews still runs Heph hosts, which other workers keep while it beats. */
    @Test
    void shouldBeatWheneverTheWorkerRoleIsOn() {
        assertThat(registers(WorkerLivenessReporter.class)).isTrue();
        assertThat(registers(WorkerLivenessReporter.class, RuntimeRole.SERVER_PROPERTY + "=false"))
                .isTrue();
        assertThat(registers(WorkerLivenessReporter.class, RuntimeRole.WORKER_PROPERTY + "=false"))
                .isFalse();
    }

    @ParameterizedTest
    @ValueSource(classes = {ReviewJobEventListener.class, ReviewJobCompletionService.class, ScanDriver.class})
    void shouldSubmitJobsOnlyWhenAgentJobsAreOn(Class<?> submitter) {
        assertThat(registers(submitter)).isFalse();
        assertThat(registers(submitter, AGENT_ON)).isTrue();
    }

    @Test
    void shouldMaintainJobsWheneverTheServerRoleIsOn() {
        assertThat(registers(ReviewJobZombieSweeper.class)).isTrue();
        assertThat(registers(ReviewJobZombieSweeper.class, AGENT_ON)).isTrue();
        assertThat(registers(ReviewJobZombieSweeper.class, AGENT_ON, RuntimeRole.SERVER_PROPERTY + "=false"))
                .isFalse();
    }

    @Test
    void shouldAllowSettingsServiceWithoutServerOnlyAuditLogger() {
        assertThat(InstanceSettingsService.class.isAnnotationPresent(ConditionalOnServerRole.class))
                .isFalse();
        assertThat(InstanceSettingsService.class.getDeclaredConstructors())
                .singleElement()
                .satisfies(constructor -> assertThat(constructor.getGenericParameterTypes())
                        .anySatisfy(type -> {
                            assertThat(type).isInstanceOf(ParameterizedType.class);
                            ParameterizedType parameterized = (ParameterizedType) type;
                            assertThat(parameterized.getRawType()).isEqualTo(Optional.class);
                            assertThat(parameterized.getActualTypeArguments()).containsExactly(AuthEventLogger.class);
                        }));
    }

    @Test
    void shouldBuildWorkerAdmissionWithoutServerControllers() {
        roleContext(false, true, false).run(context -> {
            assertThat(context)
                    .hasNotFailed()
                    .hasSingleBean(ResultAdmissionService.class)
                    .hasSingleBean(PublicReviewEligibility.class)
                    .hasSingleBean(ReviewCompositionContinuation.class)
                    .doesNotHaveBean(MentorConversationController.class)
                    .doesNotHaveBean(MentorLiveController.class);
        });
    }

    @Test
    void shouldBuildWebhookWithoutCaptureOrConversationBeans() {
        roleContext(false, false, true)
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .doesNotHaveBean(ResultAdmissionService.class)
                        .doesNotHaveBean(PublicReviewEligibility.class)
                        .doesNotHaveBean(ReviewCompositionContinuation.class)
                        .doesNotHaveBean(MentorConversationController.class)
                        .doesNotHaveBean(MentorLiveController.class));
    }

    private static ApplicationContextRunner roleContext(boolean server, boolean worker, boolean webhook) {
        var actual = List.of(
                ResultAdmissionService.class,
                PublicReviewEligibility.class,
                ReviewCompositionContinuation.class,
                MentorConversationController.class,
                MentorLiveController.class);
        return new ApplicationContextRunner()
                .withPropertyValues(
                        RuntimeRole.SERVER_PROPERTY + "=" + server,
                        RuntimeRole.WORKER_PROPERTY + "=" + worker,
                        RuntimeRole.WEBHOOK_PROPERTY + "=" + webhook)
                .withUserConfiguration(actual.toArray(Class<?>[]::new))
                .withInitializer(context -> {
                    var supplied = new HashSet<Class<?>>();
                    for (var type : actual)
                        for (var dependency : type.getDeclaredConstructors()[0].getParameterTypes()) {
                            if (!actual.contains(dependency) && supplied.add(dependency))
                                context.getBeanFactory().registerSingleton(dependency.getName(), mock(dependency));
                        }
                });
    }

    /** Evaluates the type's conditions as component scanning does, without instantiating it. */
    private static boolean registers(Class<?> type, String... properties) {
        MockEnvironment environment = new MockEnvironment();
        TestPropertyValues.of(properties).applyTo(environment);
        DefaultListableBeanFactory registry = new DefaultListableBeanFactory();
        new AnnotatedBeanDefinitionReader(registry, environment).registerBean(type);
        return registry.getBeanNamesForType(type).length > 0;
    }
}
