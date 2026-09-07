import type { ReactNode } from "react";

import type { Profile, ProfileActivityMonitor } from "@/api/types.gen";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { Separator } from "@/components/ui/separator";
import type { ActivityMonitorFilters } from "@/lib/activity-monitor";
import type { ProviderType } from "@/lib/provider";
import type { LeaderboardSchedule } from "@/lib/timeframe";

import { ProfileContent } from "./ProfileContent";
import { ProfileHeader } from "./ProfileHeader";

interface ProfileProps {
	providerType?: ProviderType;
	profileData?: Profile;
	activityMonitorData?: ProfileActivityMonitor;
	activityMonitorError?: unknown;
	onRetryActivityMonitor?: () => void;
	activityMonitorFilters: ActivityMonitorFilters;
	onActivityMonitorFiltersChange: (filters: ActivityMonitorFilters) => void;
	isLoading: boolean;
	error?: unknown;
	onRetry?: () => void;
	username: string;
	currUserIsDashboardUser: boolean;
	workspaceSlug: string;
	after?: string;
	before?: string;
	onTimeframeChange?: (afterDate: string, beforeDate?: string) => void;
	schedule?: LeaderboardSchedule;
	achievementsEnabled?: boolean;
	progressionEnabled?: boolean;
	leaguesEnabled?: boolean;
	practiceGroupStandings?: ReactNode;
}

export function ProfilePage({
	providerType = "GITHUB",
	profileData,
	activityMonitorData,
	activityMonitorError,
	onRetryActivityMonitor,
	activityMonitorFilters,
	onActivityMonitorFiltersChange,
	isLoading,
	error,
	onRetry,
	username,
	currUserIsDashboardUser,
	workspaceSlug,
	after,
	before,
	onTimeframeChange,
	schedule,
	achievementsEnabled = true,
	progressionEnabled = true,
	leaguesEnabled = true,
	practiceGroupStandings,
}: ProfileProps) {
	if (error) {
		return (
			<div className="mx-auto w-full max-w-xl">
				<QueryErrorAlert error={error} title="Could not load this profile" onRetry={onRetry} />
			</div>
		);
	}

	return (
		<div className="mx-auto flex w-full max-w-6xl flex-col gap-8">
			<ProfileHeader
				user={profileData?.userInfo}
				firstContribution={profileData?.firstContribution}
				contributedRepositories={profileData?.contributedRepositories}
				leaguePoints={profileData?.userInfo.leaguePoints}
				userXpRecord={profileData?.xpRecord}
				isLoading={isLoading}
				workspaceSlug={workspaceSlug}
				achievementsEnabled={achievementsEnabled}
				progressionEnabled={progressionEnabled}
				leaguesEnabled={leaguesEnabled}
			/>
			{practiceGroupStandings && (
				<>
					{practiceGroupStandings}
					<Separator />
				</>
			)}
			{activityMonitorError ? (
				<QueryErrorAlert
					error={activityMonitorError}
					title="Could not load activity"
					onRetry={onRetryActivityMonitor}
				/>
			) : (
				<ProfileContent
					providerType={providerType}
					activityMonitorData={activityMonitorData}
					activityMonitorFilters={activityMonitorFilters}
					onActivityMonitorFiltersChange={onActivityMonitorFiltersChange}
					isLoading={isLoading}
					username={username}
					displayName={profileData?.userInfo.name}
					currUserIsDashboardUser={currUserIsDashboardUser}
					workspaceSlug={workspaceSlug}
					afterDate={after}
					beforeDate={before}
					onTimeframeChange={onTimeframeChange}
					schedule={schedule}
				/>
			)}
		</div>
	);
}
