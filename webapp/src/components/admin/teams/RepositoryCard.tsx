import { Eye, EyeOff, Settings } from "lucide-react";

import { cn } from "cn";
import type { LabelInfo, RepositoryInfo, TeamInfo } from "@/api/types.gen";
import { LabelBadge } from "@/components/shared/LabelBadge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader } from "@/components/ui/card";
import { Popover, PopoverContent, PopoverTitle, PopoverTrigger } from "@/components/ui/popover";

import { RepositoryLabelsToggle } from "./RepositoryLabelsToggle";

export interface RepositoryCardProps {
	repository: RepositoryInfo;
	team: TeamInfo;
	catalogLabels: LabelInfo[];
	onAddLabel?: (teamId: number, repositoryId: number, label: string) => Promise<void>;
	onRemoveLabel?: (teamId: number, labelId: number) => Promise<void>;
	onToggleVisibility?: (hidden: boolean) => void | Promise<void>;
}

export function RepositoryCard({
	repository,
	team,
	catalogLabels,
	onAddLabel,
	onRemoveLabel,
	onToggleVisibility,
}: RepositoryCardProps) {
	// Keyed by lower-cased name so two labels differing only in case read as the one label they are.
	const repoLabelsByName = new Map<string, LabelInfo>();
	for (const label of team.labels) {
		if (label.repository?.id !== repository.id) continue;
		const key = label.name.toLowerCase();
		if (key && !repoLabelsByName.has(key)) repoLabelsByName.set(key, label);
	}

	const filteredRepoLabels = [...repoLabelsByName.values()].sort((a, b) =>
		a.name.localeCompare(b.name),
	);

	return (
		<Card
			variant={repository.hiddenFromContributions ? "dashed" : team.hidden ? "muted" : "default"}
			className="flex flex-col"
		>
			<CardHeader>
				<div className="flex items-start justify-between">
					<div className="min-w-0 flex-1">
						<div className="flex items-center gap-2 min-w-0">
							<a
								href={repository.htmlUrl}
								target="_blank"
								rel="noopener noreferrer"
								className={cn(
									"text-sm font-medium hover:underline block truncate",
									team.hidden || repository.hiddenFromContributions ? "text-muted-foreground" : "",
								)}
								title={repository.nameWithOwner}
							>
								{repository.nameWithOwner}
							</a>
						</div>
						{repository.hiddenFromContributions && (
							<div className="flex items-center gap-1 flex-wrap mt-1">
								<span className="text-2xs px-1.5 py-0.5 rounded bg-muted text-muted-foreground uppercase tracking-wide">
									Hidden in contributions
								</span>
							</div>
						)}
						{repository.description && (
							<p className="text-xs text-muted-foreground mt-1 line-clamp-2">
								{repository.description}
							</p>
						)}
					</div>
					<div className="flex items-center gap-1 ml-2 flex-shrink-0">
						{onToggleVisibility && (
							<Button
								variant="ghost"
								size="icon-sm"
								onClick={() => void onToggleVisibility(!repository.hiddenFromContributions)}
								title={
									repository.hiddenFromContributions
										? "Show repository contributions"
										: "Hide repository contributions"
								}
							>
								{repository.hiddenFromContributions ? (
									<EyeOff className="size-3.5" />
								) : (
									<Eye className="size-3.5" />
								)}
							</Button>
						)}
						<Popover>
							<PopoverTrigger
								render={
									<Button
										variant="ghost"
										size="icon-sm"
										aria-label={`Configure labels for ${repository.nameWithOwner}`}
									>
										<Settings className="size-3.5" />
									</Button>
								}
							/>
							<PopoverContent className="w-[32rem] max-w-[calc(100vw-2rem)] p-3 sm:p-4" align="end">
								<PopoverTitle className="sr-only">
									Labels for {repository.nameWithOwner}
								</PopoverTitle>
								<RepositoryLabelsToggle
									team={team}
									repository={repository}
									catalogLabels={catalogLabels}
									onAddLabel={onAddLabel}
									onRemoveLabel={onRemoveLabel}
								/>
							</PopoverContent>
						</Popover>
					</div>
				</div>
			</CardHeader>
			{filteredRepoLabels.length > 0 && (
				<CardContent className="flex flex-wrap gap-1">
					{filteredRepoLabels.map((label) => (
						<LabelBadge
							key={`${label.name}-${label.repository?.id ?? ""}`}
							label={label.name}
							color={label.color}
						/>
					))}
				</CardContent>
			)}
		</Card>
	);
}

export default RepositoryCard;
