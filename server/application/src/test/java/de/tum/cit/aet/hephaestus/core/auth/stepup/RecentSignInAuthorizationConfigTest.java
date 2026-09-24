package de.tum.cit.aet.hephaestus.core.auth.stepup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class RecentSignInAuthorizationConfigTest extends BaseUnitTest {

    @Test
    void shouldRegisterInfrastructureAdvisorWithoutInvokingPolicy() {
        RecentSignInPolicy policy = mock(RecentSignInPolicy.class);
        new ApplicationContextRunner()
                .withUserConfiguration(RecentSignInAuthorizationConfig.class)
                .withBean(RecentSignInPolicy.class, () -> policy)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeanFactory()
                                    .getBeanDefinition("recentSignInAuthorizationAdvisor")
                                    .getRole())
                            .isEqualTo(BeanDefinition.ROLE_INFRASTRUCTURE);
                    verifyNoInteractions(policy);
                });
    }
}
