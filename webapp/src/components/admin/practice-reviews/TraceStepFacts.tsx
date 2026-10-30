import { ArrowRightIcon, ExternalLinkIcon } from "lucide-react";
import type { ReactElement, ReactNode } from "react";

import type { ItemRef, ReviewDetail, ReviewLineage } from "@/api/types.gen";
import { useNow } from "@/components/common/use-now";
import { claimCurrentnessNote } from "@/components/practice-vocabulary/claim-currentness";
import { feedbackLocationSentence } from "@/components/practice-vocabulary/feedback-location-defs";
import { OUTCOME_DEFS } from "@/components/practice-vocabulary/outcome-defs";
import { SEVERITY_DEFS } from "@/components/practice-vocabulary/severity-defs";
import { formatDayTime } from "@/lib/dates";
import { hasText } from "@/lib/text";

import { firstStartedAt, latestAttempt } from "./job-utils";
import {
	countOf,
	feedbackLocation,
	gapLabel,
	isSlowGap,
	type LineageFeedback,
	type LineageRecords,
	lineageRecords,
	type NumberedResult,
	stageCounts,
} from "./lineage-chain";
import { sameTraceNode, type TraceNode } from "./review-trace";
import { feedbackLoss, traceReview } from "./trace-bottleneck";
import { traceVerdict } from "./trace-verdict";

export interface TraceStepFactsProps {
	lineage: ReviewLineage;
	/** The step whose facts these are. */
	node: TraceNode;
	numbered: readonly NumberedResult[];
	/** The record the trace was opened for, whose verdict already heads the trace. */
	here?: TraceNode;
	review?: ReviewDetail;
	/**
	 * Whether every step shows its facts, as "Expand all" asks. The results' table then names
	 * each practice, so a piece of feedback does not name them again.
	 */
	everyStep?: boolean;
}

export function traceStepFacts(props: TraceStepFactsProps): ReactElement | null {
	const { node, lineage, numbered, here, review, everyStep = false } = props;
	const records = lineageRecords(lineage);
	// The record the trace stands on already heads it with its verdict.
	const told = sameTraceNode(here, node);
	switch (node.kind) {
		case "end":
		case "results": {
			return null;
		}
		case "work": {
			return records.item === undefined ? null : <ItemFacts item={records.item} />;
		}
		case "review": {
			return <ReviewFacts records={records} detail={review} />;
		}
		case "result": {
			const entry = numbered.find((candidate) => candidate.result.id === node.id);
			return entry === undefined ? null : (
				<ResultFacts lineage={lineage} entry={entry} told={told} />
			);
		}
		case "feedback": {
			const feedback = records.feedback.find((candidate) => candidate.id === node.id);
			return feedback === undefined ? null : (
				<FeedbackFacts
					feedback={feedback}
					lineage={lineage}
					item={records.item}
					numbered={numbered}
					told={told}
					namesSources={!everyStep}
				/>
			);
		}
	}
}

function ResultFacts({
	lineage,
	entry,
	told,
}: {
	lineage: ReviewLineage;
	entry: NumberedResult;
	told: boolean;
}) {
	const { result } = entry;
	const node: TraceNode = { kind: "result", id: result.id };
	const currentness = claimCurrentnessNote(result.currentness);
	return (
		<FactList>
			{!told && <Fact label="Outcome">{traceVerdict(lineage, node).sentence}</Fact>}
			<Fact label="Recorded">
				<DayTime at={result.recordedAt} />
			</Fact>
			{currentness !== undefined && <Fact label="Current">{currentness}</Fact>}
		</FactList>
	);
}

/** A label and its value, in two aligned columns. */
function Fact({ label, children }: { label: string; children: ReactNode }) {
	return (
		<>
			<dt className="text-muted-foreground">{label}</dt>
			<dd className="min-w-0 break-words text-foreground">{children}</dd>
		</>
	);
}

function FactList({ children }: { children: ReactNode }) {
	return <dl className="grid grid-cols-[5.75rem_minmax(0,1fr)] gap-x-3 gap-y-0.5">{children}</dl>;
}

function DayTime({ at, from }: { at: Date; from?: Date | null }) {
	const today = new Date(useNow());
	const slow = from != null && isSlowGap((at.getTime() - from.getTime()) / 1000);
	return (
		<span className="inline-flex flex-wrap items-baseline gap-x-1.5">
			<time dateTime={at.toISOString()} className="tabular-nums">
				{formatDayTime(at, today)}
			</time>
			{from != null && from.getTime() <= at.getTime() && (
				<span
					className={
						slow
							? "text-2xs font-medium text-warning tabular-nums"
							: "text-2xs text-muted-foreground tabular-nums"
					}
				>
					{gapLabel(from, at)}
				</span>
			)}
		</span>
	);
}

const PROVIDER_NAMES: Record<NonNullable<ItemRef["provider"]>, string> = {
	GITHUB: "GitHub",
	GITLAB: "GitLab",
	SLACK: "Slack",
	OUTLINE: "Outline",
};

function ItemFacts({ item }: { item: ItemRef }) {
	if (!hasText(item.url) && item.removedAt === null) {
		return null;
	}
	return (
		<FactList>
			{hasText(item.url) && (
				<Fact label="Link">
					<a
						href={item.url}
						target="_blank"
						rel="noopener noreferrer"
						className="inline-flex items-center gap-1 text-primary underline-offset-2 hover:underline"
					>
						{`Open on ${item.provider === null ? "its site" : PROVIDER_NAMES[item.provider]}`}
						<ExternalLinkIcon aria-hidden className="size-3" />
					</a>
				</Fact>
			)}
			{item.removedAt !== null && (
				<Fact label="Removed">
					<DayTime at={item.removedAt} />
				</Fact>
			)}
		</FactList>
	);
}

function StageLine({ records }: { records: LineageRecords }) {
	const counts = stageCounts(records);
	const stages = [
		countOf(counts.results, "listed observation", "listed observations"),
		countOf(counts.feedback, "listed piece of feedback", "listed pieces of feedback"),
		`${counts.reached} reached the developer`,
	];
	return (
		<span className="flex flex-wrap items-center gap-1">
			{stages.map((stage, index) => (
				<span key={stage} className="inline-flex items-center gap-1">
					{index > 0 && <ArrowRightIcon aria-hidden className="size-3 text-muted-foreground" />}
					{stage}
				</span>
			))}
		</span>
	);
}

function ReviewFacts({
	records,
	detail,
}: {
	records: LineageRecords;
	detail: ReviewDetail | undefined;
}) {
	const review = traceReview(records, detail);
	if (review === undefined) {
		return null;
	}
	const jobs = detail?.runtime?.jobs ?? [];
	// Without the first pickup, Finished counts from the request rather than from a later attempt.
	const pickedUp = firstStartedAt(jobs);
	const latest = latestAttempt(jobs);
	return (
		<FactList>
			<Fact label="Asked for">
				<DayTime at={review.createdAt} />
			</Fact>
			{pickedUp !== undefined && (
				<Fact label="Picked up">
					<DayTime at={pickedUp} from={review.createdAt} />
				</Fact>
			)}
			{review.completedAt !== null && (
				<Fact label="Finished">
					<DayTime at={review.completedAt} from={pickedUp ?? review.createdAt} />
				</Fact>
			)}
			{jobs.length > 1 && (
				<Fact label="Attempts">{countOf(jobs.length, "attempt", "attempts")}</Fact>
			)}
			{latest !== undefined && <Fact label="Latest attempt">{latest}</Fact>}
			{hasText(detail?.model) && <Fact label="Model">{detail.model}</Fact>}
			<Fact label="How far">
				<StageLine records={records} />
			</Fact>
		</FactList>
	);
}

/**
 * What became of a piece, said without the trace's opening words: why it was lost where it was,
 * otherwise where its path ended.
 */
function outcomeText(feedback: LineageFeedback, lineage: ReviewLineage): string {
	const loss = feedbackLoss(feedback);
	if (loss !== undefined) {
		return loss.text;
	}
	const { end } = traceVerdict(lineage, { kind: "feedback", id: feedback.id });
	return `${end.label}: ${end.reason}.`;
}

function FeedbackFacts({
	feedback,
	lineage,
	item,
	numbered,
	told,
	namesSources,
}: {
	feedback: LineageFeedback;
	lineage: ReviewLineage;
	item: ItemRef | undefined;
	numbered: readonly NumberedResult[];
	told: boolean;
	namesSources: boolean;
}) {
	const location = feedbackLocation(feedback, item);
	const sources = numbered.filter(({ result }) => feedback.basis.includes(result.id));
	return (
		<FactList>
			<Fact label="Destination">{feedbackLocationSentence(location, item ?? null)}</Fact>
			{!told && <Fact label="Outcome">{outcomeText(feedback, lineage)}</Fact>}
			{feedback.deliveredAt !== null && (
				<Fact label="Delivered">
					<DayTime at={feedback.deliveredAt} />
				</Fact>
			)}
			{namesSources && sources.length > 0 && (
				<Fact label="Written from">
					<ul className="space-y-0.5">
						{sources.map(({ result, number }) => (
							<li key={result.id}>
								<span className="text-muted-foreground tabular-nums">{number}</span>{" "}
								{result.practiceName}
								<span className="text-muted-foreground">
									{` · ${OUTCOME_DEFS[result.outcome].label.toLowerCase()}${
										result.severity === null
											? ""
											: `, ${SEVERITY_DEFS[result.severity].label.toLowerCase()}`
									}`}
								</span>
							</li>
						))}
					</ul>
				</Fact>
			)}
		</FactList>
	);
}
