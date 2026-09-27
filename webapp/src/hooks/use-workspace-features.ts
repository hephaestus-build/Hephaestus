import { useQuery } from "@tanstack/react-query";

import { listWorkspacesOptions } from "@/api/@tanstack/react-query.gen";
import { useAuth } from "@/runtime/auth/AuthContext";

export interface WorkspaceFeaturesResult {
	/** Undefined until the workspace list names this workspace. */
	practicesEnabled?: boolean;
	isLoading: boolean;
	isError: boolean;
	error: unknown;
	refetch: () => void;
}

export function useWorkspaceFeatures(workspaceSlug: string | undefined): WorkspaceFeaturesResult {
	const { isAuthenticated, isLoading: authLoading } = useAuth();

	const query = useQuery({
		...listWorkspacesOptions(),
		enabled: isAuthenticated && !authLoading,
	});

	const workspaces = Array.isArray(query.data) ? query.data : [];
	const activeWorkspace = workspaces.find((ws) => ws.workspaceSlug === workspaceSlug);
	const workspaceMissing =
		Boolean(workspaceSlug) && query.isSuccess && !activeWorkspace && !authLoading;

	return {
		practicesEnabled: activeWorkspace?.practicesEnabled,
		isLoading: authLoading || query.isLoading,
		isError: query.isError || workspaceMissing,
		error: query.error ?? (workspaceMissing ? new Error("Workspace not found") : undefined),
		refetch: () => {
			void query.refetch();
		},
	};
}
