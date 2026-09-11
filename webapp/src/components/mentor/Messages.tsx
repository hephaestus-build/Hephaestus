import type { UseChatHelpers } from "@ai-sdk/react";
import { isStaticToolUIPart } from "ai";
import { motion } from "motion/react";
import type { RefObject } from "react";

import { cn } from "cn";
import type { ChatMessageVote } from "@/api/types.gen";
import { ScrollArea } from "@/components/ui/scroll-area";
import type { ChatMessage, ChatTools } from "@/lib/types";

import { Greeting } from "./Greeting";
import { PreviewMessage, ThinkingMessage } from "./Message";
import type { PartRendererMap } from "./renderers/types";

export interface MessagesProps {
	messages: ChatMessage[];
	votes?: Array<ChatMessageVote>;
	status: UseChatHelpers<ChatMessage>["status"];
	readonly?: boolean;
	showThinking?: boolean;
	showGreeting?: boolean;
	variant?: "default" | "artifact";
	containerRef?: RefObject<HTMLDivElement | null>;
	endRef?: RefObject<HTMLDivElement | null>;
	onMessageEdit?: (messageId: string, newContent: string) => void;
	onCopy?: (content: string) => void;
	onVote?: (messageId: string, isUpvote: boolean) => void;
	className?: string;
	partRenderers?: PartRendererMap;
}

export function Messages({
	messages,
	votes,
	status,
	readonly = false,
	showThinking = true,
	showGreeting = true,
	variant = "default",
	containerRef,
	endRef,
	onMessageEdit,
	onCopy,
	onVote,
	className,
	partRenderers,
}: MessagesProps) {
	const isArtifact = variant === "artifact";

	const hasVisibleContent = (message: ChatMessage): boolean => {
		const parts = message.parts;
		if (parts.length === 0) return false;
		for (const p of parts) {
			if (p.type === "text" && p.text.trim().length > 0) return true;
			if (p.type === "file") return true;
			// Every state a tool part can be in renders something, so reaching one is visible content.
			if (isStaticToolUIPart<ChatTools>(p)) return true;
		}
		return false;
	};

	return (
		<ScrollArea className="flex flex-col w-full flex-1 min-h-0" viewportRef={containerRef}>
			<div
				role="log"
				aria-live="polite"
				aria-relevant="additions text"
				aria-busy={status === "streaming" || status === "submitted"}
				className={cn(
					"flex flex-col w-full pb-16",
					{
						"min-w-0 gap-2 flex-1 pt-4 relative mx-auto md:max-w-3xl": !isArtifact,
						"gap-2 flex-1 px-0 pt-4": isArtifact,
						"gap-4": readonly,
					},
					className,
				)}
			>
				{messages.length === 0 && showGreeting && <Greeting />}

				{messages.map((message, index) => {
					const isLast = index === messages.length - 1;
					const hideEmptyAssistantPlaceholder =
						isLast &&
						message.role === "assistant" &&
						!hasVisibleContent(message) &&
						showThinking &&
						(status === "submitted" || status === "streaming");

					if (hideEmptyAssistantPlaceholder) return null;

					return (
						<PreviewMessage
							key={message.id}
							message={message}
							isLoading={status === "streaming" && messages.length - 1 === index}
							vote={votes?.find((vote) => vote.messageId === message.id)}
							readonly={readonly}
							variant={variant}
							onMessageEdit={onMessageEdit}
							onCopy={onCopy}
							onVote={onVote}
							partRenderers={partRenderers}
						/>
					);
				})}

				{showThinking &&
					(status === "submitted" || status === "streaming") &&
					(() => {
						const last = messages.at(-1);
						if (!last) return <ThinkingMessage />;
						const isUser = last.role === "user";
						const assistantHasVisible = last.role === "assistant" && hasVisibleContent(last);
						return isUser || !assistantHasVisible ? <ThinkingMessage /> : null;
					})()}

				<motion.div ref={endRef} className="shrink-0 min-w-[12px] min-h-[12px]" />
			</div>
		</ScrollArea>
	);
}
