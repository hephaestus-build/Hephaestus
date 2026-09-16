import { z } from "zod";

import type { ChatMessage } from "@/lib/types";

/**
 * Unknown keys survive: the mentor streams part kinds this client does not model, and stripping
 * their payload would leave the renderer nothing to narrow on. `text` is the one payload checked,
 * because the AI SDK types it as always present and the renderer reads it unguarded — a text part
 * that omitted it would throw mid-transcript rather than render short.
 */
const messagePartSchema = z
	.looseObject({ type: z.string() })
	.refine((part) => part.type !== "text" || typeof part.text === "string", {
		message: "A text part must carry its text",
	});

/** Loose again: the AI SDK hangs its own bookkeeping off a message and the chat UI passes it back. */
const chatMessageSchema = z.looseObject({
	id: z.uuid(),
	role: z.enum(["system", "user", "assistant"]),
	parts: z.array(messagePartSchema),
	createdAt: z.iso.datetime().optional(),
});

const chatMessagesArraySchema = z.array(chatMessageSchema);

function isChatMessageArray(value: unknown): value is ChatMessage[] {
	return chatMessagesArraySchema.safeParse(value).success;
}

export function parseThreadMessages(messages: unknown): ChatMessage[] | undefined {
	return isChatMessageArray(messages) ? messages : undefined;
}

const voteSchema = z.object({
	messageId: z.uuid().optional(),
	isUpvoted: z.boolean().optional(),
});

const votesArraySchema = z.array(voteSchema);

/** Votes are decoration, so anything unreadable degrades to "no votes" and the transcript still renders. */
export function extractVotesFromThreadDetail(
	threadDetail: unknown,
): { messageId?: string; isUpvoted?: boolean }[] {
	if (threadDetail === null || typeof threadDetail !== "object") {
		return [];
	}

	if (!("votes" in threadDetail) || !Array.isArray(threadDetail.votes)) {
		return [];
	}

	const result = votesArraySchema.safeParse(threadDetail.votes);
	return result.success ? result.data : [];
}
