package de.tum.cit.aet.hephaestus.integration.scm.gitlab.user;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("unit")
class GitLabUserClassifierTest {

    @Test
    void shouldStateATypeOnlyWhereGitLabStatesIt() {
        assertThat(GitLabUserClassifier.nativeType(true)).isEqualTo("BOT");
        assertThat(GitLabUserClassifier.nativeType(false)).isEqualTo("USER");
        assertThat(GitLabUserClassifier.nativeType(null)).isNull();
    }

    @Test
    void shouldTakeGitLabsBotFlagOverTheUsernameForANewUser() {
        assertThat(GitLabUserClassifier.insertionType("heph_introcourse_tutor_e2e", true))
                .isEqualTo("BOT");
        assertThat(GitLabUserClassifier.insertionType("group_185885_bot_39ba88423f879e8c9ec140214eda9548", false))
                .isEqualTo("USER");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "group_185885_bot_39ba88423f879e8c9ec140214eda9548",
                "group_328643_bot_de8d541a82d8e790c0ad41b12a6823ed",
                "project_7_bot_abcdef0123456789",
            })
    void shouldGuessAnAccessTokenLoginIsABotForANewUserWithoutTheFlag(String login) {
        assertThat(GitLabUserClassifier.insertionType(login, null)).isEqualTo("BOT");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "ga84xah",
                "heph_introcourse_tutor_e2e",
                "00000000014B3DCE",
                "group_185885",
                "bot_project_42",
                "group_abc_bot_123",
                "group__bot_deadbeef",
                "group_1_bot_NOTHEX",
            })
    void shouldRecordANewUserWithoutTheFlagAsAUserOtherwise(String login) {
        assertThat(GitLabUserClassifier.insertionType(login, null)).isEqualTo("USER");
    }

    @ParameterizedTest
    @NullAndEmptySource
    void shouldRecordANewUserWithoutALoginOrFlagAsAUser(String login) {
        assertThat(GitLabUserClassifier.insertionType(login, null)).isEqualTo("USER");
    }
}
