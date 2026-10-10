import { WorkspaceCapabilitiesSettings } from "./WorkspaceCapabilitiesSettings";
import { WorkspaceDangerZoneSettings } from "./WorkspaceDangerZoneSettings";
import {
	WorkspacePublicActivitySettings,
	type WorkspacePublicActivitySettingsProps,
} from "./WorkspacePublicActivitySettings";

export interface WorkspaceSettingsPageProps {
	workspaceSlug: string;
	practicesEnabled: boolean;
	publicActivity: WorkspacePublicActivitySettingsProps;
}

export function WorkspaceSettingsPage({
	workspaceSlug,
	practicesEnabled,
	publicActivity,
}: WorkspaceSettingsPageProps) {
	return (
		<div className="max-w-4xl space-y-10">
			<WorkspaceCapabilitiesSettings
				workspaceSlug={workspaceSlug}
				practicesEnabled={practicesEnabled}
			/>
			<WorkspacePublicActivitySettings {...publicActivity} />
			<WorkspaceDangerZoneSettings workspaceSlug={workspaceSlug} />
		</div>
	);
}
