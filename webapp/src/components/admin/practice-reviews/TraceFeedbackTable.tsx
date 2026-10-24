import { use } from "react";

import { cn } from "cn";
import type { ItemRef, ReviewRef } from "@/api/types.gen";
import type { BadgeVariant } from "@/components/common/status-def";
import { FEEDBACK_DESTINATION_DEFS } from "@/components/practice-vocabulary/feedback-destination-defs";
import { hasText } from "@/lib/text";

import {
	branchOutcome,
	countOf,
	gapLabel,
	isSlowGap,
	type LineageFeedback,
	type LineageRecords,
	lineageRecords,
	type NumberedById,
} from "./lineage-chain";
import { LineageSourceChips } from "./LineageResultChip";
import {
	FeedbackLetter,
	LineageTimes,
	OffPathNote,
	Step,
	StepTag,
	StepTime,
	ToneIcon,
} from "./LineageStep";
import { chainReview, sameTraceNode, type TraceNode } from "./review-trace";
import { type StepProps, StoryNode, offPath, useStory } from "./TraceStoryNode";
import { TRACE_ROW_CELLS, TRACE_ROW_MAIN, TraceTable, traceRowGrid } from "./TraceTable";

/** The tones a step may take, the one that most needs a look first. */
const TONE_ORDER: readonly BadgeVariant[] = [
	"destructive",
	"warning",
	"secondary",
	"default",
	"outline",
	"success",
];

type Elsewhere = "an earlier review" | "a later review";

/** The tone of a piece's verdict: delivered, withheld, failed or waiting. */
function feedbackTone(feedback: LineageFeedback, item: ItemRef | undefined): BadgeVariant {
	const outcome = branchOutcome(feedback, item);
	return outcome.kind === "delivered" ? "success" : outcome.def.badgeVariant;
}

function composedElsewhere(
	feedback: LineageFeedback,
	records: LineageRecords,
	review: ReviewRef | undefined,
): Elsewhere | undefined {
	const basis = records.results.filter((result) => feedback.basis.includes(result.id));
	if (review === undefined || basis.length === 0) {
		return undefined;
	}
	if (basis.some((result) => result.reviewId === review.id)) {
		return undefined;
	}
	return basis.every((result) => result.recordedAt.getTime() < review.createdAt.getTime())
		? "an earlier review"
		: "a later review";
}

export function FeedbackTableStep({
	items,
	previous,
	...props
}: StepProps & { items: readonly LineageFeedback[]; previous: Date | undefined }) {
	const { display } = useStory();
	const { lineage, traced, here } = props;
	const records = lineageRecords(lineage);
	const [first] = items;
	if (first === undefined) {
		return null;
	}
	const tones = new Set(items.map((feedback) => feedbackTone(feedback, records.item)));
	const tone = TONE_ORDER.find((entry) => tones.has(entry)) ?? feedbackTone(first, records.item);
	const nodes = items.map((feedback): TraceNode => ({ kind: "feedback", id: feedback.id }));
	const review = chainReview(records);
	return (
		<Step
			dot={{ tone, here: nodes.some((node) => sameTraceNode(here, node)) }}
			at={first.deliveredAt ?? undefined}
			gapFrom={previous}
			timeInside
			muted={nodes.every((node) => offPath(traced, node))}
			data-step="feedback-table"
		>
			<TraceTable
				kind="feedback"
				times={display.times}
				label={countOf(items.length, "piece of feedback", "pieces of feedback")}
			>
				{items.map((feedback, index) => (
					<FeedbackRow
						key={feedback.id}
						{...props}
						feedback={feedback}
						item={records.item}
						elsewhere={composedElsewhere(feedback, records, review)}
						previous={index === 0 ? previous : (items[index - 1]?.deliveredAt ?? undefined)}
					/>
				))}
			</TraceTable>
		</Step>
	);
}

function FeedbackRow({
	feedback,
	item,
	elsewhere,
	previous,
	byId,
	letters,
	traced,
}: StepProps & {
	feedback: LineageFeedback;
	item: ItemRef | undefined;
	elsewhere: Elsewhere | undefined;
	previous: Date | undefined;
}) {
	const { display, factsOpen, here } = useStory();
	const node: TraceNode = { kind: "feedback", id: feedback.id };
	// Where the facts box opens under the row, it says all of this once; the row keeps its one line.
	const boxed = factsOpen || (display.system && sameTraceNode(here, node));
	const outcome = branchOutcome(feedback, item);
	const stateText = outcome.kind === "delivered" ? "Delivered" : outcome.text;
	const notes = [
		elsewhere === undefined ? undefined : `Composed by ${elsewhere}`,
		display.system ? outcome.detail : undefined,
	].filter(hasText);
	const letter = letters.get(feedback.id) ?? "";
	const muted = offPath(traced, node);
	const deliveredAt = feedback.deliveredAt ?? undefined;
	return (
		<li
			data-step="feedback"
			data-feedback-row={feedback.id}
			data-muted={muted}
			className={cn(muted && "text-muted-foreground")}
		>
			<OffPathNote muted={muted} />
			<StoryNode
				row
				node={node}
				className={cn(traceRowGrid("feedback", display.times), "px-1.5 py-1.5")}
			>
				<FeedbackLetter letter={letter} className="mt-px justify-self-start" />
				<span className={TRACE_ROW_CELLS}>
					<span className="min-w-0">
						<StepTag
							def={FEEDBACK_DESTINATION_DEFS[feedback.destination]}
							data-feedback-destination={feedback.destination}
						/>
					</span>
					<span className={TRACE_ROW_MAIN}>
						<span className="flex flex-wrap items-center gap-x-1.5 gap-y-0.5">
							<ToneIcon def={outcome.def} />
							<span className="font-medium">{stateText}</span>
						</span>
						{!boxed && notes.length > 0 && (
							<span className="mt-0.5 block text-muted-foreground">{notes.join(" · ")}</span>
						)}
					</span>
					<span className="min-w-0 text-muted-foreground">
						<FeedbackSources
							feedback={feedback}
							byId={byId}
							elsewhere={elsewhere}
							connectors={display.connections}
						/>
					</span>
					{display.times && (
						<span className="inline-flex flex-wrap items-baseline gap-x-1.5 text-muted-foreground @lg:justify-end @lg:text-right">
							{deliveredAt === undefined ? (
								<span>Not delivered</span>
							) : (
								<>
									<StepTime at={deliveredAt} previous={previous} />
									{previous !== undefined && previous.getTime() <= deliveredAt.getTime() && (
										<GapText from={previous} to={deliveredAt} />
									)}
								</>
							)}
						</span>
					)}
				</span>
			</StoryNode>
		</li>
	);
}

/** "+30 s" after a step's own time, coloured where the wait was long, where the trace shows times. */
function GapText({ from, to }: { from: Date; to: Date }) {
	const showTimes = use(LineageTimes);
	if (!showTimes) {
		return null;
	}
	const slow = isSlowGap((to.getTime() - from.getTime()) / 1000);
	return (
		<span
			className={cn(
				"text-2xs text-muted-foreground tabular-nums",
				slow && "font-medium text-warning",
			)}
		>
			{gapLabel(from, to)}
			<span className="sr-only">{slow ? " later, a long wait" : " later"}</span>
		</span>
	);
}

function FeedbackSources({
	feedback,
	byId,
	elsewhere,
	connectors,
}: {
	feedback: LineageFeedback;
	byId: NumberedById;
	elsewhere: Elsewhere | undefined;
	connectors: boolean;
}) {
	const listed = feedback.basis.filter((id) => byId.has(id)).length;
	if (elsewhere !== undefined && listed === 0) {
		return `From ${countOf(feedback.basis.length, "observation", "observations")} that review recorded`;
	}
	return (
		<LineageSourceChips
			feedback={feedback}
			byId={byId}
			elsewhere={elsewhere}
			connectors={connectors}
			fromClassName="@lg:sr-only"
		/>
	);
}
