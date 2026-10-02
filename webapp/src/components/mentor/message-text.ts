import { visiblePartText } from "@/lib/chat-validation";
import { hasText } from "@/lib/text";
import type { ChatMessage } from "@/lib/types";

/**
 * The words a message shows the reader, in order: its text, and the feedback its observation links
 * carry. Reasoning, tool calls and any part kind this client does not model show nothing.
 */
export function visibleTexts(message: ChatMessage): string[] {
	return message.parts
		.map(visiblePartText)
		.filter((text): text is string => text !== undefined && hasText(text.trim()));
}
