import { Link } from "@tanstack/react-router";
import type { ReactElement, ReactNode } from "react";

import type { MentorEntry, ReviewedWorkRef } from "@/api/types.gen";
import { HephIcon } from "@/components/brand/HephIcon";
import { InlineLink } from "@/components/common/InlineLink";
import { UNTRUSTED_MARKDOWN_PROSE, UntrustedMarkdown } from "@/components/common/UntrustedMarkdown";
import { SlackIcon } from "@/components/icons/brand";
import { PracticePill } from "@/components/practice-vocabulary/PracticePill";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import { Bubble, BubbleContent } from "@/components/ui/bubble";
import { Item, ItemContent, ItemDescription, ItemGroup, ItemTitle } from "@/components/ui/item";
import { Message, MessageContent, MessageFooter, MessageHeader } from "@/components/ui/message";
import { artifactKindNoun } from "@/lib/artifact-kinds";
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
	"kind" | "surface" | "text" | "createdAt" | "editedAt" | "deleted" | "observations"
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
			return "Heph";
		}
	}
}

/** Who wrote a message and when, such as "Heph, 10:42". */
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
					<HephIcon size={24} pad={2} animated={false} />
				</AvatarFallback>
			</Avatar>
		),
	};
}

/** One below the `h2` that heads each place a message shows: the mentor page, the thread panel and the Heph panel. */
const HEADING_LEVEL = 3;

/**
 * Fades in each word of a streaming answer, staggered as the stream marks it. Only the live row
 * streams, so a stored answer or a remount never fades; reduced motion shows words at once.
 */
const NEW_WORDS_FADE =
	"[&_[data-sd-animate]]:animate-in [&_[data-sd-animate]]:fade-in [&_[data-sd-animate]]:[--tw-animation-delay:var(--sd-delay,0ms)] [&_[data-sd-animate]]:[--tw-animation-fill-mode:both] motion-reduce:[&_[data-sd-animate]]:animate-none";

function MessageBody({ message, streaming }: { message: ShownMessage; streaming: boolean }) {
	if (message.deleted) {
		return <p className="text-sm text-muted-foreground italic">This message was deleted.</p>;
	}
	// A weekly digest's stored line is for Heph; the digest shows as its card, in `children`.
	if (message.kind === "DIGEST" || !hasText(message.text)) {
		return null;
	}
	return (
		<Bubble variant="ghost">
			<BubbleContent className={UNTRUSTED_MARKDOWN_PROSE}>
				{streaming ? (
					<div className={NEW_WORDS_FADE}>
						<UntrustedMarkdown streaming headingLevel={HEADING_LEVEL}>
							{message.text}
						</UntrustedMarkdown>
					</div>
				) : (
					<UntrustedMarkdown headingLevel={HEADING_LEVEL}>{message.text}</UntrustedMarkdown>
				)}
			</BubbleContent>
		</Bubble>
	);
}

/** Reviewed work as feedback names it, such as "pull request #421". */
export function workName(work: ReviewedWorkRef): string {
	return `${artifactKindNoun(work.kind, 1, work.provider)} ${work.label}`;
}

/** The developer's feedback on one piece of work: the address every comment Hephaestus posts on the work ends with. */
export function workFeedbackLink(work: ReviewedWorkRef, workspaceSlug: string): ReactElement {
	return (
		<Link
			to="/w/$workspaceSlug/feedback/$artifactKind/$artifactId"
			params={{ workspaceSlug, artifactKind: work.kind, artifactId: work.id }}
		/>
	);
}

/** The work's name, linked to its feedback when the workspace is known. */
function WorkLink({ work, workspaceSlug }: { work: ReviewedWorkRef; workspaceSlug?: string }) {
	if (workspaceSlug === undefined) {
		return workName(work);
	}
	return <InlineLink render={workFeedbackLink(work, workspaceSlug)}>{workName(work)}</InlineLink>;
}

const YOU: MessageAuthor = { name: "You" };

export interface HephMessageProps {
	message: ShownMessage;
	/** Signs the developer's own messages; Heph's need none. */
	developer?: MessageAuthor;
	/** Follows a message of the same author, so it drops the avatar and the header. */
	grouped?: boolean;
	/** The answer is still arriving. */
	streaming?: boolean;
	/** Shown under the words, such as a weekly digest's card, a thread summary, or where the turn stands. */
	children?: ReactNode;
	/** The message's actions, such as Copy, which a read-only conversation shows too. */
	actions?: ReactNode;
	/** The workspace the feedback Heph showed links into; without it the work is named, not linked. */
	workspaceSlug?: string;
}

/**
 * One message, laid out as a direct-message row: every row left-aligned, with the author's avatar,
 * name and time.
 *
 * The avatar is the registry `Avatar`, not `MessageAvatar`: that one sits at the foot of a message
 * bubble and lifts above the footer, where a row reads from its top.
 */
export function HephMessage({
	message,
	developer = YOU,
	grouped = false,
	streaming = false,
	children,
	actions,
	workspaceSlug,
}: HephMessageProps) {
	const author = authorOf(message, developer);
	return (
		<Message className="gap-3">
			<div className="w-8 shrink-0 pt-0.5">{!grouped && author.avatar}</div>
			<MessageContent>
				{grouped ? (
					<span className="sr-only">{messageSubject(message, developer)}</span>
				) : (
					<MessageHeader className="gap-2">
						<span className="text-sm font-semibold text-foreground">{author.name}</span>
						<time dateTime={message.createdAt.toISOString()}>{formatTime(message.createdAt)}</time>
						<SurfaceMarker surface={message.surface} />
						{message.editedAt && <span className="font-normal">(edited)</span>}
					</MessageHeader>
				)}
				<MessageBody message={message} streaming={streaming} />
				{!message.deleted && message.observations.length > 0 && (
					<ItemGroup className="gap-2" aria-label="Feedback in this message">
						{message.observations.map((observation) => (
							<Item key={observation.observationId} variant="outline" size="sm" role="listitem">
								<ItemContent>
									{hasText(observation.practiceName) && (
										<ItemTitle>
											<PracticePill name={observation.practiceName} />
										</ItemTitle>
									)}
									<div className={UNTRUSTED_MARKDOWN_PROSE}>
										<UntrustedMarkdown headingLevel={HEADING_LEVEL}>
											{observation.text}
										</UntrustedMarkdown>
									</div>
									{observation.work && (
										<ItemDescription>
											Seen on your{" "}
											<WorkLink work={observation.work} workspaceSlug={workspaceSlug} />
										</ItemDescription>
									)}
								</ItemContent>
							</Item>
						))}
					</ItemGroup>
				)}
				{children}
				{rendersContent(actions) && <MessageFooter>{actions}</MessageFooter>}
			</MessageContent>
		</Message>
	);
}
