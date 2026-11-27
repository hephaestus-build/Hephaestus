import { ConfirmDialog } from "@/components/common/ConfirmDialog";

import type { TeamProjectRow } from "./team-projects";

export interface RemoveTeamProjectDialogProps {
	/** The team waiting for the admin's confirmation; `null` keeps the dialog closed. */
	team: TeamProjectRow | null;
	onConfirm: (team: TeamProjectRow) => void;
	/** Clears `team`. Called on dismissal and right after `onConfirm`. */
	onClose: () => void;
}

/**
 * Asks before a team project is removed. The removal cannot be undone: every member loses access at once, and every
 * version the team sent is withdrawn, which deletes its content (P4, the strictest option).
 */
export function RemoveTeamProjectDialog({
	team,
	onConfirm,
	onClose,
}: RemoveTeamProjectDialogProps) {
	return (
		<ConfirmDialog
			subject={team}
			title={({ name }) => `Remove Team ${name}?`}
			description={({ members }) =>
				[
					`${members.length === 1 ? "Its member loses" : `All ${members.length} members lose`} access to the team’s reviews, review discussions and decisions at once.`,
					"Every version the team sent is withdrawn, and its content is deleted.",
					"You cannot undo this. A new team project starts empty.",
				].join(" ")
			}
			confirmLabel="Remove team project"
			onConfirm={onConfirm}
			onClose={onClose}
		/>
	);
}
