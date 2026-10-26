import { ConfirmDialog, type ConfirmDialogProps } from "@/components/common/ConfirmDialog";

import type { WorkspaceRoleChange } from "./workspace-members";

export interface WorkspaceChangeRoleDialogProps {
	/** The change waiting for the reader; `null` keeps the dialog closed. */
	change: WorkspaceRoleChange | null;
	onConfirm: (change: WorkspaceRoleChange) => void;
	/** Clears `change`. Called on dismissal and right after `onConfirm`. */
	onClose: () => void;
	/** The member's Role menu, which the change came from. */
	finalFocus?: ConfirmDialogProps<WorkspaceRoleChange>["finalFocus"];
}

/** `roleChangeAsksFirst` admits only these three: someone becomes an owner, or you lower your own role. */
function copyFor({ name, from, to }: WorkspaceRoleChange) {
	if (to === "OWNER") {
		return {
			title: `Make ${name} an owner?`,
			description: `${name} can then delete this workspace and change the role of anyone in it, including you.`,
			action: "Make owner",
			lowers: false,
		};
	}
	if (from === "OWNER" && to === "ADMIN") {
		return {
			title: "Give up your owner role?",
			description:
				"You stay an admin, but you can no longer delete this workspace. Only an owner can make you an owner again.",
			action: "Give up owner role",
			lowers: true,
		};
	}
	return {
		title: "Remove your admin access?",
		description:
			from === "OWNER"
				? "You become a member and can no longer manage this workspace. Only an owner can make you an owner again."
				: "You become a member and can no longer manage this workspace. An admin or owner can give you admin access again.",
		action: "Remove admin access",
		lowers: true,
	};
}

/**
 * Asks before a workspace role change that is hard to take back. The instance console's
 * `ChangeRoleDialog` toggles the instance admin role of an account, so its copy and type are
 * instance-only. This one closes on confirm, and the row reports the outcome (ADR 0027).
 */
export function WorkspaceChangeRoleDialog({
	change,
	onConfirm,
	onClose,
	finalFocus,
}: WorkspaceChangeRoleDialogProps) {
	return (
		<ConfirmDialog
			subject={change}
			title={(shown) => copyFor(shown).title}
			description={(shown) => copyFor(shown).description}
			confirmLabel={(shown) => copyFor(shown).action}
			confirmVariant={(shown) => (copyFor(shown).lowers ? "default" : "destructive")}
			onConfirm={onConfirm}
			onClose={onClose}
			finalFocus={finalFocus}
		/>
	);
}
