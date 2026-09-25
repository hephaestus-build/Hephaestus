import { useQuery } from "@tanstack/react-query";

import { getWorkspaceOptions, listUserViewUsersOptions } from "@/api/@tanstack/react-query.gen";
import type { UserViewUsersState } from "@/components/admin/users/UserViewUsersTable";
import { type PanelState, panelState } from "@/components/common/panel-state";

const PAGE_SIZE = 25;

export function useUserViewWorkspace(workspaceSlug: string): PanelState<{ displayName: string }> {
	const query = useQuery(getWorkspaceOptions({ path: { workspaceSlug } }));
	return panelState(query, (workspace) => ({
		status: "ready",
		displayName: workspace.displayName,
	}));
}

export function useUserViewUsers(workspaceSlug: string, page: number): UserViewUsersState {
	const query = useQuery(
		listUserViewUsersOptions({
			path: { workspaceSlug },
			query: { page, size: PAGE_SIZE },
		}),
	);
	return panelState(query, (data) => ({
		status: "ready",
		users: data.content ?? [],
		totalPages: data.totalPages ?? 0,
	}));
}
