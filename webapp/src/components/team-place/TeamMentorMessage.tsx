import type { ReactElement, ReactNode } from "react";

import { ConversationMessage } from "@/components/conversation/ConversationMessage";

import type { TeamAnchor, TeamMentorMessageData } from "./team-place-entries";
import { TeamAnswerUse } from "./TeamAnswerUse";
import { TeamCitations } from "./TeamCitations";

/** The one fixed line for a message Hephaestus did not write. Its cause stays on the record and is never shown. */
const NOT_COMPOSED: Readonly<Record<TeamMentorMessageData["kind"], string>> = {
	REVIEW_FEEDBACK: "Hephaestus is not mentoring this review. The observations are below.",
	ANSWER: "Hephaestus cannot answer here.",
};

const STALE = "This message is hidden because a source it cited is no longer available.";

export interface TeamMentorMessageProps {
	message: TeamMentorMessageData;
	/** Opens a cited anchor in its reviewed version. */
	anchorLink?: (anchor: TeamAnchor) => ReactElement;
	/** Shown under the citations, such as the options Hephaestus proposes. */
	children?: ReactNode;
}

/**
 * Hephaestus in a team place thread: the feedback posted when a review sealed, or an answer to a member's question.
 * A written message shows its words, the places in the work it cites, and What Hephaestus used. A message Hephaestus
 * did not write, or one hidden because a cited source is gone, shows one fixed line and no old words.
 */
export function TeamMentorMessage({ message, anchorLink, children }: TeamMentorMessageProps) {
	const composed = message.status === "COMPOSED";
	const text = composed ? (message.body ?? "") : "";
	return (
		<ConversationMessage
			message={{
				kind: "ANSWER",
				surface: "APP",
				text,
				createdAt: message.createdAt,
				deleted: false,
				links: [],
			}}
		>
			{composed ? (
				<>
					<TeamCitations citations={message.citations} anchorLink={anchorLink} />
					{children}
					{/* Last, so the message's own evidence and options read before the list of what it used. */}
					<TeamAnswerUse used={message.answerUse} answeredAt={message.createdAt} text={text} />
				</>
			) : (
				<p className="text-sm text-muted-foreground italic">
					{message.status === "STALE" ? STALE : NOT_COMPOSED[message.kind]}
				</p>
			)}
		</ConversationMessage>
	);
}
