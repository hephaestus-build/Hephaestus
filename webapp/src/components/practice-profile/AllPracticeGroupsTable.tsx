import { ClipboardCheckIcon } from "lucide-react";
import { Fragment } from "react";

import { cn } from "cn";
import type { GroupRef, PracticeGroupStanding, PracticeStanding } from "@/api/types.gen";
import type { LoadState } from "@/components/common/panel-state";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import type { FeedbackTextSegment } from "@/components/practice-vocabulary/feedback-text";
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

import { type FoldReason, FoldedPractices, FoldedStandingCell } from "./FoldedPractices";
import { groupNeverApplied, groupNotReviewedYet } from "./practice-folds";
import { ALL_PRACTICE_GROUPS, NO_PRACTICE_GROUPS } from "./practice-profile-search";

export interface AllPracticeGroupsTableProps {
	/** Already sorted by the caller under `sort`. */
	groups: GroupRef[];
	standings: Record<string, PracticeGroupStanding | undefined>;
	practicesByGroup: Record<string, PracticeStanding[] | undefined>;
	sentences: Record<string, FeedbackTextSegment[][] | undefined>;
	/** The table sorts by standing only; any other column leaves the Standing header unsorted. */
	sort: SortDirection;
	/** Called with the sort a press on the Standing header asks for. */
	onSortChange: (sort: SortDirection) => void;
	onOpenGroup?: (group: GroupRef) => void;
	/** The group whose detail level is open over the page; its row stays marked. */
	openGroupSlug?: string;
	/** Opens a practice named inside a group's sentence. */
	onOpenPractice?: (practiceSlug: string) => void;
	state: LoadState;
	/**
	 * Groups with no practice to show: none added, or all switched off. They join the fold of groups
	 * not reviewed yet, so the reader can still open them.
	 */
	emptyGroups?: GroupRef[];
}

interface GroupRowProps {
	group: GroupRef;
	standing: PracticeGroupStanding | undefined;
	practices: PracticeStanding[];
	sentences?: FeedbackTextSegment[][];
	/**
	 * A row of a fold: the standing badge alone, with no trend, ring or counts, since the fold
	 * already says why the group is there.
	 */
	folded?: FoldReason;
	open: boolean;
	onOpenGroup?: (group: GroupRef) => void;
	onOpenPractice?: (practiceSlug: string) => void;
}

function GroupRow({
	group,
	standing,
	practices,
	sentences = NO_SENTENCES,
	folded,
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
			{folded ? (
				<FoldedStandingCell scope="group" reason={folded} />
			) : (
				<StandingCell
					standing={standing?.standing ?? "NOT_OBSERVED"}
					direction={standing?.direction ?? undefined}
					support={standing?.trendSupport ?? undefined}
					scope="group"
					basis={standing?.basis}
				/>
			)}

			{/* A folded row keeps the cells, empty, so its subject stays in its column. */}
			<TableCell className={cn(folded && STACKED_HIDDEN)}>
				{!folded && <PracticeGroupStandingRing counts={counts} size="sm" />}
			</TableCell>

			<TableCell className={cn(folded && STACKED_HIDDEN)}>
				{!folded && (
					<StandingCountsList counts={counts} size="sm" aria-label="Practices by standing" />
				)}
			</TableCell>

			<SubjectCell
				badge={<GroupName name={group.name} icon={Icon} pill={pill} />}
				sentences={sentences}
				onOpenPractice={onOpenPractice}
				// Every practice a row's sentences name is in that row's group: its label shows the icon.
				practiceGroup={{ Icon, pill }}
			/>
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

/** An empty cell leaves the stacked layout, where it would only add a gap. */
const STACKED_HIDDEN = "@max-[38rem]:hidden";

const NO_GROUPS: GroupRef[] = [];

const HEADS = [{ label: <span className="sr-only">Practices by standing</span>, span: 2 }];

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
	emptyGroups = NO_GROUPS,
}: AllPracticeGroupsTableProps) {
	if (state.status === "error") {
		return (
			<QueryErrorAlert
				error={state.error}
				title="We could not load your practice groups"
				onRetry={state.onRetry}
			/>
		);
	}

	// Groups whose every practice never applied, and groups no review has reviewed a practice of
	// yet (the rest never applied), leave the table for one folded line each under it.
	const neverApplied = groups.filter((group) =>
		groupNeverApplied(practicesByGroup[group.slug] ?? []),
	);
	// A group with nothing in it has nothing to act on, so it is folded away with the groups nothing
	// reviewed yet rather than left out: it still opens.
	const notReviewed = [
		...groups.filter((group) => groupNotReviewedYet(practicesByGroup[group.slug] ?? [])),
		...emptyGroups,
	];
	const shown = groups.filter(
		(group) => !neverApplied.includes(group) && !notReviewed.includes(group),
	);
	// Every group with practices sits in a fold: the workspace has practices, so "No practices set
	// up yet" would be wrong. The folds stand open instead and their own sentences say why. Groups
	// with no practices do not count: with only those, nothing is set up to review.
	const onlyFolded =
		shown.length === 0 && neverApplied.length + notReviewed.length > emptyGroups.length;
	const renderGroupRow = (group: GroupRef, folded?: FoldReason) => (
		<GroupRow
			group={group}
			standing={standings[group.slug]}
			practices={practicesByGroup[group.slug] ?? []}
			// A folded group shows as any group without data: no sentence of what moved.
			sentences={folded ? undefined : sentences[group.slug]}
			folded={folded}
			open={group.slug === openGroupSlug}
			onOpenGroup={onOpenGroup}
			onOpenPractice={onOpenPractice}
		/>
	);
	// A fold: a toggle row and the folded rows, as sections of the table itself, so they share its
	// columns.
	const fold = (folded: GroupRef[], reason: FoldReason, columns: number) => (
		<FoldedPractices
			count={folded.length}
			noun="group"
			reason={reason}
			columns={columns}
			defaultOpen={onlyFolded}
		>
			{folded.map((group) => (
				<Fragment key={group.slug}>{renderGroupRow(group, reason)}</Fragment>
			))}
		</FoldedPractices>
	);

	return (
		<PracticeTable
			aria-label={ALL_PRACTICE_GROUPS}
			sort={sort}
			onSortChange={onSortChange}
			heads={HEADS}
			subjectHead="Practice group"
			rows={shown}
			rowKey={(group) => group.slug}
			renderRow={(group) => renderGroupRow(group)}
			empty={onlyFolded ? null : { icon: <ClipboardCheckIcon />, ...NO_PRACTICE_GROUPS }}
			isLoading={state.status === "loading"}
			loadingRow={loadingRow}
			sections={(columns) =>
				state.status === "loading" ? undefined : (
					<>
						{fold(neverApplied, "never-applied", columns)}
						{fold(notReviewed, "not-reviewed", columns)}
					</>
				)
			}
		/>
	);
}
