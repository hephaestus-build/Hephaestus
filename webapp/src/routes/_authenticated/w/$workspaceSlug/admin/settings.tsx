import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";
import { Settings2 } from "lucide-react";

import {
	computeUserLeagueStatsQueryKey,
	getLeaderboardQueryKey,
	getWorkspaceOptions,
	resetAndRecalculateLeaguesMutation,
} from "@/api/@tanstack/react-query.gen";
import type { FeatureKey } from "@/components/admin/AdminFeaturesSettings";
import { AdminSettingsPage } from "@/components/admin/AdminSettingsPage";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { PageHeader } from "@/components/core/PageHeader";
import { PageLayout } from "@/components/core/PageLayout";
import { Spinner } from "@/components/ui/spinner";
import { NoWorkspace } from "@/components/workspace/NoWorkspace";
import { useActiveWorkspaceSlug } from "@/hooks/use-active-workspace";
import { useUpdateWorkspaceFeatures } from "@/hooks/use-update-workspace-features";
import { isRecord } from "@/lib/is-record";
import { workspaceAdminHead } from "@/lib/page-title";
import { queryOperationId } from "@/lib/query-operation-id";

/**
 * The reads a league reset moves: the board itself, and the standing computed per user beside it.
 * Only `_id` is taken off each key, so the arguments below are placeholders the signatures demand —
 * and a renamed operation breaks the build rather than silently stopping the invalidation.
 */
const RESET_QUERY_FAMILY_IDS: ReadonlySet<string> = new Set(
	[
		getLeaderboardQueryKey({
			path: { workspaceSlug: "" },
			query: {
				after: new Date(0),
				before: new Date(0),
				team: "",
				sort: "SCORE",
				mode: "INDIVIDUAL",
			},
		}),
		computeUserLeagueStatsQueryKey({
			path: { workspaceSlug: "", login: "" },
			query: { after: new Date(0), before: new Date(0) },
		}),
	].map(([key]) => key._id),
);

export const Route = createFileRoute("/_authenticated/w/$workspaceSlug/admin/settings")({
	head: workspaceAdminHead("Workspace settings"),
	component: AdminSettings,
});

function AdminSettings() {
	const queryClient = useQueryClient();
	const { workspaceSlug, isLoading: isWorkspaceLoading } = useActiveWorkspaceSlug();

	const workspaceQueryOptions = getWorkspaceOptions({
		path: { workspaceSlug: workspaceSlug ?? "" },
	});
	const workspaceQuery = useQuery({
		...workspaceQueryOptions,
		enabled: Boolean(workspaceSlug),
	});

	const resetLeagues = useMutation({
		...resetAndRecalculateLeaguesMutation(),
		onSuccess: (_data, variables) => {
			const resetSlug = variables.path.workspaceSlug;
			// Matching on the operation rather than the key reaches every cached timeframe, team, sort
			// and user, all of which a generated key pins.
			void queryClient.invalidateQueries({
				predicate: ({ queryKey }) => {
					const id = queryOperationId(queryKey);
					if (id === undefined || !RESET_QUERY_FAMILY_IDS.has(id)) return false;
					const [key] = queryKey;
					return isRecord(key) && isRecord(key.path) && key.path.workspaceSlug === resetSlug;
				},
			});
		},
	});

	const updateFeatures = useUpdateWorkspaceFeatures(workspaceSlug ?? "", {
		success: "Feature settings updated",
		error: "Failed to update feature settings",
	});

	if (!workspaceSlug && !isWorkspaceLoading) {
		return <NoWorkspace />;
	}

	const workspaceData = workspaceQuery.data;

	const handleToggleFeature = (feature: FeatureKey, enabled: boolean) => {
		if (!workspaceSlug) return;
		updateFeatures.mutate({
			path: { workspaceSlug },
			body: { [feature]: enabled },
		});
	};

	return (
		<PageLayout>
			<PageHeader
				icon={<Settings2 />}
				title="Workspace settings"
				description="Configure workspace features, leagues, and lifecycle."
			/>
			{!workspaceSlug || workspaceQuery.isLoading ? (
				<div className="flex h-40 max-w-4xl items-center justify-center">
					<Spinner className="size-6" />
				</div>
			) : workspaceQuery.isError || !workspaceData ? (
				<div className="max-w-4xl">
					<QueryErrorAlert
						error={workspaceQuery.error}
						title="Couldn't load workspace settings"
						onRetry={() => void workspaceQuery.refetch()}
					/>
				</div>
			) : (
				<AdminSettingsPage
					isResettingLeagues={resetLeagues.isPending}
					onResetLeagues={() => {
						resetLeagues.mutate({ path: { workspaceSlug } });
					}}
					features={{
						mentorEnabled: workspaceData.mentorEnabled,
						leaderboardEnabled: workspaceData.leaderboardEnabled,
						progressionEnabled: workspaceData.progressionEnabled,
						leaguesEnabled: workspaceData.leaguesEnabled,
					}}
					isSavingFeatures={updateFeatures.isPending}
					onToggleFeature={handleToggleFeature}
					workspaceSlug={workspaceSlug}
				/>
			)}
		</PageLayout>
	);
}
