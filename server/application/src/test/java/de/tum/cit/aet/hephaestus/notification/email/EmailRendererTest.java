package de.tum.cit.aet.hephaestus.notification.email;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class EmailRendererTest extends BaseUnitTest {

    private final EmailRenderer renderer = EmailTestSupport.renderer();

    @ParameterizedTest
    @EnumSource(EmailKind.class)
    void shouldRenderSubjectTextAndHtmlForEveryKind(EmailKind kind) {
        RenderedEmail rendered = renderer.render(kind, Map.of("purgeAfter", "14 September 2026 at 10:00 UTC"));

        assertThat(rendered.subject()).isNotBlank().doesNotContain("email.");
        assertThat(rendered.text()).contains(rendered.subject()).contains(EmailTestSupport.WEBAPP_URL);
        assertThat(rendered.html())
                .startsWith("<!DOCTYPE html>")
                .contains("<html lang=\"en\"")
                .contains("<title>" + rendered.subject() + "</title>")
                .contains("role=\"presentation\"")
                .contains("href=\"" + EmailTestSupport.WEBAPP_URL + "\"")
                .doesNotContain(" th:", "xmlns:th");
    }

    @Test
    void shouldEscapeModelValuesInHtmlButNotInText() {
        RenderedEmail rendered =
                renderer.render(EmailKind.ACCOUNT_DELETION_SCHEDULED, Map.of("purgeAfter", "<b>soon</b> & later"));

        assertThat(rendered.html())
                .contains("&lt;b&gt;soon&lt;/b&gt; &amp; later")
                .doesNotContain("<b>soon</b>");
        assertThat(rendered.text()).contains("<b>soon</b> & later");
    }

    @Test
    void shouldTellThePersonWhenThePurgeRuns() {
        RenderedEmail rendered = renderer.render(
                EmailKind.ACCOUNT_DELETION_SCHEDULED, Map.of("purgeAfter", "14 September 2026 at 10:00 UTC"));

        assertThat(rendered.subject()).isEqualTo("Your Hephaestus account is scheduled for deletion");
        assertThat(rendered.text())
                .contains("After 14 September 2026 at 10:00 UTC")
                .contains("no self-service undo");
    }
}
