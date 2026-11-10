import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "@tanstack/react-router";
import { useState } from "react";
import { toast } from "@/lib/toast";

import { listWorkspacesQueryKey, purgeWorkspaceMutation } from "@/api/@tanstack/react-query.gen";
import type { ListWorkspacesResponse } from "@/api/types.gen";
import type { WorkspaceDangerZoneSettingsProps } from "@/components/admin/settings/WorkspaceDangerZoneSettings";
import { useConfirmAccess } from "@/hooks/use-confirm-access";
import { problemDetailOf, type StepUpChallenge, stepUpChallengeOf } from "@/lib/problem-detail";
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
	// Deleting a workspace cannot be undone, so the server asks for a recent sign-in instead of deleting.
	const [challenge, setChallenge] = useState<StepUpChallenge>();
	const confirmAccess = useConfirmAccess(challenge !== undefined);
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
		onError: (e) => {
			const stepUp = stepUpChallengeOf(e);
			if (stepUp !== undefined) {
				setChallenge(stepUp);
				return;
			}
			toast.error("We could not delete the workspace", { description: problemDetailOf(e) });
		},
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
		confirmation: {
			open: challenge !== undefined,
			onOpenChange: (open) => {
				if (!open) {
					setChallenge(undefined);
				}
			},
			maxAgeSeconds: challenge?.maxAgeSeconds,
			providers: confirmAccess.providers,
			loading: confirmAccess.loading,
			error: confirmAccess.error,
			onRetry: confirmAccess.retry,
			onSignIn: confirmAccess.signIn,
		},
	};
}
