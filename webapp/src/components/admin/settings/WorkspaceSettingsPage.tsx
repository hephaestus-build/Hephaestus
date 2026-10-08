import type { ReactNode } from "react";
import { WorkspaceCapabilitiesSettings } from "./WorkspaceCapabilitiesSettings";
import { WorkspaceDangerZoneSettings } from "./WorkspaceDangerZoneSettings";

export interface WorkspaceSettingsPageProps {
	security?: ReactNode;
	workspaceSlug: string;
	practicesEnabled: boolean;
}

export function WorkspaceSettingsPage({
	security,
	workspaceSlug,
	practicesEnabled,
}: WorkspaceSettingsPageProps) {
	return (
		<div className="max-w-4xl space-y-10">
			<WorkspaceCapabilitiesSettings
				workspaceSlug={workspaceSlug}
				practicesEnabled={practicesEnabled}
			/>
			{security}
			<WorkspaceDangerZoneSettings workspaceSlug={workspaceSlug} />
		</div>
	);
}
