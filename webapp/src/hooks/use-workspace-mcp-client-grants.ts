import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import {
	listMcpClientGrantsOptions,
	listMcpClientGrantsQueryKey,
	revokeMcpClientGrantMutation,
} from "@/api/@tanstack/react-query.gen";
import { accountName } from "@/components/admin/users/account-labels";
import { problemStatusOf } from "@/lib/problem-detail";

import type { WorkspaceMcpClientGrantsSectionProps } from "@/components/admin/settings/WorkspaceMcpClientGrantsSection";

/**
 * The connected MCP clients of a workspace. Undefined while the instance has MCP clients turned off:
 * the endpoint then does not exist, and the settings page leaves the section out.
 */
export function useWorkspaceMcpClientGrants({
	workspaceSlug,
}: {
	workspaceSlug: string;
}): WorkspaceMcpClientGrantsSectionProps | undefined {
	const queryClient = useQueryClient();
	const options = { path: { workspaceSlug } };
	const grants = useQuery({ ...listMcpClientGrantsOptions(options), retry: false });
	const revoke = useMutation({
		...revokeMcpClientGrantMutation(),
		onSettled: async () => {
			await queryClient.invalidateQueries({ queryKey: listMcpClientGrantsQueryKey(options) });
		},
	});
	if (grants.isError && problemStatusOf(grants.error) === 404) {
		return undefined;
	}
	return {
		grants: grants.data?.map((grant) => ({
			id: grant.id,
			clientHost: grant.clientHost,
			clientName: grant.clientName,
			approvedBy: accountName(grant.approvedBy),
			scopes: grant.scopes,
			approvedAt: grant.approvedAt,
		})),
		loading: grants.isPending,
		loadError: grants.isError ? grants.error : undefined,
		revokeError: revoke.isError ? revoke.error : undefined,
		revokingId: revoke.isPending ? revoke.variables.path.grantId : undefined,
		onRetry: () => {
			revoke.reset();
			void grants.refetch();
		},
		onRevoke: (grantId) => revoke.mutate({ path: { ...options.path, grantId } }),
	};
}
