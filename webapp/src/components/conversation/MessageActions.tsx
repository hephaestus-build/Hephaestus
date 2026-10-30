import {
	CopyIcon,
	MessageSquareReplyIcon,
	ThumbsDownIcon,
	ThumbsUpIcon,
	Trash2Icon,
} from "lucide-react";
import type { ReactElement, ReactNode } from "react";

import type { MentorEntry } from "@/api/types.gen";
import { Button } from "@/components/ui/button";
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from "@/components/ui/tooltip";

export type MessageVote = NonNullable<MentorEntry["vote"]>;

export interface MessageActionsProps {
	/** The message's subject, as `messageSubject` writes it. Each control names it, so a screen reader tells one message's controls from another's. */
	subject: string;
	/** The words the copy action puts on the clipboard. */
	text: string;
	onCopy: (text: string) => void;
	/** Offered on Hephaestus's messages: the developer's vote and how to change it. `null` clears it. */
	vote?: { value: MessageVote | undefined; onChange: (vote: MessageVote | null) => void };
	/** Offered on top-level messages. */
	onReply?: () => void;
	/** Offered on the developer's own messages. */
	onDelete?: () => void;
}

function Action({
	label,
	subject,
	onClick,
	pressed,
	children,
}: {
	label: string;
	subject: string;
	onClick: () => void;
	pressed?: boolean;
	children: ReactNode;
}): ReactElement {
	return (
		<Tooltip>
			<TooltipTrigger
				render={
					<Button
						aria-label={`${label} (${subject})`}
						aria-pressed={pressed}
						className="pointer-coarse:w-10"
						variant="quiet"
						size="icon"
						onClick={onClick}
					/>
				}
			>
				{children}
			</TooltipTrigger>
			<TooltipContent>{label}</TooltipContent>
		</Tooltip>
	);
}

/**
 * The row under one message. Where a pointer can hover, it rests hidden until the message is hovered
 * or a control in it has focus. Pressing the vote that is already pressed clears it, so a vote is
 * never a one-way door.
 */
export function MessageActions({
	subject,
	text,
	onCopy,
	vote,
	onReply,
	onDelete,
}: MessageActionsProps) {
	return (
		<TooltipProvider delay={0}>
			<div className="flex flex-row gap-0.5 opacity-0 transition-opacity group-hover/message:opacity-100 focus-within:opacity-100 [@media(hover:none)]:opacity-100">
				<Action subject={subject} label="Copy message" onClick={() => onCopy(text)}>
					<CopyIcon />
				</Action>
				{vote && (
					<>
						<Action
							subject={subject}
							label="Helpful"
							pressed={vote.value === "HELPFUL"}
							onClick={() => vote.onChange(vote.value === "HELPFUL" ? null : "HELPFUL")}
						>
							<ThumbsUpIcon />
						</Action>
						<Action
							subject={subject}
							label="Not helpful"
							pressed={vote.value === "NOT_HELPFUL"}
							onClick={() => vote.onChange(vote.value === "NOT_HELPFUL" ? null : "NOT_HELPFUL")}
						>
							<ThumbsDownIcon />
						</Action>
					</>
				)}
				{onReply && (
					<Action subject={subject} label="Reply in thread" onClick={onReply}>
						<MessageSquareReplyIcon />
					</Action>
				)}
				{onDelete && (
					<Action subject={subject} label="Delete message" onClick={onDelete}>
						<Trash2Icon />
					</Action>
				)}
			</div>
		</TooltipProvider>
	);
}
