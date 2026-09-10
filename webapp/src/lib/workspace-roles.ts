import type { WorkspaceAccountMembership } from "@/api/types.gen";

export type WorkspaceRole = NonNullable<WorkspaceAccountMembership["role"]>;

const WORKSPACE_ROLE_RANK: Record<WorkspaceRole, number> = {
	MEMBER: 0,
	ADMIN: 1,
	OWNER: 2,
};

const isWorkspaceRole = (value: string): value is WorkspaceRole =>
	Object.hasOwn(WORKSPACE_ROLE_RANK, value);

/** Gates fail closed: no role and a role only the server knows about both rank as unranked. */
export function hasMinimumWorkspaceRole(
	role: string | null | undefined,
	minRole: WorkspaceRole,
): boolean {
	if (role == null || !isWorkspaceRole(role)) return false;
	return WORKSPACE_ROLE_RANK[role] >= WORKSPACE_ROLE_RANK[minRole];
}
