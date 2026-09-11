import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";
import { Building2, Gauge, Users } from "lucide-react";

import {
	adminGetInstanceSettingsOptions,
	adminCheckReleaseMutation,
	adminGetReleaseOptions,
	adminGetReleaseQueryKey,
	adminListAuthEventsOptions,
	adminListWorkspacesOptions,
} from "@/api/@tanstack/react-query.gen";
import { InstanceReleaseCard } from "@/components/admin/instance/InstanceReleaseCard";
import { OverviewStatCard } from "@/components/admin/instance/OverviewStatCard";
import { RecentAuthActivityCard } from "@/components/admin/instance/RecentAuthActivityCard";
import { SilentModeStatusCard } from "@/components/admin/instance/SilentModeStatusCard";
import { PageHeader } from "@/components/core/PageHeader";
import { PageLayout } from "@/components/core/PageLayout";
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
	const settingsQuery = useQuery(adminGetInstanceSettingsOptions());
	const workspacesQuery = useQuery(adminListWorkspacesOptions());
	const eventsQuery = useQuery(adminListAuthEventsOptions({ query: { page: 0, size: 8 } }));

	const workspaces = workspacesQuery.data ?? [];
	const activeWorkspaces = workspaces.filter((ws) => ws.status === "ACTIVE").length;
	const memberships = workspaces.reduce((sum, ws) => sum + ws.memberCount, 0);

	return (
		<PageLayout>
			<PageHeader
				icon={<Gauge />}
				title="Instance overview"
				description="What is running, and what changed recently on this instance."
			/>

			<InstanceReleaseCard
				state={
					releaseQuery.data
						? {
								status: "ready",
								release: releaseQuery.data,
								check: releaseCheck.isError
									? { status: "error", error: releaseCheck.error }
									: { status: releaseCheck.status },
								onCheck: () => releaseCheck.mutate({}),
							}
						: releaseQuery.isPending
							? { status: "loading" }
							: {
									status: "error",
									error: releaseQuery.error,
									onRetry: () => void releaseQuery.refetch(),
								}
				}
			/>

			<SilentModeStatusCard
				settings={settingsQuery.data}
				isLoading={settingsQuery.isLoading}
				isError={settingsQuery.isError}
			/>

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
				onRetry={() => void eventsQuery.refetch()}
			/>
		</PageLayout>
	);
}
