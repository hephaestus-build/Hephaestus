import {
	CheckCircleIcon,
	CheckIcon,
	ClockIcon,
	EyeIcon,
	FileDiffIcon,
	GitPullRequestDraftIcon,
	OrganizationIcon,
	PeopleIcon,
} from "@primer/octicons-react";

import type { OpenWork, WorkItem } from "@/api/types.gen";
import {
	GitLabCheckIcon,
	GitLabGroupIcon,
	GitLabHourglassIcon,
	GitLabMergeRequestDraftIcon,
	GitLabReviewCheckmarkIcon,
	GitLabReviewListIcon,
	GitLabReviewWarningIcon,
	GitLabUsersIcon,
} from "@/components/icons/gitlab-icons";
import type { IconComponent } from "@/components/icons/provider-icons";
import type { ProviderType } from "@/lib/provider/provider-terms";

import { type ActivityTone, providerIcon } from "./activity-tones";

/** Whose open work: the reader's own, or a member's opened from Workspace activity. */
export type OpenWorkPerspective = "self" | "member";

export const OPEN_WORK_GROUPS = [
	"review-requested",
	"returned",
	"approved",
	"waiting",
	"drafts",
	"team-requested",
	"covered",
	"reviewed",
] as const;

export type OpenWorkGroup = (typeof OPEN_WORK_GROUPS)[number];

interface OpenWorkGroupDef {
	label: (perspective: OpenWorkPerspective) => string;
	icon: (provider: ProviderType) => IconComponent;
	tone: ActivityTone;
	/**
	 * Whether it asks something of the person now. The rest waits on someone else and sits folded
	 * away, so a settled request never reads as work to do.
	 */
	counted: boolean;
}

/**
 * The groups of open pull requests, in the order they are read: what needs the person, then what
 * waits on others. `groupOpenWork` decides which one a pull request is in.
 */
export const OPEN_WORK_GROUP_DEFS = {
	"review-requested": {
		label: () => "Review requested",
		icon: providerIcon(EyeIcon, GitLabReviewListIcon),
		tone: "attention",
		counted: true,
	},
	returned: {
		label: (perspective) => (perspective === "self" ? "Returned to you" : "Returned"),
		icon: providerIcon(FileDiffIcon, GitLabReviewWarningIcon),
		tone: "danger",
		counted: true,
	},
	approved: {
		label: () => "Approved",
		icon: providerIcon(CheckCircleIcon, GitLabReviewCheckmarkIcon),
		tone: "success",
		counted: true,
	},
	waiting: {
		label: () => "Waiting for review",
		icon: providerIcon(ClockIcon, GitLabHourglassIcon),
		tone: "muted",
		counted: false,
	},
	drafts: {
		label: () => "Drafts",
		icon: providerIcon(GitPullRequestDraftIcon, GitLabMergeRequestDraftIcon),
		tone: "muted",
		counted: false,
	},
	"team-requested": {
		label: (perspective) =>
			perspective === "self" ? "Requested from your team" : "Requested from their team",
		icon: providerIcon(OrganizationIcon, GitLabGroupIcon),
		tone: "muted",
		counted: false,
	},
	covered: {
		label: () => "Covered by other reviewers",
		icon: providerIcon(PeopleIcon, GitLabUsersIcon),
		tone: "muted",
		counted: false,
	},
	reviewed: {
		label: (perspective) => (perspective === "self" ? "Reviewed by you" : "Reviewed by them"),
		icon: providerIcon(CheckIcon, GitLabCheckIcon),
		tone: "muted",
		counted: false,
	},
} as const satisfies Record<OpenWorkGroup, OpenWorkGroupDef>;

type Review = Pick<WorkItem, "reviewDecision" | "reviewers">;

const changesRequested = (work: Review): boolean =>
	work.reviewers?.some((reviewer) => reviewer.state === "CHANGES_REQUESTED") === true;

/**
 * A review request someone else has already settled: another reviewer asked for changes, so the
 * author has to move before this review does anything; or the pull request is approved and another
 * reviewer's approval is what approved it. On GitLab the decision says whether the merge request's
 * approval rules are met. The person's own entry never covers their own request: a decision their
 * earlier approval set is theirs to revisit once the author asks them again. A merge request not yet
 * synced has no decision, so only a request for changes covers it.
 */
export function isCovered(work: Review, login: string | undefined): boolean {
	const others = (work.reviewers ?? []).filter((reviewer) => reviewer.user.login !== login);
	const approvedByOther =
		work.reviewDecision === "APPROVED" && others.some((reviewer) => reviewer.state === "APPROVED");
	return approvedByOther || others.some((reviewer) => reviewer.state === "CHANGES_REQUESTED");
}

/**
 * Approved, by the provider's decision; or, where the provider reports none, by at least one
 * approval and no request for changes.
 */
function isApproved(work: Review): boolean {
	if (work.reviewDecision !== undefined) {
		return work.reviewDecision === "APPROVED";
	}
	const reviewers = work.reviewers ?? [];
	return reviewers.some((reviewer) => reviewer.state === "APPROVED") && !changesRequested(work);
}

function isReturned(work: Pick<WorkItem, "reviewDecision" | "reviewers" | "checks">): boolean {
	return (
		work.reviewDecision === "CHANGES_REQUESTED" ||
		changesRequested(work) ||
		work.checks === "FAILURE"
	);
}

function authoredGroup(work: WorkItem): OpenWorkGroup {
	if (work.isDraft) {
		return "drafts";
	}
	if (isReturned(work)) {
		return "returned";
	}
	return isApproved(work) ? "approved" : "waiting";
}

/**
 * The person's own verdict on a pull request that still lists them: GitLab keeps a reviewer who
 * approved or requested changes among the reviewers until the author asks again, where GitHub
 * turns a new request back into a plain request. A comment is not a verdict.
 */
function reviewedBy(work: Review, login: string | undefined): boolean {
	const own = work.reviewers?.find((reviewer) => reviewer.user.login === login);
	return own?.state === "APPROVED" || own?.state === "CHANGES_REQUESTED";
}

function requestGroup(work: Review, login: string | undefined): OpenWorkGroup {
	if (reviewedBy(work, login)) {
		return "reviewed";
	}
	return isCovered(work, login) ? "covered" : "review-requested";
}

/**
 * One person's open pull requests by what they need, each in exactly one group and in the order
 * the server listed them: review requests split into the ones that need this person, the ones they
 * already gave a verdict on, and the ones other reviewers already covered; requests to a team of
 * theirs apart from those to them; their own pull requests
 * into drafts, the ones returned to them, the approved ones, and the rest, waiting for review.
 */
export function groupOpenWork(
	openWork: Pick<OpenWork, "reviewRequests" | "teamReviewRequests" | "pullRequests">,
	login: string | undefined,
): Record<OpenWorkGroup, WorkItem[]> {
	const groups: Record<OpenWorkGroup, WorkItem[]> = {
		"review-requested": [],
		returned: [],
		approved: [],
		waiting: [],
		drafts: [],
		"team-requested": [],
		covered: [],
		reviewed: [],
	};
	for (const work of openWork.reviewRequests.content) {
		groups[requestGroup(work, login)].push(work);
	}
	// Asked of a team the person is in, and not of them: someone on the team will pick it up, so it
	// waits with the rest rather than counting as the person's own.
	groups["team-requested"].push(...openWork.teamReviewRequests.content);
	for (const work of openWork.pullRequests.content) {
		groups[authoredGroup(work)].push(work);
	}
	return groups;
}
