import { DefaultChatTransport } from "ai";

import { USER_AGENT } from "@/instance/instance";
import { session } from "@/session/session-store";

import type { HephMessage } from "./transcript";

/**
 * The global fetch here is Expo's, which streams response bodies. Every mentor request carries a
 * fresh bearer token and no cookies, and reacts to an ended session or a changed notice like any
 * other request.
 */
const authenticatedFetch: typeof fetch = async (input, init) => {
	const auth = await session.accessToken();
	const url = requestUrl(input);
	if (auth === undefined || !url.startsWith(`${auth.owner.apiBaseUrl}/`)) {
		throw new Error("Heph can only be reached while signed in to this Hephaestus");
	}
	const headers = new Headers(init?.headers);
	headers.set("Authorization", `Bearer ${auth.token}`);
	headers.set("User-Agent", USER_AGENT);
	const response = await fetch(input, { ...init, headers, credentials: "omit" });
	if (response.status === 401) {
		await session.unauthorized(auth.owner);
	} else if (response.status === 428) {
		session.consent(auth.owner, "required");
	}
	return response;
};

function requestUrl(input: RequestInfo | URL): string {
	if (typeof input === "string") {
		return input;
	}
	return input instanceof URL ? input.href : input.url;
}

/**
 * The mentor's existing protocol: one turn per request, carrying only the newest message. The server
 * rebuilds the conversation from the thread id, so the rest of `messages` would be bytes it ignores.
 */
export function hephTransport(apiBaseUrl: string, workspaceSlug: string, threadId: string) {
	return new DefaultChatTransport<HephMessage>({
		api: `${apiBaseUrl}/workspaces/${encodeURIComponent(workspaceSlug)}/mentor/chat`,
		fetch: authenticatedFetch,
		prepareSendMessagesRequest: ({ messages }) => ({
			body: { id: threadId, message: messages.at(-1) },
		}),
	});
}
