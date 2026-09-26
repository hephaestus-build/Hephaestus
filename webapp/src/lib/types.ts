import type { UIMessage } from "ai";

/**
 * Custom UI data types streamed by the Pi mentor.
 *
 * The Pi mentor emits a single custom data part today (`data-usage` for token
 * accounting, which the client currently ignores). Keep this open enough to
 * absorb future server-side additions without coupling the webapp to a
 * generated TypeScript schema.
 */
export type CustomUIDataTypes = Record<string, unknown>;

/**
 * Token usage block — mirror of {@code UIMessageChunk.FinishMetadata.Usage} on the Java side
 * (server/.../mentor/chat/wire/UIMessageChunk.java). Every field is optional
 * because Pi providers vary in what they report (e.g. gpt-oss-120b returns input+output+totalTokens
 * but no cache fields).
 */
export interface UsageMetadata {
	input?: number;
	output?: number;
	cacheRead?: number;
	cacheWrite?: number;
	totalTokens?: number;
}

/**
 * Message metadata attached to mentor messages. The Java side ships this on every {@code finish}
 * chunk via {@code UIMessageChunk.FinishMetadata}; both sides must stay in lock-step. The
 * persistence layer also writes these fields to {@code chat_message.metadata} JSONB so the
 * GET-thread endpoint returns the same shape. `createdAt` is NOT here — it's a top-level
 * field on the persisted message row (see {@code ChatMessageDTO.createdAt} on the Java side).
 */
export interface MessageMetadata {
	/** LLM model id, e.g. "openai/gpt-oss-120b". */
	model?: string;
	/** Token usage breakdown. */
	usage?: UsageMetadata;
	/** Computed dollar cost (Pi-reported if available, else priced from {@code model_pricing}). */
	costUsd?: number;
	/**
	 * Stored turn status, present only on messages read back from the server. `interrupted` means the
	 * reply stopped before it finished, so its text is incomplete.
	 */
	status?: "in_flight" | "completed" | "interrupted";
}

/**
 * Tool registry placeholder.
 *
 * The Pi mentor surface currently has no client-rendered tools. When tools
 * are reintroduced, declare them here (`{ toolName: { input, output } }`)
 * and the renderer map will pick them up automatically.
 */
export type ChatTools = Record<string, { input: unknown; output: unknown }>;

/**
 * Chat message type for the mentor surface.
 *
 * Aligns with AI SDK's UIMessage so `useChat<ChatMessage>` and
 * `readUIMessageStream` consume it correctly. Runtime validation lives in
 * `lib/chat-validation.ts`.
 */
export type ChatMessage = UIMessage<MessageMetadata, CustomUIDataTypes, ChatTools>;

export interface Attachment {
	name: string;
	url: string;
	contentType: string;
}
