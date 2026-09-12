import type { UseChatHelpers } from "@ai-sdk/react";
import { AlertCircle, RotateCcw } from "lucide-react";

import { cn } from "cn";
import type { ChatMessageVote } from "@/api/types.gen";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { useScrollToBottom } from "@/hooks/use-scroll-to-bottom";
import type { Attachment, ChatMessage } from "@/lib/types";

import { Messages } from "./Messages";
import { MultimodalInput } from "./MultimodalInput";
import type { PartRendererMap } from "./renderers/types";

export interface ChatProps {
	messages: ChatMessage[];
	votes?: ChatMessageVote[];
	status: UseChatHelpers<ChatMessage>["status"];
	readonly?: boolean;
	isAtBottom?: boolean;
	scrollToBottom?: () => void;
	attachments: Attachment[];
	onMessageSubmit: (data: { text: string; attachments: Attachment[] }) => void;
	onStop: () => void;
	onFileUpload: (files: File[]) => Promise<Attachment[]>;
	onAttachmentsChange: (attachments: Attachment[]) => void;
	onMessageEdit?: (messageId: string, content: string) => void;
	onCopy?: (content: string) => void;
	onVote?: (messageId: string, isUpvote: boolean) => void;
	onReload?: () => void;
	inputPlaceholder?: string;
	disableAttachments?: boolean;
	className?: string;
	partRenderers?: PartRendererMap;
}

export function Chat({
	messages,
	votes,
	status,
	readonly = false,
	isAtBottom: parentIsAtBottom = true,
	scrollToBottom: parentScrollToBottom,
	attachments,
	onMessageSubmit,
	onStop,
	onFileUpload,
	onAttachmentsChange,
	onMessageEdit,
	onCopy,
	onVote,
	onReload,
	inputPlaceholder = "Send a message...",
	disableAttachments = false,
	className,
	partRenderers,
}: ChatProps) {
	const { containerRef, endRef, isAtBottom, scrollToBottom } = useScrollToBottom();

	const actualIsAtBottom = parentScrollToBottom ? parentIsAtBottom : isAtBottom;
	const actualScrollToBottom = parentScrollToBottom ?? scrollToBottom;

	return (
		<div className={cn("relative h-full", className)}>
			<div className="flex flex-col h-full">
				<Messages
					messages={messages}
					votes={votes}
					status={status}
					readonly={readonly}
					showThinking={status === "submitted" || status === "streaming"}
					showGreeting={messages.length === 0}
					variant="default"
					containerRef={containerRef}
					endRef={endRef}
					onMessageEdit={onMessageEdit}
					onCopy={onCopy}
					onVote={onVote}
					partRenderers={partRenderers}
				/>

				<div className="flex flex-col gap-2 items-center w-full px-4 pb-2 -mt-20 relative z-10 bg-gradient-to-t from-muted dark:from-background/30 from-60% to-transparent pt-8">
					{status === "error" && (
						<div className="w-full max-w-3xl mb-2">
							<Alert variant="destructive">
								<AlertCircle className="size-4" />
								<AlertTitle>Something went wrong</AlertTitle>
								<AlertDescription className="flex items-center justify-between gap-4">
									<span>An error occurred while generating the response. Please try again.</span>
									{onReload && (
										<Button variant="outline" size="sm" onClick={onReload} className="shrink-0">
											<RotateCcw className="size-4" />
											Try again
										</Button>
									)}
								</AlertDescription>
							</Alert>
						</div>
					)}
					{!readonly && (
						<div className="w-full max-w-3xl">
							<MultimodalInput
								status={
									status === "submitted" || status === "streaming"
										? "submitted"
										: status === "error"
											? "error"
											: "ready"
								}
								onStop={onStop}
								attachments={attachments}
								onAttachmentsChange={onAttachmentsChange}
								onFileUpload={onFileUpload}
								onSubmit={onMessageSubmit}
								placeholder={inputPlaceholder}
								readonly={readonly}
								disableAttachments={disableAttachments}
								isAtBottom={actualIsAtBottom}
								scrollToBottom={actualScrollToBottom}
								isCurrentVersion={true}
								className="bg-background dark:bg-muted"
							/>
						</div>
					)}
					<p className="text-center text-balance text-xs text-muted-foreground px-4">
						Heph can make mistakes. Consider verifying important information.
					</p>
				</div>
			</div>
		</div>
	);
}
