import { WorkspaceDangerZoneSettings } from "./WorkspaceDangerZoneSettings";
import {
	WorkspaceFeaturesSettings,
	type FeatureKey,
	type FeatureValues,
} from "./WorkspaceFeaturesSettings";
import { WorkspaceLeagueSettings } from "./WorkspaceLeagueSettings";

export interface WorkspaceSettingsPageProps {
	isResettingLeagues: boolean;
	onResetLeagues: () => void;
	features: FeatureValues;
	isSavingFeatures: boolean;
	onToggleFeature: (feature: FeatureKey, enabled: boolean) => void;
	workspaceSlug?: string;
}

export function WorkspaceSettingsPage({
	isResettingLeagues,
	onResetLeagues,
	features,
	isSavingFeatures,
	onToggleFeature,
	workspaceSlug,
}: WorkspaceSettingsPageProps) {
	return (
		<div className="max-w-4xl space-y-10">
			<WorkspaceFeaturesSettings
				values={features}
				isSaving={isSavingFeatures}
				onToggle={onToggleFeature}
			/>

			{features.leaguesEnabled && (
				<WorkspaceLeagueSettings isResetting={isResettingLeagues} onResetLeagues={onResetLeagues} />
			)}

			{workspaceSlug != null && <WorkspaceDangerZoneSettings workspaceSlug={workspaceSlug} />}
		</div>
	);
}
