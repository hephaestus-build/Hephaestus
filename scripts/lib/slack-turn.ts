/**
 * TEST ONLY. What the turn-roll drill sends the server as Slack and as the member's browser: a signed Events API
 * request, and the cookie that binds an install to the browser that started it.
 */
import { createHash, createHmac } from "node:crypto";

/** Slack's `v0` signature of a request: HMAC-SHA256 over `v0:<timestamp>:<body>`. */
export function slackSignature(secret: string, timestamp: number, body: string): string {
	return `v0=${createHmac("sha256", secret).update(`v0:${timestamp}:${body}`).digest("hex")}`;
}

/** The Events API delivery of a member's direct message to the app. */
export function directMessageEvent(message: {
	team: string;
	user: string;
	channel: string;
	text: string;
	ts: string;
	eventId: string;
}): string {
	return JSON.stringify({
		type: "event_callback",
		team_id: message.team,
		event_id: message.eventId,
		event_time: Math.floor(Number(message.ts)),
		event: {
			type: "message",
			channel_type: "im",
			channel: message.channel,
			user: message.user,
			text: message.text,
			ts: message.ts,
		},
	});
}

/** The OAuth state that the server put in the vendor URL of an install. */
export function installState(vendorUrl: string): string {
	const state = new URL(vendorUrl).searchParams.get("state");
	if (state === null || state === "") {
		throw new Error("the install's vendor URL carries no state");
	}
	return state;
}

/**
 * The cookie that the browser which started an install sends back with its callback: a hash of the state, under a
 * name made from the hash (`OAuthBrowserBinding`).
 */
export function installCookie(state: string): string {
	const hash = createHash("sha256").update(state).digest("hex");
	return `__Host-CONNECT_${hash.slice(0, 16)}=${hash}`;
}
