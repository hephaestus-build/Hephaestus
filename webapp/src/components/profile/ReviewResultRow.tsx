import { cn } from "cn";
import { ChevronDownIcon, CircleHelpIcon, HistoryIcon } from "lucide-react";
import { Fragment, type ReactNode } from "react";

import type {
	EvidenceCitation,
	OpenQuestion,
	ResultDetail,
	ResultExample,
	ResultList,
} from "@/api/types.gen";
import { FOCUS_RING, FOCUS_RING_INSET } from "@/components/common/focus";
import { SectionLabel } from "@/components/common/SectionLabel";
import { statusToneClass } from "@/components/common/status-def";
import { StatusBadge } from "@/components/common/StatusBadge";
import { UNTRUSTED_MARKDOWN_PROSE, UntrustedMarkdown } from "@/components/common/UntrustedMarkdown";
import { ProblemList } from "@/components/practice-problems/ProblemList";
import {
	claimCurrentnessNote,
	NON_CURRENT,
} from "@/components/practice-vocabulary/claim-currentness";
import { OUTCOME_DEFS, outcomeLabel } from "@/components/practice-vocabulary/outcome-defs";
import { PracticePill } from "@/components/practice-vocabulary/PracticePill";
import { RESULT_MARK_DEFS } from "@/components/practice-vocabulary/result-mark-defs";
import { StatusTooltip } from "@/components/practice-vocabulary/StatusTooltip";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import { Skeleton } from "@/components/ui/skeleton";
import { rendersContent } from "@/lib/react-node";
import { hasText } from "@/lib/text";

import {
	type EvidenceCheck,
	type EvidenceLocation,
	toEvidenceCheck,
	toEvidenceLocations,
} from "./evidence";
import { EvidenceFileBlock } from "./EvidenceFileBlock";
import { type ObservationFeedbackLinks, ObservationFeedback } from "./ObservationFeedback";
import { resultSummary } from "./reviews";

// The reviewer's words are the content under each label, so they take the foreground, as the
// feedback card's do; the prose default would grey them into their labels.
function ReviewerText({ children }: { children: string }) {
	return (
		<div className={cn(UNTRUSTED_MARKDOWN_PROSE, "text-sm text-pretty text-foreground")}>
			<UntrustedMarkdown>{children}</UntrustedMarkdown>
		</div>
	);
}

interface DetailSectionProps {
	label: string;
	className?: string;
	children: ReactNode;
}

/**
 * One part of an open observation: its label, then what the review wrote. The label keeps the
 * section label's weight and muted color, and the content the foreground, so a reader scanning the
 * open row finds each part by its label.
 */
function DetailSection({ label, className, children }: DetailSectionProps) {
	return (
		<div className={cn("flex min-w-0 flex-col gap-1.5", className)}>
			<SectionLabel as="span">{label}</SectionLabel>
			{children}
		</div>
	);
}

export interface ReviewResultRowProps {
	/**
	 * A result as a list carries it — its verdict and its problems' summaries — or in full, with the
	 * proof behind it and the feedback it was the basis of.
	 */
	result: ResultList | ResultDetail;
	/** Open on arrival; a closed row waits for a press. The card placing the row decides which. */
	defaultOpen?: boolean;
	/** Off where every row is the level's own practice, so no row repeats its name. */
	showPracticeName?: boolean;
	/**
	 * The reviewed work, as a small line under the summary. A review's card gives it when the
	 * review carries this one result and the two are one block; otherwise the card's own head names
	 * the work above the rows and this is left out.
	 */
	work?: ReactNode;
	/** Full observation content, read lazily by the route when this row opens. */
	details?: ReactNode;
	/**
	 * The observation a link asked for: the current one (aria-current). It has no arrival highlight;
	 * a search match keeps its bar (`matched`).
	 */
	selected?: boolean;
	/** A match of the search over the history: the row wears the same bar, without being the current one. */
	matched?: boolean;
}

const NO_OPEN_QUESTIONS: OpenQuestion[] = [];

function isResultDetail(result: ResultList | ResultDetail): result is ResultDetail {
	return "explanation" in result;
}

function reasonsOf(result: ResultList | ResultDetail) {
	const detail = isResultDetail(result) ? result : undefined;
	return {
		explanation: detail?.explanation,
		problems: result.problems,
		openQuestions: detail?.evidence?.openQuestions ?? NO_OPEN_QUESTIONS,
		example: detail?.example ?? null,
		checks: toEvidenceCheck(detail?.evidence ?? null),
		locations: toEvidenceLocations(detail?.evidence?.citations ?? []),
	};
}

type ResultReasons = ReturnType<typeof reasonsOf>;

function hasReasons(reasons: ResultReasons): boolean {
	return (
		hasText(reasons.explanation) ||
		reasons.problems.length > 0 ||
		reasons.openQuestions.length > 0 ||
		reasons.example !== null ||
		reasons.checks.length > 0 ||
		reasons.locations.length > 0
	);
}

function ReasonSections({ reasons }: { reasons: ResultReasons }) {
	return (
		<>
			{hasText(reasons.explanation) && (
				<DetailSection label="Why it was noted">
					<ReviewerText>{reasons.explanation}</ReviewerText>
				</DetailSection>
			)}
			{reasons.problems.length > 0 && (
				<DetailSection label="Problems" className="gap-1.5">
					<ProblemList
						problems={reasons.problems}
						evidence={(problem) => <CitationBlocks citations={problem.citations} />}
						numbered
						raised
					/>
				</DetailSection>
			)}
			{reasons.openQuestions.length > 0 && (
				<DetailSection label="Open questions" className="gap-1.5">
					<OpenQuestionList questions={reasons.openQuestions} />
				</DetailSection>
			)}
			{reasons.example !== null && (
				<DetailSection label="Example from this work" className="gap-1.5">
					<ExampleFromTheWork example={reasons.example} />
				</DetailSection>
			)}
			{reasons.checks.length > 0 && (
				<DetailSection label="What was checked">
					<TermList pairs={reasons.checks} />
				</DetailSection>
			)}
			{reasons.locations.length > 0 && (
				<DetailSection label="Evidence" className="gap-1.5">
					<EvidenceBlocks locations={reasons.locations} />
				</DetailSection>
			)}
		</>
	);
}

export function ReviewResultRow({
	result,
	defaultOpen = true,
	showPracticeName = true,
	work,
	details,
	selected = false,
	matched = false,
}: ReviewResultRowProps) {
	const outcome = OUTCOME_DEFS[result.outcome];
	const OutcomeIcon = outcome.icon;
	const note = claimCurrentnessNote(result.currentness);
	const markedIncorrect = result.markedIncorrectAt !== null;
	const reasons = reasonsOf(result);
	const feedback = isResultDetail(result) ? result.feedback : [];
	const hasWorkLine = rendersContent(work);
	const hasBody =
		rendersContent(details) ||
		markedIncorrect ||
		note !== undefined ||
		hasReasons(reasons) ||
		feedback.length > 0;
	const summary = resultSummary(result);
	const showPill = showPracticeName && summary !== result.practiceName;

	const headLineClassName = cn(
		"flex w-full min-w-0 flex-wrap items-center gap-x-4 gap-y-1.5 px-4 pt-2.5",
		hasWorkLine ? "pb-1" : "pb-2.5",
	);
	const headLine = (
		<>
			<span className="flex min-w-0 basis-full flex-col items-start gap-1 text-sm sm:flex-1 sm:basis-0">
				<span className="font-medium text-foreground">{summary}</span>
				{result.summary === null && (
					<span className="text-xs text-muted-foreground">Observation text unavailable</span>
				)}
				{showPill && <PracticePill name={result.practiceName} />}
			</span>
			<span className="ml-auto flex min-w-0 flex-wrap items-center justify-end gap-x-2.5 gap-y-1">
				{/* No origin badge ("Requested"): how a review began is the review's, which the reviews
				    list says, not something this observation found. */}
				{markedIncorrect && <StatusBadge def={RESULT_MARK_DEFS.INCORRECT} />}
				{/* Inside the row's own button the chip is a span, the words visible beside the icon;
				    on a row that is no control it is the button a keyboard reaches the sentence by. */}
				<StatusTooltip
					def={outcome}
					render={hasBody ? <span /> : <button type="button" />}
					className={cn(
						"inline-flex items-center gap-1.5 text-sm font-medium whitespace-nowrap",
						statusToneClass(outcome.badgeVariant),
						!hasBody && FOCUS_RING,
					)}
				>
					<OutcomeIcon className="size-3.5 shrink-0" aria-hidden />
					{outcomeLabel(result.outcome, reasons.problems.length)}
				</StatusTooltip>
				{hasBody && (
					<ChevronDownIcon
						className="size-4 shrink-0 text-muted-foreground transition-transform group-data-[panel-open]/result:rotate-180"
						aria-hidden
					/>
				)}
			</span>
		</>
	);

	const rail = matched && (
		<span
			aria-hidden
			data-selected-rail
			className="absolute inset-y-0 left-0 z-10 w-0.5 bg-mentor"
		/>
	);

	// A row with no expandable detail must not create an empty focusable control.
	if (!hasBody) {
		return (
			<li className={cn(rail !== false && "relative")} aria-current={selected || undefined}>
				{rail}
				<div className={headLineClassName}>{headLine}</div>
				{hasWorkLine && <div className="px-4 pb-2.5">{work}</div>}
			</li>
		);
	}

	return (
		<li className={cn(rail !== false && "relative")} aria-current={selected || undefined}>
			{rail}
			<Collapsible className="group/result" defaultOpen={defaultOpen}>
				{/* The tint is the wrapper's, not the trigger's: with a work line under it the head is
				    two lines of one block, and tinting only the first would draw back the seam this
				    layout exists to remove. */}
				<div className="transition-colors hover:bg-muted/50">
					<CollapsibleTrigger
						className={cn(headLineClassName, "cursor-pointer text-left", FOCUS_RING_INSET)}
					>
						{headLine}
					</CollapsibleTrigger>
					{hasWorkLine && <div className="px-4 pb-2.5">{work}</div>}
				</div>
				<CollapsibleContent className="border-t bg-sidebar">
					<div className="flex min-w-0 flex-col gap-4 px-4 pt-3.5 pb-4">
						{details ?? <ReviewResultDetails result={result} />}
					</div>
				</CollapsibleContent>
			</Collapsible>
		</li>
	);
}

/**
 * The open observation while its details are on their way: a section label, two lines of words and
 * a box, in the shape the details take, so the reader sees something is coming. Busy for a screen
 * reader, which hears what loads.
 */
export function ObservationDetailSkeleton() {
	return (
		<div aria-busy="true" data-observation-loading="" className="flex min-w-0 flex-col gap-3">
			<span className="sr-only">Loading observation…</span>
			<div aria-hidden className="flex flex-col gap-1.5">
				<Skeleton className="h-4 w-28" />
				<Skeleton className="h-4 w-full max-w-xl" />
				<Skeleton className="h-4 w-3/5" />
			</div>
			<div aria-hidden className="flex flex-col gap-1.5">
				<Skeleton className="h-4 w-20" />
				<Skeleton className="h-20 w-full rounded-xl" />
			</div>
		</div>
	);
}

/** The observation body has one owner, whether its row carries full data or reads it on opening. */
export function ReviewResultDetails({
	result,
	feedbackLinks,
}: {
	result: ResultList | ResultDetail;
	/** Where feedback that lives in Hephaestus opens; see `ObservationFeedback`. */
	feedbackLinks?: ObservationFeedbackLinks;
}) {
	const markedIncorrect = result.markedIncorrectAt !== null;
	const note = claimCurrentnessNote(result.currentness);
	const reasons = reasonsOf(result);
	const feedback = isResultDetail(result) ? result.feedback : [];
	if (!markedIncorrect && note === undefined && !hasReasons(reasons) && feedback.length === 0) {
		return (
			<p className="text-sm text-muted-foreground">
				No explanation, evidence or feedback is available for this observation.
			</p>
		);
	}
	return (
		<>
			{markedIncorrect && (
				<p className="text-sm">
					<span className="font-medium">
						A workspace admin marked this observation as incorrect.
					</span>{" "}
					It no longer counts toward your standing or what Heph knows about your work.
				</p>
			)}
			{result.currentness !== "CURRENT" && note !== undefined && (
				<CurrentnessNotice currentness={result.currentness} note={note} />
			)}
			<ReasonSections reasons={reasons} />
			{feedback.length > 0 && (
				<DetailSection label="Feedback from this observation" className="gap-2">
					{/* Which feedback this observation gave, where it went and its delivery state, each in a
					    box with the way to read it there. No response controls: the reader answers the
					    feedback where it is. */}
					<ObservationFeedback feedback={feedback} links={feedbackLinks} />
				</DetailSection>
			)}
		</>
	);
}

/**
 * Terms and what the review wrote for each, as a two-column list: the term in its own column in the
 * section label's weight, the value aligned beside it, one rule between pairs. Below `sm` each term
 * sits over its value.
 */
function TermList({ pairs }: { pairs: EvidenceCheck[] }) {
	return (
		<dl className="grid min-w-0 grid-cols-1 overflow-hidden rounded-lg border bg-background text-sm sm:grid-cols-[minmax(7rem,max-content)_minmax(0,1fr)]">
			{pairs.map(({ term, detail }, index) => (
				<Fragment key={term}>
					<dt
						className={cn(
							"px-3 pt-2 font-semibold text-muted-foreground sm:pb-2",
							index > 0 && "border-t",
						)}
					>
						{term}
					</dt>
					<dd className={cn("min-w-0 px-3 pb-2 sm:pt-2", index > 0 && "sm:border-t")}>
						<ReviewerText>{detail}</ReviewerText>
					</dd>
				</Fragment>
			))}
		</dl>
	);
}

/**
 * Why an observation may not describe the work as it stands, as the notice it is: a bordered line
 * led by an icon in the currentness tone, not one more grey line among the details.
 */
function CurrentnessNotice({
	currentness,
	note,
}: {
	currentness: Exclude<ResultList["currentness"], "CURRENT">;
	note: string;
}) {
	const def = NON_CURRENT[currentness];
	const Icon = currentness === "STALE" ? HistoryIcon : CircleHelpIcon;
	return (
		<p
			role="note"
			className="flex min-w-0 items-start gap-2 rounded-lg border bg-background px-3 py-2 text-sm text-foreground"
		>
			<Icon
				aria-hidden
				className={cn("mt-0.5 size-4 shrink-0", statusToneClass(def.badgeVariant))}
			/>
			<span className="min-w-0">{note}</span>
		</p>
	);
}

function EvidenceBlocks({ locations }: { locations: EvidenceLocation[] }) {
	return (
		<div className="flex min-w-0 flex-col gap-2">
			{locations.map((location) => (
				<EvidenceFileBlock
					// Fold a diff pair only when both sides retain quotes; missing quotes carry distinct evidence limits.
					key={`${location.sourceKind}-${location.path ?? ""}-${location.commit ?? ""}-${location.startLine ?? ""}-${location.side ?? ""}-${location.snippet === undefined}`}
					location={location}
				/>
			))}
		</div>
	);
}

function CitationBlocks({ citations }: { citations: EvidenceCitation[] }) {
	const locations = toEvidenceLocations(citations);
	return locations.length > 0 ? <EvidenceBlocks locations={locations} /> : null;
}

const ITEM_CLASS = "flex min-w-0 flex-col gap-1.5 rounded-xl border bg-background p-3";

// Open questions describe uncertainty, so they cannot become feedback claims.
function OpenQuestionList({ questions }: { questions: OpenQuestion[] }) {
	return (
		<ul aria-label="Open questions" className="flex min-w-0 flex-col gap-2">
			{questions.map((question) => (
				<li key={`${question.openQuestion}-${question.wouldSettleIt}`} className={ITEM_CLASS}>
					<ReviewerText>{question.openQuestion}</ReviewerText>
					<TermList pairs={[{ term: "Would settle it", detail: question.wouldSettleIt }]} />
					{question.citation !== null && <CitationBlocks citations={[question.citation]} />}
				</li>
			))}
		</ul>
	);
}

// An example is evidence about the work, not praise of its author.
function ExampleFromTheWork({ example }: { example: ResultExample }) {
	return (
		<div className="flex min-w-0 flex-col gap-1.5">
			<ReviewerText>{example.note}</ReviewerText>
			<CitationBlocks citations={[example.citation]} />
		</div>
	);
}
