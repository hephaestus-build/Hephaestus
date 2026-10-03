import type { PrepareSendMessagesRequest } from "ai";

import type { ChatMessage } from "@/lib/types";

type SendOptions = Parameters<PrepareSendMessagesRequest<ChatMessage>>[0];

/** The body `POST …/mentor/chat` reads: the thread, the latest message, and what a retry replaces. */
export interface MentorTurnBody {
	id: string;
	message: ChatMessage | undefined;
	trigger: SendOptions["trigger"];
	messageId: string | undefined;
}

/**
 * One mentor turn's request body, for every client that talks to the mentor. Only the latest message
 * travels: the server rebuilds context and parent linkage from the thread id, so anything else in
 * `messages` is bytes it ignores. A custom body replaces the SDK's default one, so the trigger and the
 * replaced reply have to be carried over by hand.
 */
export function mentorTurnBody(
	options: Pick<SendOptions, "id" | "messages" | "trigger" | "messageId" | "requestMetadata">,
	fallbackThreadId: string,
): MentorTurnBody {
	return {
		id: options.id || fallbackThreadId,
		message: options.messages.at(-1),
		trigger: options.trigger,
		messageId: options.messageId ?? retriedReplyOf(options.requestMetadata),
	};
}

function retriedReplyOf(requestMetadata: unknown): string | undefined {
	return typeof requestMetadata === "object" &&
		requestMetadata !== null &&
		"retryOf" in requestMetadata &&
		typeof requestMetadata.retryOf === "string"
		? requestMetadata.retryOf
		: undefined;
}

/** What `regenerate` is called with to answer the latest prompt again. */
export type RetryOptions = { messageId: string } | { metadata: { retryOf: string } } | undefined;

/**
 * How to answer the latest prompt again, and which reply that replaces. `regenerate` drops the failed
 * reply before it posts and only accepts a reply still in the list, so after a retry refused before a
 * new reply started, the reply it replaced travels as request metadata instead.
 *
 * @param dropped the reply the previous retry replaced, if that retry never produced a new one
 */
export function retryPlan(
	messages: readonly ChatMessage[],
	dropped: string | undefined,
): { replaces: string | undefined; options: RetryOptions } {
	const last = messages.at(-1);
	if (last?.role === "assistant") {
		return { replaces: last.id, options: { messageId: last.id } };
	}
	return {
		replaces: dropped,
		options: dropped === undefined ? undefined : { metadata: { retryOf: dropped } },
	};
}
