import type { ChatMessageVote } from "@/api/types.gen";
import type { ChatMessage } from "@/lib/types";
import { STORY_NOW } from "@/stories/story-clock";

export function userMessage(id: string, text: string): ChatMessage {
	return { id, role: "user", parts: [{ type: "text", text }] };
}

export function hephReply(id: string, text: string): ChatMessage {
	return {
		id,
		role: "assistant",
		metadata: { status: "completed" },
		parts: [{ type: "text", text, state: "done" }],
	};
}

/** A reply that links one piece of feedback, with the prose Heph wrote around it. */
export const REPLY_WITH_FEEDBACK: ChatMessage = {
	id: "reply-feedback",
	role: "assistant",
	metadata: { status: "completed" },
	parts: [
		{ type: "text", text: "I looked at your latest pull request, **Cache the practice catalog**." },
		{
			type: "data-observation",
			id: "link-1",
			data: {
				observationId: "3f0c2b4e-8a1d-4c6e-9b7f-2d5e8a1c4b6f",
				text: "Your description names **the decision**, a five-minute cache, but not why it beat invalidating on write.",
			},
		},
		// Stored before links carried their feedback, so it shows nothing.
		{
			type: "data-observation",
			id: "link-2",
			data: { observationId: "c9bf9e57-1685-4c89-bafb-ff5af830be8a" },
		},
		{ type: "text", text: "What made you pick the time-based cache?" },
	],
};

export const CONVERSATION: ChatMessage[] = [
	userMessage("turn-1", "Why did I get feedback on how I describe my pull requests?"),
	hephReply(
		"turn-2",
		[
			"Your team asks for pull request descriptions that **explain significant decisions**. A reviewer can read *what* changed from the diff, but not *why* this approach won.",
			"",
			"In your last three pull requests, the descriptions list the changes but leave the reasons out.",
		].join("\n"),
	),
	userMessage("turn-3", "Can you show me what a better description looks like?"),
	hephReply(
		"turn-4",
		[
			"Here is one way to shape it:",
			"",
			"## Why",
			"",
			"Loading the catalog on every request made the practices page slow for large workspaces.",
			"",
			"## Decision",
			"",
			"- Cache the catalog for **five minutes**.",
			"- Invalidating on write was simpler to reason about, but three services write to it.",
			"",
			"```ts",
			"const catalog = await cache.get(workspaceId, loadCatalog, { ttl: minutes(5) });",
			"```",
			"",
			"- [x] Names the decision",
			"- [ ] Says what was given up",
		].join("\n"),
	),
	userMessage("turn-5", "And my latest one?"),
	REPLY_WITH_FEEDBACK,
];

export const CONVERSATION_VOTES: ChatMessageVote[] = [
	{ messageId: "turn-2", isUpvoted: true, updatedAt: new Date(STORY_NOW) },
	{ messageId: "turn-4", isUpvoted: false, updatedAt: new Date(STORY_NOW) },
];
