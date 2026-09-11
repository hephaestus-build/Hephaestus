import { useQuery } from "@tanstack/react-query";

import { listWorkspacesOptions } from "@/api/@tanstack/react-query.gen";
import type { WorkspaceListItem } from "@/api/types.gen";
import { useAuth } from "@/integrations/auth/AuthContext";

export interface WorkspaceFeatures {
	practicesEnabled: boolean;
	mentorEnabled: boolean;
	leaderboardEnabled: boolean;
	progressionEnabled: boolean;
	leaguesEnabled: boolean;
}

export interface WorkspaceFeaturesResult {
	features?: WorkspaceFeatures;
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
		features: activeWorkspace ? workspaceFeaturesOf(activeWorkspace) : undefined,
		isLoading: authLoading || query.isLoading,
		isError: query.isError || workspaceMissing,
		error: query.error ?? (workspaceMissing ? new Error("Workspace not found") : undefined),
		refetch: () => void query.refetch(),
	};
}

function workspaceFeaturesOf(workspace: WorkspaceListItem): WorkspaceFeatures {
	return {
		practicesEnabled: workspace.practicesEnabled,
		mentorEnabled: workspace.mentorEnabled,
		leaderboardEnabled: workspace.leaderboardEnabled,
		progressionEnabled: workspace.progressionEnabled,
		leaguesEnabled: workspace.leaguesEnabled,
	};
}
