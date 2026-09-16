import type { ToolUIPart } from "ai";
import type React from "react";

import type { ChatMessage, ChatTools } from "@/lib/types";

export type ToolKey = keyof ChatTools;
export type ToolType<T extends ToolKey = ToolKey> = `tool-${T}`;

export type ToolPartState = ToolUIPart<ChatTools>["state"];

export interface ToolPart<T extends ToolKey = ToolKey> {
	type: ToolType<T>;
	toolCallId: string;
	state: ToolPartState;
	input?: ChatTools[T]["input"];
	output?: ChatTools[T]["output"];
	errorText?: string;
}

export interface PartRendererProps<T extends ToolKey = ToolKey> {
	message: ChatMessage;
	part: ToolPart<T>;
	/** Layout variant for different contexts */
	variant?: "default" | "artifact";
}

export type PartRenderer<T extends ToolKey = ToolKey> = (
	props: PartRendererProps<T>,
) => React.ReactElement | null;

export type PartRendererMap = Partial<{
	[K in ToolKey as ToolType<K>]: PartRenderer<K>;
}>;
