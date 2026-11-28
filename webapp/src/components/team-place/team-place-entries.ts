import type { RevisitFinding } from "@/components/practice-vocabulary/revisit-finding-defs";

/**
 * The shapes the team place renders. They mirror the team place DTOs agreed with the server owners, so the route maps
 * the generated types onto them field for field when the endpoints exist.
 */

/** A member as the team place shows them. An erased member has no name and no account: a "Former member". */
export interface TeamMemberRef {
	accountId?: number;
	displayName?: string;
	avatarUrl?: string;
	/** The signed-in member. */
	you: boolean;
	former: boolean;
}

/** A member's message, under that member's own identity. A deleted message has no text. */
export interface TeamMemberMessageData {
	id: string;
	author: TeamMemberRef;
	createdAt: Date;
	text?: string;
	deleted: boolean;
}

/** The element of a reviewed version that a citation opens. The server derived it from the cited text. */
export interface TeamAnchor {
	versionId: string;
	versionSequence: number;
	snapshotId: string;
	elementId: string;
	label: string;
}

/** One place in the work that Hephaestus's message cites. A source that is gone is not `available` and has no anchor. */
export interface TeamCitation {
	observationId: string;
	anchor?: TeamAnchor;
	available: boolean;
}

/** What Hephaestus used for a team message, as counts from rows: never a private conversation. */
export interface TeamAnswerUseData {
	observations: number;
	decisions: number;
	messages: number;
}

/**
 * A real strategy choice that a review leaves the team on one problem, at most one in a message. Hephaestus proposes
 * the options and may draft a note; only a member records the team's decision.
 */
export interface TeamChoicePoint {
	problemId: string;
	/** Two or three. */
	options: readonly { id: string; text: string }[];
	draftNote?: string;
	citations: readonly TeamCitation[];
}

/**
 * What a later review shows about a recorded decision. It names the decision record; the note, state and recorder come
 * from that record when it is read, never from a copy.
 */
export interface TeamRevisit {
	decisionId: string;
	decisionRecordId: string;
	finding: RevisitFinding;
	/** Hephaestus's words. */
	statement: string;
	citations: readonly TeamCitation[];
}

/**
 * `COMPOSED`: Hephaestus wrote the message. `NOT_COMPOSED`: it did not, and the cause stays on the record.
 * `STALE`: a source it cited is gone, so the server sends no words.
 */
export type TeamMentorMessageStatus = "COMPOSED" | "NOT_COMPOSED" | "STALE";

/** Hephaestus in a team place thread: the feedback posted at a review's seal, or an answer to a member's question. */
export interface TeamMentorMessageData {
	id: string;
	kind: "REVIEW_FEEDBACK" | "ANSWER";
	createdAt: Date;
	status: TeamMentorMessageStatus;
	/** Only when `COMPOSED`. */
	body?: string;
	citations: readonly TeamCitation[];
	choicePoint?: TeamChoicePoint;
	revisits: readonly TeamRevisit[];
	answerUse: TeamAnswerUseData;
}

/** How a member is named: their name, or "Former member" once they are erased. */
export function memberName(member: TeamMemberRef): string {
	return member.former || member.displayName === undefined ? "Former member" : member.displayName;
}
