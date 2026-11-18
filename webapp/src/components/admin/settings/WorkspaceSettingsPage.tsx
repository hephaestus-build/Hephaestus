import type { ReactNode } from "react";
import { WorkspaceCapabilitiesSettings } from "./WorkspaceCapabilitiesSettings";
import {
	WorkspaceDangerZoneSettings,
	type WorkspaceDangerZoneSettingsProps,
} from "./WorkspaceDangerZoneSettings";
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
	/** Whether the workspace's admins see practice results per person. Only an owner turns it on. */
	perPersonResults?: ReactNode;
	security?: ReactNode;
	/** The connected MCP clients; absent while the instance has MCP clients turned off. */
	mcpClients?: ReactNode;
	workspaceSlug: string;
	practicesEnabled: boolean;
	publicActivity: WorkspacePublicActivitySettingsProps;
	hiddenPeople: Omit<WorkspaceHiddenPeopleSettingsProps, "workspaceSlug">;
	dangerZone: Omit<WorkspaceDangerZoneSettingsProps, "workspaceSlug">;
}

export function WorkspaceSettingsPage({
	setupSummary,
	perPersonResults,
	security,
	mcpClients,
	workspaceSlug,
	practicesEnabled,
	publicActivity,
	hiddenPeople,
	dangerZone,
}: WorkspaceSettingsPageProps) {
	return (
		<div className="max-w-4xl space-y-10">
			{setupSummary}
			<WorkspaceCapabilitiesSettings
				workspaceSlug={workspaceSlug}
				practicesEnabled={practicesEnabled}
			/>
			{perPersonResults}
			<WorkspacePublicActivitySettings {...publicActivity} />
			<WorkspaceHiddenPeopleSettings workspaceSlug={workspaceSlug} {...hiddenPeople} />
			{security}
			{mcpClients}
			<WorkspaceDangerZoneSettings workspaceSlug={workspaceSlug} {...dangerZone} />
		</div>
	);
}
