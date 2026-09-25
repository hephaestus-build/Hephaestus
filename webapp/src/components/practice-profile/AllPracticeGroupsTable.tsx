import { ClipboardCheckIcon } from "lucide-react";

import type { PracticeGroup, PracticeGroupStanding, PracticeStanding } from "@/api/types.gen";
import type { LoadState } from "@/components/common/panel-state";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import type { FeedbackTextSegment } from "@/components/practice-vocabulary/feedback-text";
import { FeedbackText } from "@/components/practice-vocabulary/FeedbackText";
import { getGroupVisual } from "@/components/practice-vocabulary/group-visuals";
import { GroupName } from "@/components/practice-vocabulary/GroupName";
import type { SortDirection } from "@/components/practice-vocabulary/practice-group-list-order";
import { PracticeGroupStandingRing } from "@/components/practice-vocabulary/PracticeGroupStandingRing";
import {
	PracticeTable,
	PracticeTableRow,
	StandingCell,
	SubjectCell,
} from "@/components/practice-vocabulary/PracticeTable";
import { countPracticeStandings } from "@/components/practice-vocabulary/standing-counts";
import { StandingCountsList } from "@/components/practice-vocabulary/StandingCountsList";
import { Skeleton } from "@/components/ui/skeleton";
import { TableCell } from "@/components/ui/table";

import { ALL_PRACTICE_GROUPS } from "./practice-profile-search";

export interface AllPracticeGroupsTableProps {
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
	sort: SortDirection;
	/** Called with the sort a press on the Standing header asks for. */
	onSortChange: (sort: SortDirection) => void;
	onOpenGroup?: (group: PracticeGroup) => void;
	/** The group whose detail level is open over the page; its row stays marked. */
	openGroupSlug?: string;
	/** Opens a practice named inside a group's sentence. */
	onOpenPractice?: (practiceSlug: string) => void;
	state: LoadState;
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
		<PracticeTableRow
			open={open}
			link={
				onOpenGroup && {
					text: "Open group",
					name: group.name,
					onOpen: () => onOpenGroup(group),
				}
			}
		>
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
					<ul className="max-w-md list-disc space-y-1 pl-4 text-sm marker:text-muted-foreground">
						{sentences.map((sentence, index) => (
							<li key={index}>
								<FeedbackText segments={sentence} onOpenPractice={onOpenPractice} />
							</li>
						))}
					</ul>
				)}
			</SubjectCell>
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

const NO_SENTENCES: FeedbackTextSegment[][] = [];

/**
 * Every practice group as one row: its standing and trend, a ring of its practices by standing
 * with the counts spelt out beside it, then the group and what moved inside it, one bullet per
 * move with every practice that made it as a pill. The Standing header is the table's one sort; a
 * row opens its group through the "Open group" at its end, and a practice's pill opens the
 * practice.
 */
export function AllPracticeGroupsTable({
	groups,
	standings,
	practicesByGroup,
	sentences,
	sort,
	onSortChange,
	onOpenGroup,
	openGroupSlug,
	onOpenPractice,
	state,
}: AllPracticeGroupsTableProps) {
	if (state.status === "error") {
		return (
			<QueryErrorAlert
				error={state.error}
				title="Could not load your practice groups"
				onRetry={state.onRetry}
			/>
		);
	}

	return (
		<PracticeTable
			aria-label={ALL_PRACTICE_GROUPS}
			sort={sort}
			onSortChange={onSortChange}
			heads={[{ label: <span className="sr-only">Practices by standing</span>, span: 2 }]}
			subjectHead="Practice group"
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
			isLoading={state.status === "loading"}
			loadingRow={loadingRow}
		/>
	);
}
