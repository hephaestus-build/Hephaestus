import { useQuery } from "@tanstack/react-query";
import { createFileRoute, retainSearchParams, useNavigate } from "@tanstack/react-router";
import { z } from "zod";

import {
	getActivityMonitorOptions,
	getUserProfileOptions,
	getWorkspaceOptions,
	listGroupsOptions,
	listPracticeGroupStandingsOptions,
	listPracticeStandingsOptions,
} from "@/api/@tanstack/react-query.gen";
import type { PracticeStanding } from "@/api/types.gen";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { useNow } from "@/components/common/use-now";
import { PracticeGroupStandingCard } from "@/components/profile/PracticeGroupStandingCard";
import { ProfilePage } from "@/components/profile/ProfilePage";
import { useWorkspaceFeatures } from "@/hooks/use-workspace-features";
import { useAuth } from "@/integrations/auth/AuthContext";
import {
	type ActivityMonitorFilters,
	DEFAULT_ACTIVITY_MONITOR_LIMIT,
	MAX_ACTIVITY_MONITOR_LIMIT,
} from "@/lib/activity-monitor";
import { resolveLeaderboardSchedule } from "@/lib/leaderboard-schedule";
import { toScmProviderType } from "@/lib/provider";
import { formatDateRangeForApi, getDateRangeForPreset } from "@/lib/timeframe";

const profileSearchSchema = z.object({
	after: z.string().optional(),
	before: z.string().optional(),
	monitorRepositories: z.string().optional(),
	monitorLimit: z.coerce
		.number()
		.int()
		.min(1)
		.max(MAX_ACTIVITY_MONITOR_LIMIT)
		.default(DEFAULT_ACTIVITY_MONITOR_LIMIT),
});

type ProfileSearchParams = z.infer<typeof profileSearchSchema>;

const parseRepositoryIds = (value?: string): number[] => {
	if (!value) return [];

	return value
		.split(",")
		.map((id) => id.trim())
		.filter((id) => /^\d+$/.test(id))
		.map((id) => Number(id))
		.filter((id) => Number.isSafeInteger(id) && id > 0);
};

const serializeRepositoryIds = (repositoryIds: number[]) => {
	if (repositoryIds.length === 0) return undefined;
	return repositoryIds.join(",");
};

export const Route = createFileRoute("/_authenticated/w/$workspaceSlug/user/$username/")({
	component: UserProfile,
	validateSearch: profileSearchSchema,
	search: {
		middlewares: [retainSearchParams(["after", "before", "monitorRepositories", "monitorLimit"])],
	},
});

function UserProfile() {
	const { username, workspaceSlug } = Route.useParams();
	const { isCurrentUser } = useAuth();
	const featureState = useWorkspaceFeatures(workspaceSlug);
	const achievementsEnabled = featureState.features?.achievementsEnabled;
	const progressionEnabled = featureState.features?.progressionEnabled;
	const leaguesEnabled = featureState.features?.leaguesEnabled;
	const practicesEnabled = featureState.features?.practicesEnabled;
	const { after, before, monitorRepositories, monitorLimit } = Route.useSearch();
	const navigate = useNavigate({ from: Route.fullPath });

	const workspaceQuery = useQuery({
		...getWorkspaceOptions({
			path: { workspaceSlug },
		}),
		enabled: Boolean(workspaceSlug),
	});

	const schedule = resolveLeaderboardSchedule(workspaceQuery.data);
	const nowMs = useNow();

	const getEffectiveDates = () => {
		if (after) {
			return { after, before };
		}
		const range = getDateRangeForPreset(new Date(nowMs), "this-week", schedule);
		return formatDateRangeForApi(range);
	};
	const effectiveDates = getEffectiveDates();

	const parseDateParam = (value?: string) => {
		if (!value) return undefined;
		const parsed = new Date(value);
		return Number.isNaN(parsed.getTime()) ? undefined : parsed;
	};
	const parsedAfter = parseDateParam(effectiveDates.after);
	const parsedBefore = parseDateParam(effectiveDates.before);
	const selectedRepositoryIds = parseRepositoryIds(monitorRepositories);

	const currUserIsDashboardUser = isCurrentUser(username);

	// Standings only mean anything where practices review the work, so with them off this asks for
	// nothing rather than asking and rendering an empty answer as "none configured".
	const showsPracticeStandings = currUserIsDashboardUser && practicesEnabled === true;
	const groupsQuery = useQuery({
		...listGroupsOptions({
			path: { workspaceSlug },
			query: { visibleInPracticeDashboardsOnly: true },
		}),
		enabled: Boolean(workspaceSlug) && showsPracticeStandings,
	});
	const practiceGroups = groupsQuery.data ?? [];
	const groupStandingsQuery = useQuery({
		...listPracticeGroupStandingsOptions({
			path: { workspaceSlug },
		}),
		enabled: Boolean(workspaceSlug) && showsPracticeStandings,
	});
	const groupStandings = Object.fromEntries(
		(groupStandingsQuery.data ?? []).map((status) => [status.groupSlug, status]),
	);
	const standingsQuery = useQuery({
		...listPracticeStandingsOptions({ path: { workspaceSlug } }),
		enabled: Boolean(workspaceSlug) && showsPracticeStandings,
	});
	const practicesByGroup = (standingsQuery.data ?? []).reduce<Record<string, PracticeStanding[]>>(
		(grouped, practice) => {
			if (!practice.groupSlug) return grouped;
			const forGroup = grouped[practice.groupSlug] ?? [];
			forGroup.push(practice);
			grouped[practice.groupSlug] = forGroup;
			return grouped;
		},
		{},
	);

	const profileQuery = useQuery({
		...getUserProfileOptions({
			path: { workspaceSlug, login: username },
			query: {
				after: parsedAfter,
				before: parsedBefore,
			},
		}),
		placeholderData: (previousData) => previousData,
		enabled: Boolean(username) && Boolean(workspaceSlug),
	});

	const activityMonitorQuery = useQuery({
		...getActivityMonitorOptions({
			path: { workspaceSlug, login: username },
			query: {
				after: parsedAfter,
				before: parsedBefore,
				repositoryIds: selectedRepositoryIds.length > 0 ? selectedRepositoryIds : undefined,
				limit: monitorLimit,
			},
		}),
		placeholderData: (previousData) => previousData,
		enabled: Boolean(username) && Boolean(workspaceSlug),
	});

	const handleTimeframeChange = (nextAfter: string, nextBefore?: string) => {
		void navigate({
			search: (prev: ProfileSearchParams) => ({
				...prev,
				after: nextAfter,
				before: nextBefore,
			}),
		});
	};

	const handleActivityMonitorFiltersChange = (filters: ActivityMonitorFilters) => {
		void navigate({
			search: (prev: ProfileSearchParams) => ({
				...prev,
				monitorRepositories: serializeRepositoryIds(filters.repositoryIds),
				monitorLimit: filters.limit === DEFAULT_ACTIVITY_MONITOR_LIMIT ? undefined : filters.limit,
			}),
		});
	};

	if (featureState.isError) {
		return (
			<QueryErrorAlert
				error={featureState.error}
				title="Couldn't load workspace features"
				onRetry={featureState.refetch}
			/>
		);
	}

	return (
		<ProfilePage
			providerType={toScmProviderType(workspaceQuery.data?.providerType)}
			profileData={profileQuery.data}
			activityMonitorData={activityMonitorQuery.data}
			activityMonitorFilters={{
				repositoryIds: selectedRepositoryIds,
				limit: monitorLimit,
			}}
			onActivityMonitorFiltersChange={handleActivityMonitorFiltersChange}
			isLoading={
				profileQuery.isPending ||
				workspaceQuery.isPending ||
				(activityMonitorQuery.isPending && !activityMonitorQuery.data)
			}
			error={profileQuery.error ?? undefined}
			onRetry={() => void profileQuery.refetch()}
			username={username}
			currUserIsDashboardUser={currUserIsDashboardUser}
			workspaceSlug={workspaceSlug}
			after={effectiveDates.after}
			before={effectiveDates.before}
			onTimeframeChange={handleTimeframeChange}
			schedule={schedule}
			achievementsEnabled={achievementsEnabled === true}
			progressionEnabled={progressionEnabled === true}
			leaguesEnabled={leaguesEnabled === true}
			practiceGroupStandings={
				showsPracticeStandings ? (
					<PracticeGroupStandingCard
						groups={practiceGroups}
						standings={groupStandings}
						practicesByGroup={practicesByGroup}
						isLoading={
							groupsQuery.isPending || groupStandingsQuery.isPending || standingsQuery.isPending
						}
						error={
							groupsQuery.error ?? groupStandingsQuery.error ?? standingsQuery.error ?? undefined
						}
						onRetry={() => {
							if (groupsQuery.isError) void groupsQuery.refetch();
							if (groupStandingsQuery.isError) void groupStandingsQuery.refetch();
							if (standingsQuery.isError) void standingsQuery.refetch();
						}}
						onOpenDetails={(group) => {
							void navigate({
								to: "/w/$workspaceSlug/user/$username/practice-groups/$groupSlug",
								params: { workspaceSlug, username, groupSlug: group.slug },
							});
						}}
					/>
				) : undefined
			}
		/>
	);
}
