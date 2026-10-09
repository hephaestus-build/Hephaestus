package de.tum.cit.aet.hephaestus.workspace.validation;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WorkspaceSlugValidatorTest extends BaseUnitTest {
    @ParameterizedTest
    @ValueSource(strings = {"a", "1", "ls1intum", "team-one", "ab-cd"})
    void shouldAcceptSlugWhenItIsAnAssignableDnsLabel(String slug) {
        assertThat(WorkspaceSlugValidator.isAssignable(slug)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "Team",
                "tëam",
                "team_name",
                "-team",
                "team-",
                "a--b",
                "ab--cd",
                "team--one",
                "xn--example",
                "team.name",
                "www",
                "api",
                "docs",
                "admin",
                "auth",
                "login",
                "mail",
                "status",
                "staging",
                "preview",
                "pr123",
                "info",
                "marketing",
                "sales",
                "support",
                "abuse",
                "noc",
                "security",
                "postmaster",
                "hostmaster",
                "usenet",
                "news",
                "webmaster",
                "uucp",
                "ftp",
                "w",
                "settings",
                "integrations",
                "consent",
                "unsubscribe"
            })
    void shouldRejectSlugWhenItIsInvalidOrReserved(String slug) {
        assertThat(WorkspaceSlugValidator.isAssignable(slug)).isFalse();
    }

    @Test
    void shouldBoundSlugLengthAtTheDnsLimit() {
        assertThat(WorkspaceSlugValidator.isAssignable("a".repeat(63))).isTrue();
        assertThat(WorkspaceSlugValidator.isAssignable("a".repeat(64))).isFalse();
    }
}
