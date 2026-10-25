import { ChevronRightIcon, RepeatIcon } from "lucide-react";
import { Fragment, type ReactNode } from "react";

import { cn } from "cn";
import type { ItemRef, ReviewDetail, ReviewLineage } from "@/api/types.gen";
import { DotSeparator } from "@/components/common/DotSeparator";
import { REVIEW_ORIGIN_DEFS } from "@/components/practice-vocabulary/review-origin-defs";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { hasText } from "@/lib/text";

import { TermSentence } from "./GlossaryTerm";
import { countOf, lineageRecords, stageCounts } from "./lineage-chain";
import {
	type RailItem,
	type RailStage,
	reviewStepState,
	type StorySiblings,
} from "./lineage-story";
import { StageIcon, Step, StepTag, StepTime, ToneIcon } from "./LineageStep";
import { chainReview, ownResults } from "./review-trace";
import { ReviewItemLabel } from "./ReviewItem";
import { type TraceBottleneck, traceSpans } from "./trace-bottleneck";
import { hiddenByFilter, type TraceFilter } from "./trace-filter";
import { STAGE_HEADERS, STAGE_MARKS, type StageKey } from "./trace-stage-headers";
import { TRACE_END_TONES, type TraceEnd } from "./trace-verdict";
import { FeedbackTableStep } from "./TraceFeedbackTable";
import { ResultsStep } from "./TraceResultTable";
import { type RailStepProps, type StepProps, StoryNode, useStory } from "./TraceStoryNode";

function WorkStep({ item, here }: { item: ItemRef | undefined; here: boolean }) {
	return (
		<Step dot={{ tone: "outline", hollow: true, here }} data-step="work">
			<StoryNode node={{ kind: "work" }} className="px-1.5 py-1">
				<ReviewItemLabel item={item} className="font-medium" />
				{hasText(item?.title) && (
					<span className="block break-words text-muted-foreground">{item.title}</span>
				)}
			</StoryNode>
		</Step>
	);
}

type FeedbackItem = Extract<RailItem, { kind: "feedback" }>;

function bottleneckHeader(
	stage: TraceBottleneck["stage"],
	kind: TraceBottleneck["kind"],
): StageKey {
	return stage === "review" && kind === "lost" ? "results" : stage;
}

/**
 * A stage's header on the rail: its icon on the rail itself, its name, a short summary of the stage
 * where one helps the eye (the review's queue and run, the feedback's count), and a mark where the
 * bottleneck line above places what was lost or slow.
 */
function StageHeader({
	stage,
	first,
	summary,
}: {
	stage: StageKey;
	first: boolean;
	summary?: ReactNode;
}) {
	const { bottleneck } = useStory();
	const { label } = STAGE_HEADERS[stage];
	const marked =
		bottleneck !== undefined &&
		bottleneck.kind !== "none" &&
		bottleneckHeader(bottleneck.stage, bottleneck.kind) === stage;
	return (
		<li
			data-stage={stage}
			className={cn(
				"relative grid grid-cols-[0.6875rem_minmax(0,1fr)] items-center gap-x-2.5 pb-2",
				!first && "pt-1",
			)}
		>
			{!first && <span aria-hidden className="absolute inset-y-0 left-[5px] w-px bg-border" />}
			<StageIcon stage={stage} className="relative z-[1] -ml-1" />
			{/* The gutter the connectors run in, as every step's content keeps it. */}
			<span className="flex min-w-0 flex-wrap items-baseline gap-x-2 gap-y-0.5 pr-(--lane-gutter) pl-1.5">
				<span data-stage-label className="text-xs font-semibold text-foreground">
					{label}
				</span>
				{summary}
				{marked && (
					<Badge
						variant={
							bottleneck.tone === "destructive" || bottleneck.tone === "warning"
								? bottleneck.tone
								: "outline"
						}
						size="xs"
					>
						{STAGE_MARKS[bottleneck.kind]}
					</Badge>
				)}
			</span>
		</li>
	);
}

/** A stage's summary next to its name: the review's queue and run, the feedback's count and reach. */
function stageSummary(
	stage: RailStage["stage"],
	full: ReviewLineage,
	detail: ReviewDetail | undefined,
): ReactNode {
	if (stage === "review") {
		const spans = traceSpans(full, undefined, detail).filter(
			(entry) => entry.kind === "queue" || entry.kind === "run",
		);
		if (spans.length === 0) {
			return undefined;
		}
		return (
			<span className="text-2xs text-muted-foreground">
				{spans.map((entry, index) => (
					<Fragment key={entry.kind}>
						{index > 0 && <DotSeparator inline />}
						{entry.label}{" "}
						<span
							className={cn(
								"tabular-nums",
								entry.slow ? "font-semibold text-warning" : "text-foreground",
							)}
						>
							{entry.text}
						</span>
					</Fragment>
				))}
			</span>
		);
	}
	if (stage === "feedback") {
		const counts = stageCounts(lineageRecords(full));
		if (counts.feedback === 0) {
			return undefined;
		}
		return (
			<span className="text-2xs text-muted-foreground tabular-nums">
				{`${countOf(counts.feedback, "piece", "pieces")}, ${counts.reached} reached the developer`}
			</span>
		);
	}
	return undefined;
}

export function StageSteps({
	stage,
	first,
	previousAt,
	end,
	...props
}: StepProps & {
	stage: RailStage;
	first: boolean;
	previousAt: (item: RailItem) => Date | undefined;
	end: TraceEnd;
}) {
	const { lineage, full, filter } = props;
	const { review: detail } = useStory();
	const feedbackCount = stage.items.filter((item) => item.kind === "feedback").length;
	const siblingCount = stage.items.some((item) => item.kind === "siblings");
	const step = (item: RailItem) => (
		<RailStep key={item.key} {...props} item={item} previous={previousAt(item)} />
	);
	const runs: (RailItem | { kind: "table"; key: string; items: FeedbackItem[] })[] = [];
	for (const item of stage.items) {
		const last = runs.at(-1);
		if (item.kind !== "feedback") {
			runs.push(item);
		} else if (last?.kind === "table") {
			last.items.push(item);
		} else {
			runs.push({ kind: "table", key: `table:${item.key}`, items: [item] });
		}
	}
	return (
		<>
			<StageHeader
				stage={stage.stage}
				first={first}
				summary={stageSummary(stage.stage, full, detail)}
			/>
			{stage.stage === "start" && (
				<WorkStep item={lineageRecords(lineage).item} here={props.here?.kind === "work"} />
			)}
			{runs.map((item) => {
				if (item.kind === "table") {
					return (
						<FeedbackTableStep
							key={item.key}
							{...props}
							items={item.items.map((entry) => entry.feedback)}
							previous={item.items[0] === undefined ? undefined : previousAt(item.items[0])}
						/>
					);
				}
				if (item.kind === "results") {
					return (
						<Fragment key={item.key}>
							<StageHeader stage="results" first={false} />
							{step(item)}
						</Fragment>
					);
				}
				if (item.kind === "end") {
					return <EndStep key={item.key} end={end} previous={previousAt(item)} />;
				}
				return step(item);
			})}
			{stage.stage === "feedback" && feedbackCount === 0 && !siblingCount && (
				<Step dot={{ tone: "outline", hollow: true }} data-step="no-feedback">
					<span className="px-1.5 text-muted-foreground">
						{noFeedbackText(full, lineage, filter)}
					</span>
				</Step>
			)}
		</>
	);
}

/**
 * What an empty feedback stage says. Only a review that wrote nothing at all has "No feedback from
 * this review"; a filter, or a path that leaves the review's other pieces off, says so instead.
 */
function noFeedbackText(full: ReviewLineage, shown: ReviewLineage, filter: TraceFilter): string {
	const records = lineageRecords(full);
	const recorded = new Set(ownResults(records).map((entry) => entry.id));
	const any = records.feedback.some((entry) => entry.basis.some((id) => recorded.has(id)));
	if (filter.kind !== "all" && any) {
		return hiddenByFilter(filter);
	}
	if (any || lineageRecords(shown).feedback.length > 0) {
		return "No feedback on this path.";
	}
	const status = chainReview(records)?.status;
	return status === "QUEUED" || status === "RUNNING"
		? "No feedback yet."
		: "No feedback from this review.";
}

function RailStep(props: RailStepProps) {
	const { item, previous, lineage } = props;
	switch (item.kind) {
		case "start": {
			return <StartStep item={item} previous={previous} />;
		}
		case "review": {
			return (
				<ReviewStep
					lineage={lineage}
					at={item.at}
					previous={previous}
					hasStart={props.hasStart}
					here={props.here?.kind === "review"}
				/>
			);
		}
		case "results": {
			return <ResultsStep {...props} at={item.at} />;
		}
		case "feedback": {
			return <FeedbackTableStep {...props} items={[item.feedback]} previous={previous} />;
		}
		case "siblings": {
			return (
				<SiblingsStep
					results={item.results}
					feedback={item.feedback}
					onShowSiblings={props.onShowSiblings}
				/>
			);
		}
		case "end": {
			return null;
		}
	}
}

/** The record's siblings, as one line; it lists them on the rail. */
function SiblingsStep({
	results,
	feedback,
	onShowSiblings,
}: StorySiblings & { onShowSiblings: () => void }) {
	const parts = [
		results > 0 ? countOf(results, "other observation", "other observations") : undefined,
		feedback > 0 ? `${countOf(feedback, "other piece", "other pieces")} of feedback` : undefined,
	].filter(hasText);
	return (
		<Step dot={{ tone: "outline", hollow: true }} data-step="siblings">
			<Button
				variant="link"
				size="inline"
				className="ml-1.5 text-muted-foreground"
				title="List them on the rail"
				onClick={onShowSiblings}
			>
				<ChevronRightIcon aria-hidden className="size-3" />
				{parts.join(", ")}
			</Button>
		</Step>
	);
}

/** Where the path of the record in front ends: it worked, it did not arrive, or it is still open. */
function EndStep({ end, previous }: { end: TraceEnd; previous: Date | undefined }) {
	const { display } = useStory();
	return (
		<Step
			dot={{ tone: TRACE_END_TONES[end.kind], hollow: end.kind === "none" }}
			at={end.at}
			gapFrom={end.at === undefined ? undefined : previous}
			data-step="end"
			data-end={end.kind}
		>
			<StoryNode node={{ kind: "end" }} className="px-1.5 py-1">
				<span className="font-medium">{end.label}</span>
				{end.since !== undefined && display.times && (
					<>
						{" since "}
						<StepTime at={end.since} />
					</>
				)}
				{": "}
				<TermSentence text={`${end.reason}.`} />
			</StoryNode>
		</Step>
	);
}

function StartStep({
	item,
	previous,
}: {
	item: Extract<RailItem, { kind: "start" }>;
	previous: Date | undefined;
}) {
	const def = REVIEW_ORIGIN_DEFS[item.origin];
	return (
		<Step dot={{ tone: "outline", hollow: true }} at={item.at} gapFrom={previous} data-step="start">
			<span className="block px-1.5 py-1">
				<span className="flex items-center gap-1.5">
					<ToneIcon def={def} />
					<span>{def.label}</span>
				</span>
				<span className="block text-muted-foreground">{def.description}</span>
			</span>
		</Step>
	);
}

function ReviewStep({
	lineage,
	at,
	previous,
	hasStart,
	here,
}: {
	lineage: ReviewLineage;
	at: Date | undefined;
	previous: Date | undefined;
	hasStart: boolean;
	here: boolean;
}) {
	const { display, review: detail, position, factsOpen } = useStory();
	const review = chainReview(lineageRecords(lineage));
	if (review === undefined) {
		return (
			<Step dot={{ tone: "outline", hollow: true }}>
				<p className="text-muted-foreground">The record of this review is gone.</p>
			</Step>
		);
	}
	const own = detail?.id === review.id ? detail : undefined;
	const state = reviewStepState(review, own);
	const origin = REVIEW_ORIGIN_DEFS[review.origin];
	// The queue and the run stand on the stage's header; the model is all the system adds here.
	const facts = [own?.model].filter(hasText);
	return (
		<Step
			dot={{ tone: state.def.badgeVariant, here }}
			at={at}
			gapFrom={previous}
			data-step="review"
		>
			<StoryNode node={{ kind: "review" }} className="px-1.5 py-1">
				<span className="flex flex-wrap items-center gap-x-1.5 gap-y-0.5">
					<span className="inline-flex items-center gap-1.5 font-medium">
						<ToneIcon def={state.def} />
						{state.title}
					</span>
					{/* An event is the ordinary case, said by saying nothing. */}
					{review.origin !== "EVENT" && <StepTag def={origin} />}
					{position !== undefined && position.total > 1 && (
						<Badge variant="outline" size="xs" data-review-position>
							<RepeatIcon aria-hidden />
							{`Review ${position.index} of ${position.total} on this work`}
						</Badge>
					)}
				</span>
				{hasText(state.note) && <span className="block">{state.note}</span>}
				{!hasStart && (
					<span className="block text-muted-foreground">{`Why it ran: ${origin.description}`}</span>
				)}
				{/* The facts box under the step names the model where it opens. */}
				{display.system && !factsOpen && !here && facts.length > 0 && (
					<span className="block text-muted-foreground">{facts.join(" · ")}</span>
				)}
			</StoryNode>
		</Step>
	);
}
