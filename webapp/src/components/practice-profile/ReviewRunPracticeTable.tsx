import { ArrowRightIcon } from "lucide-react";

import type {
	ObservationDetail,
	PracticeGroup,
	PracticeTraceEntry,
	TracedSignal,
} from "@/api/types.gen";
import { ALL_OPTION, FilterToolbar } from "@/components/common/FilterToolbar";
import { InlineLink } from "@/components/common/InlineLink";
import { ResultCount } from "@/components/common/ResultCount";
import { deliveryLabel } from "@/components/practice-trace/trace-format";
import { TraceOutcomeBadge } from "@/components/practice-trace/TraceOutcomeBadge";
import { AutonomyBadge } from "@/components/practice-vocabulary/AutonomyBadge";
import {
	getGroupVisual,
	UNASSIGNED_GROUP_VISUAL,
} from "@/components/practice-vocabulary/group-visuals";
import { GroupName } from "@/components/practice-vocabulary/GroupName";
import { PracticePill } from "@/components/practice-vocabulary/PracticePill";
import { StatusTooltip } from "@/components/practice-vocabulary/StatusTooltip";
import { TRACE_OUTCOME_DEFS } from "@/components/practice-vocabulary/trace-outcome-defs";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
	Select,
	SelectContent,
	SelectItem,
	SelectTrigger,
	SelectValue,
} from "@/components/ui/select";
import {
	Table,
	TableBody,
	TableCell,
	TableHead,
	TableHeader,
	TableRow,
} from "@/components/ui/table";
import { hasText } from "@/lib/text";

/** How the three filters narrow the table, as the level's own search params carry them. */
export interface ReviewRunPracticeFilters {
	/** The practice group's name, as the trace spells it. */
	group?: string;
	/** Part of a practice's name, matched case-insensitively. */
	practice?: string;
	/** One signal name, keeping the practices that watch it. */
	watches?: string;
}

export interface ReviewRunPracticeTableProps {
	/** Every practice's answer about this work; the table keeps the ones this run decided. */
	entries: PracticeTraceEntry[];
	/** The occurrences this work's activity carries, so "Rests on" can name one. */
	signals: TracedSignal[];
	/**
	 * What the run observed about the reader, by practice slug. A practice can be observed more than
	 * once in one run, so each cell lists every one of them rather than showing whichever came last.
	 */
	observationsByPractice: Record<string, ObservationDetail[] | undefined>;
	/**
	 * The workspace's practice groups, for the icon and the colour a group's name is drawn in. A
	 * group missing from this list still has its name, which the trace entry carries.
	 */
	groups: PracticeGroup[];
	filters: ReviewRunPracticeFilters;
	onFiltersChange?: (filters: ReviewRunPracticeFilters) => void;
	/** Opens a practice's own level from its pill. */
	onOpenPractice?: (practiceSlug: string) => void;
	/** Shows one occurrence on the neighbouring tab, which is where the timeline is. */
	onShowOccurrence?: (signalId: string) => void;
	/** Admins also read the delivery sentence, the autonomy and what the answer rests on. */
	canAdminister?: boolean;
}

/** Practices the workspace files in no group are still listed, under a name of their own. */
const UNGROUPED = "Other practices";

const NO_OBSERVATIONS: ObservationDetail[] = [];

/**
 * The practices this run decided, in the order the trace lists them. The trace is already asked for
 * this run alone, so the filter keeps the rows it answered and drops the ones no review speaks for:
 * a practice turned off, one nothing occasioned, one nothing here can raise.
 */
export function runPractices(
	entries: PracticeTraceEntry[],
	reviewId: string | undefined,
): PracticeTraceEntry[] {
	return reviewId === undefined ? [] : entries.filter((entry) => entry.reviewId === reviewId);
}

/** The group an entry is filed under, as every part of this table names it. */
function groupNameOf(entry: PracticeTraceEntry): string {
	return hasText(entry.groupName) ? entry.groupName : UNGROUPED;
}

/**
 * Every practice this workspace runs against this kind of work, as this run answered it: what it
 * made of the work, which practice it was, and what it found. The observation's own words where the
 * practice was reached, and the recorded reason where it stayed quiet. The two are never collapsed:
 * a practice can be reviewed and still, by design, say nothing.
 */
export function ReviewRunPracticeTable({
	entries,
	signals,
	observationsByPractice,
	groups,
	filters,
	onFiltersChange,
	onOpenPractice,
	onShowOccurrence,
	canAdminister = false,
}: ReviewRunPracticeTableProps) {
	// Every group with a practice in this table, taken from the entries themselves: the group the
	// reader's other lists know about is only the ones they have a standing in, which would leave the
	// choices short of the rows they are meant to narrow.
	const groupNames = [...new Set(entries.map(groupNameOf))].sort((left, right) =>
		left.localeCompare(right),
	);
	const groupBySlug = new Map(groups.map((group) => [group.slug, group]));
	// The occurrences a practice here can be started by, named the way the timeline names them; a
	// signal nothing has raised on this work has no occurrence, so it falls back to its own name.
	const signalNames = new Map(signals.map((signal) => [signal.signal, signal.displayName]));
	const events = [...new Set(entries.flatMap((entry) => entry.watches))].sort((left, right) =>
		(signalNames.get(left) ?? left).localeCompare(signalNames.get(right) ?? right),
	);

	const needle = filters.practice?.trim().toLowerCase() ?? "";
	const rows = entries.filter((entry) => {
		if (hasText(filters.group) && groupNameOf(entry) !== filters.group) {
			return false;
		}
		if (hasText(filters.watches) && !entry.watches.includes(filters.watches)) {
			return false;
		}
		return needle === "" || entry.practiceName.toLowerCase().includes(needle);
	});
	const hasFilter = hasText(filters.group) || hasText(filters.watches) || needle !== "";

	return (
		<div className="flex min-w-0 flex-col gap-3">
			{onFiltersChange && (
				<FilterToolbar hasFilter={hasFilter} onReset={() => onFiltersChange({})}>
					<div className="flex min-w-0 items-center gap-2">
						<Label
							id="run-practice-group-label"
							htmlFor="run-practice-group"
							className="shrink-0 text-muted-foreground"
						>
							Group
						</Label>
						<Select
							items={[
								{ value: ALL_OPTION, label: "All groups" },
								...groupNames.map((group) => ({ value: group, label: group })),
							]}
							value={filters.group ?? ALL_OPTION}
							onValueChange={(next) =>
								onFiltersChange({
									...filters,
									group: next === ALL_OPTION ? undefined : String(next),
								})
							}
						>
							<SelectTrigger id="run-practice-group" className="w-56 max-w-full">
								<SelectValue placeholder="All groups" />
							</SelectTrigger>
							<SelectContent aria-labelledby="run-practice-group-label">
								<SelectItem value={ALL_OPTION}>All groups</SelectItem>
								{groupNames.map((group) => (
									<SelectItem key={group} value={group}>
										{group}
									</SelectItem>
								))}
							</SelectContent>
						</Select>
					</div>
					<div className="flex min-w-0 items-center gap-2">
						<Label
							id="run-practice-event-label"
							htmlFor="run-practice-event"
							className="shrink-0 text-muted-foreground"
						>
							Review event
						</Label>
						<Select
							items={[
								{ value: ALL_OPTION, label: "Any event" },
								...events.map((event) => ({
									value: event,
									label: signalNames.get(event) ?? event,
								})),
							]}
							value={filters.watches ?? ALL_OPTION}
							onValueChange={(next) =>
								onFiltersChange({
									...filters,
									watches: next === ALL_OPTION ? undefined : String(next),
								})
							}
						>
							<SelectTrigger id="run-practice-event" className="w-56 max-w-full">
								<SelectValue placeholder="Any event" />
							</SelectTrigger>
							<SelectContent aria-labelledby="run-practice-event-label">
								<SelectItem value={ALL_OPTION}>Any event</SelectItem>
								{events.map((event) => (
									<SelectItem key={event} value={event}>
										{signalNames.get(event) ?? event}
									</SelectItem>
								))}
							</SelectContent>
						</Select>
					</div>
					<div className="flex min-w-0 items-center gap-2">
						<Label htmlFor="run-practice-name" className="shrink-0 text-muted-foreground">
							Practice
						</Label>
						<Input
							id="run-practice-name"
							value={filters.practice ?? ""}
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
			)}
			<ResultCount
				total={rows.length}
				noun={["practice", "practices"]}
				hasFilter={hasFilter && rows.length !== entries.length}
			/>
			<div className="shrink-0 overflow-hidden rounded-xl border bg-background">
				<Table aria-label="Every practice on this work" className="min-w-152">
					<TableHeader sticky>
						<TableRow>
							<TableHead className="w-40">State</TableHead>
							<TableHead className="w-64">Practice</TableHead>
							<TableHead>What it found</TableHead>
						</TableRow>
					</TableHeader>
					<TableBody>
						{rows.length === 0 && (
							<TableRow variant="static">
								<TableCell colSpan={3} className="p-4 whitespace-normal">
									<p className="text-sm text-muted-foreground">
										{emptyMessage(entries.length === 0)}
									</p>
								</TableCell>
							</TableRow>
						)}
						{rows.map((entry) => {
							const group = hasText(entry.groupSlug) ? groupBySlug.get(entry.groupSlug) : undefined;
							const visual =
								group === undefined
									? UNASSIGNED_GROUP_VISUAL
									: getGroupVisual(group.icon, group.color);
							return (
								<TableRow key={entry.practiceSlug}>
									<TableCell className="align-top whitespace-normal">
										<StatusTooltip
											def={TRACE_OUTCOME_DEFS[entry.outcome]}
											render={
												<button
													type="button"
													aria-label={TRACE_OUTCOME_DEFS[entry.outcome].label}
												/>
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
												onOpen={onOpenPractice && (() => onOpenPractice(entry.practiceSlug))}
											/>
											{/* The group reads here as it reads on the page below: its own icon and
											    colour, so one group is one thing wherever it is named. */}
											<GroupName
												name={groupNameOf(entry)}
												icon={visual.Icon}
												pill={group === undefined ? undefined : visual.pill}
												className="text-xs"
											/>
										</span>
									</TableCell>
									<TableCell className="align-top whitespace-normal">
										<WhatItFound
											entry={entry}
											observations={observationsByPractice[entry.practiceSlug] ?? NO_OBSERVATIONS}
											occurrenceName={
												hasText(entry.occasionedById)
													? signals.find((signal) => signal.id === entry.occasionedById)
															?.displayName
													: undefined
											}
											onShowOccurrence={onShowOccurrence}
											canAdminister={canAdminister}
										/>
									</TableCell>
								</TableRow>
							);
						})}
					</TableBody>
				</Table>
			</div>
		</div>
	);
}

interface WhatItFoundProps {
	entry: PracticeTraceEntry;
	observations: ObservationDetail[];
	occurrenceName: string | undefined;
	onShowOccurrence?: (signalId: string) => void;
	canAdminister: boolean;
}

/**
 * What this practice found about this reader, in one sentence per observation with the way to the
 * observation itself, and the recorded reason where the practice said nothing about them.
 *
 * The sentence is the review's own words about this work. The catalog's words on the practice are
 * deliberately absent: they say what the practice is about, never what this review made of this
 * work, and a reader who cannot tell the two apart is being told something about themselves that
 * nobody measured. The next step, the evidence, the feedback and the reader's own answer are
 * absent too, and for the opposite reason: they are all on the observation, which is one press
 * away, and a second copy of them here is a second place for them to go stale.
 *
 * The operating facts under it are the admin's. A member has nothing to do about an autonomy
 * setting or a delivery decision, and a sentence they cannot act on reads as a fault.
 */
function WhatItFound({
	entry,
	observations,
	occurrenceName,
	onShowOccurrence,
	canAdminister,
}: WhatItFoundProps) {
	// Narrowed once, here: the closure below would not keep a narrowing made in the JSX guard.
	const occasionedById = hasText(entry.occasionedById) ? entry.occasionedById : undefined;
	return (
		<span className="flex min-w-0 flex-col items-start gap-2.5">
			{observations.length === 0 ? (
				// A practice this run reached can still have observed nothing about this reader, and
				// a practice it never reached has its own reason; the trace wrote one sentence for
				// either case, and it is the only thing that is true about them here.
				<span className="text-sm text-muted-foreground">{entry.explanation}</span>
			) : (
				observations.map((observation) => (
					<ObservedHere key={observation.id} observation={observation} />
				))
			)}
			{canAdminister && (
				<span className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1 text-xs text-muted-foreground">
					<span>{deliveryLabel(entry)}</span>
					<AutonomyBadge autonomy={entry.autonomy} />
					{occasionedById !== undefined &&
						occurrenceName !== undefined &&
						onShowOccurrence && (
							// Not an anchor to the occurrence's own id: the timeline that draws it is the
							// neighbouring tab, so the jump has to open that tab before anything with that
							// id exists to land on.
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

/** Why the table has no row: this run reached no practice, or the filters kept none. */
function emptyMessage(nothingReached: boolean) {
	return nothingReached
		? "This run reached no practice, so there is nothing to list here."
		: "No practice here matches your filters.";
}

interface ObservedHereProps {
	observation: ObservationDetail;
}

/** One thing this review observed about the reader's work. */
function ObservedHere({ observation }: ObservedHereProps) {
	return (
		<span className="flex min-w-0 flex-col items-start gap-1">
			{hasText(observation.summary) && (
				<span className="max-w-lg text-sm font-medium text-pretty">
					{observation.summary.trim()}
				</span>
			)}
		</span>
	);
}
