import { z } from "zod";

import { hasText } from "@/lib/text";
import type { ChatMessage } from "@/lib/types";

/** A `data-observation` part's data. A link stored without `text` showed nothing and proves nothing. */
export const observationDataSchema = z.object({
	observationId: z.uuid(),
	text: z.string().optional(),
});

/** A `data-mentor-status` part's data. */
export const mentorStatusDataSchema = z.object({
	state: z.string(),
	reason: z.string().nullish(),
});

/** Whether a streamed data part says Heph's sandbox is starting cold, so the reply will be slow. */
export function isWarmingUp(part: { type: string; data: unknown }): boolean {
	if (part.type !== "data-mentor-status") {
		return false;
	}
	const parsed = mentorStatusDataSchema.safeParse(part.data);
	return parsed.success && parsed.data.state === "warming-up";
}

/** Parsed where it is read, so a streamed part and a stored one pass the same check. */
export function shownFeedbackText(part: ChatMessage["parts"][number]): string | undefined {
	if (part.type !== "data-observation") {
		return undefined;
	}
	const parsed = observationDataSchema.safeParse(part.data);
	return parsed.success && hasText(parsed.data.text?.trim()) ? parsed.data.text : undefined;
}

/** What a part shows the reader: its text, or the feedback it carries; nothing for any other kind. */
export function visiblePartText(part: ChatMessage["parts"][number]): string | undefined {
	return part.type === "text" ? part.text : shownFeedbackText(part);
}

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
	// The SPA client revives dates; extension messaging carries the wire’s ISO timestamp.
	// UIMessage does not use this extra field, so both validated representations are preserved.
	createdAt: z.union([z.date(), z.iso.datetime({ offset: true })]).optional(),
});

const chatMessagesArraySchema = z.array(chatMessageSchema);

function isChatMessageArray(value: unknown): value is ChatMessage[] {
	return chatMessagesArraySchema.safeParse(value).success;
}

export function parseThreadMessages(messages: unknown): ChatMessage[] | undefined {
	return isChatMessageArray(messages) ? messages : undefined;
}
