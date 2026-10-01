import { GitPullRequestIcon } from "@primer/octicons-react";
import { CircleDotIcon, FileTextIcon, MessagesSquareIcon } from "lucide-react";
import type { ComponentType } from "react";

import type { ReviewedWorkRef } from "@/api/types.gen";
import { capitalise, hasText } from "@/lib/text";

/** Wide enough for both icon sets in use: lucide and the provider registry's octicons. */
export type ArtifactKindIcon = ComponentType<{
	size?: number;
	className?: string;
	"aria-hidden"?: boolean;
}>;

/**
 * Artifact kinds are an open vocabulary — a `<domain>.<kind>` string named by the owning server
 * module, so the generated client types one as `string`. These are the kinds the UI can name;
 * anything else is still listed rather than dropped, as "Other work" and never by its wire id.
 */
export const ARTIFACT_KIND = {
	pullRequest: "scm.pull_request",
	issue: "scm.issue",
	conversationThread: "chat.conversation_thread",
	document: "docs.document",
} as const;

export type KnownArtifactKind = (typeof ARTIFACT_KIND)[keyof typeof ARTIFACT_KIND];

/**
 * Use for any kind coming off the wire; {@link KnownArtifactKind} only where this build must know
 * the kind to act on it, such as a URL slug or an icon.
 */
export type ArtifactKindId = string;

/** The kinds in the one order every list of them keeps: the order `ARTIFACT_KIND` declares. */
export const ARTIFACT_KIND_VALUES = Object.values(ARTIFACT_KIND);

/** Where a kind sorts among the others; a kind this build does not know sorts after them all. */
export function artifactKindRank(kind: string): number {
	return isKnownArtifactKind(kind)
		? ARTIFACT_KIND_VALUES.indexOf(kind)
		: ARTIFACT_KIND_VALUES.length;
}

export function isKnownArtifactKind(kind: string | null | undefined): kind is KnownArtifactKind {
	return ARTIFACT_KIND_VALUES.some((known) => known === kind);
}

/** Whether two references name the same piece of work: the same kind, and the same id within it. */
export function sameReviewedWork(
	left: Pick<ReviewedWorkRef, "kind" | "id">,
	right: Pick<ReviewedWorkRef, "kind" | "id">,
): boolean {
	return left.kind === right.kind && left.id === right.id;
}

/** The provider a piece of reviewed work lives at, as the wire names it on `ReviewedWorkRef`. */
export type WorkProvider = NonNullable<ReviewedWorkRef["provider"]>;

interface Noun {
	one: string;
	many: string;
}

/**
 * Every kind's noun as it reads mid-sentence. A pull request's is the provider's to decide, so
 * without one it is "pull or merge request" (`docs/contributor/practice-feedback-language.md`).
 */
const ARTIFACT_KIND_NOUNS: Record<KnownArtifactKind, Noun> = {
	[ARTIFACT_KIND.pullRequest]: { one: "pull or merge request", many: "pull or merge requests" },
	[ARTIFACT_KIND.issue]: { one: "issue", many: "issues" },
	[ARTIFACT_KIND.conversationThread]: { one: "conversation", many: "conversations" },
	[ARTIFACT_KIND.document]: { one: "document", many: "documents" },
};

const PROVIDER_PULL_REQUEST_NOUNS: Partial<Record<WorkProvider, Noun>> = {
	GITHUB: { one: "pull request", many: "pull requests" },
	GITLAB: { one: "merge request", many: "merge requests" },
};

/** Work whose kind the reader cannot be told: none on the wire, or one this build has never met. */
const REVIEWED_WORK: Noun = { one: "piece of reviewed work", many: "pieces of reviewed work" };
const OTHER_WORK: Noun = { one: "piece of work", many: "pieces of work" };

function nounOf(kind: string | undefined, provider: WorkProvider | undefined): Noun {
	if (!hasText(kind)) {
		return REVIEWED_WORK;
	}
	if (!isKnownArtifactKind(kind)) {
		return OTHER_WORK;
	}
	const byProvider =
		kind === ARTIFACT_KIND.pullRequest && provider
			? PROVIDER_PULL_REQUEST_NOUNS[provider]
			: undefined;
	return byProvider ?? ARTIFACT_KIND_NOUNS[kind];
}

/**
 * The noun for `count` pieces of work of a kind, as it reads mid-sentence: "pull or merge
 * requests", or "merge request" once the provider says GitLab. A kind the server added before this
 * build learned it reads as "piece of work", never as its wire id.
 */
export function artifactKindNoun(
	kind: string | undefined,
	count: number,
	provider?: WorkProvider,
): string {
	const noun = nounOf(kind, provider);
	return count === 1 ? noun.one : noun.many;
}

/**
 * The same noun where it opens a heading, a filter option or a caption: "Pull request", "Merge
 * requests". A kind the reader cannot be told is "Reviewed work" or "Other work", in either number.
 */
export function artifactKindLabel(
	kind: string | undefined,
	count = 1,
	provider?: WorkProvider,
): string {
	if (!hasText(kind)) {
		return "Reviewed work";
	}
	if (!isKnownArtifactKind(kind)) {
		return "Other work";
	}
	return capitalise(artifactKindNoun(kind, count, provider));
}

/** The kinds whose label is the provider's number for the work, which reads only after its noun. */
const NUMBERED_KINDS: ReadonlySet<string> = new Set([
	ARTIFACT_KIND.pullRequest,
	ARTIFACT_KIND.issue,
]);

/**
 * What the work is, in words: "Pull request #1423", "Merge request !1423", "Issue #1430". A kind
 * whose label is not a number — a document's title, a channel — is named by its kind alone, since
 * wherever this is shown the title is already beside it.
 */
export function reviewedWorkName(
	work: Pick<ReviewedWorkRef, "kind" | "provider" | "label">,
): string {
	const noun = artifactKindLabel(work.kind, 1, work.provider);
	return NUMBERED_KINDS.has(work.kind) && hasText(work.label) ? `${noun} ${work.label}` : noun;
}

const ARTIFACT_KIND_ICONS: Record<KnownArtifactKind, ArtifactKindIcon> = {
	[ARTIFACT_KIND.pullRequest]: GitPullRequestIcon,
	[ARTIFACT_KIND.issue]: CircleDotIcon,
	[ARTIFACT_KIND.conversationThread]: MessagesSquareIcon,
	[ARTIFACT_KIND.document]: FileTextIcon,
};

/**
 * A kind this build has never heard of gets the neutral page icon rather than a hole, and never
 * borrows the icon of a kind it is not.
 */
export function artifactKindIcon(kind: string | undefined): ArtifactKindIcon {
	return hasText(kind) && isKnownArtifactKind(kind) ? ARTIFACT_KIND_ICONS[kind] : FileTextIcon;
}
