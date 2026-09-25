import { useQuery } from "@tanstack/react-query";
import {
	createFileRoute,
	Link,
	Navigate,
	retainSearchParams,
	useNavigate,
} from "@tanstack/react-router";
import { formatISO } from "date-fns";
import { type ReactNode, useEffect } from "react";
import { z } from "zod";

import {
	computeUserLeagueStatsOptions,
	getAllTeamsOptions,
	getLeaderboardOptions,
	getUserProfileOptions,
	getWorkspaceOptions,
} from "@/api/@tanstack/react-query.gen";
import { NoWorkspace } from "@/components/common/NoWorkspace";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { useNow } from "@/components/common/use-now";
import { LeaderboardPage } from "@/components/leaderboard/LeaderboardPage";
import type { LeaderboardSortType } from "@/components/leaderboard/SortFilter";
import { Spinner } from "@/components/ui/spinner";
import { useActiveWorkspaceSlug } from "@/hooks/use-active-workspace";
import { useWorkspaceFeatures } from "@/hooks/use-workspace-features";
import { asDate } from "@/lib/dates";
import { resolveLeaderboardSchedule } from "@/lib/leaderboard-schedule";
import { hasText } from "@/lib/text";
import {
	formatDateRangeForApi,
	getLeaderboardWeekEnd,
	getLeaderboardWeekStart,
} from "@/lib/timeframe";
import { useAuth } from "@/runtime/auth/AuthContext";

const leaderboardSearchSchema = z.object({
	team: z.string().default("all"),
	sort: z.enum(["SCORE", "LEAGUE_POINTS"]).default("SCORE"),
	after: z.string().optional(),
	before: z.string().optional(),
	mode: z.enum(["INDIVIDUAL", "TEAM"]).default("INDIVIDUAL"),
});

type LeaderboardSearchParams = z.infer<typeof leaderboardSearchSchema>;

export const Route = createFileRoute("/_authenticated/w/$workspaceSlug/")({
	component: LeaderboardContainer,
	validateSearch: leaderboardSearchSchema,
	search: {
		// A workspace switch keeps this route and re-runs this middleware, so a retained key has to
		// mean the same thing in any workspace. `team` names a team of *this* one, so it is left out.
		middlewares: [retainSearchParams(["sort", "after", "before", "mode"])],
	},
});

function LeaderboardContainer() {
	const { username } = useAuth();
	const { workspaceSlug, providerType, isLoading: isWorkspaceLoading } = useActiveWorkspaceSlug();
	const featureState = useWorkspaceFeatures(workspaceSlug);
	const leaderboardEnabled = featureState.features?.leaderboardEnabled;
	const leaguesEnabled = featureState.features?.leaguesEnabled;
	const slug = workspaceSlug ?? "";
	const hasWorkspace = Boolean(workspaceSlug);
	const showNoWorkspace = !isWorkspaceLoading && !hasWorkspace;

	const { team, sort, after, before, mode } = Route.useSearch();
	const navigate = useNavigate({ from: Route.fullPath });

	const workspaceQuery = useQuery({
		...getWorkspaceOptions({
			path: { workspaceSlug: slug },
		}),
		enabled: hasWorkspace,
	});

	const schedule = resolveLeaderboardSchedule(workspaceQuery.data);

	// Every window derived from this lands on a scheduled week boundary, so the query keys below move
	// when the leaderboard week does rather than on the clock's tick.
	const now = new Date(useNow());

	const getEffectiveDates = () => {
		if (hasText(after)) {
			return { after, before };
		}
		const weekStart = getLeaderboardWeekStart(now, schedule);
		const weekEnd = getLeaderboardWeekEnd(weekStart);
		return formatDateRangeForApi({ after: weekStart, before: weekEnd });
	};
	const effectiveDates = getEffectiveDates();

	const parsedAfter = asDate(effectiveDates.after);
	const parsedBefore = asDate(effectiveDates.before);

	const teamsQuery = useQuery({
		...getAllTeamsOptions({
			path: { workspaceSlug: slug },
		}),
		enabled: hasWorkspace,
	});

	const leaderboardQuery = useQuery({
		...getLeaderboardOptions({
			path: { workspaceSlug: slug },
			query: {
				after: parsedAfter ?? now,
				before: parsedBefore ?? now,
				team,
				sort,
				mode,
			},
		}),
		placeholderData: (previousData) => previousData,
		enabled: hasWorkspace && Boolean(parsedAfter && teamsQuery.data),
	});

	const userProfileOptions = getUserProfileOptions({
		path: { workspaceSlug: workspaceSlug ?? "", login: username ?? "" },
		query: {
			after: parsedAfter,
			before: parsedBefore,
		},
	});

	const userProfileQuery = useQuery({
		...userProfileOptions,
		placeholderData: (previousData) => previousData,
		enabled: hasWorkspace && Boolean(username),
	});
	const currentUserId = userProfileQuery.data?.userInfo.id;
	const currentUserEntry =
		currentUserId == null
			? undefined
			: leaderboardQuery.data?.find((entry) => entry.user?.id === currentUserId);

	interface MetaTeam {
		id: number;
		name: string;
		parentId?: number;
		hidden?: boolean;
	}

	const teamsList = (teamsQuery.data ?? []) as MetaTeam[];
	const teamById = new Map<number, MetaTeam>(teamsList.map((t) => [t.id, t]));

	const makeLabel = (t: MetaTeam): string => {
		const names: string[] = [];
		let cur: MetaTeam | undefined = t;
		while (cur) {
			if (cur.hidden !== true) {
				names.push(cur.name);
			}
			const parent: MetaTeam | undefined =
				cur.parentId === undefined ? undefined : teamById.get(cur.parentId);
			cur = parent;
		}
		return names.reverse().join(" / ");
	};

	const teamLabelsById: Record<number, string> = {};
	for (const candidate of teamsList) {
		const label = makeLabel(candidate);
		teamLabelsById[candidate.id] = label.length > 0 ? label : candidate.name;
	}

	const visibleTeamEntries = teamsList
		.filter((t) => t.hidden !== true)
		.map((candidate) => ({ team: candidate, label: teamLabelsById[candidate.id] }));

	const visibleTeams = visibleTeamEntries.map((entry) => entry.label);

	const teamOptions = visibleTeamEntries
		.flatMap(({ label }) => (hasText(label) ? [{ value: label, label }] : []))
		.sort((a, b) => a.label.localeCompare(b.label));

	useEffect(() => {
		if (team && team !== "all" && !visibleTeams.includes(team)) {
			void navigate({
				search: (prev: LeaderboardSearchParams) => ({
					...prev,
					team: "all",
				}),
			});
		}
	}, [team, visibleTeams, navigate]);

	useEffect(() => {
		if (mode === "TEAM" && team !== "all") {
			void navigate({
				search: (prev: LeaderboardSearchParams) => ({
					...prev,
					team: "all",
				}),
			});
		}
	}, [mode, team, navigate]);

	useEffect(() => {
		if (mode === "TEAM" && sort !== "SCORE") {
			void navigate({
				search: (prev: LeaderboardSearchParams) => ({
					...prev,
					sort: "SCORE" as LeaderboardSortType,
				}),
			});
		}
	}, [mode, sort, navigate]);

	const endDate = new Date(parsedBefore ?? now);

	endDate.setHours(schedule.hour, schedule.minute, 0, 0);

	const leaderboardEnd = formatISO(endDate);

	const leagueStatsQuery = useQuery({
		...computeUserLeagueStatsOptions({
			path: { workspaceSlug: slug, login: username ?? "" },
			query: {
				after: parsedAfter ?? now,
				before: parsedBefore ?? now,
			},
		}),
		enabled: hasWorkspace && Boolean(username) && Boolean(parsedAfter) && Boolean(parsedBefore),
	});

	if (
		!featureState.isLoading &&
		!featureState.isError &&
		leaderboardEnabled === false &&
		hasText(workspaceSlug) &&
		hasText(username)
	) {
		return (
			<Navigate
				to="/w/$workspaceSlug/user/$username"
				params={{ workspaceSlug, username }}
				replace
			/>
		);
	}

	if (showNoWorkspace) {
		return <NoWorkspace />;
	}

	if (featureState.isError) {
		return (
			<QueryErrorAlert
				error={featureState.error}
				title="Couldn't load workspace features"
				onRetry={featureState.refetch}
			/>
		);
	}

	if (featureState.isLoading || leaderboardEnabled !== true) {
		return (
			<div className="flex h-96 items-center justify-center">
				<Spinner className="size-8" />
			</div>
		);
	}

	const handleTeamChange = (nextTeam: string) => {
		void navigate({
			search: (prev: LeaderboardSearchParams) => ({
				...prev,
				team: nextTeam,
			}),
		});
	};

	const handleSortChange = (nextSort: LeaderboardSortType) => {
		void navigate({
			search: (prev: LeaderboardSearchParams) => ({
				...prev,
				sort: nextSort,
			}),
		});
	};

	const handleTimeframeChange = (afterDate: string, beforeDate?: string) => {
		void navigate({
			search: (prev: LeaderboardSearchParams) => ({
				...prev,
				after: afterDate,
				before: beforeDate,
			}),
		});
	};

	const handleModeChange = (newMode: "INDIVIDUAL" | "TEAM") => {
		void navigate({
			search: (prev: LeaderboardSearchParams) => ({
				...prev,
				mode: newMode,
				team: newMode === "TEAM" ? "all" : prev.team,
				sort: newMode === "TEAM" ? "SCORE" : prev.sort,
			}),
		});
	};

	return (
		<LeaderboardPage
			providerType={providerType}
			leaderboard={leaderboardQuery.data ?? []}
			isLoading={isWorkspaceLoading || teamsQuery.isPending || leaderboardQuery.isPending}
			currentUser={userProfileQuery.data?.userInfo}
			currentUserEntry={currentUserEntry}
			leaguePoints={userProfileQuery.data?.userInfo.leaguePoints}
			leaguePointsChange={leagueStatsQuery.data?.leaguePointsChange}
			teamOptions={teamOptions}
			teamLabelsById={teamLabelsById}
			selectedTeam={team}
			selectedSort={sort}
			afterDate={effectiveDates.after}
			beforeDate={effectiveDates.before}
			leaderboardEnd={leaderboardEnd}
			leaderboardSchedule={schedule}
			onTeamChange={handleTeamChange}
			onSortChange={handleSortChange}
			onTimeframeChange={handleTimeframeChange}
			renderUserLink={(rowUsername, children) => (
				<Link
					to="/w/$workspaceSlug/user/$username"
					params={{ workspaceSlug: slug, username: rowUsername }}
					className="inline-flex rounded-sm outline-none focus-visible:ring-2 focus-visible:ring-ring"
				>
					{children}
				</Link>
			)}
			selectedMode={mode}
			onModeChange={handleModeChange}
			renderTeamLink={(teamId, children): ReactNode => {
				const label = teamLabelsById[teamId];
				return hasText(label) ? (
					<Link
						to="."
						search={(previous) => ({
							...previous,
							mode: "INDIVIDUAL",
							sort: "SCORE",
							team: label,
						})}
						className="inline-flex rounded-sm outline-none focus-visible:ring-2 focus-visible:ring-ring"
					>
						{children}
					</Link>
				) : (
					children
				);
			}}
			leaguesEnabled={leaguesEnabled === true}
		/>
	);
}
