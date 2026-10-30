import { type UseQueryResult, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";
import { Building2, Gauge, Users } from "lucide-react";

import {
	adminGetConfigurationReadinessOptions,
	adminGetInstanceSettingsOptions,
	adminCheckReleaseMutation,
	adminGetReleaseOptions,
	adminGetReleaseQueryKey,
	adminListAuthEventsOptions,
	adminListWorkspacesOptions,
} from "@/api/@tanstack/react-query.gen";
import {
	InstanceConfigurationReadinessCard,
	type InstanceConfigurationReadinessCardState,
} from "@/components/admin/instance/InstanceConfigurationReadinessCard";
import {
	InstanceReleaseCard,
	type InstanceReleaseCardState,
} from "@/components/admin/instance/InstanceReleaseCard";
import { OverviewStatCard } from "@/components/admin/instance/OverviewStatCard";
import { RecentAuthActivityCard } from "@/components/admin/instance/RecentAuthActivityCard";
import { SilentModeStatusCard } from "@/components/admin/instance/SilentModeStatusCard";
import { queryErrorIsRetryable } from "@/components/common/QueryErrorAlert";
import { PageHeader } from "@/components/layout/PageHeader";
import { PageLayout } from "@/components/layout/PageLayout";
import { instanceAdminHead } from "@/lib/page-title";
import type { RefreshFailure } from "@/runtime/tanstack-query/infinite-list";
import { useQueryReadRefusal } from "@/runtime/tanstack-query/use-query-read-refusal";

export const Route = createFileRoute("/_authenticated/admin/")({
	head: instanceAdminHead("Instance overview"),
	component: AdminOverviewPage,
});

/**
 * What a card may show from a read. A retryable failed refresh keeps the last answer with the
 * failure beside it. A refusal, or any read that never succeeded, shows nothing.
 */
function lastRead<TData>(query: UseQueryResult<TData>): {
	data: TData | undefined;
	refreshFailure?: RefreshFailure;
} {
	if (query.isRefetchError && queryErrorIsRetryable(query.error)) {
		return {
			data: query.data,
			refreshFailure: {
				error: query.error,
				pending: query.isFetching,
				onRetry: () => {
					void query.refetch();
				},
			},
		};
	}
	return { data: query.isError ? undefined : query.data };
}

function AdminOverviewPage() {
	const queryClient = useQueryClient();
	const releaseOptions = adminGetReleaseOptions();
	const releaseQuery = useQuery(releaseOptions);
	useQueryReadRefusal(releaseOptions.queryKey, releaseQuery.isError, releaseQuery.error);
	// A check's answer reaches the card only through a fresh read, so a read that is refused by
	// the time the check returns stays refused.
	const releaseCheck = useMutation({
		...adminCheckReleaseMutation(),
		onSuccess: async () => queryClient.invalidateQueries({ queryKey: adminGetReleaseQueryKey() }),
	});
	const readinessOptions = adminGetConfigurationReadinessOptions();
	const readinessQuery = useQuery(readinessOptions);
	useQueryReadRefusal(readinessOptions.queryKey, readinessQuery.isError, readinessQuery.error);
	const settingsQuery = useQuery(adminGetInstanceSettingsOptions());
	const workspacesQuery = useQuery(adminListWorkspacesOptions());
	const eventsQuery = useQuery(adminListAuthEventsOptions({ query: { page: 0, size: 8 } }));

	const workspaces = workspacesQuery.data ?? [];
	const activeWorkspaces = workspaces.filter((ws) => ws.status === "ACTIVE").length;
	const memberships = workspaces.reduce((sum, ws) => sum + ws.memberCount, 0);

	const retryRelease = () => {
		void releaseQuery.refetch();
	};
	const release = lastRead(releaseQuery);
	let releaseState: InstanceReleaseCardState;
	if (release.data !== undefined) {
		releaseState = {
			status: "ready",
			release: release.data,
			refreshFailure: release.refreshFailure,
			check: releaseCheck.isError
				? { status: "error", error: releaseCheck.error }
				: { status: releaseCheck.status },
			onCheck: () => releaseCheck.mutate({}),
		};
	} else if (releaseQuery.isPending) {
		releaseState = { status: "loading" };
	} else {
		releaseState = { status: "error", error: releaseQuery.error, onRetry: retryRelease };
	}

	const retryReadiness = () => {
		void readinessQuery.refetch();
	};
	const readiness = lastRead(readinessQuery);
	let readinessState: InstanceConfigurationReadinessCardState;
	if (readiness.data !== undefined) {
		readinessState =
			readiness.data.length > 0
				? { status: "ready", facts: readiness.data, refreshFailure: readiness.refreshFailure }
				: { status: "empty", refreshFailure: readiness.refreshFailure };
	} else if (readinessQuery.isPending) {
		readinessState = { status: "loading" };
	} else {
		readinessState = { status: "error", error: readinessQuery.error, onRetry: retryReadiness };
	}

	return (
		<PageLayout>
			<PageHeader
				icon={<Gauge />}
				title="Instance overview"
				description="What is running, and what changed recently on this instance."
			/>

			<SilentModeStatusCard
				settings={settingsQuery.data}
				isLoading={settingsQuery.isLoading}
				isError={settingsQuery.isError}
			/>

			<InstanceConfigurationReadinessCard state={readinessState} />

			<InstanceReleaseCard state={releaseState} />

			<div className="grid gap-4 sm:grid-cols-2">
				<OverviewStatCard
					label="Workspaces"
					value={workspaces.length}
					hint={workspaces.length > 0 ? `${activeWorkspaces} active` : "None created yet"}
					icon={Building2}
					to="/admin/workspaces"
					isLoading={workspacesQuery.isLoading}
					isError={workspacesQuery.isError}
				/>
				<OverviewStatCard
					label="Workspace memberships"
					value={memberships}
					hint="Counts a person once per workspace"
					icon={Users}
					to="/admin/workspaces"
					isLoading={workspacesQuery.isLoading}
					isError={workspacesQuery.isError}
				/>
			</div>

			<RecentAuthActivityCard
				events={eventsQuery.data?.content ?? []}
				isLoading={eventsQuery.isLoading}
				error={eventsQuery.isError ? eventsQuery.error : undefined}
				onRetry={() => {
					void eventsQuery.refetch();
				}}
			/>
		</PageLayout>
	);
}
