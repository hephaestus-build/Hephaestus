import { useMutation, useQueries, useQuery, useQueryClient } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";
import { useState } from "react";
import { toast } from "sonner";

import {
	getConnectionSyncStatusOptions,
	updateConnectionSyncJobMutation,
	approveGitHubAccessTargetMutation,
	configureGitHubAccessTargetMutation,
	createGitHubAccessTargetMutation,
	decideGitHubAccessMembershipMutation,
	endGitHubAccessTargetMutation,
	getDirectoryPolicyOptions,
	getGitHubAccessTargetsOptions,
	getGitHubAccessTargetsQueryKey,
	pauseGitHubAccessTargetMutation,
	previewGitHubAccessTargetMutation,
	reconcileGitHubAccessTargetMutation,
	renewGitHubAccessHandoffMutation,
} from "@/api/@tanstack/react-query.gen";
import type { GitHubAccessHandoff } from "@/api/types.gen";
import {
	WorkspaceGithubAccessPage,
	type GithubAccessPageState,
} from "@/components/admin/github-access/WorkspaceGithubAccessPage";
import { workspaceMembershipQueryOptions } from "@/integrations/auth/guard";
import { workspaceAdminHead } from "@/lib/page-title";
import { problemDetailOf } from "@/lib/problem-detail";

export const Route = createFileRoute("/_authenticated/w/$workspaceSlug/admin/github-access")({
	head: workspaceAdminHead("GitHub access"),
	component: GithubAccessRoute,
});

function GithubAccessRoute() {
	const { workspaceSlug } = Route.useParams();
	return <GithubAccessContainer key={workspaceSlug} workspaceSlug={workspaceSlug} />;
}

function GithubAccessContainer({ workspaceSlug }: { workspaceSlug: string }) {
	const path = { workspaceSlug };
	const queryClient = useQueryClient();
	const [handoffUrl, setHandoffUrl] = useState<string>();
	const membership = useQuery(workspaceMembershipQueryOptions(workspaceSlug));
	const isOwner = membership.data?.role === "OWNER";
	const query = useQuery({ ...getGitHubAccessTargetsOptions({ path }), refetchInterval: 5000 });
	const targetJobs = useQueries({
		queries: (query.data?.targets ?? []).map((target) => ({
			...getConnectionSyncStatusOptions({
				path: { workspaceSlug, connectionId: target.connectionId },
			}),
			refetchInterval: 5000,
		})),
	});
	const directory = useQuery({ ...getDirectoryPolicyOptions({ path }), enabled: isOwner });
	const refresh = () =>
		queryClient.invalidateQueries({ queryKey: getGitHubAccessTargetsQueryKey({ path }) });
	const report = (error: unknown) =>
		toast.error(
			problemDetailOf(error, "Couldn't update GitHub access. Inspect the target and retry."),
		);
	const handoff = async (data: GitHubAccessHandoff) => {
		setHandoffUrl(`${window.location.origin}/github-access-approval#${data.token}`);
		await refresh();
	};
	const create = useMutation({
		...createGitHubAccessTargetMutation(),
		gcTime: 0,
		onSuccess: handoff,
		onError: report,
	});
	const configure = useMutation({
		...configureGitHubAccessTargetMutation(),
		gcTime: 0,
		onSuccess: handoff,
		onError: report,
	});
	const renew = useMutation({
		...renewGitHubAccessHandoffMutation(),
		gcTime: 0,
		onSuccess: handoff,
		onError: report,
	});
	const preview = useMutation({
		...previewGitHubAccessTargetMutation(),
		onSuccess: refresh,
		onError: report,
	});
	const approve = useMutation({
		...approveGitHubAccessTargetMutation(),
		onSuccess: refresh,
		onError: report,
	});
	const reconcile = useMutation({
		...reconcileGitHubAccessTargetMutation(),
		onSuccess: refresh,
		onError: report,
	});
	const pause = useMutation({
		...pauseGitHubAccessTargetMutation(),
		onSuccess: refresh,
		onError: report,
	});
	const end = useMutation({
		...endGitHubAccessTargetMutation(),
		onSuccess: refresh,
		onError: report,
	});
	const decide = useMutation({
		...decideGitHubAccessMembershipMutation(),
		onSuccess: refresh,
		onError: report,
	});
	const cancel = useMutation({
		...updateConnectionSyncJobMutation(),
		onSuccess: async () => {
			await Promise.all(targetJobs.map((job) => job.refetch()));
		},
		onError: report,
	});
	const state: GithubAccessPageState =
		query.isPending || membership.isPending
			? { status: "loading" }
			: query.isError || membership.isError
				? {
						status: "error",
						error: query.error ?? membership.error,
						onRetry: () => {
							void query.refetch();
							void membership.refetch();
						},
					}
				: {
						status: "ready",
						data: query.data,
						approvedGroups: directory.data?.policy?.approvedGroupIds ?? [],
						directoryError: directory.error ?? undefined,
					};
	return (
		<WorkspaceGithubAccessPage
			workspaceSlug={workspaceSlug}
			state={state}
			isOwner={isOwner}
			handoffUrl={handoffUrl}
			jobs={Object.fromEntries(
				(query.data?.targets ?? []).map((target, index) => [
					target.id,
					{
						activeJob: targetJobs[index]?.data?.activeJob,
						error: targetJobs[index]?.error ?? undefined,
					},
				]),
			)}
			onCancelJob={(target, job) =>
				cancel.mutate({
					path: { workspaceSlug, connectionId: target.connectionId, jobId: job.id },
					body: { cancelRequested: true },
				})
			}
			savingPolicy={create.isPending || configure.isPending}
			changingTargetId={
				[configure, renew, preview, approve, reconcile, pause, end, decide].find(
					(mutation) => mutation.isPending,
				)?.variables.path.targetId
			}
			busy={
				create.isPending ||
				configure.isPending ||
				renew.isPending ||
				preview.isPending ||
				approve.isPending ||
				reconcile.isPending ||
				pause.isPending ||
				end.isPending ||
				decide.isPending
			}
			onConfigure={async (targetId, body) => {
				if (targetId === undefined) await create.mutateAsync({ path, body });
				else await configure.mutateAsync({ path: { ...path, targetId }, body });
			}}
			onRenew={(targetId, installationId) =>
				renew.mutate({ path: { ...path, targetId }, body: { installationId } })
			}
			onPreview={(targetId) => preview.mutate({ path: { ...path, targetId } })}
			onApprove={(target) => {
				if (target.preview)
					approve.mutate({
						path: { ...path, targetId: target.id },
						body: {
							configurationVersion: target.configurationVersion,
							previewCapturedAt: target.preview.capturedAt,
						},
					});
			}}
			onReconcile={(targetId) => reconcile.mutate({ path: { ...path, targetId } })}
			onPause={(targetId, paused) =>
				pause.mutate({ path: { ...path, targetId }, body: { paused } })
			}
			onEnd={(targetId) => end.mutate({ path: { ...path, targetId } })}
			onDecide={(targetId, githubUserId, body) =>
				decide.mutate({ path: { ...path, targetId, githubUserId }, body })
			}
		/>
	);
}
