import type {
	ProfileActivityMonitor,
	ProfileActivityStats,
	PullRequestBaseInfo,
} from "@/api/types.gen";
import { ActivityBadges } from "@/components/leaderboard/ActivityBadges";
import type { ReviewedPullRequest } from "@/components/leaderboard/ReviewsPopover";
import { type ActivityMonitorFilters, MAX_ACTIVITY_MONITOR_LIMIT } from "@/lib/activity-monitor";
import { getProviderTerms, type ProviderType } from "@/lib/provider/provider-terms";
import { firstNonBlank } from "@/lib/text";
import type { LeaderboardSchedule } from "@/lib/timeframe";

import { ActivityMonitorConfiguration } from "./ActivityMonitorConfiguration";
import { OpenPullRequestsSection } from "./OpenPullRequestsSection";
import { ProfileTimeframePicker } from "./ProfileTimeframePicker";
import { ReviewActivitySection } from "./ReviewActivitySection";

export interface ProfileContentProps {
	providerType?: ProviderType;
	activityMonitorData?: ProfileActivityMonitor;
	activityMonitorFilters: ActivityMonitorFilters;
	onActivityMonitorFiltersChange: (filters: ActivityMonitorFilters) => void;
	isLoading: boolean;
	username: string;
	displayName?: string;
	currUserIsDashboardUser: boolean;
	workspaceSlug: string;
	afterDate?: string;
	beforeDate?: string;
	onTimeframeChange?: (afterDate: string, beforeDate?: string) => void;
	schedule?: LeaderboardSchedule;
}

export const ZERO_ACTIVITY_STATS: ProfileActivityStats = {
	numberOfApprovals: 0,
	numberOfChangeRequests: 0,
	numberOfClosedIssues: 0,
	numberOfClosedPullRequests: 0,
	numberOfCodeComments: 0,
	numberOfComments: 0,
	numberOfMergedPullRequests: 0,
	numberOfOpenPullRequests: 0,
	numberOfOpenedIssues: 0,
	numberOfOwnReplies: 0,
	numberOfReviewedPRs: 0,
	numberOfUnknowns: 0,
	score: 0,
};

export function ProfileContent({
	providerType = "GITHUB",
	activityMonitorData,
	activityMonitorFilters,
	onActivityMonitorFiltersChange,
	isLoading,
	username,
	displayName,
	currUserIsDashboardUser,
	afterDate,
	beforeDate,
	onTimeframeChange,
	schedule,
}: ProfileContentProps) {
	const stats = activityMonitorData?.activityStats ?? ZERO_ACTIVITY_STATS;
	const personLabel = firstNonBlank(displayName) ?? username;
	const terms = getProviderTerms(providerType);
	const pullRequestsTerm = terms.pullRequests.toLowerCase();
	const reviewActivityEmptyMessage = currUserIsDashboardUser
		? "No review activity that counts yet. Try a wider timeframe."
		: `${personLabel} has no review activity that counts in this timeframe.`;
	const pullRequestsEmptyMessage = currUserIsDashboardUser
		? `${terms.pullRequests} you create will appear here.`
		: `${personLabel} doesn't have any open ${pullRequestsTerm}.`;
	const repositories = activityMonitorData?.repositories ?? [];

	const reviewActivity = (activityMonitorData?.reviewActivity ?? []).filter(
		(activity) => activity.score > 0,
	);
	const pullRequests = activityMonitorData?.authoredPullRequests ?? [];
	const totalReviewActivityCount = activityMonitorData?.totalReviewActivityCount ?? 0;
	const totalAuthoredPullRequestCount = activityMonitorData?.totalAuthoredPullRequestCount ?? 0;

	const canViewAllReviewActivity = !isLoading && totalReviewActivityCount > reviewActivity.length;
	const canViewAllPullRequests = !isLoading && totalAuthoredPullRequestCount > pullRequests.length;

	const reviewedPullRequestsForPopover: ReviewedPullRequest[] = reviewActivity
		.map((activity) => activity.pullRequest)
		.filter((pr): pr is PullRequestBaseInfo => Boolean(pr));

	const expandMonitor = () => {
		onActivityMonitorFiltersChange({
			...activityMonitorFilters,
			limit: MAX_ACTIVITY_MONITOR_LIMIT,
		});
	};

	return (
		<div className="flex flex-col gap-4">
			<div className="flex flex-col gap-3 md:flex-row md:items-start md:justify-between">
				<div className="flex flex-col gap-1">
					<div className="flex flex-wrap items-center gap-3">
						<h2 className="text-xl font-semibold">Activity Monitor</h2>
						<ActivityBadges
							reviewedPullRequests={reviewedPullRequestsForPopover}
							approvals={stats.numberOfApprovals}
							changeRequests={stats.numberOfChangeRequests}
							comments={stats.numberOfComments}
							codeComments={stats.numberOfCodeComments}
							ownReplies={stats.numberOfOwnReplies}
							openPullRequests={stats.numberOfOpenPullRequests}
							mergedPullRequests={stats.numberOfMergedPullRequests}
							closedPullRequests={stats.numberOfClosedPullRequests}
							openedIssues={stats.numberOfOpenedIssues}
							closedIssues={stats.numberOfClosedIssues}
							isLoading={isLoading}
							providerType={providerType}
						/>
					</div>
					<p className="text-sm text-provider-muted-foreground">
						Activity across projects in this workspace.
					</p>
				</div>
				<div className="flex flex-wrap items-center gap-2 md:justify-end">
					<ProfileTimeframePicker
						afterDate={afterDate}
						beforeDate={beforeDate}
						onTimeframeChange={onTimeframeChange}
						schedule={schedule}
						enableAllActivity
					/>
					<ActivityMonitorConfiguration
						repositories={repositories}
						filters={activityMonitorFilters}
						onFiltersChange={onActivityMonitorFiltersChange}
					/>
				</div>
			</div>
			<div className="grid grid-cols-1 gap-2 lg:grid-cols-2">
				<ReviewActivitySection
					providerType={providerType}
					reviewActivity={reviewActivity}
					isLoading={isLoading}
					emptyMessage={reviewActivityEmptyMessage}
					canViewAll={canViewAllReviewActivity}
					onViewAll={expandMonitor}
				/>
				<OpenPullRequestsSection
					providerType={providerType}
					pullRequests={pullRequests}
					isLoading={isLoading}
					emptyMessage={pullRequestsEmptyMessage}
					canViewAll={canViewAllPullRequests}
					onViewAll={expandMonitor}
				/>
			</div>
		</div>
	);
}
