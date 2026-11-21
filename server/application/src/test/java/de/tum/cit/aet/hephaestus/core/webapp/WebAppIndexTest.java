package de.tum.cit.aet.hephaestus.core.webapp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.Base64;
import java.util.HashSet;
import org.junit.jupiter.api.Test;

class WebAppIndexTest extends BaseUnitTest {

    @Test
    void shouldRefuseAPageBuiltWithoutTheNoncePlaceholder() {
        assertThatIllegalStateException()
                .isThrownBy(
                        () -> WebAppIndex.requireNoncePlaceholder(
                                "<html><head></head><body><script type=\"module\" src=\"/assets/a.js\"></script></body></html>"))
                .withMessageContaining("vp run build:webapp");
        assertThatCode(() -> WebAppIndex.requireNoncePlaceholder(
                        "<script src=\"/assets/a.js\" nonce=\"" + WebAppIndex.NONCE_PLACEHOLDER + "\"></script>"))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldMakeA128BitNonceThatDiffersEachTime() {
        var nonces = new HashSet<String>();
        for (int i = 0; i < 100; i++) {
            String nonce = WebAppIndex.newNonce();
            assertThat(Base64.getDecoder().decode(nonce)).hasSize(16);
            nonces.add(nonce);
        }
        assertThat(nonces).hasSize(100);
    }
}
