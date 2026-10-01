import type { UseChatHelpers } from "@ai-sdk/react";
import { AlertCircle, ArrowDown, RotateCcw } from "lucide-react";

import { AnimatePresence, motion } from "motion/react";

import { cn } from "cn";
import type { ChatMessageVote } from "@/api/types.gen";
import { useScrollToBottom } from "@/components/mentor/use-scroll-to-bottom";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import type { Attachment, ChatMessage } from "@/lib/types";

import { Messages } from "./Messages";
import { type AttachmentUpload, MultimodalInput } from "./MultimodalInput";

export interface ChatProps {
	messages: ChatMessage[];
	votes?: ChatMessageVote[];
	status: UseChatHelpers<ChatMessage>["status"];
	errorMessage?: string;
	readonly?: boolean;
	isAtBottom?: boolean;
	scrollToBottom?: () => void;
	attachments: Attachment[];
	onMessageSubmit: (data: { text: string; attachments: Attachment[] }) => void;
	onStop: () => void;
	attachmentUpload?: AttachmentUpload;
	onMessageEdit?: (messageId: string, content: string) => void;
	onCopy?: (content: string) => void;
	onVote?: (messageId: string, isUpvote: boolean) => void;
	onReload?: () => void;
	inputPlaceholder?: string;
	className?: string;
}

export function Chat({
	messages,
	votes,
	status,
	errorMessage,
	readonly = false,
	isAtBottom: parentIsAtBottom = true,
	scrollToBottom: parentScrollToBottom,
	attachments,
	onMessageSubmit,
	onStop,
	attachmentUpload,
	onMessageEdit,
	onCopy,
	onVote,
	onReload,
	inputPlaceholder = "Send a message...",
	className,
}: ChatProps) {
	const { containerRef, endRef, isAtBottom, scrollToBottom } = useScrollToBottom();

	const actualIsAtBottom = parentScrollToBottom ? parentIsAtBottom : isAtBottom;
	const actualScrollToBottom = parentScrollToBottom ?? scrollToBottom;

	// The live error is gone once the conversation is reopened, but a reply saved as interrupted can still
	// be tried again.
	const isBusy = status === "error" && errorMessage === "Heph is busy. Please try again.";
	const lastMessage = messages.at(-1);
	const canRetry =
		status === "error" ||
		(onReload !== undefined &&
			status === "ready" &&
			lastMessage?.role === "assistant" &&
			lastMessage.metadata?.status === "interrupted");

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
				/>

				<div className="relative z-10 -mt-20 flex w-full flex-col items-center gap-2 bg-gradient-to-t from-muted from-60% to-transparent px-4 pt-8 pb-2 dark:from-background/30">
					<AnimatePresence>
						{!actualIsAtBottom && !readonly && (
							<motion.div
								initial={{ opacity: 0, y: 10 }}
								animate={{ opacity: 1, y: 0 }}
								exit={{ opacity: 0, y: 10 }}
								transition={{ type: "spring", stiffness: 300, damping: 20 }}
								className="absolute -top-4 left-1/2 z-[95] -translate-x-1/2 rounded-full backdrop-blur-sm"
							>
								<Button
									aria-label="Scroll to latest message"
									shape="pill"
									className="border-border/50 bg-background/80 shadow-lg hover:bg-background/90 dark:bg-background/80 dark:hover:bg-background/90"
									size="icon"
									variant="outline"
									onClick={(event) => {
										event.preventDefault();
										actualScrollToBottom();
									}}
								>
									<ArrowDown />
								</Button>
							</motion.div>
						)}
					</AnimatePresence>
					{canRetry && (
						<div className="mb-2 w-full max-w-3xl">
							<Alert variant={isBusy ? "warning" : "destructive"}>
								<AlertCircle className="size-4" />
								<AlertTitle>{isBusy ? "Heph is busy" : "Something went wrong"}</AlertTitle>
								<AlertDescription className="flex items-center justify-between gap-4">
									<span>
										{isBusy
											? "Please try again in a moment."
											: "An error occurred while generating the response. Please try again."}
									</span>
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
								status={status === "streaming" ? "submitted" : status}
								onStop={onStop}
								attachments={attachments}
								attachmentUpload={attachmentUpload}
								onSubmit={onMessageSubmit}
								placeholder={inputPlaceholder}
								readonly={readonly}
								scrollToBottom={actualScrollToBottom}
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
