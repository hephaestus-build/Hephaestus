import { ConfirmDialog, type ConfirmDialogProps } from "@/components/common/ConfirmDialog";

import type { WorkspaceMemberRow } from "./workspace-members";

export interface WorkspaceRemoveMemberDialogProps {
	/** The member waiting for the reader's confirmation; `null` keeps the dialog closed. */
	member: WorkspaceMemberRow | null;
	/**
	 * The provider whose organization or group roster adds members here, such as GitHub. Its next
	 * sync adds back a person it still lists.
	 */
	providerLabel?: string;
	onConfirm: (member: WorkspaceMemberRow) => void;
	/** Clears `member`. Called on dismissal and right after `onConfirm`. */
	onClose: () => void;
	/** The member's Actions menu, which the removal came from. */
	finalFocus?: ConfirmDialogProps<WorkspaceMemberRow>["finalFocus"];
}

/**
 * Asks before a member leaves the workspace. Removal keeps the membership as a left one, so the
 * row stays and offers Add back. This dialog closes on confirm, and the row reports the outcome
 * (ADR 0027).
 */
export function WorkspaceRemoveMemberDialog({
	member,
	providerLabel,
	onConfirm,
	onClose,
	finalFocus,
}: WorkspaceRemoveMemberDialogProps) {
	return (
		<ConfirmDialog
			subject={member}
			title={({ name }) => `Remove ${name} from this workspace?`}
			description={({ name }) =>
				[
					`${name} loses access to this workspace, and Hephaestus stops sending them practice feedback here.`,
					"Their history stays recorded, and you can add them back on this page.",
					providerLabel === undefined
						? null
						: `If they are still a member in ${providerLabel}, the next sync adds them back.`,
				]
					.filter((sentence) => sentence !== null)
					.join(" ")
			}
			confirmLabel="Remove from workspace"
			onConfirm={onConfirm}
			onClose={onClose}
			finalFocus={finalFocus}
		/>
	);
}
