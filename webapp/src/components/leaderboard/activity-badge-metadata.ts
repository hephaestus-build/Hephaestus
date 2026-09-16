import {
	CheckIcon,
	CommentDiscussionIcon,
	CommentIcon,
	FileDiffIcon,
	IssueClosedIcon,
	IssueOpenedIcon,
} from "@primer/octicons-react";
import { MessageSquareReply } from "lucide-react";

import { getPullRequestStateIcon, type IconComponent } from "@/components/icons/provider-icons";
import { getProviderTerms, type ProviderType } from "@/lib/provider/provider-terms";

export interface ActivityBadgeMetadata {
	key: ActivityBadgeKey;
	label: string;
	tooltip: string;
	ariaLabel: (count: number) => string;
	icon: IconComponent;
	colorClass: string;
	countsTowardScore: boolean;
}

function buildActivityBadgeMetadata(providerType: ProviderType) {
	const pullRequests = getProviderTerms(providerType).pullRequests.toLowerCase();
	const open = getPullRequestStateIcon(providerType, "OPEN");
	const merged = getPullRequestStateIcon(providerType, "MERGED");
	const closed = getPullRequestStateIcon(providerType, "CLOSED");

	return [
		{
			key: "changeRequests",
			countsTowardScore: true,
			icon: FileDiffIcon,
			colorClass: "text-provider-danger-foreground",
			label: "Changes requested",
			tooltip: "Changes Requested",
			ariaLabel: (count: number) => `${count} changes requested. Counts toward score.`,
		},
		{
			key: "approvals",
			countsTowardScore: true,
			icon: CheckIcon,
			colorClass: "text-provider-success-foreground",
			label: "Approvals",
			tooltip: "Approvals",
			ariaLabel: (count: number) => `${count} approvals. Counts toward score.`,
		},
		{
			key: "comments",
			countsTowardScore: true,
			icon: CommentIcon,
			colorClass: "text-provider-muted-foreground",
			label: "Review comments",
			tooltip: "Review comments",
			ariaLabel: (count: number) => `${count} comment reviews. Counts toward score.`,
		},
		{
			key: "codeComments",
			countsTowardScore: true,
			icon: CommentDiscussionIcon,
			colorClass: "text-provider-muted-foreground",
			label: "Inline comments",
			tooltip: `Inline comments on someone else's ${pullRequests}. Counts toward score.`,
			ariaLabel: (count: number) =>
				`${count} scored inline feedback comments on ${pullRequests} authored by someone else. Counts toward score.`,
		},
		{
			key: "ownReplies",
			countsTowardScore: false,
			icon: MessageSquareReply,
			colorClass: "text-provider-muted-foreground",
			label: `Replies on your own ${pullRequests}`,
			tooltip: `Replies on your own ${pullRequests}. Doesn't affect score.`,
			ariaLabel: (count: number) =>
				`${count} replies on your own ${pullRequests} and inline threads. Visible only and does not affect score.`,
		},
		{
			key: "openPullRequests",
			countsTowardScore: false,
			icon: open.icon,
			colorClass: open.colorClass,
			label: `Open ${pullRequests}`,
			tooltip: `Your open ${pullRequests}. Doesn't affect score.`,
			ariaLabel: (count: number) =>
				`${count} open ${pullRequests} you authored. Visible only and does not affect score.`,
		},
		{
			key: "mergedPullRequests",
			countsTowardScore: false,
			icon: merged.icon,
			colorClass: merged.colorClass,
			label: `Merged ${pullRequests}`,
			tooltip: `Your merged ${pullRequests}. Doesn't affect score.`,
			ariaLabel: (count: number) =>
				`${count} merged ${pullRequests} you authored. Visible only and does not affect score.`,
		},
		{
			key: "closedPullRequests",
			countsTowardScore: false,
			icon: closed.icon,
			colorClass: closed.colorClass,
			label: `Closed ${pullRequests}`,
			tooltip: `Your closed ${pullRequests}. Doesn't affect score.`,
			ariaLabel: (count: number) =>
				`${count} closed ${pullRequests} you authored. Visible only and does not affect score.`,
		},
		{
			key: "openedIssues",
			countsTowardScore: false,
			icon: IssueOpenedIcon,
			colorClass: "text-provider-open-foreground",
			label: "Opened issues",
			tooltip: "Issues you opened. Doesn't affect score.",
			ariaLabel: (count: number) =>
				`${count} opened issues. Visible only and does not affect score.`,
		},
		{
			key: "closedIssues",
			countsTowardScore: false,
			icon: IssueClosedIcon,
			colorClass: "text-provider-done-foreground",
			label: "Closed issues",
			tooltip: "Issues you closed. Doesn't affect score.",
			ariaLabel: (count: number) =>
				`${count} closed issues. Visible only and does not affect score.`,
		},
	] as const;
}

const RESOLVED = {
	GITHUB: buildActivityBadgeMetadata("GITHUB"),
	GITLAB: buildActivityBadgeMetadata("GITLAB"),
} as const;

export type ActivityBadgeKey = (typeof RESOLVED)["GITHUB"][number]["key"];

export function getActivityBadgeMetadata(
	providerType: ProviderType,
): readonly ActivityBadgeMetadata[] {
	return RESOLVED[providerType];
}
