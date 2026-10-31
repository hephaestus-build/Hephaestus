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
	/** Whether members get value from the workspace yet, first because it is what an admin acts on. */
	setupSummary?: ReactNode;
	security?: ReactNode;
	workspaceSlug: string;
	practicesEnabled: boolean;
	publicActivity: WorkspacePublicActivitySettingsProps;
	hiddenPeople: Omit<WorkspaceHiddenPeopleSettingsProps, "workspaceSlug">;
}

export function WorkspaceSettingsPage({
	setupSummary,
	security,
	workspaceSlug,
	practicesEnabled,
	publicActivity,
	hiddenPeople,
}: WorkspaceSettingsPageProps) {
	return (
		<div className="max-w-4xl space-y-10">
			{setupSummary}
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
