import { GitPullRequestIcon } from "@primer/octicons-react";
import { CircleDotIcon, FileTextIcon, MessagesSquareIcon } from "lucide-react";
import type { ComponentType } from "react";

import type { ReviewedWorkRef } from "@/api/types.gen";
import { hasText } from "@/lib/text";

/** Wide enough for both icon sets in use: lucide and the provider registry's octicons. */
export type ArtifactKindIcon = ComponentType<{
	size?: number;
	className?: string;
	"aria-hidden"?: boolean;
}>;

/**
 * Artifact kinds are an open vocabulary — a `<domain>.<kind>` string named by the owning server
 * module, so the generated client types one as `string`. These are the kinds the UI can label;
 * anything else is rendered by its raw id rather than dropped, so a new kind stays visible.
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
export const ARTIFACT_KIND_VALUES = Object.values(ARTIFACT_KIND) as KnownArtifactKind[];

/** Where a kind sorts among the others; a kind this build does not know sorts after them all. */
export function artifactKindRank(kind: string): number {
	const index = (ARTIFACT_KIND_VALUES as string[]).indexOf(kind);
	return index === -1 ? ARTIFACT_KIND_VALUES.length : index;
}

const ARTIFACT_KIND_LABELS: Record<KnownArtifactKind, string> = {
	[ARTIFACT_KIND.pullRequest]: "Pull or merge request",
	[ARTIFACT_KIND.issue]: "Issue",
	[ARTIFACT_KIND.conversationThread]: "Conversation",
	[ARTIFACT_KIND.document]: "Document",
};

const ARTIFACT_KIND_PLURAL_LABELS: Record<KnownArtifactKind, string> = {
	[ARTIFACT_KIND.pullRequest]: "Pull or merge requests",
	[ARTIFACT_KIND.issue]: "Issues",
	[ARTIFACT_KIND.conversationThread]: "Conversations",
	[ARTIFACT_KIND.document]: "Documents",
};

export function isKnownArtifactKind(kind: string | null | undefined): kind is KnownArtifactKind {
	return kind != null && (ARTIFACT_KIND_VALUES as string[]).includes(kind);
}

export function artifactKindLabel(kind: string | undefined): string {
	if (!hasText(kind)) {
		return "Reviewed work";
	}
	return isKnownArtifactKind(kind) ? ARTIFACT_KIND_LABELS[kind] : kind;
}

export function artifactKindPluralLabel(kind: string | undefined): string {
	if (!hasText(kind)) {
		return "Reviewed work";
	}
	return isKnownArtifactKind(kind) ? ARTIFACT_KIND_PLURAL_LABELS[kind] : kind;
}

/** The provider a piece of reviewed work lives at, as the wire names it on `ReviewedWorkRef`. */
export type WorkProvider = ReviewedWorkRef["provider"];

/**
 * The same kinds as they read mid-sentence — "Based on 4 pull requests" rather than the title-case
 * form a heading or a filter option wants. Kept beside those labels so a kind cannot gain one
 * spelling and miss the other.
 */
const ARTIFACT_KIND_INLINE_LABELS: Record<KnownArtifactKind, { one: string; many: string }> = {
	[ARTIFACT_KIND.pullRequest]: { one: "pull request", many: "pull requests" },
	[ARTIFACT_KIND.issue]: { one: "issue", many: "issues" },
	[ARTIFACT_KIND.conversationThread]: { one: "conversation", many: "conversations" },
	[ARTIFACT_KIND.document]: { one: "document", many: "documents" },
};

/** GitLab's word for the one kind whose noun the provider decides. */
const MERGE_REQUEST = { one: "merge request", many: "merge requests" };

/**
 * The noun for `count` pieces of work of a kind, as it reads mid-sentence: "pull request", "pull
 * requests", and "merge request" when the provider is GitLab — the provider-specific name where
 * the provider is known (`docs/contributor/practice-feedback-language.md`). An unknown kind keeps
 * its raw id rather than being dropped, so a kind the server added before this build stays
 * legible instead of vanishing from the total.
 */
export function artifactKindNoun(
	kind: string | undefined,
	count: number,
	provider?: WorkProvider,
): string {
	if (!hasText(kind)) {
		return "reviewed work";
	}
	if (!isKnownArtifactKind(kind)) {
		return kind;
	}
	const labels =
		kind === ARTIFACT_KIND.pullRequest && provider === "GITLAB"
			? MERGE_REQUEST
			: ARTIFACT_KIND_INLINE_LABELS[kind];
	return count === 1 ? labels.one : labels.many;
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
