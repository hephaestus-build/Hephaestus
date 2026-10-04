import { AlertCircleIcon } from "lucide-react";
import { type InputHTMLAttributes, useState } from "react";
import { Streamdown } from "streamdown";

import type { ChatMessageVote } from "@/api/types.gen";
import { HephIcon } from "@/components/brand/HephIcon";
import { MarkdownCode } from "@/components/common/MarkdownCode";
import { Bubble, BubbleContent } from "@/components/ui/bubble";
import { Marker, MarkerContent, MarkerIcon } from "@/components/ui/marker";
import { Message, MessageContent, MessageFooter, MessageHeader } from "@/components/ui/message";
import type { ChatMessage } from "@/lib/types";

import { visibleTexts } from "./message-text";
import { MessageActions } from "./MessageActions";
import { MessageEditor } from "./MessageEditor";

export interface MentorMessageProps {
	message: ChatMessage;
	/** The reader's vote on this reply, if they cast one. */
	vote?: ChatMessageVote;
	/** The reply is still arriving, so it offers no actions yet. */
	streaming?: boolean;
	/** A saved conversation the reader can look at but not continue. */
	readonly?: boolean;
	onMessageEdit?: (messageId: string, content: string) => void;
	onCopy: (content: string) => void;
	onVote?: (messageId: string, isUpvote: boolean) => void;
}

function MarkdownTaskCheckbox(props: InputHTMLAttributes<HTMLInputElement>) {
	return (
		<input {...props} aria-label={props.checked === true ? "Completed task" : "Incomplete task"} />
	);
}

const MESSAGE_MARKDOWN_COMPONENTS = {
	code: MarkdownCode,
	input: MarkdownTaskCheckbox,
};

/** The library names its code-block button in title case; every other control here uses sentence case. */
const MESSAGE_TRANSLATIONS = { copyCode: "Copy code" };

/** Heph's name and mark over a reply, alive while the reply is still arriving. */
function HephMessageHeader({ streaming = false }: { streaming?: boolean }) {
	return (
		<MessageHeader className="gap-1.5">
			<HephIcon size={20} pad={1} animated={streaming} streaming={streaming} />
			Heph
		</MessageHeader>
	);
}

/** One turn of a conversation with Heph: the reader's message on the right, Heph's reply on the left. */
export function MentorMessage({
	message,
	vote,
	streaming = false,
	readonly = false,
	onMessageEdit,
	onCopy,
	onVote,
}: MentorMessageProps) {
	const [editing, setEditing] = useState(false);
	const texts = visibleTexts(message);
	const isUser = message.role === "user";
	const interrupted = !isUser && message.metadata?.status === "interrupted";
	const hasActions = !readonly && !streaming && !editing && texts.length > 0;

	return (
		<Message align={isUser ? "end" : "start"}>
			<MessageContent>
				{isUser ? (
					<span className="sr-only">You said:</span>
				) : (
					<HephMessageHeader streaming={streaming} />
				)}
				{editing ? (
					<MessageEditor
						initialContent={texts.join("\n")}
						onCancel={() => setEditing(false)}
						onSend={(content) => {
							onMessageEdit?.(message.id, content);
							setEditing(false);
						}}
					/>
				) : (
					texts.map((text, index) => (
						<Bubble key={`${message.id}-${index}`} variant={isUser ? "default" : "ghost"}>
							<BubbleContent>
								<Streamdown
									components={MESSAGE_MARKDOWN_COMPONENTS}
									translations={MESSAGE_TRANSLATIONS}
									isAnimating={streaming}
								>
									{text}
								</Streamdown>
							</BubbleContent>
						</Bubble>
					))
				)}
				{interrupted && (
					<Marker>
						<MarkerIcon>
							<AlertCircleIcon />
						</MarkerIcon>
						<MarkerContent>
							This reply stopped before it finished. Ask again for a complete answer.
						</MarkerContent>
					</Marker>
				)}
				{hasActions && (
					<MessageFooter>
						<MessageActions
							content={texts.join("\n")}
							vote={vote}
							onCopy={onCopy}
							onVote={!isUser && onVote ? (isUpvote) => onVote(message.id, isUpvote) : undefined}
							onEdit={isUser && onMessageEdit ? () => setEditing(true) : undefined}
						/>
					</MessageFooter>
				)}
			</MessageContent>
		</Message>
	);
}
