import { ClipboardCheckIcon } from "lucide-react";
import { Fragment, type ReactNode, useState } from "react";

import { cn } from "cn";

import type { GroupRef, PracticeGroupStanding, PracticeStanding } from "@/api/types.gen";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import type { LevelPath } from "@/components/layout/detail-drawer/DetailPath";
import { LevelHeader } from "@/components/layout/detail-drawer/LevelHeader";
import { Section } from "@/components/layout/Section";
import { count, type FeedbackTextSegment } from "@/components/practice-vocabulary/feedback-text";
import {
	getGroupVisual,
	groupMark,
	UNASSIGNED_GROUP_VISUAL,
} from "@/components/practice-vocabulary/group-visuals";
import {
	DEFAULT_PRACTICE_GROUP_SORT,
	sortByStanding,
} from "@/components/practice-vocabulary/practice-group-list-order";
import { PracticePill } from "@/components/practice-vocabulary/PracticePill";
import {
	PracticeTable,
	PracticeTableRow,
	StandingCell,
	SubjectCell,
} from "@/components/practice-vocabulary/PracticeTable";
import { countPracticeStandings } from "@/components/practice-vocabulary/standing-counts";
import { StandingBadge, TrendNote } from "@/components/practice-vocabulary/StandingBadge";
import { StandingBasisNote } from "@/components/practice-vocabulary/StandingBasisNote";
import { StandingSummaryBox } from "@/components/practice-vocabulary/StandingSummaryBox";
import { DrawerBody } from "@/components/ui/drawer";
import { Separator } from "@/components/ui/separator";
import { Skeleton } from "@/components/ui/skeleton";
import { TableCell } from "@/components/ui/table";
import { capitalise, hasText } from "@/lib/text";

import {
	type FoldReason,
	FoldedPractices,
	FoldedStandingCell,
	NeverAppliedBadge,
} from "./FoldedPractices";
import { groupNeverApplied, splitFoldedPractices } from "./practice-folds";
import type { PracticePattern } from "./practice-pattern";
import { GroupWithoutPractices, LevelLead, NoSuchGroup } from "./practice-profile-blocks";
import { PracticePatternLine } from "./PracticePatternLine";

export interface PracticeGroupDetailLevelProps {
	nested?: boolean;
	/** Where the level sits, from the drawer. */
	path: LevelPath;
	group?: GroupRef;
	standing?: PracticeGroupStanding;
	/** The group's practices, as the standings carry them; left out while they load. */
	practices?: PracticeStanding[];
	/**
	 * One sentence per practice under its pill, keyed by slug; a practice without one shows only
	 * the pill.
	 */
	practiceSentences?: Record<string, FeedbackTextSegment[][] | undefined>;
	/** Opens the practice's own level over this one. */
	onOpenPractice?: (practiceSlug: string) => void;
	/** The practice whose level is open over this one; its row stays marked. */
	openPracticeSlug?: string;
	isLoading: boolean;
	error?: unknown;
	onRetry?: () => void;
	/** Each practice's pattern from its open card, keyed by slug, as a label beside its name. */
	patterns?: Record<string, PracticePattern | undefined>;
}

// A group can hold practices that only people review, which never reach this table, so the words
// claim no reviewed practice rather than no practice at all.
const NO_PRACTICES_YET = {
	icon: <ClipboardCheckIcon />,
	title: "No reviewed practices yet",
	description: "Practices in this group appear here once reviews of your work check them.",
};

const NO_PATTERNS: Record<string, PracticePattern | undefined> = {};

const practiceCountLabel = (n: number) =>
	capitalise(`${count(n, "practice", "practices")} in this group`);

/**
 * A practice row's shape while the practices load: the badge and chip, the pill and its sentence,
 * the link.
 */
const loadingRow = (
	<>
		<TableCell>
			<div className="flex flex-col items-start gap-1.5">
				<Skeleton className="h-5 w-32 rounded-full" />
				<Skeleton className="h-5 w-40" />
			</div>
		</TableCell>
		<TableCell>
			<div className="flex flex-col items-start gap-2">
				<Skeleton className="h-5 w-56 rounded-full" />
				<Skeleton className="h-5 w-72 max-w-full" />
			</div>
		</TableCell>
		<TableCell>
			<Skeleton className="ml-auto h-5 w-24" />
		</TableCell>
	</>
);

/** Each practice row carries the group's icon in its color, so a row reads as part of this group. */
function rowGroupMark(group?: GroupRef) {
	return group === undefined ? undefined : groupMark(getGroupVisual(group.icon, group.color));
}

const NO_SENTENCES: NonNullable<PracticeGroupDetailLevelProps["practiceSentences"]> = {};

export function PracticeGroupDetailLevel({
	nested,
	path,
	group,
	standing,
	practices,
	practiceSentences = NO_SENTENCES,
	onOpenPractice,
	openPracticeSlug,
	isLoading,
	error,
	onRetry,
	patterns = NO_PATTERNS,
}: PracticeGroupDetailLevelProps) {
	const practiceCount = practices?.length ?? 0;
	// A group with no practices has no standing to name and nothing to count.
	const showsStanding = group !== undefined && (isLoading || practiceCount > 0);
	const counts = countPracticeStandings(practices ?? []);
	const rowMark = rowGroupMark(group);
	// The group's color without its pill's ground, as `GroupName` draws it; muted for no group.
	const heading = rowMark ?? groupMark(UNASSIGNED_GROUP_VISUAL);
	// The table's sort is this level's alone: it is not a place a reader returns to by URL.
	const [sort, setSort] = useState(DEFAULT_PRACTICE_GROUP_SORT);
	// Practices that never applied, and the ones no review has reviewed yet, leave the table for one
	// folded line each under it.
	const { shown: applied, neverApplied, notReviewed } = splitFoldedPractices(practices ?? []);
	// Every practice of the group sits in a fold: the group has practices, so "No practices yet"
	// would be wrong. The folds stand open instead and their own sentences say why.
	const onlyFolded = applied.length === 0 && neverApplied.length + notReviewed.length > 0;
	const byStanding = (rows: readonly PracticeStanding[]) =>
		sortByStanding(
			rows,
			sort,
			(practice) => practice.standing,
			(left, right) => left.name.localeCompare(right.name),
		);
	// A folded practice shows as any practice without data: its standing alone, no pattern and no
	// sentence of what moved.
	const renderPracticeRow = (practice: PracticeStanding, folded?: FoldReason) => {
		const pattern = folded ? undefined : patterns[practice.slug];
		const sentences = folded ? undefined : practiceSentences[practice.slug];
		return (
			<PracticeTableRow
				open={practice.slug === openPracticeSlug}
				link={
					onOpenPractice && {
						text: "Open practice",
						name: practice.name,
						onOpen: () => onOpenPractice(practice.slug),
					}
				}
			>
				{folded ? (
					<FoldedStandingCell scope="practice" reason={folded} />
				) : (
					<StandingCell
						standing={practice.standing}
						direction={practice.direction ?? undefined}
						support={practice.trendSupport ?? undefined}
						scope="practice"
						basis={practice.basis}
					/>
				)}
				<SubjectCell
					badge={
						pattern ? (
							// The pattern on the first line of the row, beside the name and level with
							// the standing, so the two columns open on the same fact.
							<span className="flex flex-wrap items-center gap-2">
								<PracticePill name={practice.name} group={rowMark} />
								<PracticePatternLine pattern={pattern} />
							</span>
						) : (
							<PracticePill name={practice.name} group={rowMark} />
						)
					}
					sentences={sentences}
				/>
			</PracticeTableRow>
		);
	};
	// A fold: a toggle row and the folded rows, as sections of the table itself, so they share its
	// columns.
	const fold = (folded: readonly PracticeStanding[], reason: FoldReason, columns: number) => (
		<FoldedPractices
			count={folded.length}
			noun="practice"
			reason={reason}
			columns={columns}
			defaultOpen={onlyFolded}
		>
			{byStanding(folded).map((practice) => (
				<Fragment key={practice.slug}>{renderPracticeRow(practice, reason)}</Fragment>
			))}
		</FoldedPractices>
	);
	// One table for both states: while loading it draws its rows' shape, so the level does not
	// jump when they land.
	const practicesTable = (
		<PracticeTable
			aria-label="Practices in this group"
			sort={sort}
			onSortChange={setSort}
			subjectHead="Practice"
			rows={byStanding(applied)}
			rowKey={(practice) => practice.slug}
			renderRow={(practice) => renderPracticeRow(practice)}
			empty={onlyFolded ? null : NO_PRACTICES_YET}
			isLoading={isLoading}
			loadingRow={loadingRow}
			sections={(columns) =>
				isLoading ? undefined : (
					<>
						{fold(neverApplied, "never-applied", columns)}
						{fold(notReviewed, "not-reviewed", columns)}
					</>
				)
			}
		/>
	);

	let body: ReactNode;
	if (isLoading) {
		body =
			// The practices table, as it will be laid out.
			practicesTable;
	} else if (error != null) {
		body = (
			<QueryErrorAlert
				error={error}
				title={
					group
						? `We could not load your standing for ${group.name}`
						: "We could not load this practice group"
				}
				onRetry={onRetry}
			/>
		);
	} else if (group && practiceCount === 0) {
		// The page folds such a group; its row or a link to it lands here rather than on an empty table.
		body = <GroupWithoutPractices />;
	} else if (group) {
		body = (
			<Section
				size="lg"
				title="Practices in this group"
				description="Each practice with its standing and trend. Open one for the work behind it."
			>
				{practicesTable}
			</Section>
		);
	} else {
		body = <NoSuchGroup />;
	}

	return (
		<>
			<LevelHeader
				nested={nested}
				path={path}
				current="Group"
				// As the group is named in the All practice groups table, its icon and name in its
				// color, at the heading's size.
				title={<span className={heading.ink}>{group?.name ?? "Practice group"}</span>}
				mark={<heading.Icon aria-hidden className={cn("size-6 shrink-0", heading.ink)} />}
				chips={
					showsStanding &&
					// A group whose every practice never applied reads as its folded row does.
					(groupNeverApplied(practices ?? []) ? (
						<NeverAppliedBadge scope="group" />
					) : (
						<>
							<StandingBadge
								standing={standing?.standing ?? "NOT_OBSERVED"}
								scope="group"
								support={standing?.trendSupport ?? undefined}
							/>
							<TrendNote
								direction={standing?.direction ?? undefined}
								support={standing?.trendSupport ?? undefined}
								scope="group"
							/>
							<StandingBasisNote basis={standing?.basis} />
						</>
					))
				}
				aside={
					showsStanding ? (
						<StandingSummaryBox
							label={practiceCountLabel(practiceCount)}
							counts={counts}
							isLoading={isLoading}
							className="w-full sm:w-auto"
						/>
					) : undefined
				}
			/>
			<DrawerBody className="flex flex-col gap-6 pt-2">
				{/* The line under the header, with the room on each side that the blocks below keep
				    between them. */}
				<Separator />
				{hasText(standing?.groupDescription) && <LevelLead>{standing.groupDescription}</LevelLead>}
				{body}
			</DrawerBody>
		</>
	);
}
