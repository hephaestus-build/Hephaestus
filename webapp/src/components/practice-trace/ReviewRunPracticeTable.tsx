import { Link } from "@tanstack/react-router";
import { ArrowRightIcon, ScanSearchIcon } from "lucide-react";
import { type ReactNode, useId } from "react";

import type {
	ObservationDetail,
	PracticeGroup,
	PracticeTraceEntry,
	PrecomputeRun,
	TracedSignal,
} from "@/api/types.gen";
import { reviewLevel } from "@/components/admin/practice-reviews/review-levels";
import { FilterToolbar } from "@/components/common/FilterToolbar";
import { InlineLink } from "@/components/common/InlineLink";
import { ResultCount } from "@/components/common/ResultCount";
import { SelectFilter } from "@/components/common/SelectFilter";
import { StatusBadge } from "@/components/common/StatusBadge";
import { detailSearch } from "@/components/layout/detail-drawer/detail-stack";
import { AutonomyBadge } from "@/components/practice-vocabulary/AutonomyBadge";
import {
	getGroupVisual,
	UNASSIGNED_GROUP_VISUAL,
} from "@/components/practice-vocabulary/group-visuals";
import { GroupName } from "@/components/practice-vocabulary/GroupName";
import { MARKED_INCORRECT_DEF } from "@/components/practice-vocabulary/observation-invalidation-defs";
import { PracticePill } from "@/components/practice-vocabulary/PracticePill";
import {
	precomputeFix,
	precomputeRunSentence,
} from "@/components/practice-vocabulary/precompute-run-status-defs";
import { StatusTooltip } from "@/components/practice-vocabulary/StatusTooltip";
import { TRACE_OUTCOME_DEFS } from "@/components/practice-vocabulary/trace-outcome-defs";
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
import { hasText } from "@/lib/text";

import { PrecomputeFixLink } from "./PrecomputeFixLink";
import { deliveryLabel, REVIEW_FILTER_MAX_LENGTH } from "./trace-format";
import { TraceOutcomeBadge } from "./TraceOutcomeBadge";

export interface ReviewRunPracticeFilters {
	/** The practice group's name, as the trace spells it. */
	group?: string;
	/** Part of a practice's name, matched case-insensitively. */
	practice?: string;
	/** One signal name, keeping the practices that watch it. */
	watches?: string;
}

export interface ReviewRunPracticeTableProps {
	/** Where an admin's fix links lead, such as the AI models page for a missing precompute model. */
	workspaceSlug: string;
	/** Each practice's answer on the work: one review's, or the latest across every review of it. */
	entries: PracticeTraceEntry[];
	/** The occurrences this work's activity carries, so "Rests on" can name one. */
	signals: TracedSignal[];
	/**
	 * What the review observed about the reader, by practice slug; one practice may have several.
	 * Absent, each row reads the recorded explanation.
	 */
	observationsByPractice?: Record<string, ObservationDetail[] | undefined>;
	/** For the icon and colour a group's name is drawn in; the entry carries the name itself. */
	groups: PracticeGroup[];
	filters: ReviewRunPracticeFilters;
	onFiltersChange: (filters: ReviewRunPracticeFilters) => void;
	/** Opens a practice's own level from its pill. */
	onOpenPractice: (practiceSlug: string) => void;
	/** Shows one occurrence on the neighbouring tab, which is where the timeline is. */
	onShowOccurrence: (signalId: string) => void;
	/** Admins also read the delivery sentence, the autonomy and what the answer rests on. */
	canAdminister?: boolean;
	/** Why there is no practice at all to list, which only the caller can say. */
	emptyMessage: string;
	/**
	 * The review the table lists, when it lists one: its rows then speak for that review, and a row
	 * answered by an earlier review says so. Absent, the rows speak for every review of the work.
	 */
	reviewId?: string;
	/** How a row answered by an earlier review links to it; the caller owns where reviews open. */
	earlierReviewLink?: (reviewId: string) => ReactNode;
	/**
	 * How a precompute line's *Details* opens its review, where the table sits in a stack that a
	 * review opens over. The label carries a trailing icon, so the link lays it out inline. Absent,
	 * it opens the review on Practice reviews.
	 */
	reviewLink?: (reviewId: string, label: ReactNode) => ReactNode;
}

/** Practices the workspace files in no group are still listed, under a name of their own. */
const UNGROUPED = "Other practices";

const NO_OBSERVATIONS: ObservationDetail[] = [];

/**
 * The practices this review decided, in the trace's order. The trace is asked for this review, which
 * still lists the practices no review speaks for — turned off, never occasioned — without its id. A
 * practice it left to an earlier review's answer names that review instead, so it belongs here through
 * the occurrence this review started.
 */
export function runPractices(
	entries: PracticeTraceEntry[],
	signals: TracedSignal[],
	reviewId: string | undefined,
): PracticeTraceEntry[] {
	if (reviewId === undefined) {
		return [];
	}
	const startedHere = new Set(
		signals.filter((signal) => signal.reviewId === reviewId).map((signal) => signal.id),
	);
	return entries.filter(
		(entry) =>
			entry.reviewId === reviewId ||
			(hasText(entry.reviewId) &&
				hasText(entry.occasionedById) &&
				startedHere.has(entry.occasionedById)),
	);
}

/**
 * The earlier review whose answer `reviewId` reused for the entry, or `undefined` when this review
 * assessed the practice itself, did not reach it, or the entry names no review.
 */
export function earlierReviewOf(
	entry: PracticeTraceEntry,
	reviewId: string | undefined,
): string | undefined {
	return reviewId !== undefined &&
		entry.outcome === "REVIEWED" &&
		hasText(entry.reviewId) &&
		entry.reviewId !== reviewId
		? entry.reviewId
		: undefined;
}

function groupNameOf(entry: PracticeTraceEntry): string {
	return hasText(entry.groupName) ? entry.groupName : UNGROUPED;
}

/**
 * Every practice's answer on one piece of work: its outcome, and the observations' own words where
 * it said something about the reader, or the recorded reason where it did not.
 */
export function ReviewRunPracticeTable({
	workspaceSlug,
	entries,
	signals,
	observationsByPractice,
	groups,
	filters,
	onFiltersChange,
	onOpenPractice,
	onShowOccurrence,
	canAdminister = false,
	emptyMessage,
	reviewId,
	earlierReviewLink,
	reviewLink,
}: ReviewRunPracticeTableProps) {
	// From the entries, not `groups`: that holds only the groups the reader has a standing in.
	const groupNames = [...new Set(entries.map(groupNameOf))].sort((left, right) =>
		left.localeCompare(right),
	);
	const groupBySlug = new Map(groups.map((group) => [group.slug, group]));
	// Each entry carries the words for what it watches, so a moment never raised on this work is named too.
	const moments = [
		...new Map(
			entries.flatMap((entry) =>
				entry.watches.map((watched) => [watched.signal, watched.displayName] as const),
			),
		),
	].sort(([, left], [, right]) => left.localeCompare(right));
	// A moment named in the address that no practice here watches — a hand-edited or stale link — is
	// no filter at all, rather than one the select would have to show by its wire name.
	const watches = moments.some(([signal]) => signal === filters.watches)
		? filters.watches
		: undefined;

	const needle = filters.practice?.trim().toLowerCase() ?? "";
	const rows = entries.filter((entry) => {
		if (hasText(filters.group) && groupNameOf(entry) !== filters.group) {
			return false;
		}
		if (hasText(watches) && !entry.watches.some((watched) => watched.signal === watches)) {
			return false;
		}
		return needle === "" || entry.practiceName.toLowerCase().includes(needle);
	});
	const hasFilter = hasText(filters.group) || hasText(watches) || needle !== "";
	const practiceFilterId = useId();

	return (
		<div className="flex min-w-0 flex-col gap-3">
			<FilterToolbar hasFilter={hasFilter} onReset={() => onFiltersChange({})}>
				<SelectFilter
					label="Group"
					allLabel="All groups"
					options={groupNames.map((group) => ({ value: group, label: group }))}
					value={filters.group}
					onChange={(group) => onFiltersChange({ ...filters, group })}
				/>
				<SelectFilter
					label="Reviews when"
					allLabel="Any moment"
					options={moments.map(([value, label]) => ({ value, label }))}
					value={watches}
					onChange={(next) => onFiltersChange({ ...filters, watches: next })}
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
			{hasFilter && rows.length !== entries.length && (
				<ResultCount total={rows.length} noun={["practice", "practices"]} hasFilter />
			)}
			<PracticesFrame>
				{rows.length === 0 && (
					<TableRow variant="static">
						<TableCell colSpan={3} className="p-4 whitespace-normal">
							<p className="text-sm text-muted-foreground">
								{entries.length > 0 ? "No practices match your filters." : emptyMessage}
							</p>
						</TableCell>
					</TableRow>
				)}
				{rows.map((entry) => {
					const group = hasText(entry.groupSlug) ? groupBySlug.get(entry.groupSlug) : undefined;
					const visual =
						group === undefined ? UNASSIGNED_GROUP_VISUAL : getGroupVisual(group.icon, group.color);
					return (
						<TableRow key={entry.practiceSlug}>
							<TableCell className="align-top whitespace-normal">
								<StatusTooltip
									def={TRACE_OUTCOME_DEFS[entry.outcome]}
									render={
										<button type="button" aria-label={TRACE_OUTCOME_DEFS[entry.outcome].label} />
									}
									className="cursor-help"
								>
									<TraceOutcomeBadge outcome={entry.outcome} />
								</StatusTooltip>
							</TableCell>
							<TableCell className="align-top whitespace-normal">
								<span className="flex min-w-0 flex-col items-start gap-1">
									<PracticePill
										name={entry.practiceName}
										onOpen={() => onOpenPractice(entry.practiceSlug)}
									/>
									<GroupName
										name={groupNameOf(entry)}
										icon={visual.Icon}
										pill={group === undefined ? undefined : visual.pill}
										className="text-xs"
									/>
								</span>
							</TableCell>
							<TableCell className="align-top whitespace-normal">
								<WhatItSaw
									workspaceSlug={workspaceSlug}
									entry={entry}
									observations={observationsByPractice?.[entry.practiceSlug] ?? NO_OBSERVATIONS}
									occurrenceName={
										hasText(entry.occasionedById)
											? (signals.find((signal) => signal.id === entry.occasionedById)
													?.displayName ?? entry.occasionedBy?.displayName)
											: undefined
									}
									onShowOccurrence={onShowOccurrence}
									canAdminister={canAdminister}
									earlierReview={earlierReviewOf(entry, reviewId)}
									earlierReviewLink={earlierReviewLink}
									reviewLink={reviewLink}
								/>
							</TableCell>
						</TableRow>
					);
				})}
			</PracticesFrame>
		</div>
	);
}

function PracticesFrame({ busy = false, children }: { busy?: boolean; children: ReactNode }) {
	return (
		<div className="shrink-0 overflow-hidden rounded-xl border bg-background">
			<Table
				aria-label="Every practice on this work"
				aria-busy={busy || undefined}
				className="min-w-152"
			>
				<TableHeader>
					<TableRow>
						<TableHead className="w-40">State</TableHead>
						<TableHead className="w-64">Practice</TableHead>
						<TableHead>What it saw</TableHead>
					</TableRow>
				</TableHeader>
				<TableBody>{children}</TableBody>
			</Table>
		</div>
	);
}

export function ReviewRunPracticeTableSkeleton({ rows }: { rows: number }) {
	return (
		<PracticesFrame busy>
			{Array.from({ length: rows }, (_, index) => (
				<TableRow key={index} variant="static" aria-hidden>
					<TableCell className="align-top">
						<Skeleton className="h-5 w-20" />
					</TableCell>
					<TableCell className="align-top">
						<Skeleton className="h-5 w-40" />
					</TableCell>
					<TableCell className="align-top">
						<Skeleton className="h-4 w-64" />
					</TableCell>
				</TableRow>
			))}
		</PracticesFrame>
	);
}

interface WhatItSawProps {
	workspaceSlug: string;
	entry: PracticeTraceEntry;
	observations: ObservationDetail[];
	occurrenceName: string | undefined;
	onShowOccurrence: (signalId: string) => void;
	canAdminister: boolean;
	earlierReview: string | undefined;
	earlierReviewLink: ((reviewId: string) => ReactNode) | undefined;
	reviewLink: ((reviewId: string, label: ReactNode) => ReactNode) | undefined;
}

/**
 * One line per observation about the reader, or the recorded reason where there is none. The
 * catalog's words on the practice never stand in: they say what the practice is about, not what
 * this review made of this work. The operating facts under it are the admin's.
 */
function WhatItSaw({
	workspaceSlug,
	entry,
	observations,
	occurrenceName,
	onShowOccurrence,
	canAdminister,
	earlierReview,
	earlierReviewLink,
	reviewLink,
}: WhatItSawProps) {
	// Narrowed here: the closure below would not keep a narrowing made in the JSX guard.
	const occasionedById = hasText(entry.occasionedById) ? entry.occasionedById : undefined;
	const summaries = observations.filter(
		(observation) => hasText(observation.summary) || observation.invalidatedAt !== undefined,
	);
	return (
		<span className="flex min-w-0 flex-col items-start gap-2.5">
			{summaries.length === 0 ? (
				<span className="text-sm text-muted-foreground">{entry.explanation}</span>
			) : (
				summaries.map((observation) => (
					<span
						key={observation.id}
						className="flex max-w-lg flex-col items-start gap-1 text-sm text-pretty"
					>
						{hasText(observation.summary) && (
							<span className="font-medium">{observation.summary.trim()}</span>
						)}
						{observation.invalidatedAt !== undefined && (
							<>
								<StatusBadge def={MARKED_INCORRECT_DEF} />
								{hasText(observation.invalidationReason) && (
									<span className="text-muted-foreground">
										Reason: {observation.invalidationReason}
									</span>
								)}
							</>
						)}
					</span>
				))
			)}
			{earlierReview !== undefined && earlierReviewLink?.(earlierReview)}
			{canAdminister && (
				<span className="flex min-w-0 flex-col gap-1 text-xs text-muted-foreground">
					<span className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1">
						<span>
							{/* The observations behind a reused answer are the earlier review's; this one made none. */}
							{earlierReview !== undefined &&
							entry.observationCount === 0 &&
							entry.deliveredCount === 0
								? "No new observations in this review, so nothing was sent"
								: deliveryLabel(entry)}
						</span>
						<AutonomyBadge autonomy={entry.autonomy} />
					</span>
					{entry.precompute !== undefined && (
						<PrecomputeTraceLine
							workspaceSlug={workspaceSlug}
							entry={entry}
							run={entry.precompute}
							reviewLink={reviewLink}
						/>
					)}
					{occasionedById !== undefined &&
						occurrenceName !== undefined && (
							// Not an anchor: the occurrence is drawn on the other tab, which has to open first.
							<InlineLink
								onClick={() => onShowOccurrence(occasionedById)}
								className="inline-flex items-center gap-1"
							>
								Rests on {occurrenceName}
								<ArrowRightIcon className="size-3 shrink-0" aria-hidden />
							</InlineLink>
						)}
				</span>
			)}
		</span>
	);
}

/**
 * What the practice's precompute script did before the review, in one muted line: how it ended, the
 * one change that would help it, and *Details*, which opens the review's Precompute scripts
 * section with the reasons behind any unrated call. The links name the practice for a screen
 * reader, which lists every row's links together.
 */
function PrecomputeTraceLine({
	workspaceSlug,
	entry,
	run,
	reviewLink,
}: {
	workspaceSlug: string;
	entry: PracticeTraceEntry;
	run: PrecomputeRun;
	reviewLink: ((reviewId: string, label: ReactNode) => ReactNode) | undefined;
}) {
	const fix = precomputeFix(run);
	const { reviewId, practiceName } = entry;
	const details = (
		<>
			Details <span className="sr-only">of the precompute script for {practiceName}</span>
			<ArrowRightIcon className="size-3 shrink-0" aria-hidden />
		</>
	);
	return (
		<span className="flex min-w-0 items-start gap-1.5 text-pretty">
			<ScanSearchIcon className="mt-px size-3.5 shrink-0" aria-hidden />
			<span className="flex min-w-0 flex-wrap items-baseline gap-x-3 gap-y-1">
				<span>{precomputeRunSentence(run)}</span>
				{fix !== undefined && (
					<PrecomputeFixLink
						workspaceSlug={workspaceSlug}
						practiceSlug={entry.practiceSlug}
						practiceName={practiceName}
						fix={fix}
					/>
				)}
				{hasText(reviewId) &&
					(reviewLink?.(reviewId, details) ?? (
						<InlineLink
							render={
								<Link
									to="/w/$workspaceSlug/admin/practices/reviews"
									params={{ workspaceSlug }}
									search={detailSearch(reviewLevel(reviewId))}
								/>
							}
							className="inline-flex items-center gap-1 font-medium"
						>
							{details}
						</InlineLink>
					))}
			</span>
		</span>
	);
}
