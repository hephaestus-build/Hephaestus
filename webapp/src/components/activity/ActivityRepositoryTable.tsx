import type { ActivityRepositoryCounts } from "@/api/types.gen";
import {
	Table,
	TableBody,
	TableCell,
	TableHead,
	TableHeader,
	TableRow,
} from "@/components/ui/table";
import { ARTIFACT_KIND, artifactKindNoun } from "@/lib/artifact-kinds";
import type { ProviderType } from "@/lib/provider/provider-terms";
import { capitalise } from "@/lib/text";

export interface ActivityRepositoryTableProps {
	repositories: readonly ActivityRepositoryCounts[];
	providerType: ProviderType;
}

/** One person's counts in each repository, most contributions first, by the table's own columns. */
export function ActivityRepositoryTable({
	repositories,
	providerType,
}: ActivityRepositoryTableProps) {
	const rows = [...repositories].sort(
		(a, b) =>
			b.counts.contributions - a.counts.contributions ||
			a.repository.key.localeCompare(b.repository.key),
	);
	return (
		<Table bordered aria-label="Repositories">
			<TableHeader>
				<TableRow variant="static">
					<TableHead>Repository</TableHead>
					<TableHead>Contributions</TableHead>
					<TableHead>
						{capitalise(artifactKindNoun(ARTIFACT_KIND.pullRequest, 2, providerType))}
					</TableHead>
					<TableHead>Reviews</TableHead>
					<TableHead>Issues</TableHead>
				</TableRow>
			</TableHeader>
			<TableBody>
				{rows.map(({ repository, counts }) => (
					<TableRow key={repository.id} variant="static">
						<TableCell className="font-medium">{repository.key}</TableCell>
						<TableCell numeric>{counts.contributions}</TableCell>
						<TableCell numeric>
							{counts.pullRequestsOpened}
							<span className="text-muted-foreground"> · {counts.pullRequestsMerged} merged</span>
						</TableCell>
						<TableCell numeric>{counts.pullRequestsReviewed}</TableCell>
						<TableCell numeric>{counts.issuesOpened}</TableCell>
					</TableRow>
				))}
			</TableBody>
		</Table>
	);
}
