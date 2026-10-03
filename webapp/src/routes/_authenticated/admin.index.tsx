import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
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
import { PageHeader } from "@/components/layout/PageHeader";
import { PageLayout } from "@/components/layout/PageLayout";
import { instanceAdminHead } from "@/lib/page-title";

export const Route = createFileRoute("/_authenticated/admin/")({
	head: instanceAdminHead("Overview"),
	component: AdminOverviewPage,
});

function AdminOverviewPage() {
	const queryClient = useQueryClient();
	const releaseQuery = useQuery(adminGetReleaseOptions());
	const releaseCheck = useMutation({
		...adminCheckReleaseMutation(),
		onSuccess: (data) => queryClient.setQueryData(adminGetReleaseQueryKey(), data),
	});
	const readinessQuery = useQuery(adminGetConfigurationReadinessOptions());
	const settingsQuery = useQuery(adminGetInstanceSettingsOptions());
	const workspacesQuery = useQuery(adminListWorkspacesOptions());
	const eventsQuery = useQuery(adminListAuthEventsOptions({ query: { page: 0, size: 8 } }));

	const workspaces = workspacesQuery.data ?? [];
	const activeWorkspaces = workspaces.filter((ws) => ws.status === "ACTIVE").length;
	const memberships = workspaces.reduce((sum, ws) => sum + ws.memberCount, 0);

	let releaseState: InstanceReleaseCardState;
	if (releaseQuery.data) {
		releaseState = {
			status: "ready",
			release: releaseQuery.data,
			check: releaseCheck.isError
				? { status: "error", error: releaseCheck.error }
				: { status: releaseCheck.status },
			onCheck: () => releaseCheck.mutate({}),
		};
	} else if (releaseQuery.isPending) {
		releaseState = { status: "loading" };
	} else {
		releaseState = {
			status: "error",
			error: releaseQuery.error,
			onRetry: () => {
				void releaseQuery.refetch();
			},
		};
	}

	const retryReadiness = () => {
		void readinessQuery.refetch();
	};
	let readinessState: InstanceConfigurationReadinessCardState;
	if (readinessQuery.data) {
		// A failed refresh keeps the last facts, so the card must be told they may be out of date.
		readinessState =
			readinessQuery.data.length > 0
				? {
						status: "ready",
						facts: readinessQuery.data,
						refreshFailure: readinessQuery.isRefetchError
							? { error: readinessQuery.error, onRetry: retryReadiness }
							: undefined,
					}
				: { status: "empty" };
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
