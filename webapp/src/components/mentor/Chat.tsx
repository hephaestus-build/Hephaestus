import type { ChatStatus } from "ai";
import { AlertCircleIcon, RotateCcwIcon } from "lucide-react";

import type { ChatMessageVote } from "@/api/types.gen";
import { HephIcon } from "@/components/brand/HephIcon";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Marker, MarkerContent, MarkerIcon } from "@/components/ui/marker";
import {
	MessageScroller,
	MessageScrollerButton,
	MessageScrollerContent,
	MessageScrollerItem,
	MessageScrollerProvider,
	MessageScrollerViewport,
} from "@/components/ui/message-scroller";
import type { ChatMessage } from "@/lib/types";

import { ChatComposer } from "./ChatComposer";
import { Greeting } from "./Greeting";
import { MentorMessage } from "./MentorMessage";
import { visibleTexts } from "./message-text";

export interface ChatProps {
	messages: ChatMessage[];
	votes?: ChatMessageVote[];
	status: ChatStatus;
	/** The turn in flight is waiting for Heph's sandbox to start, so its first words come late. */
	warmingUp?: boolean;
	errorMessage?: string;
	/** A saved conversation the reader can look at but not continue: no composer, no actions. */
	readonly?: boolean;
	onMessageSubmit: (text: string) => void;
	onStop: () => void;
	onMessageEdit?: (messageId: string, content: string) => void;
	onCopy: (content: string) => void;
	onVote?: (messageId: string, isUpvote: boolean) => void;
	onReload?: () => void;
	inputPlaceholder?: string;
}

/**
 * A conversation with Heph. The reader's newest message scrolls to the top so the reply reads down
 * from it, the view follows the reply while the reader stays at the end, and a saved conversation
 * opens at its last exchange.
 */
export function Chat({
	messages,
	votes,
	status,
	warmingUp = false,
	errorMessage,
	readonly = false,
	onMessageSubmit,
	onStop,
	onMessageEdit,
	onCopy,
	onVote,
	onReload,
	inputPlaceholder,
}: ChatProps) {
	const busy = status === "submitted" || status === "streaming";
	const lastMessage = messages.at(-1);
	// Until a reply shows words, it is a status line rather than an empty message.
	const replyPending =
		busy && (lastMessage?.role !== "assistant" || visibleTexts(lastMessage).length === 0);
	const shownMessages =
		replyPending && lastMessage?.role === "assistant" ? messages.slice(0, -1) : messages;
	const pendingStatus = warmingUp
		? "Getting ready. The first reply takes a little longer."
		: "Thinking…";

	// The live error is gone once the conversation is reopened, but a reply saved as interrupted can still
	// be tried again.
	const isBusyError = status === "error" && errorMessage === "Heph is busy. Please try again.";
	const canRetry =
		status === "error" ||
		(onReload !== undefined &&
			status === "ready" &&
			lastMessage?.role === "assistant" &&
			lastMessage.metadata?.status === "interrupted");

	return (
		<MessageScrollerProvider autoScroll defaultScrollPosition="last-anchor">
			<div className="flex h-full min-h-0 flex-col">
				<MessageScroller className="flex-1">
					<MessageScrollerViewport aria-label="Conversation with Heph">
						<MessageScrollerContent
							// Busy while words arrive, so assistive technology can wait for the whole reply.
							aria-busy={status === "streaming"}
							className="mx-auto w-full max-w-3xl px-4 py-6"
						>
							{messages.length === 0 && (
								// The viewport stays hidden until it has placed a row, so the greeting is one.
								<MessageScrollerItem>
									<Greeting />
								</MessageScrollerItem>
							)}
							{shownMessages.map((message) => (
								<MessageScrollerItem
									key={message.id}
									messageId={message.id}
									scrollAnchor={message.role === "user"}
								>
									<MentorMessage
										message={message}
										vote={votes?.find((vote) => vote.messageId === message.id)}
										streaming={status === "streaming" && message === lastMessage}
										readonly={readonly}
										onMessageEdit={onMessageEdit}
										onCopy={onCopy}
										onVote={onVote}
									/>
								</MessageScrollerItem>
							))}
							{replyPending && (
								<MessageScrollerItem>
									<Marker aria-hidden>
										<MarkerIcon>
											<HephIcon size={16} pad={1} animated={false} />
										</MarkerIcon>
										<MarkerContent>
											{/* One sweep per status, so the motion stops while the words stay (WCAG 2.2.2). */}
											<span key={pendingStatus} className="shimmer shimmer-once">
												{pendingStatus}
											</span>
										</MarkerContent>
									</Marker>
								</MessageScrollerItem>
							)}
						</MessageScrollerContent>
					</MessageScrollerViewport>
					<MessageScrollerButton />
				</MessageScroller>
				{/* Outside the log, which announces rows as they arrive but not a row whose words change. */}
				<p role="status" className="sr-only">
					{replyPending ? pendingStatus : ""}
				</p>

				<div className="mx-auto flex w-full max-w-3xl flex-col gap-2 px-4 pb-2">
					{canRetry && (
						<Alert variant={isBusyError ? "warning" : "destructive"}>
							<AlertCircleIcon />
							<AlertTitle>{isBusyError ? "Heph is busy" : "Something went wrong"}</AlertTitle>
							<AlertDescription className="flex items-center justify-between gap-4">
								<span>
									{isBusyError
										? "Please try again in a moment."
										: "An error occurred while generating the response. Please try again."}
								</span>
								{onReload && (
									<Button variant="outline" size="sm" onClick={onReload} className="shrink-0">
										<RotateCcwIcon />
										Try again
									</Button>
								)}
							</AlertDescription>
						</Alert>
					)}
					{!readonly && (
						<ChatComposer
							busy={busy}
							onSubmit={onMessageSubmit}
							onStop={onStop}
							placeholder={inputPlaceholder}
						/>
					)}
					<p className="text-center text-xs text-balance text-muted-foreground">
						Heph can make mistakes. Consider verifying important information.
					</p>
				</div>
			</div>
		</MessageScrollerProvider>
	);
}
