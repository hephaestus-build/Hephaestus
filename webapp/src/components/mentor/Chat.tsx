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
			<div className="flex h-full flex-col">
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

				<div className="relative z-10 -mt-20 flex w-full flex-col items-center gap-2 bg-gradient-to-t from-muted from-60% to-transparent px-4 pt-8 pb-2 dark:from-background/30">
					{status === "error" && (
						<div className="mb-2 w-full max-w-3xl">
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
					<p className="px-4 text-center text-xs text-balance text-muted-foreground">
						Heph can make mistakes. Consider verifying important information.
					</p>
				</div>
			</div>
		</div>
	);
}
