import { cn } from "cn";
import { ChevronDownIcon } from "lucide-react";
import { Fragment, type ReactNode, useId, useState } from "react";

import type { FeedbackResponseRequest, ObservationDetail } from "@/api/types.gen";
import { FOCUS_RING, FOCUS_RING_INSET } from "@/components/common/focus";
import { ResponseButton, toneOf } from "@/components/common/ResponseButton";
import { ResponseCommentBand } from "@/components/common/ResponseCommentBand";
import { SectionLabel } from "@/components/common/SectionLabel";
import { statusToneClass, statusValues } from "@/components/common/status-def";
import { StatusBadge } from "@/components/common/StatusBadge";
import { UNTRUSTED_MARKDOWN_PROSE, UntrustedMarkdown } from "@/components/common/UntrustedMarkdown";
import { claimCurrentnessNote } from "@/components/practice-vocabulary/ClaimCurrentness";
import {
	FEEDBACK_RESOLUTION_DEFS,
	type FeedbackResolution,
} from "@/components/practice-vocabulary/feedback-resolution-defs";
import { FEEDBACK_USEFULNESS_DEFS } from "@/components/practice-vocabulary/feedback-usefulness-defs";
import { OBSERVATION_ORIGIN_DEFS } from "@/components/practice-vocabulary/observation-origin-defs";
import {
	OBSERVATION_OUTCOME_PRESENTATION,
	observationOutcome,
} from "@/components/practice-vocabulary/observation-outcome";
import { PracticePill } from "@/components/practice-vocabulary/PracticePill";
import { StatusTooltip } from "@/components/practice-vocabulary/StatusTooltip";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import { rendersContent } from "@/lib/react-node";
import { capitalise, hasText } from "@/lib/text";

import { toEvidenceCheck, toEvidenceLocations } from "./evidence";
import { EvidenceFileBlock } from "./EvidenceFileBlock";

/**
 * The reviewer's own words about this observation, in the Markdown it writes them in: a file, a
 * field or a value it quotes comes back as code rather than as a line of stray backticks. The
 * same restricted rendering the feedback card's body gets, so the two never drift apart.
 */
function ReviewerText({ children }: { children: string }) {
	return (
		<div className={cn(UNTRUSTED_MARKDOWN_PROSE, "text-sm text-pretty")}>
			<UntrustedMarkdown>{children}</UntrustedMarkdown>
		</div>
	);
}

interface DetailSectionProps {
	label: string;
	labelId?: string;
	className?: string;
	children: ReactNode;
}

/** A section of the open row: its label in the muted kicker, then what it carries. */
function DetailSection({ label, labelId, className, children }: DetailSectionProps) {
	return (
		<div className={cn("flex min-w-0 flex-col gap-1", className)}>
			{/* The row's anchor is its summary, so a label naming one of its details carries no
			    weight of its own. */}
			<SectionLabel id={labelId} as="span" className="font-normal">
				{label}
			</SectionLabel>
			{children}
		</div>
	);
}

export interface ReviewObservationRowProps {
	observation: ObservationDetail;
	/** Open on arrival; a closed row waits for a press. The card placing the row decides which. */
	defaultOpen?: boolean;
	/** Off where every row is the level's own practice, so no row repeats its name. */
	showPracticeName?: boolean;
	/**
	 * The reviewed work, as a small line under the summary. A run's card gives it when the run
	 * carries this one observation and the two are one block; otherwise the card's own head names
	 * the work above the rows and this is left out.
	 */
	work?: ReactNode;
	onRespond?: (observation: ObservationDetail, response: FeedbackResponseRequest) => void;
	/**
	 * The response being written to this observation's feedback: shown over the one it arrived
	 * with, and its buttons wait until the write lands.
	 */
	pendingResponse?: FeedbackResponseRequest;
}

/**
 * One observation as a row of its review's card. The outcome is what the reader acts on, so the
 * severity behind it stays in the workspace's admin console rather than ranking the reader's own
 * work here.
 */
export function ReviewObservationRow({
	observation,
	defaultOpen = true,
	showPracticeName = true,
	work,
	onRespond,
	pendingResponse,
}: ReviewObservationRowProps) {
	const outcome = OBSERVATION_OUTCOME_PRESENTATION[observationOutcome(observation)];
	const OutcomeIcon = outcome.icon;
	const note = claimCurrentnessNote(observation.claimCurrentness);
	const evidenceLocations = toEvidenceLocations(observation.evidence);
	const checks = toEvidenceCheck(observation.evidence);
	// The sentence the review wrote about this work stands over the one that was delivered: the
	// delivery may have been withheld, replaced or rewritten, and only one next step can be acted on.
	// Either is written as a clause, and the row shows it as a sentence.
	const nextStep = capitalise(observation.nextStep ?? observation.deliveredFeedback ?? "");
	const detector = observation.evidence?.detector;
	// A response needs feedback to respond to and a route that records it.
	const respondTo = hasText(observation.feedbackResponse?.feedbackId) ? onRespond : undefined;
	const hasWorkLine = rendersContent(work);
	const hasBody =
		note !== undefined ||
		hasText(observation.evidenceRationale) ||
		checks.length > 0 ||
		evidenceLocations.length > 0 ||
		hasText(nextStep) ||
		respondTo !== undefined;
	// The summary is the row's anchor and the heaviest text in it; the practice under it is the pill
	// every practice surface names a practice with, and it stands alone when the observation has no
	// words of its own.
	const summary = observation.summary.trim();
	const showPill = showPracticeName || summary.length === 0;

	// At the reflow width the summary takes the whole line and the outcome and the badges sit under
	// it, at the right, wrapping among themselves when even that line is too narrow; from `sm` up
	// they share the line with the summary.
	const headLineClassName = cn(
		"flex w-full min-w-0 flex-wrap items-center gap-x-4 gap-y-1.5 px-4 pt-2.5",
		// The work line below is the same block's second line, so the padding closes under it.
		hasWorkLine ? "pb-1" : "pb-2.5",
	);
	const headLine = (
		<>
			<span className="flex min-w-0 basis-full flex-col items-start gap-1 text-sm sm:flex-1 sm:basis-0">
				{summary.length > 0 && <span className="font-medium text-foreground">{summary}</span>}
				{showPill && <PracticePill name={observation.practiceName} />}
			</span>
			<span className="ml-auto flex min-w-0 flex-wrap items-center justify-end gap-x-2.5 gap-y-1">
				{observation.origin !== "LIVE" && (
					<StatusBadge def={OBSERVATION_ORIGIN_DEFS[observation.origin]} />
				)}
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
					{outcome.label}
				</StatusTooltip>
				{hasBody && (
					<ChevronDownIcon
						className="size-4 shrink-0 text-muted-foreground transition-transform group-data-[panel-open]/observation:rotate-180"
						aria-hidden
					/>
				)}
			</span>
		</>
	);

	// A row with nothing under its head line is not a control: a button that opens nothing would
	// still take focus and a pointer.
	if (!hasBody) {
		return (
			<li>
				<div className={headLineClassName}>{headLine}</div>
				{hasWorkLine && <div className="px-4 pb-2.5">{work}</div>}
			</li>
		);
	}

	return (
		<li>
			<Collapsible className="group/observation" defaultOpen={defaultOpen}>
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
					<div className="flex min-w-0 flex-col gap-3.5 px-4 pt-3.5 pb-4">
						{note !== undefined && <p className="text-sm text-muted-foreground">{note}</p>}
						{hasText(observation.evidenceRationale) && (
							<DetailSection label="Why it was noted">
								<ReviewerText>{observation.evidenceRationale}</ReviewerText>
							</DetailSection>
						)}
						{checks.length > 0 && (
							<DetailSection label="What was checked">
								{/* A definition list, so the term and what the review wrote for it reach a screen
								    reader as one pair rather than as two sentences in a row. */}
								<dl className="grid min-w-0 grid-cols-1 gap-x-3 gap-y-1 text-sm sm:grid-cols-[auto_1fr]">
									{checks.map(({ term, detail }) => (
										<Fragment key={term}>
											<dt className="text-muted-foreground">{term}:</dt>
											<dd className="min-w-0">
												<ReviewerText>{detail}</ReviewerText>
											</dd>
										</Fragment>
									))}
								</dl>
							</DetailSection>
						)}
						{evidenceLocations.length > 0 && (
							<DetailSection label="Evidence" className="gap-1.5">
								<div className="flex min-w-0 flex-col gap-2">
									{evidenceLocations.map((location) => (
										<EvidenceFileBlock
											// A diff pair only folds when both sides carry a quote, so a redacted side leaves
											// two citations of the same lines apart: the side and the redaction tell them apart.
											key={`${location.sourceKind}-${location.path}-${location.revision ?? ""}-${location.startLine}-${location.side ?? ""}-${location.redacted}`}
											location={location}
											detector={detector}
										/>
									))}
									{hasText(detector) && (
										<p className="text-xs text-muted-foreground">
											Captured by <code className="font-mono">{detector}</code>
										</p>
									)}
								</div>
							</DetailSection>
						)}
						{hasText(nextStep) && (
							<DetailSection label="Next step">
								<ReviewerText>{nextStep}</ReviewerText>
							</DetailSection>
						)}
					</div>
					{respondTo && (
						<ObservationResponse
							observation={observation}
							onRespond={respondTo}
							pendingResponse={pendingResponse}
						/>
					)}
				</CollapsibleContent>
			</Collapsible>
		</li>
	);
}

interface ObservationResponseProps {
	observation: ObservationDetail;
	onRespond: NonNullable<ReviewObservationRowProps["onRespond"]>;
	pendingResponse?: FeedbackResponseRequest;
}

/**
 * The reader's response under the row: the three resolutions, what already stands — the rating
 * given on the feedback card and the comment sent with the response — and the comment band a
 * press opens. The endpoint replaces, so the usefulness the observation arrived with travels every
 * time.
 */
function ObservationResponse({
	observation,
	onRespond,
	pendingResponse,
}: ObservationResponseProps) {
	const labelId = useId();
	// The resolution whose comment band is open; recorded only once Send or Skip closes it.
	const [pendingResolution, setPendingResolution] = useState<FeedbackResolution>();
	const isPending = pendingResponse !== undefined;
	// The response being written stands over the recorded one, so the buttons do not jump back and
	// then forward again when it lands.
	const current: FeedbackResponseRequest = pendingResponse ?? observation.feedbackResponse ?? {};
	const shownResolution = pendingResolution ?? current.resolution;
	const respond = (change: Pick<FeedbackResponseRequest, "resolution" | "comment">) => {
		onRespond(observation, { usefulness: current.usefulness, ...change });
	};
	// A press opens the band for that choice; the chosen one pressed again withdraws it, comment
	// and all, and a band still open closes without recording anything.
	const chooseResolution = (resolution: FeedbackResolution) => {
		if (pendingResolution === resolution) {
			setPendingResolution(undefined);
			return;
		}
		if (current.resolution === resolution) {
			setPendingResolution(undefined);
			respond({ resolution: undefined, comment: undefined });
			return;
		}
		setPendingResolution(resolution);
	};
	const record = (comment: string | undefined) => {
		if (pendingResolution === undefined) {
			return;
		}
		setPendingResolution(undefined);
		respond({ resolution: pendingResolution, comment });
	};
	return (
		<>
			<DetailSection label="Your response" labelId={labelId} className="gap-2.5 px-4 pb-4">
				<div role="group" aria-labelledby={labelId} className="flex flex-wrap items-center gap-2">
					{statusValues(FEEDBACK_RESOLUTION_DEFS).map((value) => {
						const def = FEEDBACK_RESOLUTION_DEFS[value];
						return (
							<ResponseButton
								key={value}
								tone={toneOf(def.badgeVariant)}
								pressed={shownResolution === value}
								disabled={isPending}
								className="bg-background"
								onClick={() => chooseResolution(value)}
							>
								{def.label}
							</ResponseButton>
						);
					})}
				</div>
				{/* What the reader already said: the rating given on the feedback card, and the
				    comment sent with the response that stands. */}
				{(current.usefulness !== undefined || hasText(current.comment)) && (
					<p className="flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
						{current.usefulness !== undefined && (
							<StatusBadge def={FEEDBACK_USEFULNESS_DEFS[current.usefulness]} />
						)}
						{hasText(current.comment) && <q>{current.comment}</q>}
					</p>
				)}
			</DetailSection>
			{pendingResolution === "DISPUTED" && (
				// The sentence is what a dispute has to carry, so Skip only closes the band.
				<ResponseCommentBand
					key={pendingResolution}
					name="Why you dispute this"
					label="What was missed?"
					placeholder="One or two sentences on what is off"
					required
					isPending={isPending}
					onSend={({ comment }) => {
						const sentence = comment.trim();
						if (hasText(sentence)) {
							record(sentence);
						}
					}}
					onSkip={() => setPendingResolution(undefined)}
				/>
			)}
			{pendingResolution !== undefined && pendingResolution !== "DISPUTED" && (
				<ResponseCommentBand
					key={pendingResolution}
					name={pendingResolution === "ADDRESSED" ? "What you changed" : "Why this does not apply"}
					label="Anything to add?"
					placeholder="Optional: a note to yourself"
					isPending={isPending}
					onSend={({ comment }) => record(comment.trim() || undefined)}
					onSkip={() => record(undefined)}
				/>
			)}
		</>
	);
}
