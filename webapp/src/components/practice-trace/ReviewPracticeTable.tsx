import { type ReactNode, useId, useState } from "react";

import { cn } from "cn";

import type { GroupRef, ResultList } from "@/api/types.gen";
import {
	AppliedFacetPills,
	listFacet,
} from "@/components/admin/practice-reviews/AppliedFacetPills";
import { FilterToolbar } from "@/components/common/FilterToolbar";
import { ResultCount } from "@/components/common/ResultCount";
import { SelectFilter } from "@/components/common/SelectFilter";
import { statusFacetOptions } from "@/components/common/status-def";
import { StatusBadge } from "@/components/common/StatusBadge";
import {
	getGroupVisual,
	UNASSIGNED_GROUP_VISUAL,
} from "@/components/practice-vocabulary/group-visuals";
import { GroupName } from "@/components/practice-vocabulary/GroupName";
import { type Outcome, OUTCOME_DEFS } from "@/components/practice-vocabulary/outcome-defs";
import { PracticePill } from "@/components/practice-vocabulary/PracticePill";
import { OPEN_ROW_BAR } from "@/components/practice-vocabulary/PracticeTable";
import { RESULT_MARK_DEFS } from "@/components/practice-vocabulary/result-mark-defs";
import { StatusLabel, StatusTooltip } from "@/components/practice-vocabulary/StatusTooltip";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Skeleton } from "@/components/ui/skeleton";
import {
	Table,
	TableBody,
	TableCell,
	TableHead,
	TableHeader,
	TableRow,
} from "@/components/ui/table";
import { nonEmpty } from "@/lib/search-params";
import { hasText } from "@/lib/text";

import { REVIEW_FILTER_MAX_LENGTH } from "./trace-format";

export interface ReviewPracticeFilters {
	group?: string;
	practice?: string;
	/** The outcomes the table keeps, or `undefined` for every outcome. */
	outcome?: Outcome[];
}

export interface ReviewPracticeTableProps {
	results: ResultList[];
	reviewId: string;
	groupOf: (practiceSlug: string) => GroupRef | undefined;
	filters: ReviewPracticeFilters;
	onFiltersChange: (filters: ReviewPracticeFilters) => void;
	onOpenPractice?: (practiceSlug: string) => void;
	emptyMessage: string;
	/** How a result reused from an earlier review links to it; the caller owns where reviews open. */
	earlierReviewLink?: (reviewId: string) => ReactNode;
	/**
	 * Link to this exact observation in its practice. With it, the Observation column holds only
	 * the link (and an admin's mark or an earlier review): the practice level shows the rest.
	 */
	observationLink?: (result: ResultList) => ReactNode;
	/**
	 * Each row's outcome as words, with a badge kept for an outcome that went wrong, and no tooltip
	 * stop: for a level that keeps its focus stops for what the reader acts on.
	 */
	plainState?: boolean;
	/**
	 * The observation open over this table's level, from the address, so its row is marked after a
	 * reload too; a press on a row's link marks that row as well.
	 */
	openedObservationId?: string;
}

const UNGROUPED = "Unassigned";

/** The outcomes as the admin Observations tab offers them, with their icons. */
const OUTCOME_OPTIONS = statusFacetOptions(OUTCOME_DEFS);

/**
 * The earlier review whose result `reviewId` reused, or `undefined` when this review recorded the
 * result itself. A reused result keeps the id of the review that recorded it.
 */
export function earlierReviewOf(
	result: Pick<ResultList, "reviewId">,
	reviewId: string,
): string | undefined {
	return result.reviewId === reviewId ? undefined : result.reviewId;
}

export function ReviewPracticeTable({
	results,
	reviewId,
	groupOf,
	filters,
	onFiltersChange,
	onOpenPractice,
	emptyMessage,
	earlierReviewLink,
	observationLink,
	plainState = false,
	openedObservationId,
}: ReviewPracticeTableProps) {
	const groupNameOf = (result: ResultList) => groupOf(result.practiceSlug)?.name ?? UNGROUPED;
	const groupNames = [...new Set(results.map(groupNameOf))].sort((left, right) =>
		left.localeCompare(right),
	);

	const needle = filters.practice?.trim().toLowerCase() ?? "";
	const rows = results.filter(
		(result) =>
			(!hasText(filters.group) || groupNameOf(result) === filters.group) &&
			(needle === "" || result.practiceName.toLowerCase().includes(needle)) &&
			(filters.outcome === undefined || filters.outcome.includes(result.outcome)),
	);
	const hasFilter = hasText(filters.group) || needle !== "" || filters.outcome !== undefined;
	const practiceFilterId = useId();
	// The observation the reader opened last: the level stays under the practice it opened, so its
	// row is still marked when the reader comes back.
	const [openedId, setOpenedId] = useState(openedObservationId);
	// An observation the address opens over the level, such as after a reload, takes the mark.
	const [addressed, setAddressed] = useState(openedObservationId);
	if (openedObservationId !== addressed) {
		setAddressed(openedObservationId);
		if (openedObservationId !== undefined) {
			setOpenedId(openedObservationId);
		}
	}
	// The admin Observations tab's Outcome facet, as a chip: the control and its pills on a phone.
	const outcomeFacet = listFacet("Outcome", OUTCOME_OPTIONS, filters.outcome, (values) =>
		onFiltersChange({ ...filters, outcome: nonEmpty(values) }),
	);

	return (
		<div className="flex min-w-0 flex-col gap-3">
			<FilterToolbar hasFilter={hasFilter} onReset={() => onFiltersChange({})}>
				{outcomeFacet.control}
				<SelectFilter
					label="Group"
					allLabel="All groups"
					options={groupNames.map((group) => ({ value: group, label: group }))}
					value={filters.group}
					onChange={(group) => onFiltersChange({ ...filters, group })}
				/>
				<div className="flex min-w-0 items-center gap-2">
					<Label htmlFor={practiceFilterId} className="shrink-0 text-muted-foreground">
						Practice
					</Label>
					<Input
						id={practiceFilterId}
						value={filters.practice ?? ""}
						maxLength={REVIEW_FILTER_MAX_LENGTH}
						placeholder="Filter by name"
						className="w-56 max-w-full"
						onChange={(event) =>
							onFiltersChange({
								...filters,
								practice: event.target.value === "" ? undefined : event.target.value,
							})
						}
					/>
				</div>
			</FilterToolbar>
			<AppliedFacetPills pills={outcomeFacet.pills} />
			{hasFilter && rows.length !== results.length && (
				<ResultCount total={rows.length} noun={["observation", "observations"]} hasFilter />
			)}
			<PracticesFrame>
				{rows.length === 0 && (
					<TableBody>
						<TableRow variant="static">
							<TableCell colSpan={COLUMN_COUNT} className="p-4 whitespace-normal">
								<p className="text-sm text-muted-foreground">
									{results.length > 0 ? "No observations match your filters." : emptyMessage}
								</p>
							</TableCell>
						</TableRow>
					</TableBody>
				)}
				{/* One body per practice group under a gray heading that names the group, as the reviews
				    list heads its days: a row names only its practice. */}
				{byGroup(rows, groupOf).map(({ name, group, rows: groupRows }) => {
					const visual =
						group === undefined ? UNASSIGNED_GROUP_VISUAL : getGroupVisual(group.icon, group.color);
					return (
						<TableBody key={name}>
							<TableRow variant="static">
								{/* `border-t`: a body's last row drops its border, so the line above a group is
								    the heading's. */}
								<th
									scope="rowgroup"
									colSpan={COLUMN_COUNT}
									className="border-t bg-sidebar px-2 py-1.5 text-left text-xs font-medium text-muted-foreground"
								>
									{/* The group's name at the size a feedback card's head names it (text-sm), not the
									    row heading's small print. */}
									<GroupName
										name={name}
										icon={visual.Icon}
										pill={group === undefined ? undefined : visual.pill}
										className="text-sm"
									/>
								</th>
							</TableRow>
							{groupRows.map((result) => (
								<ResultRow
									key={result.id}
									result={result}
									reviewId={reviewId}
									onOpenPractice={onOpenPractice}
									earlierReviewLink={earlierReviewLink}
									observationLink={observationLink}
									plainState={plainState}
									marked={result.id === openedId}
									onOpened={() => setOpenedId(result.id)}
								/>
							))}
						</TableBody>
					);
				})}
			</PracticesFrame>
		</div>
	);
}

/** The table's columns: Outcome, Practice and the row's link. */
const COLUMN_COUNT = 3;

interface ResultGroup {
	name: string;
	group: GroupRef | undefined;
	rows: ResultList[];
}

/**
 * The rows by practice group, the groups by name and the practices with no group last; each group
 * keeps the rows in the order given.
 */
function byGroup(
	rows: readonly ResultList[],
	groupOf: (practiceSlug: string) => GroupRef | undefined,
): ResultGroup[] {
	const groups = new Map<string, ResultGroup>();
	for (const result of rows) {
		const group = groupOf(result.practiceSlug);
		const name = group?.name ?? UNGROUPED;
		const known = groups.get(name);
		if (known === undefined) {
			groups.set(name, { name, group, rows: [result] });
		} else {
			known.rows.push(result);
		}
	}
	return [...groups.values()].sort(
		(left, right) =>
			Number(left.group === undefined) - Number(right.group === undefined) ||
			left.name.localeCompare(right.name),
	);
}

interface ResultRowProps {
	result: ResultList;
	reviewId: string;
	onOpenPractice?: (practiceSlug: string) => void;
	earlierReviewLink?: (reviewId: string) => ReactNode;
	observationLink?: (result: ResultList) => ReactNode;
	plainState: boolean;
	/**
	 * The row of the observation the reader opened last: marked by the accent bar on its leading edge
	 * alone, as a tint under the outcome badges would cost them their contrast.
	 */
	marked: boolean;
	/** Called when the row's observation is opened, from the link or the row. */
	onOpened: () => void;
}

function ResultRow({
	result,
	reviewId,
	onOpenPractice,
	earlierReviewLink,
	observationLink,
	plainState,
	marked,
	onOpened,
}: ResultRowProps) {
	const earlierReview = earlierReviewOf(result, reviewId);
	return (
		// The row's hover lights its "Open observation" link, as a record row lights its own.
		<TableRow
			aria-current={marked || undefined}
			className={cn("group/row", observationLink !== undefined && "cursor-pointer")}
			// Every way to the observation passes through its link, the row's forwarded press too.
			onClickCapture={(event) => {
				if (
					event.target instanceof Element &&
					event.target.closest("[data-observation-link] a") !== null
				) {
					onOpened();
				}
			}}
			// A press anywhere on the row opens its observation, as its link does. The link stays the
			// keyboard's way; a press on another control is that control's.
			onClick={
				observationLink === undefined
					? undefined
					: (event) => {
							if (
								event.target instanceof Element &&
								event.target.closest("a, button, input, select") !== null
							) {
								return;
							}
							event.currentTarget.querySelector<HTMLElement>("[data-observation-link] a")?.click();
						}
			}
		>
			{/* The first cell carries the accent bar of the row the reader came back from. */}
			<TableCell className={cn(OPEN_ROW_BAR, "align-middle whitespace-normal")}>
				{/* An admin's mark belongs with the outcome it qualifies. */}
				<span className="flex flex-col items-start gap-1">
					<ResultOutcome outcome={result.outcome} plain={plainState} />
					{result.markedIncorrectAt !== null && <StatusBadge def={RESULT_MARK_DEFS.INCORRECT} />}
				</span>
			</TableCell>
			<TableCell className="align-middle whitespace-normal">
				<span className="flex min-w-0 flex-col items-start gap-1">
					{/* The group heads the rows above, so the row names the practice alone. */}
					<PracticePill
						name={result.practiceName}
						onOpen={
							onOpenPractice === undefined ? undefined : () => onOpenPractice(result.practiceSlug)
						}
					/>
					{/* Where the observation came from: a review before this one recorded it. */}
					{earlierReview !== undefined && earlierReviewLink?.(earlierReview)}
				</span>
			</TableCell>
			<TableCell className="text-right align-middle whitespace-normal">
				<span className="ml-auto flex max-w-lg min-w-0 flex-col items-end gap-1 text-sm text-pretty">
					{/* Where the observation opens in its practice, the link is the cell: the practice level
					    shows its words, problems and next steps. */}
					{observationLink === undefined && (
						<>
							{hasText(result.summary) && (
								<span className="font-medium">{result.summary.trim()}</span>
							)}
							{result.problems.length > 0 && (
								<span className="text-muted-foreground">
									{`${result.problems.length} ${result.problems.length === 1 ? "problem" : "problems"}`}
								</span>
							)}
						</>
					)}
					{observationLink !== undefined && (
						<span data-observation-link>{observationLink(result)}</span>
					)}
				</span>
			</TableCell>
		</TableRow>
	);
}

function ResultOutcome({ outcome, plain }: { outcome: Outcome; plain: boolean }) {
	const def = OUTCOME_DEFS[outcome];
	if (plain) {
		return def.badgeVariant === "destructive" || def.badgeVariant === "warning" ? (
			<StatusBadge def={def} />
		) : (
			<StatusLabel def={def} />
		);
	}
	return (
		<StatusTooltip
			def={def}
			render={<button type="button" aria-label={def.label} />}
			className="cursor-help"
		>
			<StatusBadge def={def} />
		</StatusTooltip>
	);
}

const TABLE_LABEL = "Every practice in this review";

function PracticesFrame({
	busy = false,
	children,
}: {
	busy?: boolean;
	/** One `TableBody` per group, or the placeholder rows' body. */
	children: ReactNode;
}) {
	return (
		<div className="shrink-0 overflow-hidden rounded-xl border bg-background">
			<Table aria-label={TABLE_LABEL} aria-busy={busy || undefined} className="min-w-152">
				<TableHeader>
					<TableRow>
						{/* In the review's order: the Outcome filter above narrows the rows instead. */}
						<TableHead scope="col" className="w-40">
							Outcome
						</TableHead>
						<TableHead scope="col" className="w-auto">
							Practice
						</TableHead>
						{/* The row's own link stands here, so the head names it for a screen reader only. */}
						<TableHead scope="col" className="text-right">
							<span className="sr-only">Observation</span>
						</TableHead>
					</TableRow>
				</TableHeader>
				{children}
			</Table>
		</div>
	);
}

export function ReviewPracticeTableSkeleton({ rows }: { rows: number }) {
	return (
		<PracticesFrame busy>
			<TableBody>
				{Array.from({ length: rows }, (_, index) => (
					<TableRow key={index} variant="static" aria-hidden>
						<TableCell className="align-middle">
							<Skeleton className="h-5 w-20" />
						</TableCell>
						<TableCell className="align-middle">
							<Skeleton className="h-5 w-40" />
						</TableCell>
						<TableCell className="align-middle">
							<Skeleton className="h-4 w-64" />
						</TableCell>
					</TableRow>
				))}
			</TableBody>
		</PracticesFrame>
	);
}
