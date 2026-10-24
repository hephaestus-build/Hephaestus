import { ChevronsDownUpIcon, ChevronsUpDownIcon } from "lucide-react";
import { useEffect, useId, useRef, useState } from "react";

import type { ReviewDetail, ReviewLineage } from "@/api/types.gen";
import { useNow } from "@/components/common/use-now";
import { OUTCOME_DEFS, type Outcome } from "@/components/practice-vocabulary/outcome-defs";
import type { ReviewOrigin } from "@/components/practice-vocabulary/review-origin-defs";
import { Button } from "@/components/ui/button";
import {
	Select,
	SelectContent,
	SelectItem,
	SelectTrigger,
	SelectValue,
} from "@/components/ui/select";
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group";
import { formatWeekdayDay } from "@/lib/dates";

import { TermSentence } from "./GlossaryTerm";
import {
	type LineageRecords,
	lineageRecords,
	type NumberedById,
	type NumberedResult,
	numberedResults,
} from "./lineage-chain";
import {
	feedbackLetters,
	type RailItem,
	sourcesOf,
	type StorySiblings,
	storyFeedback,
	storyResults,
	storyRail,
} from "./lineage-story";
import { connectorLanes, laneGutter, LineageConnectors } from "./LineageConnectors";
import { LineageDay, LineageTimes } from "./LineageStep";
import type { RecordRef } from "./review-layers";
import { chainReview, type TraceNode, tracedBy, traceStepKey } from "./review-trace";
import { traceBottleneck, traceSpans } from "./trace-bottleneck";
import {
	DEFAULT_TRACE_DISPLAY,
	TRACE_DISPLAY_PARTS,
	type TraceDisplay,
	type TraceDisplayPart,
} from "./trace-display";
import {
	ALL_STEPS,
	filteredLineage,
	parseTraceFilter,
	shownRecords,
	type TraceFilter,
	traceFilterValue,
	traceOutcomes,
	tracePractices,
} from "./trace-filter";
import { traceVerdict } from "./trace-verdict";
import { TraceBottleneckLine } from "./TraceBottleneckLine";
import { TraceLegend } from "./TraceLegend";
import { StageSteps } from "./TraceRailSteps";
import { resultRows, standsOnReview } from "./TraceResultTable";
import { traceStepFacts } from "./TraceStepFacts";
import { type StoryContext, StoryNodes } from "./TraceStoryNode";

/** The chain once it is in: `TraceLevel` owns its loading and error states. */
export interface ReviewLineageGraphProps {
	lineage: ReviewLineage;
	/**
	 * The record the trace was opened for, marked "You started here" and ringed: its path is drawn
	 * strong. Opening another record over the trace leaves the mark where it is. Absent, nothing is
	 * marked, as in the state matrix.
	 */
	here?: TraceNode;
	onOpen?: (record: RecordRef) => void;
	/**
	 * What the trace shows on top of its steps, for the whole trace at once: times, system info,
	 * siblings, connections. Uncontrolled from `defaultDisplay` when absent.
	 */
	display?: TraceDisplay;
	defaultDisplay?: TraceDisplay;
	onDisplayChange?: (display: TraceDisplay) => void;
	/** What of the chain is shown. Uncontrolled when absent. */
	filter?: TraceFilter;
	onFilterChange?: (filter: TraceFilter) => void;
	review?: ReviewDetail;
	origin?: ReviewOrigin;
	position?: { index: number; total: number };
}

export function ReviewLineageGraph({
	lineage: full,
	here,
	onOpen,
	display: displayProp,
	defaultDisplay = DEFAULT_TRACE_DISPLAY,
	onDisplayChange,
	filter: filterProp,
	onFilterChange,
	review,
	origin,
	position,
}: ReviewLineageGraphProps) {
	const now = new Date(useNow());
	const [ownDisplay, setOwnDisplay] = useState<TraceDisplay>(defaultDisplay);
	const [ownFilter, setOwnFilter] = useState<TraceFilter>(ALL_STEPS);
	const filter = filterProp ?? ownFilter;
	const setFilter = (next: TraceFilter) => {
		setOwnFilter(next);
		onFilterChange?.(next);
	};
	const filtered = filter.kind !== "all";
	const display = displayProp ?? ownDisplay;
	const setDisplay = (next: TraceDisplay) => {
		setOwnDisplay(next);
		onDisplayChange?.(next);
	};
	const hereKey = here === undefined ? undefined : traceStepKey(here);
	// The folded parts, opened one by one or all at once; none of them is a reading preference.
	const [othersOpen, setOthersOpen] = useState(false);
	const [siblingsOpen, setSiblingsOpen] = useState(false);
	const [factsOpen, setFactsOpen] = useState(false);
	const [legendOpen, setLegendOpen] = useState(false);
	const [tableOpen, setTableOpen] = useState<boolean | undefined>(undefined);
	const ownTable = standsOnReview(here);
	const setAllOpen = (open: boolean) => {
		setOthersOpen(open);
		setSiblingsOpen(open);
		setFactsOpen(open);
		// Collapse all folds a review's own table too; elsewhere the table follows the display again.
		if (open) {
			setTableOpen(true);
		} else {
			setTableOpen(ownTable ? false : undefined);
		}
	};
	const box = useRef<HTMLDivElement>(null);
	const rail = useRef<HTMLDivElement>(null);
	useReveal(rail, hereKey);

	const lineage = filteredLineage(full, filter);
	const fullRecords = lineageRecords(full);
	const records = shownRecords(fullRecords, lineage);
	const chain = chainReview(fullRecords);
	const ownReview = review !== undefined && review.id === chain?.id ? review : undefined;
	const { numbered, self } = storyResults(records, here, fullRecords);
	const siblingsShown =
		display.siblings || siblingsOpen || filtered || foldedOffPath(records, here, numbered, self);
	// What a piece of feedback rests on is named by the whole review's numbers, filtered or not.
	const byId: NumberedById = new Map(
		numberedResults(fullRecords.results).map((entry) => [entry.result.id, entry]),
	);
	const letters = feedbackLetters(fullRecords.feedback);
	const told = storyFeedback(records, here, siblingsShown);
	const path = siblingsShown ? undefined : pathResults(records, here, numbered, self);
	const listed = tableOpen ?? (ownTable || siblingsShown || display.connections);
	const expanded = othersOpen && siblingsOpen && factsOpen && listed;
	const siblings: StorySiblings | undefined =
		path === undefined
			? undefined
			: { results: numbered.length - path.length, feedback: told.siblings.length };
	const stages = storyRail(records, told.shown, { startedBy: ownReview?.startedBy, siblings });
	const verdict = traceVerdict(full, here);
	const bottleneck = traceBottleneck(full, here, ownReview, now);
	const spans = traceSpans(full, here, ownReview);
	const traced = tracedBy(records, here);
	const rows = resultRows(numbered, path, self, listed);
	const lanes = display.connections ? connectorLanes(rows, told.shown) : [];
	const hasStart = stages[0]?.items.some((item) => item.kind === "start") ?? false;
	const practices = tracePractices(full);
	const outcomes = traceOutcomes(full);

	const recordOf = (node: TraceNode): RecordRef | undefined => {
		switch (node.kind) {
			case "work": {
				return fullRecords.item === undefined
					? undefined
					: { kind: "item", id: fullRecords.item.id };
			}
			case "review": {
				return chain === undefined ? undefined : { kind: "review", id: chain.id };
			}
			case "result":
			case "feedback": {
				return { kind: node.kind, id: node.id };
			}
			case "results":
			case "end": {
				return undefined;
			}
		}
	};

	const context: StoryContext = {
		here,
		recordOf,
		open: onOpen,
		facts: (node) =>
			traceStepFacts({
				lineage: full,
				node,
				numbered,
				here,
				review: ownReview,
				everyStep: factsOpen,
			}),
		display,
		factsOpen,
		origin,
		review: ownReview,
		position,
		bottleneck,
	};

	const day = chain?.createdAt ?? numbered[0]?.result.recordedAt;
	const drawn = stages.flatMap(({ items }) => items);
	const endAt = verdict.end.at ?? verdict.end.since;
	const previousAt = (item: RailItem) => {
		const index = drawn.indexOf(item);
		const at = item.kind === "end" ? endAt : item.at;
		let earlier: Date | undefined;
		for (const entry of drawn.slice(0, Math.max(index, 0))) {
			// A review whose record is gone keeps its place on the rail, but has no time to count from.
			const gone = entry.kind === "review" && chain === undefined;
			earlier = (gone ? undefined : entry.at) ?? earlier;
		}
		return at !== undefined && earlier !== undefined && earlier.getTime() <= at.getTime()
			? earlier
			: undefined;
	};

	return (
		<StoryNodes value={context}>
			<LineageDay value={day}>
				<LineageTimes value={display.times}>
					<div
						ref={box}
						role="group"
						aria-label="How this came about"
						className="min-w-0 space-y-3"
					>
						<p data-trace-verdict className="text-sm font-medium text-balance">
							<TermSentence text={verdict.sentence} />
						</p>
						<TraceBottleneckLine
							bottleneck={bottleneck}
							spans={display.times ? spans : []}
							verdict={verdict.sentence}
						/>
						<div data-trace-toolbar className="flex flex-wrap items-center gap-2">
							<TraceFilterSelect
								value={filter}
								outcomes={outcomes}
								practices={practices}
								onChange={setFilter}
							/>
							<TraceDisplayControl
								display={display}
								onChange={setDisplay}
								siblingsListed={filtered}
							/>
							<Button
								variant="ghost"
								size="sm"
								aria-expanded={expanded}
								title={
									expanded
										? "Fold the observations, the siblings and the facts"
										: "Open every observation, the siblings and every step’s facts"
								}
								onClick={() => setAllOpen(!expanded)}
							>
								{expanded ? (
									<ChevronsDownUpIcon aria-hidden data-icon="inline-start" />
								) : (
									<ChevronsUpDownIcon aria-hidden data-icon="inline-start" />
								)}
								{expanded ? "Collapse all" : "Expand all"}
							</Button>
							<TraceLegend open={legendOpen} onOpenChange={setLegendOpen} />
						</div>
						<TraceDay day={display.times ? day : undefined} />
						<div ref={rail} className="relative">
							{lanes.length > 0 && (
								<LineageConnectors
									// Drawn again whenever what the rail lists changes; a resize it measures itself.
									key={[
										othersOpen,
										siblingsShown,
										listed,
										factsOpen,
										display.times,
										display.system,
										traceFilterValue(filter),
									].join(":")}
									container={rail}
									lanes={lanes}
									numbered={numbered}
									traced={traced}
								/>
							)}
							<ol
								aria-label="Steps, oldest first"
								className="text-xs"
								style={{
									"--lane-gutter": laneGutter(lanes),
									// With connectors, every step's words end at one edge, where the lanes start.
									"--time-column": lanes.length > 0 && display.times ? "6.5rem" : undefined,
								}}
							>
								{stages.map((stage, index) => (
									<StageSteps
										key={stage.stage}
										stage={stage}
										first={index === 0}
										previousAt={previousAt}
										end={verdict.end}
										lineage={lineage}
										full={full}
										filter={filter}
										numbered={numbered}
										self={self}
										path={path}
										listed={listed}
										rows={rows}
										onListedChange={setTableOpen}
										byId={byId}
										letters={letters}
										traced={traced}
										here={here}
										hasStart={hasStart}
										othersOpen={othersOpen}
										onOthersOpenChange={setOthersOpen}
										onShowSiblings={() => setSiblingsOpen(true)}
									/>
								))}
							</ol>
						</div>
					</div>
				</LineageTimes>
			</LineageDay>
		</StoryNodes>
	);
}

/** Above the rail: the day the times are on, while the times are shown. */
function TraceDay({ day }: { day: Date | undefined }) {
	if (day === undefined) {
		return null;
	}
	return (
		<div className="flex flex-wrap items-center gap-x-2 gap-y-1 text-xs">
			<p className="ml-auto font-medium text-muted-foreground">
				<DayLabel day={day} />
			</p>
		</div>
	);
}

/**
 * What the trace shows on top of its steps, each part a toggle of its own for the whole trace. A
 * filter lists every step it keeps, so the siblings show while one is on.
 */
function TraceDisplayControl({
	display,
	onChange,
	siblingsListed,
}: {
	display: TraceDisplay;
	onChange: (display: TraceDisplay) => void;
	siblingsListed: boolean;
}) {
	const labelId = useId();
	const value = TRACE_DISPLAY_PARTS.filter(({ part }) => display[part]).map(({ part }) => part);
	return (
		<div className="flex items-center gap-1.5">
			<span id={labelId} className="text-xs font-medium text-muted-foreground">
				Show
			</span>
			<ToggleGroup
				multiple
				variant="outline"
				size="sm"
				value={value}
				onValueChange={(next: TraceDisplayPart[]) => {
					onChange({
						times: next.includes("times"),
						system: next.includes("system"),
						siblings: next.includes("siblings"),
						connections: next.includes("connections"),
					});
				}}
				aria-labelledby={labelId}
			>
				{TRACE_DISPLAY_PARTS.map(({ part, label, hint }) => (
					<ToggleGroupItem
						key={part}
						value={part}
						title={
							part === "siblings" && siblingsListed ? "A filter lists every step it keeps" : hint
						}
						disabled={part === "siblings" && siblingsListed}
					>
						{label}
					</ToggleGroupItem>
				))}
			</ToggleGroup>
		</div>
	);
}

function foldedOffPath(
	records: LineageRecords,
	here: TraceNode | undefined,
	numbered: NumberedResult[],
	self: NumberedResult | undefined,
): boolean {
	const node = here;
	if (node?.kind === "result") {
		const path = pathResults(records, here, numbered, self) ?? [];
		return !path.some((entry) => entry.result.id === node.id);
	}
	if (node?.kind === "feedback") {
		return !storyFeedback(records, here, false).shown.some((entry) => entry.id === node.id);
	}
	return false;
}

function TraceFilterSelect({
	value,
	outcomes,
	practices,
	onChange,
}: {
	value: TraceFilter;
	outcomes: readonly { outcome: Outcome; count: number }[];
	practices: readonly { slug: string; name: string }[];
	onChange: (filter: TraceFilter) => void;
}) {
	const labelId = useId();
	const items = [
		{ value: "all", label: "Every step" },
		{ value: "feedback", label: "Only feedback" },
		{ value: "losses", label: "Only what was lost" },
		// A result keeps its number under an outcome filter, and the results step says how many of
		// all the results it shows.
		...outcomes.map(({ outcome, count }) => ({
			value: traceFilterValue({ kind: "outcome", outcome }),
			label: `Only ${OUTCOME_DEFS[outcome].label.toLowerCase()} (${String(count)})`,
		})),
		...practices.map((practice) => ({
			value: traceFilterValue({ kind: "practice", practiceSlug: practice.slug }),
			label: `Only ${practice.name}`,
		})),
	];
	return (
		<Select
			items={items}
			value={traceFilterValue(value)}
			onValueChange={(next: string | null) => {
				if (next !== null) {
					onChange(parseTraceFilter(next));
				}
			}}
		>
			<span id={labelId} className="sr-only">
				Steps
			</span>
			<SelectTrigger size="sm" aria-labelledby={labelId} className="w-48 max-w-full">
				<SelectValue />
			</SelectTrigger>
			<SelectContent aria-labelledby={labelId}>
				{items.map((item) => (
					<SelectItem key={item.value} value={item.value}>
						{item.label}
					</SelectItem>
				))}
			</SelectContent>
		</Select>
	);
}

function DayLabel({ day }: { day: Date }) {
	const now = useNow();
	return <time dateTime={day.toISOString()}>{formatWeekdayDay(day, new Date(now))}</time>;
}

/**
 * Brings the record the reader started from into the viewport and focuses its control, once, when
 * the trace arrives on it. Opening another record over the trace moves neither.
 */
function useReveal(rail: React.RefObject<HTMLElement | null>, step: string | undefined) {
	const done = useRef<string>(undefined);
	useEffect(() => {
		if (step === undefined || done.current === step) {
			return;
		}
		const element = rail.current?.querySelector<HTMLElement>(
			`[data-step-key="${CSS.escape(step)}"]`,
		);
		if (element === undefined || element === null) {
			return;
		}
		done.current = step;
		element.scrollIntoView({ block: "nearest" });
		element.querySelector<HTMLElement>("[data-step-control]")?.focus({ preventScroll: true });
	});
}

function pathResults(
	records: LineageRecords,
	here: TraceNode | undefined,
	numbered: readonly NumberedResult[],
	self: NumberedResult | undefined,
): NumberedResult[] | undefined {
	if (here?.kind === "result") {
		return self === undefined ? undefined : [self];
	}
	if (here?.kind === "feedback") {
		const feedback = records.feedback.find((entry) => entry.id === here.id);
		return feedback === undefined ? undefined : sourcesOf(feedback, numbered);
	}
	return undefined;
}
