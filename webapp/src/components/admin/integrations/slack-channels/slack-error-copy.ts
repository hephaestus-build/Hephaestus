import { hasText } from "@/lib/text";

/**
 * Slack's API answers with machine codes (`channel_not_found`, `not_in_channel`, …). Those are
 * for us, not for the admin reading the toast — so every code we can actually act on gets a
 * sentence that names the cause and the way out. Anything unmapped falls back to a generic
 * sentence rather than leaking the raw code.
 *
 * Codes: https://api.slack.com/methods/chat.postMessage#errors
 */
const SLACK_ERROR_COPY: Record<string, string> = {
	// Raised by our own probe, not by Slack, when nothing is configured to post to.
	no_channel_configured: "No channel is selected yet — choose a digest channel first.",
	channel_not_found:
		"Slack could not find that channel. It may have been deleted, or it is private and Hephaestus has not been invited to it.",
	not_in_channel:
		"Hephaestus is not a member of that channel. Invite it in Slack (/invite @Hephaestus), then try again.",
	is_archived: "That channel is archived. Pick a channel that is still in use.",
	channel_is_archived: "That channel is archived. Pick a channel that is still in use.",
	invalid_auth: "The Slack connection is no longer valid. Reconnect the Slack workspace.",
	token_revoked: "Slack access was revoked. Reconnect the Slack workspace.",
	account_inactive: "The Slack app was uninstalled or deactivated. Reconnect the Slack workspace.",
	missing_scope:
		"The Slack app is missing a permission it needs to post. Reconnect the Slack workspace to grant it.",
	not_authed: "Hephaestus has no Slack credentials for this workspace. Connect Slack first.",
	restricted_action: "Slack workspace settings do not allow the app to post in that channel.",
	rate_limited: "Slack is rate-limiting us right now. Wait a moment and try again.",
	ratelimited: "Slack is rate-limiting us right now. Wait a moment and try again.",
	msg_too_long: "The message was too long for Slack to accept.",
	invalid_arguments: "Slack rejected the request as malformed. Re-select the channel and retry.",
};

const GENERIC =
	"Slack rejected the request. Check that the channel still exists and that Hephaestus is a member of it.";

/** Turn a Slack error code into a sentence an admin can act on. */
export function slackErrorMessage(code?: string): string {
	if (!hasText(code)) {
		return GENERIC;
	}
	return SLACK_ERROR_COPY[code] ?? GENERIC;
}
