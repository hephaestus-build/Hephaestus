import {
	CheckIcon,
	CommentDiscussionIcon,
	DotFillIcon,
	EyeIcon,
	FileDiffIcon,
	XCircleFillIcon,
} from "@primer/octicons-react";

import type { Reviewer, WorkItem } from "@/api/types.gen";
import {
	GitLabCheckCircleIcon,
	GitLabClockIcon,
	GitLabCommentIcon,
	GitLabErrorIcon,
	GitLabEyeIcon,
	GitLabStatusFailedIcon,
} from "@/components/icons/gitlab-icons";
import {
	getIssueStateIcon,
	getPullRequestStateIcon,
	type IconComponent,
} from "@/components/icons/provider-icons";
import { ARTIFACT_KIND, artifactKindNoun } from "@/lib/artifact-kinds";
import type { ProviderType } from "@/lib/provider/provider-terms";

import type { ActivityKindDef } from "./activity-kind-defs";
import { type ActivityTone, providerIcon } from "./activity-tones";

export type ReviewerState = Reviewer["state"];

interface ReviewerStateDef {
	/** The dot on the reviewer's avatar: the provider's own icon for where their review stands. */
	icon: (provider: ProviderType) => IconComponent;
	tone: ActivityTone;
	/** Where the review stands, after the reviewer's name: "Bob Brenner approved". */
	label: string;
}

/**
 * Where each reviewer of an open pull request stands, as GitHub's sidebar and GitLab's reviewer
 * avatars draw it: approved, changes requested, commented, or still asked for.
 */
export const REVIEWER_STATE_DEFS = {
	CHANGES_REQUESTED: {
		icon: providerIcon(FileDiffIcon, GitLabErrorIcon),
		tone: "danger",
		label: "requested changes",
	},
	APPROVED: {
		icon: providerIcon(CheckIcon, GitLabCheckCircleIcon),
		tone: "success",
		label: "approved",
	},
	COMMENTED: {
		icon: providerIcon(EyeIcon, GitLabEyeIcon),
		tone: "muted",
		label: "commented",
	},
	REQUESTED: {
		icon: providerIcon(DotFillIcon, GitLabClockIcon),
		tone: "attention",
		label: "review requested",
	},
} as const satisfies Record<ReviewerState, ReviewerStateDef>;

/**
 * The one check state a row shows. A pull request whose checks pass, run or never ran reads as
 * nothing, so passing is the quiet default and only a failure earns a mark.
 */
export const FAILING_CHECKS = {
	icon: providerIcon(XCircleFillIcon, GitLabStatusFailedIcon),
	tone: "danger",
	label: "Checks failing",
} as const satisfies {
	icon: (provider: ProviderType) => IconComponent;
	tone: ActivityTone;
	label: string;
};

export interface WorkStateVisual {
	icon: IconComponent;
	/** The icon's colour class, from the provider palette. */
	colorClass: string;
	/** What the icon says, for a reader who does not see it: "Merged pull request". */
	label: string;
}

const PULL_REQUEST_STATE_WORDS = {
	OPEN: "Open",
	MERGED: "Merged",
	CLOSED: "Closed",
} as const satisfies Record<WorkItem["state"], string>;

/** A pull request's or an issue's state as its provider draws it, with the words for it. */
export function workStateVisual(
	work: Pick<WorkItem, "type" | "state" | "isDraft">,
	provider: ProviderType,
): WorkStateVisual {
	if (work.type === "ISSUE") {
		const { icon, colorClass } = getIssueStateIcon(provider, work.state);
		return { icon, colorClass, label: `${work.state === "OPEN" ? "Open" : "Closed"} issue` };
	}
	const { icon, colorClass } = getPullRequestStateIcon(provider, work.state, work.isDraft);
	const state =
		work.state === "OPEN" && work.isDraft ? "Draft" : PULL_REQUEST_STATE_WORDS[work.state];
	return {
		icon,
		colorClass,
		label: `${state} ${artifactKindNoun(ARTIFACT_KIND.pullRequest, 1, provider)}`,
	};
}

const DISCUSSION_ICON = providerIcon(CommentDiscussionIcon, GitLabCommentIcon);

/**
 * Work the provider no longer has, drawn as its kind of work in the muted tone: the shape still says
 * whether it was a pull request or an issue, and the colour claims no state. Where only a comment
 * says what it was, the icon is the conversation's.
 */
export function goneWorkVisual(
	work: ActivityKindDef["work"],
	provider: ProviderType,
): WorkStateVisual {
	const colorClass = "text-provider-muted-foreground";
	if (work === "pull-request") {
		const { icon } = getPullRequestStateIcon(provider, "OPEN");
		return { icon, colorClass, label: "No longer available" };
	}
	const icon =
		work === "issue" ? getIssueStateIcon(provider, "OPEN").icon : DISCUSSION_ICON(provider);
	return { icon, colorClass, label: "No longer available" };
}
