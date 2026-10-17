import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useMatch } from "@tanstack/react-router";

import { getWorkspaceOptions, listWorkspacesQueryKey } from "@/api/@tanstack/react-query.gen";
import { queryErrorIsRetryable } from "@/components/common/QueryErrorAlert";
import { useQueryReadRefusal } from "@/runtime/tanstack-query/use-query-read-refusal";

import { accountName, workspaceIdentity } from "@/components/admin/users/account-labels";
import { hasMinimumWorkspaceRole } from "@/lib/workspace-roles";
import { useAuth } from "@/runtime/auth/AuthContext";
import { workspaceMembershipQueryOptions } from "@/runtime/auth/guard";

import { useActiveWorkspaceSlug } from "./use-active-workspace";

/** Member chrome follows the member list; administration outside it follows the verified target read. */
export function useWorkspaceAccess() {
	const {
		chromeWorkspaceSlug: memberWorkspaceSlug,
		chromeWorkspace: memberWorkspace,
		workspaces,
		isLoading: workspacesLoading,
		error: workspacesError,
	} = useActiveWorkspaceSlug();
	const queryClient = useQueryClient();
	const { isAuthenticated, isLoading: authLoading, isAppAdmin, userView, username } = useAuth();
	const match = useMatch({
		from: "/_authenticated/w/$workspaceSlug",
		shouldThrow: false,
	});
	const target = match?.context.administrationWorkspace;
	const administrationTarget = isAppAdmin && userView === undefined ? target : undefined;
	// The target identity survives a pending or refused read; member chrome cannot take its place.
	const chromeWorkspaceSlug = administrationTarget === undefined ? memberWorkspaceSlug : undefined;
	const chromeWorkspace = administrationTarget === undefined ? memberWorkspace : undefined;
	const administrationOptions = getWorkspaceOptions({
		path: { workspaceSlug: administrationTarget?.workspaceSlug ?? "" },
	});
	const administrationQuery = useQuery({
		...administrationOptions,
		enabled: administrationTarget !== undefined,
	});
	useQueryReadRefusal(
		administrationOptions.queryKey,
		administrationQuery.isError,
		administrationQuery.error,
	);
	const administrationWorkspace =
		administrationTarget !== undefined &&
		(!administrationQuery.isError || queryErrorIsRetryable(administrationQuery.error))
			? administrationQuery.data
			: undefined;

	const membershipQuery = useQuery({
		...workspaceMembershipQueryOptions(chromeWorkspaceSlug ?? ""),
		enabled: Boolean(chromeWorkspaceSlug) && isAuthenticated && !authLoading,
	});

	const membership = administrationTarget === undefined ? membershipQuery.data : undefined;
	const role = membership?.role;
	const provider = chromeWorkspace?.providerType;

	return {
		chromeWorkspaceSlug,
		chromeWorkspace,
		workspaces,
		administrationWorkspace,
		administrationWorkspaceSlug: administrationTarget?.workspaceSlug,
		adminWorkspaceSlug:
			administrationTarget === undefined ? chromeWorkspaceSlug : administrationTarget.workspaceSlug,
		role,
		isAdmin:
			!userView &&
			(administrationWorkspace !== undefined || hasMinimumWorkspaceRole(role, "ADMIN")),
		// Sign-in usernames can name another linked identity; workspace activity needs this provider's login.
		selfLogin:
			chromeWorkspaceSlug === undefined || membershipQuery.isError
				? username
				: membership && (workspaceIdentity(membership.account, provider)?.login ?? undefined),
		userName: membership && accountName(membership.account, provider),
		isLoading:
			administrationTarget === undefined
				? workspacesLoading || membershipQuery.isLoading
				: administrationQuery.isLoading,
		error:
			(administrationTarget === undefined
				? (workspacesError ?? membershipQuery.error)
				: administrationQuery.error) ?? undefined,
		refetch: () => {
			if (administrationTarget === undefined) {
				void queryClient.refetchQueries({ queryKey: listWorkspacesQueryKey(), exact: true });
				void membershipQuery.refetch();
			} else {
				void administrationQuery.refetch();
			}
		},
	};
}
