import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import {
	getOwnMembershipQueryKey,
	leaveWorkspaceMutation,
	listWorkspacesOptions,
	listWorkspacesQueryKey,
} from "@/api/@tanstack/react-query.gen";
import type { ListWorkspacesResponse } from "@/api/types.gen";
import type { LeaveWorkspaceSectionProps } from "@/components/settings/LeaveWorkspaceSection";
import { problemDetailOf } from "@/lib/problem-detail";
import { toast } from "@/lib/toast";

/** The reader's workspaces and leaving one, for the Workspaces section of User settings. */
export function useLeaveWorkspace(): LeaveWorkspaceSectionProps {
	const queryClient = useQueryClient();
	const workspaces = useQuery(listWorkspacesOptions());
	const leave = useMutation({
		...leaveWorkspaceMutation(),
		onSuccess: (_data, { path: { workspaceSlug } }) => {
			const name =
				workspaces.data?.find((workspace) => workspace.workspaceSlug === workspaceSlug)
					?.displayName ?? workspaceSlug;
			toast.success(`You left ${name}`);
			queryClient.setQueryData(listWorkspacesQueryKey(), (listed?: ListWorkspacesResponse) =>
				listed?.filter((workspace) => workspace.workspaceSlug !== workspaceSlug),
			);
			queryClient.removeQueries({
				queryKey: getOwnMembershipQueryKey({ path: { workspaceSlug } }),
			});
			void queryClient.invalidateQueries({ queryKey: listWorkspacesQueryKey() });
		},
	});
	return {
		workspaces: (workspaces.data ?? []).map((workspace) => ({
			slug: workspace.workspaceSlug,
			name: workspace.displayName,
		})),
		isLoading: workspaces.isPending,
		loadFailure: workspaces.isError
			? {
					retrying: workspaces.isFetching,
					onRetry: () => {
						void workspaces.refetch();
					},
				}
			: undefined,
		leavingSlug: leave.isPending ? leave.variables.path.workspaceSlug : undefined,
		leaveError: leave.error == null ? undefined : problemDetailOf(leave.error),
		onLeave: async (workspaceSlug) => {
			await leave.mutateAsync({ path: { workspaceSlug } });
		},
		onDismissError: () => leave.reset(),
	};
}
