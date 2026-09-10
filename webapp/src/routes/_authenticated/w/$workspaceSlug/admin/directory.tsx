import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";
import { useState } from "react";
import { toast } from "sonner";

import {
	adoptDirectoryMemberMutation,
	approveDirectoryPolicyMutation,
	changeDirectoryPolicyStatusMutation,
	configureDirectoryPolicyMutation,
	getConnectionSyncStatusOptions,
	getConnectionSyncStatusQueryKey,
	getDirectoryPolicyOptions,
	getDirectoryPolicyQueryKey,
	getDirectorySourcesOptions,
	listConnectionSyncJobsOptions,
	listConnectionSyncJobsQueryKey,
	listMembersQueryKey,
	previewDirectoryPolicyMutation,
	reconcileDirectoryPolicyMutation,
	updateConnectionSyncJobMutation,
} from "@/api/@tanstack/react-query.gen";
import {
	WorkspaceDirectoryAccessPage,
	type DirectoryPageState,
} from "@/components/admin/directory/WorkspaceDirectoryAccessPage";
import { workspaceMembershipQueryOptions } from "@/integrations/auth/guard";
import { workspaceAdminHead } from "@/lib/page-title";
import { problemDetailOf } from "@/lib/problem-detail";

export const Route = createFileRoute("/_authenticated/w/$workspaceSlug/admin/directory")({
	head: workspaceAdminHead("Directory access"),
	component: DirectoryAccessContainer,
});

function DirectoryAccessContainer() {
	const { workspaceSlug } = Route.useParams();
	const queryClient = useQueryClient();
	const [page, setPage] = useState(0);
	const path = { workspaceSlug };
	const membership = useQuery(workspaceMembershipQueryOptions(workspaceSlug));
	const isOwner = membership.data?.role === "OWNER";
	const policyQuery = useQuery({ ...getDirectoryPolicyOptions({ path }), refetchInterval: 5000 });
	const policy = policyQuery.data?.policy;
	const sourcesQuery = useQuery({ ...getDirectorySourcesOptions({ path }), enabled: isOwner });
	const connectionPath = { workspaceSlug, connectionId: policy?.connectionId ?? 0 };
	const sync = useQuery({
		...getConnectionSyncStatusOptions({ path: connectionPath }),
		enabled: policy !== undefined,
		refetchInterval: 5000,
	});
	const history = useQuery({
		...listConnectionSyncJobsOptions({ path: connectionPath, query: { page, size: 10 } }),
		enabled: policy !== undefined,
		refetchInterval: 5000,
	});
	const refresh = async () => {
		await Promise.all([
			queryClient.invalidateQueries({ queryKey: getDirectoryPolicyQueryKey({ path }) }),
			queryClient.invalidateQueries({
				queryKey: getConnectionSyncStatusQueryKey({ path: connectionPath }),
			}),
			queryClient.invalidateQueries({
				queryKey: listConnectionSyncJobsQueryKey({ path: connectionPath }),
			}),
			queryClient.invalidateQueries({ queryKey: listMembersQueryKey({ path }) }),
		]);
	};
	const report = (error: unknown) =>
		toast.error(
			problemDetailOf(
				error,
				"Couldn't update directory access. Review the policy status and retry.",
			),
		);
	const configure = useMutation({
		...configureDirectoryPolicyMutation(),
		onSuccess: refresh,
		onError: report,
	});
	const preview = useMutation({
		...previewDirectoryPolicyMutation(),
		onSuccess: refresh,
		onError: report,
	});
	const approve = useMutation({
		...approveDirectoryPolicyMutation(),
		onSuccess: refresh,
		onError: report,
	});
	const reconcile = useMutation({
		...reconcileDirectoryPolicyMutation(),
		onSuccess: refresh,
		onError: report,
	});
	const status = useMutation({
		...changeDirectoryPolicyStatusMutation(),
		onSuccess: refresh,
		onError: report,
	});
	const adopt = useMutation({
		...adoptDirectoryMemberMutation(),
		onSuccess: refresh,
		onError: report,
	});
	const cancel = useMutation({
		...updateConnectionSyncJobMutation(),
		onSuccess: refresh,
		onError: report,
	});
	const state: DirectoryPageState =
		policyQuery.isPending || membership.isPending || (isOwner && sourcesQuery.isPending)
			? { status: "loading" }
			: policyQuery.isError || membership.isError
				? {
						status: "error",
						error: policyQuery.error ?? membership.error,
						onRetry: () => {
							void policyQuery.refetch();
							void membership.refetch();
						},
					}
				: {
						status: "ready",
						policy,
						sources: sourcesQuery.data ?? [],
						sourceError: sourcesQuery.error ?? undefined,
						onRetrySources: () => void sourcesQuery.refetch(),
					};
	return (
		<WorkspaceDirectoryAccessPage
			workspaceSlug={workspaceSlug}
			state={state}
			isOwner={isOwner}
			activeJob={sync.data?.activeJob}
			pendingAction={
				configure.isPending
					? "save"
					: preview.isPending
						? "preview"
						: approve.isPending
							? "approve"
							: reconcile.isPending
								? "reconcile"
								: status.isPending
									? "status"
									: adopt.isPending
										? "adopt"
										: cancel.isPending
											? "cancel"
											: undefined
			}
			jobs={{
				jobs: history.data?.content ?? [],
				isLoading: history.isLoading,
				isError: history.isError || sync.isError,
				error: history.error ?? sync.error,
				onRetry: () => {
					void history.refetch();
					void sync.refetch();
				},
				page,
				totalPages: history.data?.totalPages ?? 0,
				onPageChange: setPage,
			}}
			onConfigure={async (body) => {
				await configure.mutateAsync({ path, body });
			}}
			onPreview={() => preview.mutate({ path })}
			onApprove={(configurationVersion) => approve.mutate({ path, body: { configurationVersion } })}
			onReconcile={() => reconcile.mutate({ path })}
			onStatusChange={(nextStatus) => status.mutate({ path, body: { status: nextStatus } })}
			onAdopt={(accountId) => adopt.mutate({ path: { workspaceSlug, accountId } })}
			onCancel={() => {
				if (sync.data?.activeJob)
					cancel.mutate({
						path: { ...connectionPath, jobId: sync.data.activeJob.id },
						body: { cancelRequested: true },
					});
			}}
		/>
	);
}
