import type { UIMessage } from "ai";
import type { z } from "zod";

import type { mentorStatusDataSchema, observationDataSchema } from "@/lib/chat-validation";

/**
 * Mirrors `UIMessageChunk.DataObservation` and `UIMessageChunk.DataMentorStatus`; read them through
 * `shownFeedbackText` and `mentorStatus`, which check the payload.
 */
export interface CustomUIDataTypes {
	[name: string]: unknown;
	observation: z.infer<typeof observationDataSchema>;
	"mentor-status": z.infer<typeof mentorStatusDataSchema>;
}

export interface MessageMetadata {
	/**
	 * Stored turn status, present only on messages read back from the server. `interrupted` means the
	 * reply stopped before it finished, so its text is incomplete.
	 */
	status?: "in_flight" | "completed" | "interrupted";
}

/**
 * Chat message type for the mentor surface: the AI SDK's `UIMessage`, so `useChat<ChatMessage>`
 * consumes it directly. Runtime validation lives in `lib/chat-validation.ts`.
 */
export type ChatMessage = UIMessage<MessageMetadata, CustomUIDataTypes>;

/**
 * Where the latest mentor turn stands. `warmingUp` means the turn waits for Heph's sandbox to start,
 * so its first words come late. A `busy` failure means Heph had no room to start the turn, so trying
 * later helps; `failed` is anything else.
 */
export type ChatTurn =
	| { kind: "ready" }
	| { kind: "submitted" | "streaming"; warmingUp: boolean }
	| { kind: "error"; failure: "busy" | "failed" };
