import { useQuery } from "@tanstack/react-query";

import { getCurrentUserOptions, listWorkspacesOptions } from "@/api/@tanstack/react-query.gen";
import { useWorkspace } from "@/workspace/workspace-context";

/** Both tab roots show the same account and disambiguate the selected workspace only when needed. */
export function useTabAccount() {
	const user = useQuery(getCurrentUserOptions({}));
	const workspaces = useQuery(listWorkspacesOptions({}));
	const workspace = useWorkspace();
	return {
		avatarUrl: user.data?.avatarUrl,
		loading: user.isPending,
		workspaceName: (workspaces.data?.length ?? 0) > 1 ? workspace.displayName : undefined,
	};
}
