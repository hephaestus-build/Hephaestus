package de.tum.cit.aet.hephaestus.integration.slack.messaging;

import de.tum.cit.aet.hephaestus.integration.slack.metrics.SlackMetrics.MembershipStatus;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.BooleanNode;

final class SlackMembership {

    private static final List<String> REQUIRED_NON_MEMBER_FLAGS =
            List.of("is_restricted", "is_ultra_restricted", "is_bot", "is_app_user");
    // Slack may omit the deleted field for an active user.
    private static final List<String> OPTIONAL_NON_MEMBER_FLAGS = List.of(
            "deleted",
            "is_stranger",
            "is_external",
            "is_invited_user",
            "is_profile_only_user",
            "is_forgotten",
            "is_connector_bot",
            "is_workflow_bot");

    private SlackMembership() {}

    static RestClientResponseException httpFailure(HttpStatusCode status) {
        // The inbox uses the status to classify the failure. Keep provider response data out of the exception.
        return new RestClientResponseException("Slack membership lookup failed", status, "", null, null, null);
    }

    static MembershipStatus status(JsonNode response, String teamId, String subject) {
        if (BooleanNode.FALSE.equals(response.path("ok"))) {
            String error = response.path("error").asString("");
            if (SlackSendException.isProviderUnavailable(error)) {
                throw httpFailure(HttpStatus.SERVICE_UNAVAILABLE);
            }
            return "user_not_found".equals(error) ? MembershipStatus.INELIGIBLE : MembershipStatus.PROVIDER;
        }
        var user = response.path("user");
        var userTeam = user.path("team_id").asString("");
        if (!BooleanNode.TRUE.equals(response.path("ok"))
                || !subject.equals(user.path("id").asString(""))
                || userTeam.isBlank()) return MembershipStatus.PAYLOAD;
        if (!teamId.equals(userTeam)
                || REQUIRED_NON_MEMBER_FLAGS.stream().anyMatch(flag -> BooleanNode.TRUE.equals(user.path(flag)))
                || OPTIONAL_NON_MEMBER_FLAGS.stream().anyMatch(flag -> BooleanNode.TRUE.equals(user.path(flag)))) {
            return MembershipStatus.INELIGIBLE;
        }
        boolean incomplete = REQUIRED_NON_MEMBER_FLAGS.stream()
                        .anyMatch(flag -> !user.path(flag).isBoolean())
                || OPTIONAL_NON_MEMBER_FLAGS.stream()
                        .anyMatch(flag -> user.has(flag) && !user.path(flag).isBoolean());
        return incomplete ? MembershipStatus.PAYLOAD : MembershipStatus.CONFIRMED;
    }

}
