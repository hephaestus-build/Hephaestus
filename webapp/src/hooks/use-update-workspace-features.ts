import { useMutation, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";

import {
	getWorkspaceQueryKey,
	listWorkspacesQueryKey,
	updateFeaturesMutation,
} from "@/api/@tanstack/react-query.gen";
import type { UpdateWorkspaceFeaturesRequest, Workspace, WorkspaceListItem } from "@/api/types.gen";

export interface UpdateWorkspaceFeaturesMessages {
	success: string;
	error: string;
}

export function useUpdateWorkspaceFeatures(
	workspaceSlug: string,
	messages: UpdateWorkspaceFeaturesMessages,
) {
	const queryClient = useQueryClient();
	const workspaceQueryKey = getWorkspaceQueryKey({ path: { workspaceSlug } });
	const workspacesQueryKey = listWorkspacesQueryKey();
	const mutationKey = ["workspace", workspaceSlug, "features"] as const;

	return useMutation({
		...updateFeaturesMutation(),
		mutationKey,
		scope: { id: `workspace:${workspaceSlug}:features` },
		onMutate: async (variables) => {
			await Promise.all([
				queryClient.cancelQueries({ queryKey: workspaceQueryKey }),
				queryClient.cancelQueries({ queryKey: workspacesQueryKey }),
			]);
			const previousWorkspace = queryClient.getQueryData<Workspace>(workspaceQueryKey);
			const previousWorkspaces = queryClient.getQueryData<WorkspaceListItem[]>(workspacesQueryKey);
			const previousWorkspacePatch = previousWorkspace
				? selectWorkspaceFeatures(previousWorkspace, variables.body)
				: undefined;
			const previousListItem = previousWorkspaces?.find(
				(workspace) => workspace.workspaceSlug === workspaceSlug,
			);
			const previousListPatch = previousListItem
				? selectWorkspaceListFeatures(previousListItem, variables.body)
				: undefined;
			const listPatch = selectWorkspaceListFeatures(variables.body);
			queryClient.setQueryData<Workspace>(workspaceQueryKey, (workspace) =>
				workspace ? { ...workspace, ...variables.body } : workspace,
			);
			queryClient.setQueryData<WorkspaceListItem[]>(workspacesQueryKey, (workspaces) =>
				workspaces?.map((workspace) =>
					workspace.workspaceSlug === workspaceSlug ? { ...workspace, ...listPatch } : workspace,
				),
			);
			return { previousWorkspacePatch, previousListPatch };
		},
		onError: (error, _variables, context) => {
			if (context?.previousWorkspacePatch) {
				queryClient.setQueryData<Workspace>(workspaceQueryKey, (workspace) =>
					workspace ? { ...workspace, ...context.previousWorkspacePatch } : workspace,
				);
			}
			if (context?.previousListPatch) {
				queryClient.setQueryData<WorkspaceListItem[]>(workspacesQueryKey, (workspaces) =>
					workspaces?.map((workspace) =>
						workspace.workspaceSlug === workspaceSlug
							? { ...workspace, ...context.previousListPatch }
							: workspace,
					),
				);
			}
			toast.error(messages.error, {
				description: error instanceof Error ? error.message : undefined,
			});
		},
		onSuccess: (updated, variables) => {
			const workspacePatch = selectWorkspaceFeatures(updated, variables.body);
			queryClient.setQueryData<Workspace>(workspaceQueryKey, (workspace) =>
				workspace ? { ...workspace, ...workspacePatch } : workspace,
			);
			const listPatch = selectWorkspaceListFeatures(updated, variables.body);
			queryClient.setQueryData<WorkspaceListItem[]>(workspacesQueryKey, (workspaces) =>
				workspaces?.map((workspace) =>
					workspace.workspaceSlug === workspaceSlug ? { ...workspace, ...listPatch } : workspace,
				),
			);
			toast.success(messages.success);
		},
		onSettled: () => {
			if (queryClient.isMutating({ mutationKey }) === 1) {
				void queryClient.invalidateQueries({ queryKey: workspaceQueryKey });
				void queryClient.invalidateQueries({ queryKey: workspacesQueryKey });
			}
		},
	});
}

function selectWorkspaceFeatures(
	source: UpdateWorkspaceFeaturesRequest | Workspace,
	request: UpdateWorkspaceFeaturesRequest = source,
): UpdateWorkspaceFeaturesRequest {
	return {
		...("leaderboardEnabled" in request ? { leaderboardEnabled: source.leaderboardEnabled } : {}),
		...("leaguesEnabled" in request ? { leaguesEnabled: source.leaguesEnabled } : {}),
		...("mentorEnabled" in request ? { mentorEnabled: source.mentorEnabled } : {}),
		...("practiceReviewAutoTriggerEnabled" in request
			? { practiceReviewAutoTriggerEnabled: source.practiceReviewAutoTriggerEnabled }
			: {}),
		...("practiceReviewManualTriggerEnabled" in request
			? { practiceReviewManualTriggerEnabled: source.practiceReviewManualTriggerEnabled }
			: {}),
		...("practicesEnabled" in request ? { practicesEnabled: source.practicesEnabled } : {}),
		...("progressionEnabled" in request ? { progressionEnabled: source.progressionEnabled } : {}),
	};
}

function selectWorkspaceListFeatures(
	source: UpdateWorkspaceFeaturesRequest | Workspace | WorkspaceListItem,
	request: UpdateWorkspaceFeaturesRequest = source,
): Partial<WorkspaceListItem> {
	return {
		...("leaderboardEnabled" in request ? { leaderboardEnabled: source.leaderboardEnabled } : {}),
		...("leaguesEnabled" in request ? { leaguesEnabled: source.leaguesEnabled } : {}),
		...("mentorEnabled" in request ? { mentorEnabled: source.mentorEnabled } : {}),
		...("practicesEnabled" in request ? { practicesEnabled: source.practicesEnabled } : {}),
		...("progressionEnabled" in request ? { progressionEnabled: source.progressionEnabled } : {}),
	};
}
