import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "@tanstack/react-router";
import { toast } from "@/lib/toast";

import { listWorkspacesQueryKey, purgeWorkspaceMutation } from "@/api/@tanstack/react-query.gen";
import type { ListWorkspacesResponse } from "@/api/types.gen";
import type { WorkspaceDangerZoneSettingsProps } from "@/components/admin/settings/WorkspaceDangerZoneSettings";
import { problemDetailOf } from "@/lib/problem-detail";
import { hasText } from "@/lib/text";
import { workspaceMembershipQueryOptions } from "@/runtime/auth/guard";

/** The reader's ownership of the workspace and its deletion, for the danger zone of its settings. */
export function useWorkspaceDangerZone(
	workspaceSlug: string,
): Omit<WorkspaceDangerZoneSettingsProps, "workspaceSlug"> {
	const queryClient = useQueryClient();
	const navigate = useNavigate();
	const role = useQuery({
		...workspaceMembershipQueryOptions(workspaceSlug),
		enabled: hasText(workspaceSlug),
	});
	const purge = useMutation({
		...purgeWorkspaceMutation(),
		onSuccess: () => {
			toast.success("Workspace deleted");
			queryClient.setQueryData(listWorkspacesQueryKey(), (workspaces?: ListWorkspacesResponse) =>
				workspaces?.filter((workspace) => workspace.workspaceSlug !== workspaceSlug),
			);
			void navigate({ to: "/", replace: true });
			void queryClient.invalidateQueries({ queryKey: listWorkspacesQueryKey() });
		},
		onError: (e) =>
			toast.error("We could not delete the workspace", { description: problemDetailOf(e) }),
	});

	let ownerCheck: WorkspaceDangerZoneSettingsProps["ownerCheck"] = { status: "pending" };
	if (role.data != null) {
		ownerCheck = { status: "known", owner: role.data.role === "OWNER" };
	} else if (role.isError) {
		ownerCheck = {
			status: "failed",
			retrying: role.isFetching,
			onRetry: () => {
				void role.refetch();
			},
		};
	}
	return {
		ownerCheck,
		deleting: purge.isPending,
		onDelete: async () => {
			await purge.mutateAsync({ path: { workspaceSlug } });
		},
	};
}
