import type { ChatStatus } from "ai";

import { cn } from "cn";
import { InterruptedReplyNote } from "@/components/mentor/InterruptedReplyNote";
import { MessageText } from "@/components/mentor/MessageText";
import { visiblePartText } from "@/lib/chat-validation";
import type { ChatMessage } from "@/lib/types";
import { HephMark } from "~/components/brand/HephaestusLogo";

export interface MentorTranscriptProps {
	messages: ChatMessage[];
	status: ChatStatus;
}

/**
 * The conversation as the reader sees it in the Heph panel, rendered by the web app's own message
 * leaves, so a reply reads the same here and in Hephaestus. Their links open in a new tab, so
 * following one never replaces the panel, and the conversation, with another site.
 */
export function MentorTranscript({ messages, status }: MentorTranscriptProps) {
	const waiting = status === "submitted" && messages.at(-1)?.role === "user";
	return (
		<ol className="flex flex-col gap-5" aria-label="Conversation">
			{messages.map((message) => {
				const parts = message.parts.flatMap((part) => {
					const text = visiblePartText(part);
					return text === undefined ? [] : [text];
				});
				const interrupted =
					message.role === "assistant" && message.metadata?.status === "interrupted";
				if (parts.length === 0 && !interrupted) {
					return null;
				}
				return (
					<li
						key={message.id}
						className={cn(
							"flex min-w-0 gap-2.5 text-sm",
							message.role === "user" ? "justify-end" : "justify-start",
						)}
					>
						{message.role === "assistant" ? <HephMark className="mt-0.5 size-5" /> : null}
						<div
							className={cn(
								"flex min-w-0 flex-col gap-2 break-words",
								message.role === "user"
									? "max-w-[85%] rounded-xl bg-muted px-3 py-2 text-foreground"
									: "flex-1",
							)}
						>
							<span className="sr-only">{message.role === "user" ? "You:" : "Heph:"}</span>
							{parts.map((text, index) => (
								<MessageText key={`${message.id}-part-${index}`} text={text} allowImages={false} />
							))}
							{interrupted ? <InterruptedReplyNote /> : null}
						</div>
					</li>
				);
			})}
			{/* Announced by the panel's own live region, which exists before the first message. */}
			{waiting ? (
				<li className="flex gap-2.5 text-sm text-muted-foreground" aria-hidden>
					<HephMark className="mt-0.5 size-5" />
					Heph is thinking…
				</li>
			) : null}
		</ol>
	);
}
