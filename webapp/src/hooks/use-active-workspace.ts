import { useQuery } from "@tanstack/react-query";
import { useParams } from "@tanstack/react-router";

import { listWorkspacesOptions } from "@/api/@tanstack/react-query.gen";
import type { WorkspaceListItem } from "@/api/types.gen";
import { toScmProviderType } from "@/lib/provider/provider-terms";
import { useAuth } from "@/runtime/auth/AuthContext";

/** Shared so an unloaded query keeps the same `workspaces` identity across renders. */
const NO_WORKSPACES: WorkspaceListItem[] = [];

export function useActiveWorkspaceSlug() {
	const { isAuthenticated, isLoading: authLoading } = useAuth();
	const query = useQuery({
		...listWorkspacesOptions(),
		enabled: isAuthenticated && !authLoading,
	});
	const workspaces = Array.isArray(query.data) ? query.data : NO_WORKSPACES;
	const urlSlug = useParams({ strict: false, select: (params) => params.workspaceSlug });
	// The only address of an unlisted workspace that renders is its public activity page, which the
	// account reads as a visitor. A workspace it has no part in is no active workspace: its
	// members-only reads, the Heph chat and the feedback menu would all be refused.
	const visiting =
		urlSlug !== undefined &&
		query.isSuccess &&
		!workspaces.some((item) => item.workspaceSlug === urlSlug);
	const workspaceSlug = visiting ? undefined : urlSlug;
	// The app chrome renders on every route, including the ones whose URL names no workspace, and a
	// member of workspaces must not be shown as belonging to none. `workspaceSlug` stays the only
	// source for workspace-scoped data, so the two are separate values rather than one fallback.
	const chromeWorkspaceSlug = visiting
		? undefined
		: (workspaceSlug ?? workspaces[0]?.workspaceSlug);
	const chromeWorkspace = workspaces.find(
		(workspace) => workspace.workspaceSlug === chromeWorkspaceSlug,
	);

	return {
		workspaceSlug,
		chromeWorkspaceSlug,
		chromeWorkspace,
		workspaces,
		// A workspace is SCM-backed; SLACK (an identity provider) never reaches SCM-only UI, but the
		// generated type includes it, so narrow to the SCM ProviderType with a GITHUB fallback.
		providerType: toScmProviderType(chromeWorkspace?.providerType),
		isLoading: authLoading || query.isLoading,
		error: query.error,
	};
}
