import { useQuery } from "@tanstack/react-query";

import { getProvidersOptions } from "@/api/@tanstack/react-query.gen";
import type { WorkspaceProviders } from "@/api/types.gen";
import type { WorkspaceCreation } from "@/components/common/NoWorkspace";
import { useAuth } from "@/runtime/auth/AuthContext";

/**
 * Mirrors the server gate (`hephaestus.workspace.creation-policy`): under ADMIN_ONLY, only an
 * instance admin may create a workspace. Any other answer lets the reader try, and the server decides.
 */
export function workspaceCreationOf(
	creationPolicy: WorkspaceProviders["creationPolicy"],
	isAppAdmin: boolean,
): WorkspaceCreation {
	return creationPolicy === "ADMIN_ONLY" && !isAppAdmin ? "instance-admin-only" : "allowed";
}

/**
 * Whether the signed-in person may create a workspace. Undefined while the policy is unread: while
 * it loads, after a failed read, and where the caller has no use for it yet. Unread is not "allowed",
 * so a person the policy forbids is never offered the step.
 */
export function useWorkspaceCreation({ enabled = true }: { enabled?: boolean } = {}):
	| WorkspaceCreation
	| undefined {
	const { isAppAdmin } = useAuth();
	const providers = useQuery({ ...getProvidersOptions(), staleTime: 5 * 60 * 1000, enabled });
	return providers.isSuccess
		? workspaceCreationOf(providers.data.creationPolicy, isAppAdmin)
		: undefined;
}
