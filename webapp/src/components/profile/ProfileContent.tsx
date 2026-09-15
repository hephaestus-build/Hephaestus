import { CodeReviewIcon } from "@primer/octicons-react";
import { ArrowRightIcon } from "lucide-react";

import type { ProfileActivityMonitor, PullRequestBaseInfo } from "@/api/types.gen";
import { ActivityBadges } from "@/components/leaderboard/ActivityBadges";
import type { ReviewedPullRequest } from "@/components/leaderboard/ReviewsPopover";
import { EmptyState } from "@/components/shared/EmptyState";
import { IssueCard } from "@/components/shared/IssueCard";
import { Button } from "@/components/ui/button";
import { type ActivityMonitorFilters, MAX_ACTIVITY_MONITOR_LIMIT } from "@/lib/activity-monitor";
import { getProviderTerms, getPullRequestStateIcon, type ProviderType } from "@/lib/provider";
import { firstNonBlank } from "@/lib/text";
import type { LeaderboardSchedule } from "@/lib/timeframe";

import { ActivityMonitorConfiguration } from "./ActivityMonitorConfiguration";
import { ProfileTimeframePicker } from "./ProfileTimeframePicker";
import { ReviewActivityCard } from "./ReviewActivityCard";

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
	const stats = activityMonitorData?.activityStats;
	const personLabel = firstNonBlank(displayName) ?? username;
	const repositories = activityMonitorData?.repositories ?? [];

	const reviewActivity = (activityMonitorData?.reviewActivity ?? []).filter(
		(activity) => activity.score > 0,
	);
	const pullRequests = activityMonitorData?.authoredPullRequests ?? [];
	const totalReviewActivityCount = activityMonitorData?.totalReviewActivityCount ?? 0;
	const totalAuthoredPullRequestCount = activityMonitorData?.totalAuthoredPullRequestCount ?? 0;

	const terms = getProviderTerms(providerType);
	const { icon: PrIcon } = getPullRequestStateIcon(providerType, "OPEN");

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
							approvals={stats?.numberOfApprovals ?? 0}
							changeRequests={stats?.numberOfChangeRequests ?? 0}
							comments={stats?.numberOfComments ?? 0}
							codeComments={stats?.numberOfCodeComments ?? 0}
							ownReplies={stats?.numberOfOwnReplies ?? 0}
							openPullRequests={stats?.numberOfOpenPullRequests ?? 0}
							mergedPullRequests={stats?.numberOfMergedPullRequests ?? 0}
							closedPullRequests={stats?.numberOfClosedPullRequests ?? 0}
							openedIssues={stats?.numberOfOpenedIssues ?? 0}
							closedIssues={stats?.numberOfClosedIssues ?? 0}
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
				<div className="flex flex-col gap-4">
					<h3 className="text-lg font-semibold">Review activity</h3>
					<div className="flex flex-col gap-2">
						{isLoading ? (
							Array.from({ length: 3 }, (_, i) => (
								<ReviewActivityCard key={i} isLoading providerType={providerType} />
							))
						) : reviewActivity.length > 0 ? (
							reviewActivity.map((activity) => (
								<ReviewActivityCard
									key={activity.id}
									isLoading={false}
									state={activity.state}
									submittedAt={activity.submittedAt}
									htmlUrl={activity.htmlUrl}
									pullRequest={activity.pullRequest}
									repositoryName={activity.pullRequest?.repository?.name}
									score={activity.score}
									providerType={providerType}
								/>
							))
						) : (
							<EmptyState
								icon={<CodeReviewIcon className="size-6" size={24} />}
								title="No review activity"
								description={
									currUserIsDashboardUser
										? `No review activity that counts yet. Try a wider timeframe.`
										: `${personLabel} has no review activity that counts in this timeframe.`
								}
							/>
						)}
					</div>
					{canViewAllReviewActivity && (
						<Button
							type="button"
							variant="link"
							size="inline"
							className="w-fit text-sm"
							onClick={expandMonitor}
						>
							View all review activity
							<ArrowRightIcon data-icon="inline-end" />
						</Button>
					)}
				</div>
				<div className="flex flex-col gap-4">
					<h3 className="text-lg font-semibold">Open {terms.pullRequests.toLowerCase()}</h3>
					<div className="flex flex-col gap-2">
						{isLoading ? (
							Array.from({ length: 2 }, (_, i) => (
								<IssueCard key={i} isLoading providerType={providerType} />
							))
						) : pullRequests.length > 0 ? (
							pullRequests.map((pullRequest) => (
								<IssueCard
									key={pullRequest.id}
									isLoading={false}
									additions={pullRequest.additions}
									deletions={pullRequest.deletions}
									number={pullRequest.number}
									repositoryName={pullRequest.repository?.name}
									title={pullRequest.title}
									htmlUrl={pullRequest.htmlUrl}
									state={pullRequest.state}
									isDraft={pullRequest.isDraft}
									isMerged={pullRequest.isMerged}
									createdAt={pullRequest.createdAt}
									pullRequestLabels={pullRequest.labels}
									providerType={providerType}
								/>
							))
						) : (
							<EmptyState
								icon={<PrIcon className="size-6" size={24} />}
								title={`No open ${terms.pullRequests.toLowerCase()}`}
								description={
									currUserIsDashboardUser
										? `${terms.pullRequests} you create will appear here.`
										: `${personLabel} doesn't have any open ${terms.pullRequests.toLowerCase()}.`
								}
							/>
						)}
					</div>
					{canViewAllPullRequests && (
						<Button
							type="button"
							variant="link"
							size="inline"
							className="w-fit text-sm"
							onClick={expandMonitor}
						>
							View all {terms.pullRequests.toLowerCase()}
							<ArrowRightIcon data-icon="inline-end" />
						</Button>
					)}
				</div>
			</div>
		</div>
	);
}
