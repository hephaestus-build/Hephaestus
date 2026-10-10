import type { ActivityRepositoryCounts } from "@/api/types.gen";
import {
	Table,
	TableBody,
	TableCell,
	TableHead,
	TableHeader,
	TableRow,
} from "@/components/ui/table";
import type { ProviderType } from "@/lib/provider/provider-terms";

import { NoneMark } from "./ActionChip";
import { ActivityCountCell, ActivityCountHeader, type CountedCategory } from "./ActivityCountCell";

export interface ActivityRepositoryTableProps {
	repositories: readonly ActivityRepositoryCounts[];
	providerType: ProviderType;
}

const CATEGORIES: readonly CountedCategory[] = ["pull-requests", "reviews", "issues"];

/**
 * One person's counts in each repository, most contributions first, in the people table's own
 * cells, so a row here reads like that person's row there.
 */
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
					<TableHead className="pl-3 text-muted-foreground">Repository</TableHead>
					<TableHead numeric className="w-px text-right text-muted-foreground">
						Contributions
					</TableHead>
					{CATEGORIES.map((category) => (
						<TableHead
							key={category}
							numeric
							className="w-px text-right text-muted-foreground last:pr-3"
						>
							<ActivityCountHeader category={category} providerType={providerType} />
						</TableHead>
					))}
				</TableRow>
			</TableHeader>
			<TableBody>
				{rows.map(({ repository, counts }) => (
					<TableRow key={repository.id} variant="static">
						<TableCell className="pl-3 font-medium">{repository.key}</TableCell>
						<TableCell numeric className="text-right font-semibold">
							{counts.contributions === 0 ? <NoneMark /> : counts.contributions}
						</TableCell>
						{CATEGORIES.map((category) => (
							<TableCell key={category} numeric className="text-right last:pr-3">
								<ActivityCountCell
									category={category}
									counts={counts}
									providerType={providerType}
								/>
							</TableCell>
						))}
					</TableRow>
				))}
			</TableBody>
		</Table>
	);
}
