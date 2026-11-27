import { ConfirmDialog } from "@/components/common/ConfirmDialog";

import type { TeamProjectPerson, TeamProjectRow } from "./team-projects";

export interface TeamMemberRemoval {
	team: TeamProjectRow;
	member: TeamProjectPerson;
}

export interface RemoveTeamMemberDialogProps {
	/** The removal waiting for the admin's confirmation; `null` keeps the dialog closed. */
	removal: TeamMemberRemoval | null;
	onConfirm: (removal: TeamMemberRemoval) => void;
	/** Clears `removal`. Called on dismissal and right after `onConfirm`. */
	onClose: () => void;
}

/**
 * Asks before a member leaves a team project, because the removal changes who can read the team's work at once, and
 * it withdraws the versions that member sent to the team.
 */
export function RemoveTeamMemberDialog({
	removal,
	onConfirm,
	onClose,
}: RemoveTeamMemberDialogProps) {
	return (
		<ConfirmDialog
			subject={removal}
			title={({ team, member }) => `Remove ${member.displayName} from Team ${team.name}?`}
			description={({ member }) =>
				`${member.displayName} loses access to this team’s reviews, review discussions and decisions at once. The versions they sent to this team are withdrawn, so the messages that cite them are hidden. Their notes on those versions are deleted. Their own messages stay under their name. If you add them again, they read the discussion only from that day.`
			}
			confirmLabel="Remove from team"
			onConfirm={onConfirm}
			onClose={onClose}
		/>
	);
}
