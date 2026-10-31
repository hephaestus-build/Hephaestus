package de.tum.cit.aet.hephaestus.notification.email;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.WorkspaceSubdomainProperties;
import de.tum.cit.aet.hephaestus.productfeedback.notification.ProductFeedbackSubmittedEvent.Kind;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class EmailRendererTest extends BaseUnitTest {

    private final EmailRenderer renderer = EmailTestSupport.renderer();

    @Test
    void shouldUseWorkspaceAddressInBothEmailAlternatives() {
        var tenantRenderer = EmailTestSupport.renderer(new WorkspaceSubdomainProperties(true, "hephaestus.example"));
        var email = tenantRenderer.render(
                EmailKind.WORKSPACE_ALERT,
                Map.of(
                        "workspaceSlug",
                        "delivery-team",
                        "workspaceName",
                        "Delivery team",
                        "integration",
                        "GitHub",
                        "description",
                        "Reconnect GitHub",
                        "unsubscribeUrl",
                        "https://hephaestus.example/unsubscribe"));
        assertThat(email.text()).contains("https://delivery-team.hephaestus.example/admin/settings");
        assertThat(email.html()).contains("https://delivery-team.hephaestus.example/admin/settings");
        assertThat(email.text()).doesNotContain("/w/delivery-team");
    }

    @Test
    void shouldLinkTheWeeklyDigestOnTheWorkspaceAddress() {
        var tenantRenderer = EmailTestSupport.renderer(new WorkspaceSubdomainProperties(true, "hephaestus.example"));
        var email = tenantRenderer.render(
                EmailKind.MENTOR_DIGEST,
                Map.of(
                        "workspaceSlug",
                        "delivery-team",
                        "workspaceName",
                        "Delivery team",
                        "weekDays",
                        "5–11 October",
                        "entryId",
                        "0f8a6c1e-3b0d-4a43-9f43-3bd1d5a0e2c4",
                        "unsubscribeUrl",
                        "https://hephaestus.example/unsubscribe"));
        String link =
                "https://delivery-team.hephaestus.example/conversation?entry=0f8a6c1e-3b0d-4a43-9f43-3bd1d5a0e2c4";
        assertThat(email.text()).contains(link).doesNotContain("/w/delivery-team");
        assertThat(email.html()).contains("href=\"" + link + "\"");
    }

    @ParameterizedTest
    @EnumSource(EmailKind.class)
    void shouldRenderSubjectTextAndHtmlForEveryKind(EmailKind kind) {
        Map<String, Object> model = new HashMap<>(
                switch (kind) {
                    case TEST_MESSAGE -> Map.of();
                    case PRODUCT_FEEDBACK -> Map.of("feedbackKind", "BUG");
                    case PRODUCT_FEEDBACK_UPDATE ->
                        Map.of(
                                "updateKind",
                                "COMMENTED",
                                "issueUrl",
                                "https://github.com/example/feedback/issues/1",
                                "publicIssue",
                                true);
                    case ACCOUNT_DELETION_SCHEDULED -> Map.of("purgeAfter", "14 September 2026 at 10:00 UTC");
                    case ACCOUNT_SECURITY_CHANGED ->
                        Map.of("change", "A sign-in identity is now linked to your account.");
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
                    case MENTOR_DIGEST ->
                        Map.of(
                                "workspaceName",
                                "Delivery team",
                                "workspaceSlug",
                                "delivery-team",
                                "weekDays",
                                "5–11 October",
                                "entryId",
                                "0f8a6c1e-3b0d-4a43-9f43-3bd1d5a0e2c4");
                });
        String unsubscribeUrl = EmailTestSupport.WEBAPP_URL + "/unsubscribe?token=opaque-token";
        if (kind.optional()) model.put("unsubscribeUrl", unsubscribeUrl);
        RenderedEmail rendered = renderer.render(kind, model);
        String actionPath = switch (kind) {
            case TEST_MESSAGE, ACCOUNT_DELETION_SCHEDULED -> "";
            case ACCOUNT_SECURITY_CHANGED -> "/settings";
            case PRODUCT_FEEDBACK -> "/admin/feedback";
            case PRODUCT_FEEDBACK_UPDATE -> "/product-feedback";
            case WORKSPACE_ALERT -> "/w/delivery-team/admin/settings";
            case MENTOR_DIGEST -> "/w/delivery-team/conversation?entry=0f8a6c1e-3b0d-4a43-9f43-3bd1d5a0e2c4";
        };
        String actionUrl = EmailTestSupport.WEBAPP_URL + actionPath;
        assertThat(rendered.text()).contains(actionUrl);
        assertThat(rendered.html()).contains("href=\"" + actionUrl + "\"");
        for (var entry : model.entrySet()) {
            if (entry.getKey().equals("feedbackKind") || entry.getKey().equals("updateKind")) continue;
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
    @EnumSource(Kind.class)
    void shouldDescribeTheProductFeedbackKind(Kind kind) {
        var rendered = renderer.render(
                EmailKind.PRODUCT_FEEDBACK,
                Map.of(
                        "feedbackKind",
                        kind.name(),
                        "unsubscribeUrl",
                        EmailTestSupport.WEBAPP_URL + "/unsubscribe?token=test"));
        String expected = switch (kind) {
            case BUG -> "Someone reported a bug";
            case IDEA -> "Someone suggested an idea";
            case FEEDBACK -> "Someone shared general feedback";
        };
        String expectedSubject = switch (kind) {
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
                .contains("You cannot undo this yourself");
    }
}
