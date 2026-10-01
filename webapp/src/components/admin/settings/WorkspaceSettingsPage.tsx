import { WorkspaceCapabilitiesSettings } from "./WorkspaceCapabilitiesSettings";
import { WorkspaceDangerZoneSettings } from "./WorkspaceDangerZoneSettings";

export interface WorkspaceSettingsPageProps {
	workspaceSlug: string;
	practicesEnabled: boolean;
}

export function WorkspaceSettingsPage({
	workspaceSlug,
	practicesEnabled,
}: WorkspaceSettingsPageProps) {
	return (
		<div className="max-w-4xl space-y-10">
			<WorkspaceCapabilitiesSettings
				workspaceSlug={workspaceSlug}
				practicesEnabled={practicesEnabled}
			/>
			<WorkspaceDangerZoneSettings workspaceSlug={workspaceSlug} />
		</div>
	);
}
