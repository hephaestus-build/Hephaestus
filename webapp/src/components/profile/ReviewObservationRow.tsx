import { ChevronDownIcon } from "lucide-react";
import { Fragment, type ReactNode, useState } from "react";

import type { ObservationDetail } from "@/api/types.gen";
import { FOCUS_RING, FOCUS_RING_INSET } from "@/components/common/focus";
import { InlineLink } from "@/components/common/InlineLink";
import {
	ResponseButton,
	ResponseCommentBand,
	toneOf,
} from "@/components/common/ResponseCommentBand";
import { SectionLabel } from "@/components/common/SectionLabel";
import { UNTRUSTED_MARKDOWN_PROSE, UntrustedMarkdown } from "@/components/common/UntrustedMarkdown";
import { CLAIM_CURRENTNESS_NOTES } from "@/components/practice-vocabulary/claim-currentness-notes";
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
import { statusValues } from "@/components/practice-vocabulary/status-def";
import { StatusBadge } from "@/components/practice-vocabulary/StatusBadge";
import { StatusTooltip } from "@/components/practice-vocabulary/StatusTooltip";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import { artifactKindLabel } from "@/lib/artifact-kinds";
import { capitalise } from "@/lib/text";
import { cn } from "@/lib/utils";

import { toEvidenceCheck, toEvidenceLocations } from "./evidence";
import { EvidenceFileBlock } from "./EvidenceFileBlock";
import { type FeedbackResponse, feedbackResponseOf } from "./review-runs";

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
	className?: string;
	children: ReactNode;
}

/** A section of the open row: its label in the muted kicker, then what it carries. */
function DetailSection({ label, className, children }: DetailSectionProps) {
	return (
		<div className={cn("flex min-w-0 flex-col gap-1", className)}>
			{/* The row's anchor is its summary, so a label naming one of its details carries no
			    weight of its own. */}
			<SectionLabel as="span" className="font-normal">
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
	/** Off where the row sits under the work's own head, as it does in a review run's card. */
	showWorkLink?: boolean;
	/**
	 * The reviewed work, as a small line under the summary. A run's card gives it when the run
	 * carries this one observation and the two are one block; otherwise the card's own head names
	 * the work above the rows and this is left out.
	 */
	work?: ReactNode;
	onRespond?: (observation: ObservationDetail, response: FeedbackResponse) => void;
	isFeedbackResponsePending?: boolean;
}

/**
 * One observation as a row of its review's card, showing everything the feed carries about it:
 * its summary and outcome on one line and, when not live, where it came from; then why it was
 * noted, what the review checked to say it, the evidence, the next step and the reader's
 * response. The outcome is what the reader
 * acts on, so the severity behind it stays in the admin console rather than ranking the reader's
 * own work here. The day is the card's, named once
 * on the timeline above these rows, so no row repeats it; the work is the card's too, unless the
 * card handed this row a `work` line because the two are one block. Each row opens and closes on its own,
 * and nothing is loaded when it does — the feed already brought it all — so the open state is the
 * row's, through the collapsible's own `defaultOpen`. A block whose field is absent is left out
 * rather than headed over nothing.
 */
export function ReviewObservationRow({
	observation,
	defaultOpen = true,
	showPracticeName = true,
	showWorkLink = true,
	work,
	onRespond,
	isFeedbackResponsePending = false,
}: ReviewObservationRowProps) {
	// The resolution whose comment band is open; recorded only once Send or Skip closes it.
	const [pendingResolution, setPendingResolution] = useState<FeedbackResolution>();
	const outcome = OBSERVATION_OUTCOME_PRESENTATION[observationOutcome(observation)];
	const OutcomeIcon = outcome.icon;
	const note =
		observation.claimCurrentness === "CURRENT"
			? undefined
			: CLAIM_CURRENTNESS_NOTES[observation.claimCurrentness];
	const evidenceLocations = toEvidenceLocations(observation.evidence);
	const checks = toEvidenceCheck(observation.evidence);
	// The sentence the review wrote about this work stands over the one that was delivered: the
	// delivery may have been withheld, replaced or rewritten, and only one next step can be acted on.
	// Either is written as a clause, and the row shows it as a sentence.
	const nextStep = capitalise(observation.nextStep ?? observation.deliveredFeedback ?? "");
	const workLink = showWorkLink ? observation.artifactUrl : undefined;
	const detector = observation.evidence?.detector;
	const canRespond = Boolean(observation.feedbackId && onRespond);
	const hasBody =
		note !== undefined ||
		Boolean(observation.evidenceRationale) ||
		checks.length > 0 ||
		evidenceLocations.length > 0 ||
		workLink !== undefined ||
		Boolean(nextStep) ||
		canRespond;
	// The summary is the row's anchor and the heaviest text in it; the practice under it is the pill
	// every practice surface names a practice with, and it stands alone when the observation has no
	// words of its own.
	const summary = observation.summary.trim();
	const showPill = showPracticeName || summary.length === 0;

	const recorded = feedbackResponseOf(observation);
	const shownResolution = pendingResolution ?? recorded.resolution;
	// The endpoint replaces, so the usefulness the observation arrived with travels every time.
	const respond = (change: Pick<FeedbackResponse, "resolution" | "comment">) => {
		if (!observation.feedbackId || !onRespond) return;
		onRespond(observation, { usefulness: recorded.usefulness, ...change });
	};
	// A press opens the band for that choice; the chosen one pressed again withdraws it, comment
	// and all, and a band still open closes without recording anything.
	const chooseResolution = (resolution: FeedbackResolution) => {
		if (pendingResolution === resolution) {
			setPendingResolution(undefined);
			return;
		}
		if (recorded.resolution === resolution) {
			setPendingResolution(undefined);
			respond({ resolution: undefined, comment: undefined });
			return;
		}
		setPendingResolution(resolution);
	};
	const record = (comment: string | undefined) => {
		if (!pendingResolution) return;
		setPendingResolution(undefined);
		respond({ resolution: pendingResolution, comment });
	};

	// At the reflow width the summary takes the whole line and the outcome and the badges sit under
	// it, at the right, wrapping among themselves when even that line is too narrow; from `sm` up
	// they share the line with the summary.
	const headLineClassName = cn(
		"flex w-full min-w-0 flex-wrap items-center gap-x-4 gap-y-1.5 px-4 pt-2.5",
		// The work line below is the same block's second line, so the padding closes under it.
		work ? "pb-1" : "pb-2.5",
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
						"whitespace-nowrap inline-flex items-center gap-1.5 text-sm font-medium",
						outcome.className,
						!hasBody && ["rounded-sm", FOCUS_RING],
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
				{work && <div className="px-4 pb-2.5">{work}</div>}
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
					{work && <div className="px-4 pb-2.5">{work}</div>}
				</div>
				<CollapsibleContent className="border-t bg-sidebar">
					<div className="flex min-w-0 flex-col gap-3.5 px-4 pt-3.5 pb-4">
						{note && <p className="text-sm text-muted-foreground">{note}</p>}
						{observation.evidenceRationale && (
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
						{(evidenceLocations.length > 0 || workLink) && (
							<DetailSection label="Evidence" className="gap-1.5">
								<div className="flex min-w-0 flex-col gap-2">
									{evidenceLocations.map((location) => (
										<EvidenceFileBlock
											key={`${location.sourceKind}-${location.path}-${location.startLine}`}
											location={location}
											detector={detector}
										/>
									))}
									{(detector !== undefined || workLink !== undefined) && (
										<p className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-foreground">
											{detector && (
												<span>
													Captured by <code className="font-mono">{detector}</code>
												</span>
											)}
											{workLink && (
												<InlineLink href={workLink} external className="text-xs">
													Open the {artifactKindLabel(observation.artifactKind).toLowerCase()}
												</InlineLink>
											)}
										</p>
									)}
								</div>
							</DetailSection>
						)}
						{nextStep && (
							<DetailSection label="Next step">
								<ReviewerText>{nextStep}</ReviewerText>
							</DetailSection>
						)}
						{canRespond && (
							<DetailSection label="Your response" className="gap-2.5">
								<div className="flex flex-wrap items-center gap-2">
									{statusValues(FEEDBACK_RESOLUTION_DEFS).map((value) => {
										const def = FEEDBACK_RESOLUTION_DEFS[value];
										const isChosen = shownResolution === value;
										return (
											<ResponseButton
												key={value}
												tone={toneOf(def.badgeVariant)}
												pressed={isChosen}
												disabled={isFeedbackResponsePending}
												className="bg-background"
												onClick={() => chooseResolution(value)}
											>
												{def.label}
											</ResponseButton>
										);
									})}
								</div>
								{/* What the reader already said: the rating given on the feedback card, and
									    the comment sent with the response that stands. */}
								{(recorded.usefulness !== undefined || recorded.comment !== undefined) && (
									<p className="flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
										{recorded.usefulness && (
											<StatusBadge def={FEEDBACK_USEFULNESS_DEFS[recorded.usefulness]} />
										)}
										{recorded.comment && <q>{recorded.comment}</q>}
									</p>
								)}
							</DetailSection>
						)}
					</div>
					{canRespond &&
						pendingResolution === "DISPUTED" && (
							// The sentence is what a dispute has to carry, so Skip only closes the band.
							<ResponseCommentBand
								key={pendingResolution}
								name="Why you dispute this"
								label="What was missed?"
								placeholder="One or two sentences on what is off"
								required
								isPending={isFeedbackResponsePending}
								onSend={({ comment }) => {
									const sentence = comment.trim();
									if (sentence) record(sentence);
								}}
								onSkip={() => setPendingResolution(undefined)}
							/>
						)}
					{canRespond && pendingResolution && pendingResolution !== "DISPUTED" && (
						<ResponseCommentBand
							key={pendingResolution}
							name={
								pendingResolution === "ADDRESSED" ? "What you changed" : "Why this does not apply"
							}
							label="Anything to add?"
							placeholder="Optional: a note to yourself"
							isPending={isFeedbackResponsePending}
							onSend={({ comment }) => record(comment.trim() || undefined)}
							onSkip={() => record(undefined)}
						/>
					)}
				</CollapsibleContent>
			</Collapsible>
		</li>
	);
}
