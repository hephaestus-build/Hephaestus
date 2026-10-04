package de.tum.cit.aet.hephaestus.integration.slack.channel;

import de.tum.cit.aet.hephaestus.core.LoggingUtils;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackSendException;
import java.util.Optional;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps Slack domain exceptions to RFC-7807 {@code ProblemDetail} for every Slack REST route, per
 * {@code docs/contributor/api-error-handling.md}. Not-found / validation / auth flow through the global advice.
 */
@RestControllerAdvice(basePackages = "de.tum.cit.aet.hephaestus.integration.slack")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SlackChannelControllerAdvice {

    @ExceptionHandler(SlackSendException.class)
    ProblemDetail handleSlackSend(SlackSendException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(mapSlackError(exception.slackError()));
        problem.setTitle("Slack channel is not reachable");
        problem.setDetail(slackErrorDetail(exception.slackError()));
        problem.setProperty("slackError", LoggingUtils.sanitizeForLog(exception.slackError()));
        return problem;
    }

    @ExceptionHandler(SlackChannelConsentViolationException.class)
    ProblemDetail handleConsentViolation(SlackChannelConsentViolationException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setTitle("Slack channel consent violation");
        problem.setDetail(Optional.ofNullable(exception.getMessage())
                .map(LoggingUtils::sanitizeForLog)
                .filter(s -> !s.isBlank())
                .orElse("The channel state does not allow the requested consent transition."));
        return problem;
    }

    private static HttpStatus mapSlackError(String slackError) {
        return switch (slackError == null ? "" : slackError) {
            case "not_in_channel", "channel_not_found", "is_archived" -> HttpStatus.CONFLICT;
            case "missing_scope", "no_active_slack_connection" -> HttpStatus.PRECONDITION_FAILED;
            default -> HttpStatus.BAD_GATEWAY;
        };
    }

    private static String slackErrorDetail(String slackError) {
        return switch (slackError == null ? "" : slackError) {
            case "not_in_channel" -> "Invite Hephaestus to the Slack channel, then try again.";
            case "channel_not_found" -> "Check the Slack channel link or ID, then try again.";
            case "is_archived" -> "Unarchive the Slack channel or choose another channel.";
            case "missing_scope" -> "Reinstall the Slack app with the required scopes, then try again.";
            case "no_active_slack_connection" -> "Reconnect Slack for this workspace, then try again.";
            default -> "Slack rejected the channel operation. Check the channel and the app access, then try again.";
        };
    }
}
