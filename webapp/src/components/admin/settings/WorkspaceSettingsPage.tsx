import type { ReactNode } from "react";
import { WorkspaceCapabilitiesSettings } from "./WorkspaceCapabilitiesSettings";
import { WorkspaceDangerZoneSettings } from "./WorkspaceDangerZoneSettings";
import {
	WorkspaceHiddenPeopleSettings,
	type WorkspaceHiddenPeopleSettingsProps,
} from "./WorkspaceHiddenPeopleSettings";
import {
	WorkspacePublicActivitySettings,
	type WorkspacePublicActivitySettingsProps,
} from "./WorkspacePublicActivitySettings";

export interface WorkspaceSettingsPageProps {
	security?: ReactNode;
	workspaceSlug: string;
	practicesEnabled: boolean;
	publicActivity: WorkspacePublicActivitySettingsProps;
	hiddenPeople: Omit<WorkspaceHiddenPeopleSettingsProps, "workspaceSlug">;
}

export function WorkspaceSettingsPage({
	security,
	workspaceSlug,
	practicesEnabled,
	publicActivity,
	hiddenPeople,
}: WorkspaceSettingsPageProps) {
	return (
		<div className="max-w-4xl space-y-10">
			<WorkspaceCapabilitiesSettings
				workspaceSlug={workspaceSlug}
				practicesEnabled={practicesEnabled}
			/>
			<WorkspacePublicActivitySettings {...publicActivity} />
			<WorkspaceHiddenPeopleSettings workspaceSlug={workspaceSlug} {...hiddenPeople} />
			{security}
			<WorkspaceDangerZoneSettings workspaceSlug={workspaceSlug} />
		</div>
	);
}
