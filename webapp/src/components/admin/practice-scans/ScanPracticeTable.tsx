import { ListChecksIcon } from "lucide-react";
import { definitionFor } from "@/components/common/status-def";

import type { ScanPracticeReport } from "@/api/types.gen";
import { EmptyState } from "@/components/common/EmptyState";
import { StatusBadge } from "@/components/common/StatusBadge";
import { REVIEW_PRACTICE_STATUS_DEFS } from "@/components/practice-vocabulary/review-status-defs";
import {
	REMOVAL_GUARD_DEFS,
	SCAN_COMPARISON_DEFS,
} from "@/components/practice-vocabulary/scan-baseline-defs";
import {
	Table,
	TableBody,
	TableCell,
	TableHead,
	TableHeader,
	TableRow,
} from "@/components/ui/table";

import { ofTotal, type ScanProgress, withheldSentence } from "./scan-display";

export interface ScanPracticeTableProps {
	practices: readonly ScanPracticeReport[];
	progress: ScanProgress;
}

const NO_PRACTICES: Record<ScanProgress, { title: string; description?: string }> = {
	active: { title: "No practice reached yet" },
	complete: { title: "This scan reached no practice" },
	stopped: {
		title: "This scan reached no practice",
		description: "It stopped before it reviewed any file against a practice.",
	},
};

// Incomplete listings count only files reached, so their totals cannot describe the repository.
export function ScanPracticeTable({ practices, progress }: ScanPracticeTableProps) {
	if (practices.length === 0) {
		return <EmptyState icon={<ListChecksIcon />} headingLevel={2} {...NO_PRACTICES[progress]} />;
	}
	return (
		<Table bordered aria-label="Practices in this scan">
			<TableHeader>
				<TableRow>
					<TableHead scope="col">Practice</TableHead>
					<TableHead scope="col">Status</TableHead>
					<TableHead scope="col">Comparison</TableHead>
					<TableHead scope="col" className="text-right">
						Reviewed or reused
					</TableHead>
					<TableHead scope="col" className="text-right">
						Not met
					</TableHead>
					<TableHead scope="col" className="text-right">
						Problems
					</TableHead>
					<TableHead scope="col" className="text-right">
						Withheld
					</TableHead>
					<TableHead scope="col" className="text-right">
						Not reviewed
					</TableHead>
				</TableRow>
			</TableHeader>
			<TableBody>
				{practices.map((practice) => (
					<TableRow key={practice.practiceId}>
						<TableCell className="max-w-xs">
							<span className="block truncate font-medium">{practice.practiceName}</span>
							<span className="block text-xs text-muted-foreground">
								Version {practice.number}
								{!practice.listingComplete && ` · ${REMOVAL_GUARD_DEFS.INCOMPLETE_LISTING.label}`}
							</span>
						</TableCell>
						<TableCell>
							<StatusBadge def={definitionFor(REVIEW_PRACTICE_STATUS_DEFS, practice.status)} />
							{practice.status === "WITHHELD" && (
								<span className="mt-1 block text-xs text-muted-foreground">
									{withheldSentence(practice.reason)}
								</span>
							)}
						</TableCell>
						<TableCell>
							<StatusBadge
								def={definitionFor(SCAN_COMPARISON_DEFS, practice.comparison)}
								title={definitionFor(SCAN_COMPARISON_DEFS, practice.comparison).description}
							/>
						</TableCell>
						<TableCell numeric>
							{ofTotal(practice.counts.reviewed, practice.counts.practiceItemPairs)}
						</TableCell>
						<TableCell numeric>{practice.counts.notMet}</TableCell>
						<TableCell numeric>{practice.counts.problems}</TableCell>
						<TableCell numeric>{practice.counts.withheld}</TableCell>
						<TableCell numeric>{practice.counts.notReviewed}</TableCell>
					</TableRow>
				))}
			</TableBody>
		</Table>
	);
}
