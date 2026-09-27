import {
	CheckIcon,
	CircleCheckIcon,
	CircleDotIcon,
	FileDiffIcon,
	GitMergeIcon,
	GitPullRequestArrowIcon,
	GitPullRequestClosedIcon,
	MessageCircleIcon,
	MessageSquareCodeIcon,
	MessageSquareTextIcon,
	type LucideIcon,
} from "lucide-react";

import type { ActivityItem, ActivitySummary } from "@/api/types.gen";
import { countsTogether } from "@/components/practice-vocabulary/feedback-text";
import { ARTIFACT_KIND, artifactKindNoun } from "@/lib/artifact-kinds";
import type { ProviderType } from "@/lib/provider/provider-terms";
import { andList, capitalise } from "@/lib/text";

export type ActivityKind = ActivityItem["kind"];

export interface ActivityKindDef {
	/** What happened, as a timeline entry says it under the work's title: "Merged". */
	label: string;
	icon: LucideIcon;
	/** The same event as a summary counts it: "three merged", "one approval". */
	counted: { one: string; many: string };
	summaryField: keyof ActivitySummary;
	/**
	 * Whom the entry names. A review, a comment or an opening is the actor's own doing; a merge or a
	 * close is counted for the work's author, who is not necessarily the person who merged or closed it.
	 */
	credit: "actor" | "author";
	/** What it happens on, which is how the entry names the work once the provider no longer has it. */
	work: "pull-request" | "issue" | "any";
}

/** One registry for what someone did: "Approved by Bob Brenner", "Merged · opened by Ada Lovelace". */
export const ACTIVITY_KIND_DEFS = {
	PULL_REQUEST_OPENED: {
		label: "Opened",
		icon: GitPullRequestArrowIcon,
		counted: { one: "opened", many: "opened" },
		summaryField: "pullRequestsOpened",
		credit: "actor",
		work: "pull-request",
	},
	PULL_REQUEST_MERGED: {
		label: "Merged",
		icon: GitMergeIcon,
		counted: { one: "merged", many: "merged" },
		summaryField: "pullRequestsMerged",
		credit: "author",
		work: "pull-request",
	},
	PULL_REQUEST_CLOSED: {
		label: "Closed without merging",
		icon: GitPullRequestClosedIcon,
		counted: { one: "closed without merging", many: "closed without merging" },
		summaryField: "pullRequestsClosed",
		credit: "author",
		work: "pull-request",
	},
	REVIEW_APPROVED: {
		label: "Approved",
		icon: CheckIcon,
		counted: { one: "approval", many: "approvals" },
		summaryField: "approvals",
		credit: "actor",
		work: "pull-request",
	},
	REVIEW_CHANGES_REQUESTED: {
		label: "Changes requested",
		icon: FileDiffIcon,
		counted: { one: "change request", many: "change requests" },
		summaryField: "changeRequests",
		credit: "actor",
		work: "pull-request",
	},
	REVIEW_COMMENTED: {
		label: "Comment-only review",
		icon: MessageSquareTextIcon,
		counted: { one: "comment-only review", many: "comment-only reviews" },
		summaryField: "commentReviews",
		credit: "actor",
		work: "pull-request",
	},
	COMMENTED: {
		label: "Commented",
		icon: MessageCircleIcon,
		counted: { one: "in a conversation", many: "in conversations" },
		summaryField: "comments",
		credit: "actor",
		work: "any",
	},
	CODE_COMMENTED: {
		label: "Commented on code",
		icon: MessageSquareCodeIcon,
		counted: { one: "on code", many: "on code" },
		summaryField: "codeComments",
		credit: "actor",
		work: "pull-request",
	},
	ISSUE_OPENED: {
		label: "Opened",
		icon: CircleDotIcon,
		counted: { one: "opened", many: "opened" },
		summaryField: "issuesOpened",
		credit: "actor",
		work: "issue",
	},
	ISSUE_CLOSED: {
		label: "Closed",
		icon: CircleCheckIcon,
		counted: { one: "closed", many: "closed" },
		summaryField: "issuesClosed",
		credit: "author",
		work: "issue",
	},
} as const satisfies Record<ActivityKind, ActivityKindDef>;

/** The event stays, so the count stays true; the work it happened on is gone from the provider. */
export function goneWork(kind: ActivityKind, provider: ProviderType): string {
	const noun = {
		"pull-request": `A ${artifactKindNoun(ARTIFACT_KIND.pullRequest, 1, provider)}`,
		issue: "An issue",
		any: "Work",
	}[ACTIVITY_KIND_DEFS[kind].work];
	return `${noun} that is no longer available`;
}

export const ACTIVITY_CATEGORIES = ["pull-requests", "reviews", "issues", "comments"] as const;

export type ActivityCategory = (typeof ACTIVITY_CATEGORIES)[number];

export interface ActivityCategoryDef {
	/** The category's title, in the provider's words where it has its own: "Merge requests". */
	label: (provider: ProviderType) => string;
	icon: LucideIcon;
	/** What the category's row counts and its level lists, in the order the row's figures read. */
	kinds: readonly ActivityKind[];
}

export const ACTIVITY_CATEGORY_DEFS = {
	"pull-requests": {
		label: (provider) => capitalise(artifactKindNoun(ARTIFACT_KIND.pullRequest, 2, provider)),
		icon: GitPullRequestArrowIcon,
		kinds: ["PULL_REQUEST_OPENED", "PULL_REQUEST_MERGED", "PULL_REQUEST_CLOSED"],
	},
	reviews: {
		label: () => "Reviews",
		icon: MessageSquareTextIcon,
		kinds: ["REVIEW_APPROVED", "REVIEW_CHANGES_REQUESTED", "REVIEW_COMMENTED"],
	},
	issues: {
		label: () => "Issues",
		icon: CircleDotIcon,
		kinds: ["ISSUE_OPENED", "ISSUE_CLOSED"],
	},
	comments: {
		label: () => "Comments",
		icon: MessageCircleIcon,
		kinds: ["COMMENTED", "CODE_COMMENTED"],
	},
} as const satisfies Record<ActivityCategory, ActivityCategoryDef>;

function categoryCounts(category: ActivityCategory, summary: ActivitySummary) {
	return ACTIVITY_CATEGORY_DEFS[category].kinds.map((kind) => {
		const { summaryField, counted } = ACTIVITY_KIND_DEFS[kind];
		return { n: summary[summaryField], ...counted };
	});
}

export function categoryTotal(category: ActivityCategory, summary: ActivitySummary): number {
	return categoryCounts(category, summary).reduce((sum, part) => sum + part.n, 0);
}

export function hasActivity(summary: ActivitySummary): boolean {
	return ACTIVITY_CATEGORIES.some((category) => categoryTotal(category, summary) > 0);
}

/** "four opened and three merged": the category's figures that are not zero, as one sentence. */
export function categorySentence(category: ActivityCategory, summary: ActivitySummary): string {
	return andList.format(
		countsTogether(categoryCounts(category, summary).filter((part) => part.n > 0)),
	);
}
