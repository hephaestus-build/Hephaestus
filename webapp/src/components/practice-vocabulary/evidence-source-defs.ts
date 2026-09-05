import {
	CircleDotIcon,
	FileDiffIcon,
	FileQuestionIcon,
	FileTextIcon,
	FolderTreeIcon,
	GitCommitHorizontalIcon,
	GitPullRequestIcon,
	HistoryIcon,
	Layers2Icon,
	LibraryIcon,
	LinkIcon,
	MailCheckIcon,
	MessageCircleIcon,
	MessageSquareCodeIcon,
	MessageSquareIcon,
	MessageSquareTextIcon,
	MessagesSquareIcon,
} from "lucide-react";

import type { EvidenceCitation } from "@/api/types.gen";

import type { StatusDef } from "./status-def";

/**
 * Where a citation's line numbers point, which decides whether a surface may show them.
 *
 * <p>`code` — a real span of a real file, verified by the server against the annotated unified diff.
 *
 * <p>`object` — an offset inside the serialised context artifact the quote was pulled from: a line
 * of `conversation_thread.json`, not a position in the Slack thread. The schema demands a number
 * ≥ 1 so one is always present, but nothing verifies it points at the quote, so these citations
 * render their `path` as a name and no numbers.
 */
export type EvidenceLocator = "code" | "object";

export interface EvidenceSourceDef extends StatusDef {
	locator: EvidenceLocator;
}

/**
 * The registered inputs a review may quote from, in operator-facing words.
 *
 * <p>Not a total `StatusDefs` over a union, because `sourceKind` is a `string` on the wire: an
 * unknown kind has to fall back rather than fail a lookup. {@link evidenceSourceDef} is the only
 * way in, so a surface cannot forget the fallback.
 */
const EVIDENCE_SOURCE_DEFS: Record<string, EvidenceSourceDef> = {
	"scm.pull-request.core": {
		label: "The pull request itself",
		icon: GitPullRequestIcon,
		badgeVariant: "outline",
		description: "Its title, description and state, as the author wrote them.",
		locator: "object",
	},
	"scm.pull-request.diff": {
		label: "The code changes",
		icon: FileDiffIcon,
		badgeVariant: "outline",
		description: "The diff under review, quoted at the lines it was read from.",
		locator: "code",
	},
	"scm.pull-request.comments": {
		label: "Comments on the pull request",
		icon: MessageSquareIcon,
		badgeVariant: "outline",
		description: "What people said on the request as a whole.",
		locator: "object",
	},
	"scm.repository.tree": {
		label: "Files in the repository",
		icon: FolderTreeIcon,
		badgeVariant: "outline",
		description: "Files that were not changed, read for context around the work.",
		locator: "code",
	},
	"scm.issue.core": {
		label: "The issue itself",
		icon: CircleDotIcon,
		badgeVariant: "outline",
		description: "Its title, description and state.",
		locator: "object",
	},
	"scm.issue.comments": {
		label: "Comments on the issue",
		icon: MessageSquareTextIcon,
		badgeVariant: "outline",
		description: "The discussion under the issue.",
		locator: "object",
	},
	"docs.document.core": {
		label: "The document itself",
		icon: FileTextIcon,
		badgeVariant: "outline",
		description: "The document under review, as it read at the time.",
		locator: "object",
	},
	"slack.conversation.thread": {
		label: "The conversation",
		icon: MessageCircleIcon,
		badgeVariant: "outline",
		description: "The chat thread under review, message by message.",
		locator: "object",
	},
	"scm.linked-work-items": {
		label: "Linked issues and requests",
		icon: LinkIcon,
		badgeVariant: "outline",
		description: "Work the reviewed item points at, for the intent behind it.",
		locator: "object",
	},
	"scm.review-threads": {
		label: "Review threads on the code",
		icon: MessageSquareCodeIcon,
		badgeVariant: "outline",
		description: "Conversations anchored to specific lines by human reviewers.",
		locator: "object",
	},
	"scm.general-review-comments": {
		label: "Review comments",
		icon: MessagesSquareIcon,
		badgeVariant: "outline",
		description: "Reviewers' remarks about the change overall.",
		locator: "object",
	},
	"scm.pull-request.commits": {
		label: "The commits",
		icon: GitCommitHorizontalIcon,
		badgeVariant: "outline",
		description: "The commits the pull request carries, with their messages.",
		locator: "object",
	},
	"workspace.project-inventory": {
		label: "What this project contains",
		icon: Layers2Icon,
		badgeVariant: "outline",
		description: "The languages, frameworks and layout the project was found to use.",
		locator: "object",
	},
	"outline.documents": {
		label: "Referenced documents",
		icon: LibraryIcon,
		badgeVariant: "outline",
		description: "Team documentation the reviewed work links to, such as a guideline.",
		locator: "object",
	},
	"hephaestus.observation-history": {
		label: "Earlier observations",
		icon: HistoryIcon,
		badgeVariant: "outline",
		description: "What past reviews of this developer's work already recorded.",
		locator: "object",
	},
	"hephaestus.feedback-history": {
		label: "Feedback already sent",
		icon: MailCheckIcon,
		badgeVariant: "outline",
		description: "Feedback this developer has already had, so the review does not repeat it.",
		locator: "object",
	},
};

/** The words for one source kind, or a neutral entry naming the unknown kind verbatim. */
export function evidenceSourceDef(sourceKind: string): EvidenceSourceDef {
	return (
		EVIDENCE_SOURCE_DEFS[sourceKind] ?? {
			label: sourceKind,
			icon: FileQuestionIcon,
			badgeVariant: "outline",
			description: "A source this version of the app has no description for.",
			locator: "object",
		}
	);
}

export function knownEvidenceSourceKinds(): string[] {
	return Object.keys(EVIDENCE_SOURCE_DEFS);
}

/**
 * Which side of the diff a passage was read from. The server sets `side` exactly when the source
 * kind is the diff and on no other kind, so a surface only looks for it inside a code locator.
 */
export const DIFF_SIDE_LABELS = {
	OLD: "before",
	NEW: "after",
} satisfies Record<NonNullable<EvidenceCitation["side"]>, string>;

/**
 * A place in a file, as the coordinate a developer would paste: `path:12–18`.
 *
 * <p>`endLine` is optional because the two callers disagree: a citation always carries one (the
 * server defaults it to `startLine`), while an inline placement's anchor may have none. Either way
 * a single line prints as one number rather than `12–12`.
 */
export function codeCitationLocator(span: {
	path: string;
	startLine: number;
	endLine?: number;
}): string {
	const { path, startLine, endLine } = span;
	return endLine && endLine > startLine
		? `${path}:${startLine}–${endLine}`
		: `${path}:${startLine}`;
}

/** Citations gathered under the source they came from, in the order the sources first appear. */
export interface EvidenceSourceGroup {
	sourceKind: string;
	def: EvidenceSourceDef;
	citations: EvidenceCitation[];
}

export function groupCitationsBySource(citations: EvidenceCitation[]): EvidenceSourceGroup[] {
	const groups = new Map<string, EvidenceSourceGroup>();
	for (const citation of citations) {
		const group = groups.get(citation.sourceKind);
		if (group) {
			group.citations.push(citation);
		} else {
			groups.set(citation.sourceKind, {
				sourceKind: citation.sourceKind,
				def: evidenceSourceDef(citation.sourceKind),
				citations: [citation],
			});
		}
	}
	return [...groups.values()];
}
