package de.tum.cit.aet.hephaestus.core.release;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

@Tag("unit")
class ReleaseRoleContextTest {
    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ReleaseProperties.class)
    static class PropertiesConfiguration {}

    @TestConfiguration(proxyBeanMethods = false)
    @EnableMethodSecurity
    static class MethodSecurityConfiguration {}

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withBean(Clock.class, Clock::systemUTC)
            .withPropertyValues("spring.application.version=1.2.3")
            .withUserConfiguration(
                    PropertiesConfiguration.class,
                    ValidationAutoConfiguration.class,
                    RunningRelease.class,
                    ReleaseCheckClient.class,
                    ReleaseCheckService.class,
                    ReleaseAdminController.class);

    @Test
    void shouldCreateEveryReleaseBeanOnTheServerRole() {
        context.run(application -> assertThat(application)
                .hasNotFailed()
                .hasSingleBean(RunningRelease.class)
                .hasSingleBean(ReleaseCheckService.class)
                .hasSingleBean(ReleaseAdminController.class));
    }

    @Test
    void shouldKeepIdentityButNotDiscoveryWhenServerRoleIsDisabled() {
        context.withPropertyValues("hephaestus.runtime.server.enabled=false")
                .run(application -> assertThat(application)
                        .hasNotFailed()
                        .hasSingleBean(RunningRelease.class)
                        .doesNotHaveBean(ReleaseCheckClient.class)
                        .doesNotHaveBean(ReleaseCheckService.class)
                        .doesNotHaveBean(ReleaseAdminController.class));
    }

    @Test
    void shouldRefuseStartupOnAMalformedLockIdentity() {
        context.withPropertyValues("hephaestus.release.commit=abc")
                .run(application -> assertThat(application).hasFailed());
        context.withPropertyValues("hephaestus.release.image=ghcr.io/hephaestus-build/application-server:1.2.3")
                .run(application -> assertThat(application).hasFailed());
    }

    @Test
    void shouldRequireTheInstanceAdministratorAuthorityOnReadAndCheck() {
        context.withUserConfiguration(MethodSecurityConfiguration.class)
                .withPropertyValues("hephaestus.release.check-enabled=false")
                .run(application -> {
                    var controller = application.getBean(ReleaseAdminController.class);
                    SecurityContextHolder.clearContext();
                    try {
                        assertThatThrownBy(controller::get)
                                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
                        assertThatThrownBy(controller::check)
                                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
                        SecurityContextHolder.getContext()
                                .setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                                        "workspace-admin", "", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
                        assertThatThrownBy(controller::get).isInstanceOf(AccessDeniedException.class);
                        assertThatThrownBy(controller::check).isInstanceOf(AccessDeniedException.class);
                        SecurityContextHolder.getContext()
                                .setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                                        "instance-admin", "", List.of(new SimpleGrantedAuthority("app_admin"))));
                        var read = controller.get().getBody();
                        var checked = controller.check().getBody();
                        assertThat(read).isNotNull();
                        assertThat(checked).isNotNull();
                        assertThat(read.status()).isEqualTo(ReleaseCheckStatus.DISABLED);
                        assertThat(checked.lastAttempt()).isNull();
                    } finally {
                        SecurityContextHolder.clearContext();
                    }
                });
    }
}
