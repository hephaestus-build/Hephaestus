import { createContext, type ReactNode, use } from "react";

import type { WorkspaceListItem } from "@/api/types.gen";

const WorkspaceContext = createContext<WorkspaceListItem | null>(null);

export function WorkspaceProvider({
	workspace,
	children,
}: {
	workspace: WorkspaceListItem;
	children: ReactNode;
}) {
	return <WorkspaceContext value={workspace}>{children}</WorkspaceContext>;
}

/** The workspace every tab shows. Only rendered inside the signed-in shell, which resolves it first. */
export function useWorkspace(): WorkspaceListItem {
	const workspace = use(WorkspaceContext);
	if (workspace === null) {
		throw new Error("useWorkspace outside the signed-in shell");
	}
	return workspace;
}
