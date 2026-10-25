import type { WorkspaceMembership } from "@/api/types.gen";
import { accountName } from "@/components/admin/users/account-labels";
import { hasText } from "@/lib/text";
import type { WorkspaceRole } from "@/lib/workspace-roles";

export const WORKSPACE_ROLE_LABELS: Record<WorkspaceRole, string> = {
	OWNER: "Owner",
	ADMIN: "Admin",
	MEMBER: "Member",
};

export interface WorkspaceMemberRow {
	membership: WorkspaceMembership;
	name: string;
	logins: string[];
}

export function workspaceMemberRows(
	memberships: readonly WorkspaceMembership[],
): WorkspaceMemberRow[] {
	return memberships.map((membership) => ({
		membership,
		name: accountName(membership.account),
		logins: membership.account.identities.map((identity) => identity.login).filter(hasText),
	}));
}

/** A role a member is about to get, with what the reader needs to judge it. */
export interface WorkspaceRoleChange {
	accountId: string;
	name: string;
	from: WorkspaceRole;
	to: WorkspaceRole;
	/** The account is the reader's own. */
	own: boolean;
}

/**
 * An owner can delete the workspace and change any role, and a reader who lowers their own role
 * may not be able to raise it again. Both ask first; every other change is one step.
 */
export const roleChangeAsksFirst = (change: WorkspaceRoleChange) =>
	change.to === "OWNER" || change.own;

const OWNER_ASSIGNS: readonly WorkspaceRole[] = ["OWNER", "ADMIN", "MEMBER"];
const ADMIN_ASSIGNS: readonly WorkspaceRole[] = ["ADMIN", "MEMBER"];

/** Only an owner gives or takes the owner role; the server refuses it to anyone else. */
export const assignableWorkspaceRoles = (readerRole: WorkspaceRole | undefined) =>
	readerRole === "OWNER" ? OWNER_ASSIGNS : ADMIN_ASSIGNS;
