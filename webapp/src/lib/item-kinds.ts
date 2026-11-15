import { GitPullRequestIcon, RepoIcon } from "@primer/octicons-react";
import {
	CircleDotIcon,
	FileCode2Icon,
	FileTextIcon,
	MessageSquareTextIcon,
	MessagesSquareIcon,
} from "lucide-react";
import type { ComponentType } from "react";

import type { ItemRef } from "@/api/types.gen";
import { capitalise, hasText } from "@/lib/text";

/** Wide enough for both icon sets in use: lucide and the provider registry's octicons. */
export type ItemKindIcon = ComponentType<{
	size?: number;
	className?: string;
	"aria-hidden"?: boolean;
}>;

// Item kinds form an open wire vocabulary; unknown domain kinds need a neutral presentation.
export const ITEM_KIND = {
	pullRequest: "scm.pull_request",
	pullRequestReview: "scm.pull_request_review",
	issue: "scm.issue",
	repository: "scm.repository",
	file: "scm.file",
	thread: "chat.thread",
	document: "docs.document",
} as const;

export type KnownItemKind = (typeof ITEM_KIND)[keyof typeof ITEM_KIND];

export const ITEM_KIND_VALUES = Object.values(ITEM_KIND);

export function itemKindRank(kind: string): number {
	return isKnownItemKind(kind) ? ITEM_KIND_VALUES.indexOf(kind) : ITEM_KIND_VALUES.length;
}

export function isKnownItemKind(kind: string | null | undefined): kind is KnownItemKind {
	return ITEM_KIND_VALUES.some((known) => known === kind);
}

export type ItemProvider = NonNullable<ItemRef["provider"]>;

interface Noun {
	one: string;
	many: string;
}

/**
 * Every kind's noun as it reads mid-sentence. A pull request's is the provider's to decide, so
 * without one it is "pull or merge request" (`docs/contributor/practice-feedback-language.mdx`).
 */
const ITEM_KIND_NOUNS: Record<KnownItemKind, Noun> = {
	[ITEM_KIND.pullRequest]: { one: "pull or merge request", many: "pull or merge requests" },
	[ITEM_KIND.pullRequestReview]: {
		one: "pull or merge request review",
		many: "pull or merge request reviews",
	},
	[ITEM_KIND.issue]: { one: "issue", many: "issues" },
	[ITEM_KIND.repository]: { one: "repository", many: "repositories" },
	[ITEM_KIND.file]: { one: "file", many: "files" },
	[ITEM_KIND.thread]: { one: "conversation", many: "conversations" },
	[ITEM_KIND.document]: { one: "document", many: "documents" },
};

const PROVIDER_NOUNS: Partial<Record<KnownItemKind, Partial<Record<ItemProvider, Noun>>>> = {
	[ITEM_KIND.pullRequest]: {
		GITHUB: { one: "pull request", many: "pull requests" },
		GITLAB: { one: "merge request", many: "merge requests" },
	},
	[ITEM_KIND.pullRequestReview]: {
		GITHUB: { one: "pull request review", many: "pull request reviews" },
		GITLAB: { one: "merge request review", many: "merge request reviews" },
	},
};

/** Work whose kind the reader cannot be told: none on the wire, or one this build has never met. */
const REVIEWED_WORK: Noun = { one: "piece of reviewed work", many: "pieces of reviewed work" };
const OTHER_WORK: Noun = { one: "piece of work", many: "pieces of work" };

function nounOf(kind: string | undefined, provider: ItemProvider | null | undefined): Noun {
	if (!hasText(kind)) {
		return REVIEWED_WORK;
	}
	if (!isKnownItemKind(kind)) {
		return OTHER_WORK;
	}
	return (
		(hasText(provider) ? PROVIDER_NOUNS[kind]?.[provider] : undefined) ?? ITEM_KIND_NOUNS[kind]
	);
}

/**
 * The noun for `count` items of a kind, as it reads mid-sentence: "pull or merge requests", or
 * "merge request" once the provider says GitLab. A kind the server added before this build learned
 * it reads as "piece of work", never as its wire id.
 */
export function itemKindNoun(
	kind: string | undefined,
	count: number,
	provider?: ItemProvider | null,
): string {
	const noun = nounOf(kind, provider);
	return count === 1 ? noun.one : noun.many;
}

/**
 * The same noun where it opens a heading, a filter option or a caption: "Pull request", "Merge
 * requests". A kind the reader cannot be told is "Reviewed work" or "Other work", in either number.
 */
export function itemKindLabel(
	kind: string | undefined,
	count = 1,
	provider?: ItemProvider | null,
): string {
	if (!hasText(kind)) {
		return "Reviewed work";
	}
	if (!isKnownItemKind(kind)) {
		return "Other work";
	}
	return capitalise(itemKindNoun(kind, count, provider));
}

/**
 * The kinds whose label names the item only after its noun: the provider's number for a pull
 * request or issue, a repository's full name, a file's path.
 */
const LABELLED_KINDS: ReadonlySet<string> = new Set([
	ITEM_KIND.pullRequest,
	ITEM_KIND.issue,
	ITEM_KIND.repository,
	ITEM_KIND.file,
]);

/**
 * What the item is, in words: "Pull request #1423", "Merge request !1423", "File src/app.ts". A kind
 * whose label is not an identifier — a document's title, a channel — is named by its kind alone,
 * since wherever this is shown the title is already beside it.
 */
export function itemName(item: Pick<ItemRef, "kind" | "provider" | "label">): string {
	const noun = itemKindLabel(item.kind, 1, item.provider);
	return LABELLED_KINDS.has(item.kind) && hasText(item.label) ? `${noun} ${item.label}` : noun;
}

const ITEM_KIND_ICONS: Record<KnownItemKind, ItemKindIcon> = {
	[ITEM_KIND.pullRequest]: GitPullRequestIcon,
	[ITEM_KIND.pullRequestReview]: MessageSquareTextIcon,
	[ITEM_KIND.issue]: CircleDotIcon,
	[ITEM_KIND.repository]: RepoIcon,
	[ITEM_KIND.file]: FileCode2Icon,
	[ITEM_KIND.thread]: MessagesSquareIcon,
	[ITEM_KIND.document]: FileTextIcon,
};

/** Unknown kinds get a neutral icon; they must not borrow another kind's meaning. */
export function itemKindIcon(kind: string | undefined): ItemKindIcon {
	return hasText(kind) && isKnownItemKind(kind) ? ITEM_KIND_ICONS[kind] : FileTextIcon;
}
