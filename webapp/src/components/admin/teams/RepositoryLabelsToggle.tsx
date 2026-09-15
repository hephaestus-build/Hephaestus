import type { LabelInfo, RepositoryInfo, TeamInfo } from "@/api/types.gen";
import { LabelBadge } from "@/components/shared/LabelBadge";
import { Toggle } from "@/components/ui/toggle";

export interface RepositoryLabelsToggleProps {
	team: TeamInfo;
	repository: RepositoryInfo;
	catalogLabels: LabelInfo[];
	onAddLabel?: (teamId: number, repositoryId: number, label: string) => Promise<void>;
	onRemoveLabel?: (teamId: number, labelId: number) => Promise<void>;
}

export function RepositoryLabelsToggle({
	team,
	repository,
	catalogLabels,
	onAddLabel,
	onRemoveLabel,
}: RepositoryLabelsToggleProps) {
	const activeByName = new Map<string, LabelInfo>();
	for (const l of team.labels) {
		if (l.repository?.id !== repository.id) continue;
		const key = l.name.toLowerCase();
		if (key && !activeByName.has(key)) activeByName.set(key, l);
	}

	const shown = [...catalogLabels].sort((a, b) => a.name.localeCompare(b.name));

	const handleToggle = async (label: LabelInfo) => {
		const key = label.name.toLowerCase();
		const active = activeByName.get(key);
		if (active) {
			await onRemoveLabel?.(team.id, active.id);
		} else {
			await onAddLabel?.(team.id, repository.id, label.name);
		}
	};

	return (
		<div className="space-y-1.5">
			<p className="font-medium text-sm">Labels</p>
			<p className="text-xs text-muted-foreground">
				Selecting labels limits this team's contribution metrics to items tagged with any of the
				selected labels for this repository.
			</p>
			{shown.length > 0 ? (
				<div className="flex flex-wrap gap-1.5">
					{shown.map((label) => {
						const isActive = activeByName.has(label.name.toLowerCase());
						return (
							<Toggle
								key={`${label.id}-${label.name}`}
								pressed={isActive}
								onPressedChange={() => void handleToggle(label)}
								aria-label={`${isActive ? "Remove" : "Add"} ${label.name} label`}
								className="h-auto min-w-0 rounded-full p-0 data-pressed:ring-2 data-pressed:ring-primary data-pressed:ring-offset-1"
							>
								<LabelBadge label={label.name} color={label.color} className="text-2xs" />
							</Toggle>
						);
					})}
				</div>
			) : (
				<p className="text-xs text-muted-foreground">No labels available for this repository.</p>
			)}
		</div>
	);
}

export default RepositoryLabelsToggle;
