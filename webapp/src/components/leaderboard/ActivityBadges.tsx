import { ChevronLeftIcon } from "@primer/octicons-react";

import { cn } from "cn";
import { Skeleton } from "@/components/ui/skeleton";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import type { ProviderType } from "@/lib/provider";

import {
	type ActivityBadgeKey,
	type ActivityBadgeMetadata,
	getActivityBadgeMetadata,
} from "./activity-badge-metadata";
import { type ReviewedPullRequest, ReviewsPopover } from "./ReviewsPopover";

export interface ActivityBadgesProps {
	reviewedPullRequests?: readonly ReviewedPullRequest[];
	changeRequests: number;
	approvals: number;
	comments: number;
	codeComments: number;
	ownReplies?: number;
	openPullRequests?: number;
	mergedPullRequests?: number;
	closedPullRequests?: number;
	openedIssues?: number;
	closedIssues?: number;
	highlightReviews?: boolean;
	className?: string;
	isLoading?: boolean;
	providerType?: ProviderType;
}

export function ActivityBadges({
	reviewedPullRequests = [],
	changeRequests,
	approvals,
	comments,
	codeComments,
	ownReplies = 0,
	openPullRequests = 0,
	mergedPullRequests = 0,
	closedPullRequests = 0,
	openedIssues = 0,
	closedIssues = 0,
	highlightReviews = false,
	className,
	isLoading = false,
	providerType = "GITHUB",
}: ActivityBadgesProps) {
	const counts: Record<ActivityBadgeKey, number> = {
		changeRequests,
		approvals,
		comments,
		codeComments,
		ownReplies,
		openPullRequests,
		mergedPullRequests,
		closedPullRequests,
		openedIssues,
		closedIssues,
	};
	const activityBadgeMetadata = getActivityBadgeMetadata(providerType);
	const scoredActivityBadges = activityBadgeMetadata.filter((item) => item.countsTowardScore);
	const visibleOnlyActivityBadges = activityBadgeMetadata.filter((item) => !item.countsTowardScore);
	const hasScoredActivity =
		reviewedPullRequests.length > 0 || scoredActivityBadges.some((item) => counts[item.key] > 0);
	const hasVisibleOnlyActivity = visibleOnlyActivityBadges.some((item) => counts[item.key] > 0);

	const hasActivity = hasScoredActivity || hasVisibleOnlyActivity;

	if (!hasActivity && !isLoading) return null;

	if (isLoading) {
		return (
			<div className={cn("flex items-center gap-2", className)}>
				{[40, 15, 25, 35, 30, 30, 30].map((width, index) => (
					<Skeleton key={`activity-badge-skeleton-${index}`} className="h-4" style={{ width }} />
				))}
			</div>
		);
	}

	return (
		<div className={cn("flex items-center gap-2", className)}>
			<span className="sr-only">
				Activity badges. Reviews affect score. The rest is shown for context.
			</span>
			{reviewedPullRequests.length > 0 && (
				<>
					<ReviewsPopover
						reviewedPullRequests={reviewedPullRequests}
						highlight={highlightReviews}
						providerType={providerType}
					/>
					<div className="flex items-center text-provider-muted-foreground">
						<ChevronLeftIcon className="h-4 w-4" />
					</div>
				</>
			)}
			{scoredActivityBadges.map((item) => (
				<ActivityBadgeItem key={item.key} item={item} count={counts[item.key]} />
			))}
			{hasScoredActivity && hasVisibleOnlyActivity && (
				<div className="mx-1 h-4 border-l border-border" aria-hidden="true" />
			)}
			{visibleOnlyActivityBadges.map((item) => (
				<ActivityBadgeItem key={item.key} item={item} count={counts[item.key]} />
			))}
		</div>
	);
}

interface ActivityBadgeItemProps {
	item: ActivityBadgeMetadata;
	count: number;
}

function ActivityBadgeItem({ item, count }: ActivityBadgeItemProps) {
	if (count <= 0) return null;

	const Icon = item.icon;

	return (
		<Tooltip>
			<TooltipTrigger
				className="cursor-help"
				aria-label={item.ariaLabel(count)}
				render={<div className={cn("flex items-center gap-1", item.colorClass)} />}
			>
				<Icon className="h-4 w-4" />
				<span>{count}</span>
			</TooltipTrigger>
			<TooltipContent>{item.tooltip}</TooltipContent>
		</Tooltip>
	);
}
