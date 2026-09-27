import { Platform } from "react-native";

import type { WorkspaceListItem } from "@/api/types.gen";
import { Row } from "@/ui/Row";
import { Section } from "@/ui/Section";

export interface WorkspaceListProps {
	workspaces: WorkspaceListItem[];
	selectedSlug?: string | null;
	onSelect: (workspace: WorkspaceListItem) => void;
}

/**
 * The workspaces this account can open, the current one marked: with a trailing checkmark on iOS, as its
 * lists mark a choice, and with a radio icon on Android, as Material does.
 */
export function WorkspaceList({ workspaces, selectedSlug, onSelect }: WorkspaceListProps) {
	return (
		<Section title="Workspaces">
			{workspaces.map((workspace) => {
				const open = workspace.workspaceSlug === selectedSlug;
				return Platform.OS === "ios" ? (
					<Row
						key={workspace.workspaceSlug}
						testID={`workspace-${workspace.workspaceSlug}`}
						title={workspace.displayName}
						subtitle={workspace.accountLogin}
						// Choosing the first workspace leads on, so those rows keep their chevron.
						checked={selectedSlug === undefined || selectedSlug === null ? undefined : open}
						onPress={() => onSelect(workspace)}
					/>
				) : (
					<Row
						key={workspace.workspaceSlug}
						testID={`workspace-${workspace.workspaceSlug}`}
						title={workspace.displayName}
						subtitle={open ? "Open now" : workspace.accountLogin}
						icon={
							open
								? { ios: "checkmark.circle.fill", android: "check_circle" }
								: { ios: "circle", android: "radio_button_unchecked" }
						}
						kind="action"
						onPress={() => onSelect(workspace)}
					/>
				);
			})}
		</Section>
	);
}
