package de.tum.cit.aet.hephaestus.notification.email;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class EmailRendererTest extends BaseUnitTest {

    private final EmailRenderer renderer = EmailTestSupport.renderer();

    @ParameterizedTest
    @EnumSource(EmailKind.class)
    void shouldRenderSubjectTextAndHtmlForEveryKind(EmailKind kind) {
        Map<String, Object> model = new HashMap<>(
                switch (kind) {
                    case TEST_MESSAGE -> Map.of();
                    case PRODUCT_FEEDBACK -> Map.of("feedbackKind", "BUG");
                    case ACCOUNT_DELETION_SCHEDULED -> Map.of("purgeAfter", "14 September 2026 at 10:00 UTC");
                    case ACCOUNT_SECURITY_CHANGED -> Map.of("change", "A sign-in identity was linked to your account.");
                    case SURVEY_ENDED_SUMMARY -> Map.of("invited", 37L, "responded", 23L, "declined", 11L);
                    case SURVEY_INVITATION ->
                        Map.of(
                                "workspaceSlug",
                                "survey-team",
                                "surveyId",
                                "7bc509de-ce47-498f-bec3-2a5ea141c241",
                                "research",
                                false,
                                "reminder",
                                false);
                    case WORKSPACE_ALERT ->
                        Map.of(
                                "integration",
                                "GitHub",
                                "workspaceName",
                                "Delivery team",
                                "workspaceSlug",
                                "delivery-team",
                                "description",
                                "The installation was suspended.");
                });
        String unsubscribeUrl = EmailTestSupport.WEBAPP_URL + "/unsubscribe?token=opaque-token";
        if (kind.optional()) model.put("unsubscribeUrl", unsubscribeUrl);
        RenderedEmail rendered = renderer.render(kind, model);
        String actionPath =
                switch (kind) {
                    case TEST_MESSAGE, ACCOUNT_DELETION_SCHEDULED -> "";
                    case ACCOUNT_SECURITY_CHANGED -> "/settings";
                    case PRODUCT_FEEDBACK -> "/admin/feedback";
                    case SURVEY_ENDED_SUMMARY -> "/admin/surveys";
                    case SURVEY_INVITATION -> "/w/survey-team?survey=7bc509de-ce47-498f-bec3-2a5ea141c241";
                    case WORKSPACE_ALERT -> "/w/delivery-team/admin/settings";
                };
        String actionUrl = EmailTestSupport.WEBAPP_URL + actionPath;
        assertThat(rendered.text()).contains(actionUrl);
        assertThat(rendered.html()).contains("href=\"" + actionUrl + "\"");
        for (var entry : model.entrySet()) {
            if (entry.getKey().equals("feedbackKind")) continue;
            Object value = entry.getValue();
            if (value instanceof Boolean) continue;
            assertThat(rendered.text()).contains(value.toString());
            assertThat(rendered.html()).contains(value.toString());
        }
        if (!kind.optional()) {
            assertThat(rendered.text()).doesNotContain("/unsubscribe");
            assertThat(rendered.html()).doesNotContain("/unsubscribe");
        }

        assertThat(rendered.subject()).isNotBlank().doesNotContain("email.");
        assertThat(rendered.text()).contains(rendered.subject());
        assertThat(rendered.html())
                .startsWith("<!DOCTYPE html>")
                .contains("<html lang=\"en\"")
                .contains("<title>" + rendered.subject() + "</title>")
                .contains("role=\"presentation\"")
                .doesNotContain(" th:", "xmlns:th");
    }

    @ParameterizedTest
    @EnumSource(de.tum.cit.aet.hephaestus.productfeedback.notification.ProductFeedbackSubmittedEvent.Kind.class)
    void shouldDescribeTheProductFeedbackKind(
            de.tum.cit.aet.hephaestus.productfeedback.notification.ProductFeedbackSubmittedEvent.Kind kind) {
        var rendered = renderer.render(
                EmailKind.PRODUCT_FEEDBACK,
                Map.of(
                        "feedbackKind",
                        kind.name(),
                        "unsubscribeUrl",
                        EmailTestSupport.WEBAPP_URL + "/unsubscribe?token=test"));
        String expected =
                switch (kind) {
                    case BUG -> "Someone reported a bug";
                    case IDEA -> "Someone suggested an idea";
                    case FEEDBACK -> "Someone shared general feedback";
                };
        String expectedSubject =
                switch (kind) {
                    case BUG -> "New bug report in Hephaestus";
                    case IDEA -> "New idea for Hephaestus";
                    case FEEDBACK -> "New product feedback in Hephaestus";
                };
        assertThat(rendered.subject()).isEqualTo(expectedSubject);
        assertThat(rendered.text()).contains(expected);
        assertThat(rendered.html()).contains(expected);
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
