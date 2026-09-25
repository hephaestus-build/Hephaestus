import { Meter } from "@base-ui/react/meter";
import { ArrowRightIcon, CheckIcon, ChevronRightIcon, ClockIcon, PackageIcon } from "lucide-react";
import { type ComponentType, type Ref, useId } from "react";

import { cn } from "cn";
import type { ReviewedWorkRef } from "@/api/types.gen";
import { FOCUS_RING } from "@/components/common/focus";
import { InlineLink } from "@/components/common/InlineLink";
import { ResponseButton, toneOf } from "@/components/common/ResponseButton";
import {
	type ResponseComment,
	ResponseCommentBand,
	type ResponseReason,
} from "@/components/common/ResponseCommentBand";
import { statusValues } from "@/components/common/status-def";
import { StatusBadge } from "@/components/common/StatusBadge";
import { UNTRUSTED_MARKDOWN_PROSE, UntrustedMarkdown } from "@/components/common/UntrustedMarkdown";
import { reviewedWorkIcon } from "@/components/icons/reviewed-work-icon";
import { pillClasses } from "@/components/practice-vocabulary/group-visuals";
import { Button } from "@/components/ui/button";
import { Spinner } from "@/components/ui/spinner";
import { formatDay, formatDayTime, formatShortDay } from "@/lib/dates";
import { hasText } from "@/lib/text";

import { FEEDBACK_STATE_DEFS, type FeedbackState, isOpenFeedback } from "./feedback-state-defs";
import { type FeedbackTextSegment, linkWork } from "./feedback-text";
import { FEEDBACK_USEFULNESS_DEFS, type FeedbackUsefulness } from "./feedback-usefulness-defs";
import { FeedbackText } from "./FeedbackText";
import { GroupName } from "./GroupName";
import {
	OBSERVATION_OUTCOME_OF_WORK,
	OBSERVATION_OUTCOME_PRESENTATION,
	type ReviewedWorkKind,
} from "./observation-outcome";
import { countedStripWork, type StripPiece, stripPieces } from "./practice-feedback-card-strip";
import { PracticePill } from "./PracticePill";
import { StatusIcon } from "./StatusTooltip";

/** Why a piece of feedback was not helpful; the wire's dispute carries the comment beside it. */
type NotHelpfulReason = "not-accurate" | "not-useful" | "already-doing";

const NOT_HELPFUL_REASONS: ResponseReason<NotHelpfulReason>[] = [
	{ value: "not-accurate", label: "Not accurate" },
	{ value: "not-useful", label: "Not useful" },
	{ value: "already-doing", label: "Already doing this" },
];

/** The note under a rating; the reason comes only with a "not helpful" one. */
export type FeedbackComment = ResponseComment<NotHelpfulReason>;

/**
 * One piece of reviewed work in the card's strip — the evidence behind the feedback — with what
 * the review saw on it.
 */
export interface ReviewedWorkOutcome {
	/** The work by number — "!425" — linked to its page when the provider has one. */
	ref: ReviewedWorkRef;
	/** When the work was reviewed; the strip orders by it and shows it as the short day, "28 Aug". */
	date: Date;
	outcome: ReviewedWorkKind;
}

/**
 * One piece of work that came back clean on the practice since the feedback was prepared. It has
 * no outcome of its own: being here is what the strip marks it with.
 */
export interface CleanWork {
	ref: ReviewedWorkRef;
	/** When the piece was reviewed; the strip orders and dates it by this. */
	date: Date;
}

/** The group a card's practice belongs to, with what the card draws its name in. */
export interface PracticeFeedbackCardGroup {
	slug: string;
	name: string;
	/**
	 * The group's palette key, `PracticeGroup.color`; without one the pill and the group name are
	 * neutral grey.
	 */
	color?: string | null;
	icon?: ComponentType<{ className?: string; size?: number }>;
}

/**
 * One piece of practice feedback as the page lists it, keyed by the feedback's id: everything the
 * card shows. The handlers that open a level and the rating are bound by whoever renders it.
 */
export interface PracticeFeedbackCardEntry {
	feedbackId: string;
	practiceSlug: string;
	practiceName: string;
	/**
	 * The group the practice belongs to; a practice in none has no group here, and the card names
	 * it as unassigned with nowhere to go.
	 */
	group?: PracticeFeedbackCardGroup;
	headline: string;
	/**
	 * What was seen, in the composer's own Markdown: paragraphs, bullet lists, bold, italic and
	 * inline code. Every reference to a piece of work the card carries — its evidence and its clean
	 * work — becomes that piece's link wherever it stands in the text.
	 */
	body: string;
	/**
	 * The evidence behind the feedback; the strip lists the newest that fit, oldest to newest, in
	 * any order given. Each piece carries its own kind and provider, which decides the noun the
	 * strip's label counts by.
	 */
	reviewedWork: ReviewedWorkOutcome[];
	/**
	 * The clean work since, oldest first and at most `cleanNeeded` long; each piece carries the day
	 * it was reviewed, so the strip places it among the evidence by time rather than after it. The
	 * meter fills by it, and once resolved these are the pieces that resolved it.
	 */
	cleanWork: CleanWork[];
	cleanNeeded: number;
	nextStep: string;
	/** The line under the next step: what ticks it, or what did. */
	condition: FeedbackTextSegment[];
	state: FeedbackState;
	/** When the feedback was created or, once `state` is resolved or closed, when that happened. */
	timestamp: Date;
}

/** The card props that let one piece of feedback be rated. */
export interface FeedbackRatingProps {
	usefulness?: FeedbackUsefulness;
	/**
	 * Whether the comment band under the footer is open; a rating press opens it, Send and Skip
	 * close it.
	 */
	commentOpen?: boolean;
	/**
	 * A rating is being read or written: the chosen rating says it is saving, the other and the
	 * band's controls wait for it.
	 */
	isPending?: boolean;
	/**
	 * A press on a rating button, the chosen one included: the caller decides that a second press
	 * withdraws. Without it the card has no rating buttons, as a control nobody can answer is hidden
	 * rather than disabled.
	 */
	onRate?: (usefulness: FeedbackUsefulness) => void;
	onSendComment?: (comment: FeedbackComment) => void;
	onSkipComment?: () => void;
}

export interface PracticeFeedbackCardProps extends FeedbackRatingProps {
	card: PracticeFeedbackCardEntry;
	/** Opens the practice's own words; without it, like its siblings, the button is not drawn. */
	onLearnMore?: () => void;
	onOpenPractice?: (slug: string) => void;
	onOpenGroup?: (slug: string) => void;
	/**
	 * The card as an element the page can land on: "Read the feedback" in Heph's card scrolls to it
	 * and moves the focus here, so the reader's next Tab continues from the card rather than from
	 * where the link stood.
	 */
	ref?: Ref<HTMLElement>;
	className?: string;
}

/**
 * The footer's phrase: a piece of feedback that is still open says when it was written, to the
 * minute — "Created 9 September, 2:10 pm" — and one the work resolved says the day it happened —
 * "Resolved 9 September" — because the minute of a resolution is the review's, not the reader's.
 */
function formatTimestamp(date: Date, state: FeedbackState): string {
	return isOpenFeedback(state)
		? `Created ${formatDayTime(date)}`
		: `${FEEDBACK_STATE_DEFS[state].label} ${formatDay(date)}`;
}

/**
 * One piece of practice feedback in three bands: what was seen, the next step with the meter that
 * resolves it, and the footer that rates it. The glyph leading the next-step band says where the
 * work stands and is never a control, so nothing in that place looks like a checkbox the reader
 * could tick.
 */
export function PracticeFeedbackCard({
	card: {
		practiceSlug,
		practiceName,
		group,
		headline,
		body,
		reviewedWork,
		cleanWork,
		cleanNeeded,
		nextStep,
		condition,
		state,
		timestamp,
	},
	usefulness,
	commentOpen = false,
	isPending = false,
	onRate,
	onSendComment,
	onSkipComment,
	onLearnMore,
	onOpenPractice,
	onOpenGroup,
	ref,
	className,
}: PracticeFeedbackCardProps) {
	const headingId = useId();
	const resolved = state === "resolved";
	const BandIcon = FEEDBACK_STATE_DEFS[isOpenFeedback(state) ? "open" : state].icon;
	const GroupIcon = group?.icon ?? PackageIcon;
	const groupPill = hasText(group?.color) ? pillClasses(group.color) : undefined;
	const cleanCount = cleanWork.length;
	const cleanLabel = `${cleanCount} of ${cleanNeeded} clean`;
	const shownWork = stripPieces(reviewedWork, cleanWork);
	const evidenceRefs = reviewedWork.map((piece) => piece.ref);
	const stripLabel = countedStripWork(shownWork.map((piece) => piece.ref));
	// The work the body may name: everything the card can vouch for, whether the strip shows it or
	// gave way to a newer piece.
	const knownWork = [...evidenceRefs, ...cleanWork.map((clean) => clean.ref)];
	const WorkIcon = reviewedWorkIcon(stripLabel.kind, stripLabel.provider);

	return (
		<article
			ref={ref}
			aria-labelledby={headingId}
			// Not in the tab order, but a target the page can put the focus on.
			tabIndex={-1}
			className={cn(
				FOCUS_RING,
				"flex flex-col overflow-hidden rounded-xl border bg-background",
				// The two washes: `webapp/AGENTS.md` § Practice surfaces palette.
				state === "new" && "border-mentor/35 bg-linear-160 from-mentor/5 to-background to-55%",
				resolved && "border-success/35 bg-linear-160 from-success/5 to-background to-55%",
				className,
			)}
		>
			<div className="flex flex-col gap-4 p-4">
				<div className="flex flex-wrap items-center justify-between gap-4">
					<div className="flex flex-wrap items-center gap-3 text-sm">
						<PracticePill
							name={practiceName}
							onOpen={onOpenPractice && (() => onOpenPractice(practiceSlug))}
						/>
						<span className="text-muted-foreground">in</span>
						<GroupName
							name={group?.name ?? "Unassigned"}
							icon={GroupIcon}
							pill={groupPill}
							onOpen={group && onOpenGroup && (() => onOpenGroup(group.slug))}
						/>
					</div>
					<StatusBadge
						def={FEEDBACK_STATE_DEFS[state]}
						className={FEEDBACK_STATE_DEFS[state].className}
					/>
				</div>

				<div className="flex flex-col gap-2.5">
					<h3 id={headingId} className="max-w-3xl text-lg font-semibold">
						{headline}
					</h3>
					<div className={cn(UNTRUSTED_MARKDOWN_PROSE, "max-w-3xl text-sm text-muted-foreground")}>
						<UntrustedMarkdown
							renderText={(value) => <FeedbackText segments={linkWork(value, knownWork)} />}
						>
							{body}
						</UntrustedMarkdown>
					</div>
				</div>

				<div className="flex flex-wrap items-center gap-x-4 gap-y-2 text-sm">
					<span className="inline-flex items-center gap-2 whitespace-nowrap text-muted-foreground">
						<WorkIcon className="size-3.5 shrink-0" aria-hidden />
						{stripLabel.text}
					</span>
					{shownWork.map((piece, index) => (
						<StripPieceView key={piece.key} piece={piece} arrow={index > 0} />
					))}
				</div>
			</div>

			<div
				className={cn(
					"grid grid-cols-[auto_minmax(0,1fr)] items-start gap-x-4 gap-y-3 border-t px-4 py-3 sm:grid-cols-[auto_minmax(0,1fr)_auto]",
					resolved ? "bg-success/10 dark:bg-success/15" : "bg-sidebar",
				)}
			>
				{/* The line box is 20 px, so the glyph sits on the first line without an offset. */}
				<span className="flex h-5 items-center" aria-hidden>
					{resolved ? (
						<span className="inline-flex size-4 items-center justify-center rounded-full bg-success">
							<CheckIcon className="size-3 text-success-foreground" strokeWidth={3} />
						</span>
					) : (
						<BandIcon className="size-4 text-muted-foreground" />
					)}
				</span>
				<div className="flex min-w-0 flex-col gap-1">
					<span className="text-sm font-semibold">Next step</span>
					<p className="max-w-3xl text-sm">{nextStep}</p>
					<p className="text-xs text-muted-foreground">
						<FeedbackText segments={condition} onOpenPractice={onOpenPractice} />
					</p>
				</div>
				{/* A count toward a threshold is a meter, not task progress (APG meter pattern). */}
				<Meter.Root
					value={cleanCount}
					max={cleanNeeded}
					aria-label="Clean work in a row"
					getAriaValueText={() => cleanLabel}
					className="col-start-2 flex flex-col items-start gap-1.5 sm:col-start-3 sm:items-end sm:pt-0.5"
				>
					<Meter.Track className="inline-flex gap-1">
						{Array.from({ length: cleanNeeded }, (_, index) => (
							<span
								key={index}
								className={cn(
									"h-2 w-6 rounded-full",
									index < cleanCount ? "bg-success" : "bg-border",
								)}
							/>
						))}
					</Meter.Track>
					<Meter.Value className="text-xs whitespace-nowrap text-muted-foreground tabular-nums">
						{() => cleanLabel}
					</Meter.Value>
				</Meter.Root>
			</div>

			<div className="flex flex-wrap items-center justify-between gap-4 border-t p-4">
				<div className="flex flex-wrap items-center gap-3">
					<span className="inline-flex items-center gap-2 text-sm text-muted-foreground">
						<ClockIcon className="size-4 shrink-0" aria-hidden />
						<time dateTime={timestamp.toISOString()}>{formatTimestamp(timestamp, state)}</time>
					</span>
					{onRate &&
						statusValues(FEEDBACK_USEFULNESS_DEFS).map((value) => {
							const { icon: Icon, label } = FEEDBACK_USEFULNESS_DEFS[value];
							const pressed = usefulness === value;
							const saving = isPending && pressed;
							return (
								<ResponseButton
									key={value}
									tone={toneOf(FEEDBACK_USEFULNESS_DEFS[value].badgeVariant)}
									pressed={pressed}
									disabled={isPending}
									onClick={() => onRate(value)}
								>
									{saving ? <Spinner /> : <Icon aria-hidden />}
									{saving ? "Saving…" : label}
								</ResponseButton>
							);
						})}
				</div>
				{onLearnMore && (
					<Button variant="outline" onClick={onLearnMore}>
						Learn more about this practice
						<ArrowRightIcon aria-hidden />
					</Button>
				)}
			</div>

			{commentOpen && usefulness === "HELPFUL" && (
				<ResponseCommentBand
					key={usefulness}
					name="What was helpful"
					label="What worked about this feedback?"
					placeholder="Optional: what helped, or what you did"
					isPending={isPending}
					onSend={onSendComment}
					onSkip={onSkipComment}
				/>
			)}
			{commentOpen &&
				usefulness === "UNHELPFUL" && (
					// A reason and a sentence: the sentence is what the dispute has to carry.
					<ResponseCommentBand
						key={usefulness}
						name="What was not helpful"
						label="What was missed?"
						placeholder="One or two sentences on what is off"
						required
						reasons={NOT_HELPFUL_REASONS}
						isPending={isPending}
						onSend={onSendComment}
						onSkip={onSkipComment}
					/>
				)}
		</article>
	);
}

/**
 * One piece of the strip. The arrow that leads it travels with it, so a wrapped line never ends
 * on one.
 */
function StripPieceView({ piece, arrow }: { piece: StripPiece; arrow: boolean }) {
	return (
		<span className="inline-flex items-center gap-2 whitespace-nowrap">
			{arrow && <ChevronRightIcon className="size-3 shrink-0 text-muted-foreground" aria-hidden />}
			<StatusIcon
				def={OBSERVATION_OUTCOME_PRESENTATION[OBSERVATION_OUTCOME_OF_WORK[piece.outcome]]}
			/>
			<InlineLink href={piece.ref.url} external className="font-medium">
				{piece.ref.label}
			</InlineLink>
			<time dateTime={piece.date.toISOString()} className="text-muted-foreground tabular-nums">
				{formatShortDay(piece.date)}
			</time>
		</span>
	);
}
