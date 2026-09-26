import { useQuery } from "@tanstack/react-query";

import { hasMinimumWorkspaceRole } from "@/lib/workspace-roles";
import { useAuth } from "@/runtime/auth/AuthContext";
import { workspaceMembershipQueryOptions } from "@/runtime/auth/guard";

import { useActiveWorkspaceSlug } from "./use-active-workspace";

/** Read by the app chrome, which renders on every route, so the slug is the chrome's, not the URL's. */
export function useWorkspaceAccess() {
	const {
		chromeWorkspaceSlug,
		chromeWorkspace,
		workspaces,
		isLoading: workspacesLoading,
	} = useActiveWorkspaceSlug();
	const { isAuthenticated, isLoading: authLoading, userView, username } = useAuth();

	const membershipQuery = useQuery({
		...workspaceMembershipQueryOptions(chromeWorkspaceSlug ?? ""),
		enabled: Boolean(chromeWorkspaceSlug) && isAuthenticated && !authLoading,
	});

	const role = membershipQuery.data?.role;

	return {
		chromeWorkspaceSlug,
		chromeWorkspace,
		workspaces,
		role,
		isAdmin: !userView && hasMinimumWorkspaceRole(role, "ADMIN"),
		// The account's own login here, which the server chooses per workspace; the sign-in `username`
		// names whichever linked identity signed in, so it answers only where no membership can.
		// Undefined while the membership loads, so no page asks for another identity's data first.
		selfLogin:
			chromeWorkspaceSlug === undefined || membershipQuery.isError
				? username
				: membershipQuery.data?.userLogin,
		userName: membershipQuery.data?.userName,
		isLoading: workspacesLoading || membershipQuery.isLoading,
		error: membershipQuery.error,
	};
}
