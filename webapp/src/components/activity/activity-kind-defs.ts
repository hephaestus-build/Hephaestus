import {
	CheckIcon,
	CodeReviewIcon,
	CommentDiscussionIcon,
	EyeIcon,
	FileDiffIcon,
	GitMergeIcon,
	GitPullRequestClosedIcon,
	GitPullRequestIcon,
	IssueClosedIcon,
	IssueOpenedIcon,
} from "@primer/octicons-react";

import type { ActivityAction, ActivitySummary } from "@/api/types.gen";
import {
	GitLabCheckCircleIcon,
	GitLabCodeIcon,
	GitLabCommentIcon,
	GitLabCommentsIcon,
	GitLabErrorIcon,
	GitLabEyeIcon,
	GitLabIssueClosedIcon,
	GitLabIssueOpenIcon,
	GitLabMergeIcon,
	GitLabMergeRequestClosedIcon,
	GitLabMergeRequestIcon,
} from "@/components/icons/gitlab-icons";
import type { IconComponent } from "@/components/icons/provider-icons";
import { ARTIFACT_KIND, artifactKindNoun } from "@/lib/artifact-kinds";
import type { ProviderType } from "@/lib/provider/provider-terms";
import { capitalise } from "@/lib/text";

import { type ActivityTone, providerIcon } from "./activity-tones";

export type ActivityKind = ActivityAction["kind"];

/** A count's noun in both numbers: "comment on code", "comments on code". */
export interface Noun {
	one: string;
	many: string;
}

export interface ActivityKindDef {
	/** The provider's own icon for it: an octicon on GitHub, a Pajamas icon on GitLab. */
	icon: (provider: ProviderType) => IconComponent;
	/** The provider colour role it wears, on its icon and its bars; never on its count. */
	tone: ActivityTone;
	/**
	 * What happened, as a row's chip and a chart's row name it: "Merged", "Approved". A count kind
	 * names where it happened instead: "In conversations", "On code". Always its `countLabel` with a
	 * capital, so a kind reads the same on a chip, a chart and a table.
	 */
	label: string;
	/**
	 * What a count of it is, right after the number where there is room for words: "4 opened",
	 * "82 on code". The same in both providers' words.
	 */
	countLabel: string;
	/**
	 * How a row of one piece of work shows it: a lifecycle event once, by its label ("Merged"); a
	 * comment, which piles up, by its count.
	 */
	chip: "label" | "count";
	/** What one or many of it are, for accessible names and Markdown: "3 comments on code". */
	noun: (provider: ProviderType) => Noun;
	summaryField: keyof ActivitySummary;
	/**
	 * Whom it counts for. A review, a comment or an opening is the actor's own doing; a merge or a
	 * close is counted for the work's author, who is not necessarily the person who merged or closed it.
	 */
	credit: "actor" | "author";
	/** What it happens on, which is how a row names the work once the provider no longer has it. */
	work: "pull-request" | "issue" | "any";
}

const pullRequestNoun =
	(verb: string) =>
	(provider: ProviderType): Noun => ({
		one: `${artifactKindNoun(ARTIFACT_KIND.pullRequest, 1, provider)} ${verb}`,
		many: `${artifactKindNoun(ARTIFACT_KIND.pullRequest, 2, provider)} ${verb}`,
	});

const fixedNoun = (one: string, many: string) => (): Noun => ({ one, many });

/**
 * One registry for what someone did, in the order the server lists a piece of work's actions. Every
 * surface that shows a kind — a chip, a tile, a chart, a table cell, a copied line — reads it here.
 */
export const ACTIVITY_KIND_DEFS = {
	PULL_REQUEST_OPENED: {
		icon: providerIcon(GitPullRequestIcon, GitLabMergeRequestIcon),
		tone: "open",
		label: "Opened",
		countLabel: "opened",
		chip: "label",
		noun: pullRequestNoun("opened"),
		summaryField: "pullRequestsOpened",
		credit: "actor",
		work: "pull-request",
	},
	PULL_REQUEST_MERGED: {
		icon: providerIcon(GitMergeIcon, GitLabMergeIcon),
		tone: "done",
		label: "Merged",
		countLabel: "merged",
		chip: "label",
		noun: pullRequestNoun("merged"),
		summaryField: "pullRequestsMerged",
		credit: "author",
		work: "pull-request",
	},
	PULL_REQUEST_CLOSED: {
		icon: providerIcon(GitPullRequestClosedIcon, GitLabMergeRequestClosedIcon),
		tone: "closed",
		label: "Closed",
		countLabel: "closed",
		chip: "label",
		noun: pullRequestNoun("closed without merging"),
		summaryField: "pullRequestsClosed",
		credit: "author",
		work: "pull-request",
	},
	REVIEW_APPROVED: {
		icon: providerIcon(CheckIcon, GitLabCheckCircleIcon),
		tone: "success",
		label: "Approved",
		countLabel: "approved",
		chip: "label",
		noun: fixedNoun("approval", "approvals"),
		summaryField: "approvals",
		credit: "actor",
		work: "pull-request",
	},
	REVIEW_CHANGES_REQUESTED: {
		icon: providerIcon(FileDiffIcon, GitLabErrorIcon),
		tone: "danger",
		label: "Changes requested",
		countLabel: "changes requested",
		chip: "label",
		noun: fixedNoun("review requesting changes", "reviews requesting changes"),
		summaryField: "changeRequests",
		credit: "actor",
		work: "pull-request",
	},
	REVIEW_COMMENTED: {
		icon: providerIcon(EyeIcon, GitLabEyeIcon),
		tone: "muted",
		label: "Commented",
		countLabel: "commented",
		chip: "label",
		noun: fixedNoun("comment-only review", "comment-only reviews"),
		summaryField: "commentReviews",
		credit: "actor",
		work: "pull-request",
	},
	COMMENTED: {
		icon: providerIcon(CommentDiscussionIcon, GitLabCommentIcon),
		tone: "muted",
		label: "In conversations",
		countLabel: "in conversations",
		chip: "count",
		noun: fixedNoun("comment in a conversation", "comments in conversations"),
		summaryField: "comments",
		credit: "actor",
		work: "any",
	},
	CODE_COMMENTED: {
		icon: providerIcon(CodeReviewIcon, GitLabCodeIcon),
		tone: "muted",
		label: "On code",
		countLabel: "on code",
		chip: "count",
		noun: fixedNoun("comment on code", "comments on code"),
		summaryField: "codeComments",
		credit: "actor",
		work: "pull-request",
	},
	ISSUE_OPENED: {
		icon: providerIcon(IssueOpenedIcon, GitLabIssueOpenIcon),
		tone: "open",
		label: "Opened",
		countLabel: "opened",
		chip: "label",
		noun: fixedNoun("issue opened", "issues opened"),
		summaryField: "issuesOpened",
		credit: "actor",
		work: "issue",
	},
	ISSUE_CLOSED: {
		icon: providerIcon(IssueClosedIcon, GitLabIssueClosedIcon),
		tone: "done",
		label: "Closed",
		countLabel: "closed",
		chip: "label",
		noun: fixedNoun("issue closed", "issues closed"),
		summaryField: "issuesClosed",
		credit: "author",
		work: "issue",
	},
} as const satisfies Record<ActivityKind, ActivityKindDef>;

/**
 * Every kind in the server's order of a piece of work's actions. A kind the server adds fails the
 * registry above; its unit test holds this list to the registry's keys.
 */
export const ACTIVITY_KINDS = [
	"PULL_REQUEST_OPENED",
	"PULL_REQUEST_MERGED",
	"PULL_REQUEST_CLOSED",
	"REVIEW_APPROVED",
	"REVIEW_CHANGES_REQUESTED",
	"REVIEW_COMMENTED",
	"COMMENTED",
	"CODE_COMMENTED",
	"ISSUE_OPENED",
	"ISSUE_CLOSED",
] as const satisfies readonly ActivityKind[];

/** "3 comments on code", "1 approval": a count of a kind as its chip and its copied line name it. */
export function countPhrase(kind: ActivityKind, count: number, provider: ProviderType): string {
	const { one, many } = ACTIVITY_KIND_DEFS[kind].noun(provider);
	return `${count} ${count === 1 ? one : many}`;
}

/**
 * One piece of work's action as a row reads it: a lifecycle event by its label ("merged", "approved
 * 2 times"), a comment by its count ("3 comments on code").
 */
export function actionPhrase({ kind, count }: ActivityAction, provider: ProviderType): string {
	const def = ACTIVITY_KIND_DEFS[kind];
	if (def.chip === "count") {
		return countPhrase(kind, count, provider);
	}
	const label = def.label.toLowerCase();
	return count === 1 ? label : `${label} ${count} times`;
}

/** A summary's non-zero counts of `kinds`, in the registry's order. */
export function summaryActions(
	summary: ActivitySummary,
	kinds: readonly ActivityKind[] = ACTIVITY_KINDS,
): ActivityAction[] {
	return ACTIVITY_KINDS.filter((kind) => kinds.includes(kind))
		.map((kind) => ({ kind, count: summary[ACTIVITY_KIND_DEFS[kind].summaryField] }))
		.filter((action) => action.count > 0);
}

/** How often `kinds` happened in a summary, together. */
export function kindsTotal(summary: ActivitySummary, kinds: readonly ActivityKind[]): number {
	return kinds.reduce((sum, kind) => sum + summary[ACTIVITY_KIND_DEFS[kind].summaryField], 0);
}

/**
 * The work a row stands for is gone from the provider, but what happened on it stays, so the counts
 * stay true: "A pull request that is no longer available".
 */
export function goneWork(kinds: readonly ActivityKind[], provider: ProviderType): string {
	const work = workOf(kinds);
	if (work === "pull-request") {
		return `A ${artifactKindNoun(ARTIFACT_KIND.pullRequest, 1, provider)} that is no longer available`;
	}
	return work === "issue"
		? "An issue that is no longer available"
		: "Work that is no longer available";
}

/** What the kinds happened on: a pull request if any of them says so, else an issue, else either. */
export function workOf(kinds: readonly ActivityKind[]): ActivityKindDef["work"] {
	const works = new Set(kinds.map((kind) => ACTIVITY_KIND_DEFS[kind].work));
	if (works.has("pull-request")) {
		return "pull-request";
	}
	return works.has("issue") ? "issue" : "any";
}

export const ACTIVITY_CATEGORIES = ["pull-requests", "reviews", "comments", "issues"] as const;

export type ActivityCategory = (typeof ACTIVITY_CATEGORIES)[number];

export interface ActivityCategoryDef {
	/** The category's title, in the provider's words where it has its own: "Merge requests". */
	label: (provider: ProviderType) => string;
	icon: (provider: ProviderType) => IconComponent;
	/**
	 * The tone of its icon and its tile's bars: its headline's, where one state is the headline
	 * (merged, opened); the accent where the headline sums several, since no one state's colour is
	 * true of the sum and grey would read as disabled.
	 */
	tone: ActivityTone;
	/** Every kind it holds, in the registry's order: its table cell, its chart, its level's rows. */
	kinds: readonly ActivityKind[];
	/**
	 * Whether its kinds partition it, so they add up to a total worth a column: a review is one
	 * verdict, a comment is in one place. The steps of a lifecycle — opened, then merged or closed —
	 * add up to nothing.
	 */
	partitioned: boolean;
	/**
	 * The one number its tile leads with and charts over time: the kinds summed for it, what the
	 * number counts in a sentence ("3 pull requests merged"), and — only where the tile's title does
	 * not already say it — the word after the number ("3 merged"). Kinds of different units are never
	 * summed.
	 */
	headline: {
		kinds: readonly ActivityKind[];
		noun: (provider: ProviderType) => Noun;
		qualifier?: string;
	};
	/** The chips under the headline: the rest of the category, or the headline's own breakdown. */
	chips: readonly ActivityKind[];
}

const REVIEW_KINDS = ["REVIEW_APPROVED", "REVIEW_CHANGES_REQUESTED", "REVIEW_COMMENTED"] as const;
const COMMENT_KINDS = ["COMMENTED", "CODE_COMMENTED"] as const;

export const ACTIVITY_CATEGORY_DEFS = {
	"pull-requests": {
		label: (provider) => capitalise(artifactKindNoun(ARTIFACT_KIND.pullRequest, 2, provider)),
		icon: providerIcon(GitMergeIcon, GitLabMergeIcon),
		tone: "done",
		kinds: ["PULL_REQUEST_OPENED", "PULL_REQUEST_MERGED", "PULL_REQUEST_CLOSED"],
		partitioned: false,
		headline: {
			kinds: ["PULL_REQUEST_MERGED"],
			noun: pullRequestNoun("merged"),
			qualifier: "merged",
		},
		chips: ["PULL_REQUEST_OPENED", "PULL_REQUEST_CLOSED"],
	},
	reviews: {
		label: () => "Reviews",
		icon: providerIcon(EyeIcon, GitLabEyeIcon),
		tone: "accent",
		kinds: REVIEW_KINDS,
		partitioned: true,
		headline: { kinds: REVIEW_KINDS, noun: fixedNoun("review", "reviews") },
		chips: REVIEW_KINDS,
	},
	comments: {
		label: () => "Comments",
		icon: providerIcon(CommentDiscussionIcon, GitLabCommentsIcon),
		tone: "accent",
		kinds: COMMENT_KINDS,
		partitioned: true,
		headline: { kinds: COMMENT_KINDS, noun: fixedNoun("comment", "comments") },
		chips: COMMENT_KINDS,
	},
	issues: {
		label: () => "Issues",
		icon: providerIcon(IssueOpenedIcon, GitLabIssueOpenIcon),
		tone: "open",
		kinds: ["ISSUE_OPENED", "ISSUE_CLOSED"],
		partitioned: false,
		headline: {
			kinds: ["ISSUE_OPENED"],
			noun: fixedNoun("issue opened", "issues opened"),
			qualifier: "opened",
		},
		chips: ["ISSUE_CLOSED"],
	},
} as const satisfies Record<ActivityCategory, ActivityCategoryDef>;

/** Whether anything at all happened in a summary. */
export function hasActivity(summary: ActivitySummary): boolean {
	return kindsTotal(summary, ACTIVITY_KINDS) > 0;
}
