package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ConversationLoopPropertiesTest extends BaseUnitTest {

    @Test
    void shouldAcceptAWellFormedSecretAndNoSecret() {
        var configured = new ConversationLoopProperties("0123456789abcdef".repeat(4));
        var empty = new ConversationLoopProperties("");

        assertThatCode(configured::requireFormat).doesNotThrowAnyException();
        assertThatCode(empty::requireFormat).doesNotThrowAnyException();
        assertThatCode(new ConversationLoopProperties(null)::requireFormat).doesNotThrowAnyException();
        assertThat(configured.configured()).isTrue();
        assertThat(empty.configured()).as("an empty secret is not configured").isFalse();
    }

    /** A wrong value may still be a real key, so the message names only the property. */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF",
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcde"
            })
    void shouldRefuseASecretOfTheWrongFormatWithoutQuotingIt(String secret) {
        var properties = new ConversationLoopProperties(secret);

        assertThatThrownBy(properties::requireFormat)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("hephaestus.conversation-loop.secret")
                .message()
                .doesNotContain(secret);
    }
}
