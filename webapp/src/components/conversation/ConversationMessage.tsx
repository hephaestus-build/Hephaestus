import { Link } from "@tanstack/react-router";
import { CalendarDays } from "lucide-react";
import type { ReactNode } from "react";

import type { MentorEntry, MentorLink } from "@/api/types.gen";
import { HephaestusMark } from "@/components/brand/HephaestusMark";
import { InlineLink } from "@/components/common/InlineLink";
import { UNTRUSTED_MARKDOWN_PROSE, UntrustedMarkdown } from "@/components/common/UntrustedMarkdown";
import { SlackIcon } from "@/components/icons/brand";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import { Badge } from "@/components/ui/badge";
import { Bubble, BubbleContent } from "@/components/ui/bubble";
import { Message, MessageContent, MessageFooter, MessageHeader } from "@/components/ui/message";
import { getInitials } from "@/lib/avatar";
import { formatTime } from "@/lib/dates";
import { rendersContent } from "@/lib/react-node";
import { hasText } from "@/lib/text";

/** The developer the conversation belongs to, as their messages are signed. */
export interface MessageAuthor {
	name: string;
	avatarUrl?: string;
}

/** What a message shows. An unsent message has no id or `seq` yet, so it is not a whole entry. */
type ShownMessage = Pick<
	MentorEntry,
	"kind" | "surface" | "text" | "createdAt" | "editedAt" | "deleted" | "links"
>;

/** Marks a message written in Slack, so the reader knows where the conversation happened. */
function SurfaceMarker({ surface }: Pick<MentorEntry, "surface">) {
	if (surface !== "SLACK") {
		return null;
	}
	return (
		<span className="inline-flex items-center gap-1 font-normal">
			<SlackIcon className="size-3" />
			Slack
		</span>
	);
}

function authorName(message: Pick<ShownMessage, "kind">, developer: MessageAuthor): string {
	switch (message.kind) {
		case "USER": {
			return developer.name;
		}
		case "DIGEST": {
			return "Weekly digest";
		}
		case "HEPH": {
			return "Hephaestus";
		}
	}
}

/** Who wrote a message and when, such as "Hephaestus, 10:42". */
export function messageSubject(
	message: Pick<ShownMessage, "kind" | "createdAt">,
	developer: MessageAuthor = YOU,
): string {
	return `${authorName(message, developer)}, ${formatTime(message.createdAt)}`;
}

function authorOf(message: ShownMessage, developer: MessageAuthor) {
	if (message.kind === "USER") {
		return {
			name: developer.name,
			avatar: (
				<Avatar>
					{hasText(developer.avatarUrl) && <AvatarImage src={developer.avatarUrl} alt="" />}
					<AvatarFallback>{getInitials(developer.name)}</AvatarFallback>
				</Avatar>
			),
		};
	}
	return {
		name: authorName(message, developer),
		avatar: (
			<Avatar>
				<AvatarFallback className="bg-background text-foreground">
					{message.kind === "DIGEST" ? (
						<CalendarDays aria-hidden className="size-5" />
					) : (
						<HephaestusMark size={24} pad={2} />
					)}
				</AvatarFallback>
			</Avatar>
		),
	};
}

/** One below the `h2` that heads each place a message shows: the conversation page, the thread panel and the Hephaestus panel. */
const HEADING_LEVEL = 3;

function MessageBody({ message, streaming }: { message: ShownMessage; streaming: boolean }) {
	if (message.deleted) {
		return <p className="text-sm text-muted-foreground italic">This message was deleted.</p>;
	}
	// A weekly digest's stored line is for Hephaestus; the digest shows as its card, in `children`.
	if (message.kind === "DIGEST" || !hasText(message.text)) {
		return null;
	}
	return (
		<Bubble variant="ghost">
			<BubbleContent className={UNTRUSTED_MARKDOWN_PROSE}>
				{streaming ? (
					<UntrustedMarkdown streaming headingLevel={HEADING_LEVEL}>
						{message.text}
					</UntrustedMarkdown>
				) : (
					<UntrustedMarkdown headingLevel={HEADING_LEVEL}>{message.text}</UntrustedMarkdown>
				)}
			</BubbleContent>
		</Bubble>
	);
}

/**
 * A result, problem or feedback Hephaestus showed, in Hephaestus's words, as main's chat shows a shown link: a
 * bubble of its own. The work it is about links to the developer's feedback on that work.
 */
function LinkBody({ link, workspaceSlug }: { link: MentorLink; workspaceSlug?: string }) {
	return (
		<>
			<Bubble variant="ghost">
				<BubbleContent className={UNTRUSTED_MARKDOWN_PROSE}>
					<UntrustedMarkdown headingLevel={HEADING_LEVEL}>{link.text}</UntrustedMarkdown>
				</BubbleContent>
			</Bubble>
			{link.itemId !== undefined && workspaceSlug !== undefined && (
				<p className="text-sm">
					<InlineLink
						render={
							<Link
								to="/w/$workspaceSlug/feedback/$itemId"
								params={{ workspaceSlug, itemId: String(link.itemId) }}
							/>
						}
					>
						Your feedback on this work
					</InlineLink>
				</p>
			)}
		</>
	);
}

const YOU: MessageAuthor = { name: "You" };

export interface ConversationMessageProps {
	message: ShownMessage;
	/** Signs the developer's own messages; Hephaestus's need none. */
	developer?: MessageAuthor;
	/** Groups a follow-up from the developer. Answers and digests keep their identity and disclosure. */
	grouped?: boolean;
	/** The answer is still arriving. */
	streaming?: boolean;
	/** Shown under the words, such as a weekly digest's card, a thread summary, or where the turn stands. */
	children?: ReactNode;
	/** The message's actions, such as Copy, which a read-only conversation shows too. */
	actions?: ReactNode;
	/** The workspace the feedback Hephaestus showed links into; without it the work is not linked. */
	workspaceSlug?: string;
}

/**
 * One message, laid out as a direct-message row: every row left-aligned, with the author's avatar,
 * name and time.
 *
 * The avatar is the registry `Avatar`, not `MessageAvatar`: that one sits at the foot of a message
 * bubble and lifts above the footer, where a row reads from its top.
 */
export function ConversationMessage({
	message,
	developer = YOU,
	grouped = false,
	streaming = false,
	children,
	actions,
	workspaceSlug,
}: ConversationMessageProps) {
	const author = authorOf(message, developer);
	const groupedAuthor = grouped && message.kind === "USER";
	return (
		<Message className="gap-3">
			<div className="w-8 shrink-0 pt-0.5">{!groupedAuthor && author.avatar}</div>
			<MessageContent>
				{groupedAuthor ? (
					<span className="sr-only">{messageSubject(message, developer)}</span>
				) : (
					<MessageHeader className="gap-2">
						<span className="text-sm font-semibold text-foreground">{author.name}</span>
						{message.kind === "HEPH" && <Badge variant="muted">AI</Badge>}
						<time dateTime={message.createdAt.toISOString()}>{formatTime(message.createdAt)}</time>
						<SurfaceMarker surface={message.surface} />
						{message.editedAt && <span className="font-normal">(edited)</span>}
					</MessageHeader>
				)}
				<MessageBody message={message} streaming={streaming} />
				{!message.deleted &&
					message.links
						.filter((link) => hasText(link.text.trim()))
						.map((link) => (
							<LinkBody key={`${link.kind}:${link.id}`} link={link} workspaceSlug={workspaceSlug} />
						))}
				{children}
				{rendersContent(actions) && <MessageFooter>{actions}</MessageFooter>}
			</MessageContent>
		</Message>
	);
}
