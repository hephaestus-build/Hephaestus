package de.tum.cit.aet.hephaestus.integration.scm.gitlab.credentials;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@Tag("unit")
class GitLabTokenLifecycleRoleTest {
    @Test
    void shouldNotRequireTheServerLeaseProviderWhenTheServerRoleIsOff() {
        new ApplicationContextRunner()
                .withPropertyValues(
                        "hephaestus.runtime.server.enabled=false", "hephaestus.integration.gitlab.enabled=true")
                .withUserConfiguration(GitLabTokenLifecycleService.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(GitLabTokenLifecycleService.class);
                });
    }
}
