import { ClipboardCheckIcon } from "lucide-react";

import type { PracticeGroup, PracticeGroupStanding, PracticeStanding } from "@/api/types.gen";
import { BulletList } from "@/components/common/BulletList";
import type { FeedbackTextSegment } from "@/components/common/feedback-text";
import { FeedbackText } from "@/components/common/FeedbackText";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { getGroupVisual } from "@/components/practice-vocabulary/group-visuals";
import { GroupName } from "@/components/practice-vocabulary/GroupName";
import type { PracticeGroupSort } from "@/components/practice-vocabulary/practice-group-list-order";
import {
	countPracticeStandings,
	PracticeGroupStandingRing,
	StandingCountsList,
} from "@/components/practice-vocabulary/PracticeGroupStandingRing";
import {
	PracticeTable,
	PracticeTableRow,
	RowLinkCell,
	StandingCell,
	SubjectCell,
} from "@/components/practice-vocabulary/PracticeTable";
import { Skeleton } from "@/components/ui/skeleton";
import { TableCell, TableHead } from "@/components/ui/table";

export interface AllPracticesTableProps {
	/** Already sorted by the caller under `sort`. */
	groups: PracticeGroup[];
	standings: Record<string, PracticeGroupStanding | undefined>;
	practicesByGroup: Record<string, PracticeStanding[] | undefined>;
	/**
	 * What moved in each group since the latest run, one sentence per bullet, keyed by group slug;
	 * a group without an entry shows only its name. Practices that made the same move arrive as one
	 * sentence naming them together.
	 */
	sentences: Record<string, FeedbackTextSegment[][] | undefined>;
	/** The table sorts by standing only; any other column leaves the Standing header unsorted. */
	sort: PracticeGroupSort;
	/** Called with the sort a press on the Standing header asks for. */
	onSortChange: (sort: PracticeGroupSort) => void;
	onOpenGroup?: (group: PracticeGroup) => void;
	/** The group whose detail level is open over the page; its row stays marked. */
	openGroupSlug?: string;
	/** Opens a practice named inside a group's sentence. */
	onOpenPractice?: (practiceSlug: string) => void;
	isLoading: boolean;
	error?: unknown;
	onRetry?: () => void;
}

interface GroupRowProps {
	group: PracticeGroup;
	standing: PracticeGroupStanding | undefined;
	practices: PracticeStanding[];
	sentences?: FeedbackTextSegment[][];
	open: boolean;
	onOpenGroup?: (group: PracticeGroup) => void;
	onOpenPractice?: (practiceSlug: string) => void;
}

function GroupRow({
	group,
	standing,
	practices,
	sentences = NO_SENTENCES,
	open,
	onOpenGroup,
	onOpenPractice,
}: GroupRowProps) {
	const { Icon, pill } = getGroupVisual(group.icon, group.color);
	const counts = countPracticeStandings(practices);

	return (
		<PracticeTableRow open={open} onOpen={onOpenGroup && (() => onOpenGroup(group))}>
			<StandingCell
				standing={standing?.standing ?? "NOT_OBSERVED"}
				direction={standing?.direction}
				support={standing?.trendSupport}
				scope="group"
			/>

			<TableCell>
				<PracticeGroupStandingRing counts={counts} size="sm" />
			</TableCell>

			<TableCell>
				<StandingCountsList counts={counts} size="sm" aria-label="Practices by standing" />
			</TableCell>

			<SubjectCell badge={<GroupName name={group.name} icon={Icon} pill={pill} />}>
				{sentences.length > 0 && (
					<BulletList className="max-w-md text-sm">
						{sentences.map((sentence, index) => (
							<li key={index}>
								<FeedbackText segments={sentence} onOpenPractice={onOpenPractice} />
							</li>
						))}
					</BulletList>
				)}
			</SubjectCell>

			<RowLinkCell label={onOpenGroup && `Open group ${group.name}`}>Open group</RowLinkCell>
		</PracticeTableRow>
	);
}

const loadingRow = (
	<>
		<TableCell>
			<div className="flex flex-col items-start gap-1.5">
				<Skeleton className="h-5 w-32 rounded-full" />
				<Skeleton className="h-5 w-40" />
			</div>
		</TableCell>
		<TableCell>
			<Skeleton className="size-11 rounded-full" />
		</TableCell>
		<TableCell>
			<div className="flex flex-col gap-1">
				<Skeleton className="h-3 w-24" />
				<Skeleton className="h-3 w-20" />
			</div>
		</TableCell>
		<TableCell>
			<div className="flex flex-col items-start gap-2">
				<Skeleton className="h-5 w-48 rounded-full" />
				<Skeleton className="h-5 w-full max-w-md" />
			</div>
		</TableCell>
		<TableCell />
	</>
);

/**
 * Every practice group as one row: its standing and trend, a ring of its practices by standing
 * with the counts spelt out beside it, then the group and what moved inside it, one bullet per
 * move with every practice that made it as a pill. The Standing header is the table's one sort; a
 * row opens its group through the "Open group" at its end, and a practice's pill opens the
 * practice.
 */
const NO_SENTENCES: FeedbackTextSegment[][] = [];

export function AllPracticesTable({
	groups,
	standings,
	practicesByGroup,
	sentences,
	sort,
	onSortChange,
	onOpenGroup,
	openGroupSlug,
	onOpenPractice,
	isLoading,
	error,
	onRetry,
}: AllPracticesTableProps) {
	if (error != null) {
		return (
			<QueryErrorAlert error={error} title="Could not load your practices" onRetry={onRetry} />
		);
	}

	return (
		<PracticeTable
			aria-label="All practices"
			sort={sort}
			onSortChange={onSortChange}
			heads={
				<TableHead colSpan={2}>
					<span className="sr-only">Practices by standing</span>
				</TableHead>
			}
			subjectHead="Practice group"
			columns={5}
			rows={groups}
			rowKey={(group) => group.slug}
			renderRow={(group) => (
				<GroupRow
					group={group}
					standing={standings[group.slug]}
					practices={practicesByGroup[group.slug] ?? []}
					sentences={sentences[group.slug]}
					open={group.slug === openGroupSlug}
					onOpenGroup={onOpenGroup}
					onOpenPractice={onOpenPractice}
				/>
			)}
			empty={{
				icon: <ClipboardCheckIcon />,
				title: "No practices set up yet.",
				description:
					"Practice groups appear here once an admin sets up the practices this workspace reviews.",
			}}
			isLoading={isLoading}
			loadingRow={loadingRow}
		/>
	);
}
