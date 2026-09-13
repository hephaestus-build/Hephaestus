package de.tum.cit.aet.hephaestus.notification.email;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SurveyEmailRendererTest extends BaseUnitTest {
    @Test
    void shouldLinkToTheSelectedSurveyAndItsOwnUnsubscribeConfirmation() {
        UUID surveyId = UUID.randomUUID();
        String unsubscribeUrl = EmailTestSupport.WEBAPP_URL + "/unsubscribe?token=opaque-token";
        var rendered = EmailTestSupport.renderer()
                .render(
                        EmailKind.SURVEY_INVITATION,
                        Map.of(
                                "workspaceSlug",
                                "team",
                                "surveyId",
                                surveyId,
                                "research",
                                false,
                                "unsubscribeUrl",
                                unsubscribeUrl));

        String target = EmailTestSupport.WEBAPP_URL + "/w/team?survey=" + surveyId;
        assertThat(rendered.text()).contains(target, unsubscribeUrl, "Taking part is optional");
        assertThat(rendered.html()).contains("href=\"" + target + "\"", "href=\"" + unsubscribeUrl + "\"");
    }

    @Test
    void shouldDescribeParticipationCountsWithoutCallingEmailRequestsInAppInvitations() {
        var rendered = EmailTestSupport.renderer()
                .render(
                        EmailKind.SURVEY_ENDED_SUMMARY,
                        Map.of(
                                "invited",
                                3L,
                                "responded",
                                2L,
                                "declined",
                                1L,
                                "unsubscribeUrl",
                                EmailTestSupport.WEBAPP_URL + "/unsubscribe?token=opaque-token"));

        assertThat(rendered.text()).contains("People shown an in-app invitation: 3", "Responded: 2", "Declined: 1");
        assertThat(rendered.html())
                .contains("/admin/surveys", "<strong>3</strong>", "<strong>2</strong>", "<strong>1</strong>");
    }
}
