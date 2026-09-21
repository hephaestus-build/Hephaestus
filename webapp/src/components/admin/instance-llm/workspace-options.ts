import type { FacetOption } from "@/components/common/FacetMultiSelect";

export interface WorkspaceOption {
	id: number;
	displayName: string;
	workspaceSlug: string;
}

/** The slug rides along as the option description: two workspaces may share a display name. */
export function workspaceFacetOptions(workspaces: WorkspaceOption[]): FacetOption<number>[] {
	return workspaces.map((workspace) => ({
		value: workspace.id,
		label: workspace.displayName,
		description: workspace.workspaceSlug,
	}));
}
