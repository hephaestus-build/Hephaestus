import { ArrowRightIcon, CheckIcon, ChevronRightIcon, ClockIcon, PackageIcon } from "lucide-react";
import { type ComponentType, useId } from "react";

import type { PracticeStandingObservation, ReviewedWorkRef } from "@/api/types.gen";
import { pillClasses } from "@/components/admin/practice-catalog/group-visuals";
import { count, countedWork, type FeedbackTextSegment } from "@/components/common/feedback-text";
import { FeedbackText } from "@/components/common/FeedbackText";
import { FOCUS_RING, HIT_AREA_24 } from "@/components/common/focus";
import { InlineLink } from "@/components/common/InlineLink";
import {
	ResponseButton,
	type ResponseComment,
	ResponseCommentBand,
	type ResponseReason,
	toneOf,
} from "@/components/common/ResponseCommentBand";
import { Button } from "@/components/ui/button";
import { Spinner } from "@/components/ui/spinner";
import { artifactKindIcon } from "@/lib/artifact-kinds";
import { asDate, formatDay, formatDayTime, formatShortDay } from "@/lib/dates";
import { hasText } from "@/lib/text";
import { cn } from "@/lib/utils";

import { FEEDBACK_STATE_DEFS, type FeedbackState, isOpenFeedback } from "./feedback-state-defs";
import { FEEDBACK_USEFULNESS_DEFS, type FeedbackUsefulness } from "./feedback-usefulness-defs";
import { GroupName } from "./GroupName";
import {
	OBSERVATION_OUTCOME_OF_WORK,
	OBSERVATION_OUTCOME_PRESENTATION,
} from "./observation-outcome";
import { PracticePill } from "./PracticePill";
import { statusValues } from "./status-def";
import { StatusBadge } from "./StatusBadge";
import { StatusTooltip } from "./StatusTooltip";

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
 * The strip shows at most this many pieces of work: it is a glance at the pattern, not a log of
 * every place it was seen. The clean pieces are always all shown, since they are what resolves
 * the feedback; the evidence gives way, newest kept. The wire's `occurrenceCount` stays the word
 * for how many pieces the habit was seen on.
 */
const STRIP_WORK_LIMIT = 5;

/**
 * One piece of reviewed work in the card's strip — the evidence behind the feedback — with what
 * the review saw on it.
 */
export interface ReviewedWorkOutcome {
	/** The work by number — "!425" — linked to its page when the provider has one. */
	ref: ReviewedWorkRef;
	/**
	 * When the work was reviewed, as an ISO date-time; the strip orders by it and shows it as the
	 * short day, "28 Aug".
	 */
	date: string;
	outcome: PracticeStandingObservation["outcome"];
}

/**
 * One piece of practice feedback as the page lists it, keyed by the feedback's id: everything the
 * card shows. The handlers that open a level and the rating are bound by whoever renders it.
 */
export interface PracticeFeedbackCardEntry {
	feedbackId: string;
	practiceSlug: string;
	practiceName: string;
	groupSlug: string;
	groupName: string;
	/**
	 * The group's palette key, `PracticeGroup.color`; without one the pill and the group name are
	 * neutral grey.
	 */
	groupColor?: string | null;
	groupIcon?: ComponentType<{ className?: string; size?: number }>;
	headline: string;
	body: FeedbackTextSegment[];
	/**
	 * The evidence behind the feedback; the strip lists the newest that fit, oldest to newest, in
	 * any order given. Each piece carries its own kind and provider, which is what the strip's
	 * label counts.
	 */
	reviewedWork: ReviewedWorkOutcome[];
	/**
	 * The clean work since, oldest first and at most `cleanNeeded` long; the strip lists each as a
	 * strength shown after the evidence, the meter fills by it, and once resolved these are the
	 * pieces that resolved it. The wire names them without a date, so the strip shows none.
	 */
	cleanWork: ReviewedWorkRef[];
	cleanNeeded: number;
	nextStep: string;
	/** The line under the next step: what ticks it, or what did. */
	condition: FeedbackTextSegment[];
	state: FeedbackState;
	/**
	 * When the feedback was created or, once `state` is resolved or closed, when that happened;
	 * an ISO date-time.
	 */
	timestamp: string;
}

/** Newest first, by the timestamp the card shows. */
export function newestFirst<T extends { timestamp: string }>(cards: T[]): T[] {
	return [...cards].sort((a, b) => b.timestamp.localeCompare(a.timestamp));
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
	className?: string;
}

/**
 * The footer's phrase: a piece of feedback that is still open says when it was written, to the
 * minute — "Created 9 September, 14:10" — and one the work resolved says the day it happened —
 * "Resolved 9 September" — because the minute of a resolution is the review's, not the reader's.
 */
function formatTimestamp(date: Date, state: FeedbackState): string {
	switch (state) {
		case "resolved":
			return `Resolved ${formatDay(date)}`;
		case "closed":
			return `Closed ${formatDay(date)}`;
		default:
			return `Created ${formatDayTime(date)}`;
	}
}

/**
 * One piece of practice feedback in three bands: what was seen, the next step with the meter that
 * resolves it, and the footer that rates it. The glyph leading the next-step band is the state
 * the work changes, never a control the reader ticks: it is the badge's own icon, a circle-minus
 * while open and a filled circle-check once resolved, so it reads as the same status said twice,
 * and nothing in that place looks like a checkbox.
 *
 * Two states wear a tint, each in its own colour and the same way: a new card the accent's wash
 * and edge, a resolved card the success colour's wash, edge and next-step band. Every other card
 * is the plain ground.
 */
export function PracticeFeedbackCard({
	card: {
		practiceSlug,
		practiceName,
		groupSlug,
		groupName,
		groupColor,
		groupIcon: GroupIcon = PackageIcon,
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
	className,
}: PracticeFeedbackCardProps) {
	const headingId = useId();
	const resolved = state === "resolved";
	// The band's glyph is the badge's own icon said twice; a resolved card's is the filled tick.
	const BandIcon = FEEDBACK_STATE_DEFS[isOpenFeedback(state) ? "open" : state].icon;
	const stamp = asDate(timestamp);
	const groupPill = hasText(groupColor) ? pillClasses(groupColor) : undefined;
	// The strip reads left to right in time: the newest evidence that fits, oldest first, then the
	// clean work, which the wire already lists oldest first.
	const cleanCount = cleanWork.length;
	const strip: StripPiece[] = [
		...[...reviewedWork]
			.sort((a, b) => b.date.localeCompare(a.date))
			.slice(0, Math.max(STRIP_WORK_LIMIT - cleanWork.length, 0))
			.reverse()
			// The same thread can be reviewed twice on different days.
			.map((work) => ({ ...work, key: `${work.ref.kind} ${work.ref.id} ${work.date}` })),
		...cleanWork.map((ref) => ({ key: ref.id, ref, outcome: "DEMONSTRATED_STRENGTH" as const })),
	];
	const stripLabel = countedStrip(strip.map((piece) => piece.ref));
	const KindIcon = artifactKindIcon(stripLabel.kind);

	return (
		<article
			aria-labelledby={headingId}
			className={cn(
				"flex flex-col overflow-hidden rounded-xl border bg-background",
				// The accent's one wash: a new card is the thing the eye should land on.
				state === "new" &&
					"border-mentor/35 bg-[linear-gradient(160deg,color-mix(in_oklab,var(--color-mentor)_5%,var(--color-background))_0%,var(--color-background)_55%)]",
				resolved &&
					"border-success/35 bg-[linear-gradient(160deg,color-mix(in_oklab,var(--color-success)_5%,var(--color-background))_0%,var(--color-background)_55%)]",
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
							name={groupName}
							icon={GroupIcon}
							pill={groupPill}
							onOpen={onOpenGroup && (() => onOpenGroup(groupSlug))}
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
					<p className="max-w-3xl text-sm text-muted-foreground">
						<FeedbackText segments={body} onOpenPractice={onOpenPractice} />
					</p>
				</div>

				<div className="flex flex-wrap items-center gap-x-4 gap-y-3 text-sm">
					<span className="inline-flex items-center gap-2 text-muted-foreground">
						<KindIcon className="size-3.5 shrink-0" aria-hidden />
						Seen on {stripLabel.text}
					</span>
					{strip.map((piece, index) => (
						<span key={piece.key} className="whitespace-nowrap inline-flex items-center gap-2">
							{/* The arrow travels with the piece it leads to, so a wrapped line never ends on
							    one. */}
							{index > 0 && (
								<ChevronRightIcon className="size-3 shrink-0 text-muted-foreground" aria-hidden />
							)}
							<WorkOutcome outcome={piece.outcome} />
							<InlineLink href={piece.ref.url} external className="font-medium">
								{piece.ref.label}
							</InlineLink>
							{piece.date && <WorkDate value={piece.date} />}
						</span>
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
				<div className="col-start-2 flex flex-col items-start gap-1.5 sm:col-start-3 sm:items-end sm:pt-0.5">
					<span className="inline-flex gap-1" aria-hidden>
						{Array.from({ length: cleanNeeded }, (_, index) => (
							<span
								key={index}
								className={cn(
									"h-2 w-6 rounded-full",
									index < cleanCount ? "bg-success" : "bg-border",
								)}
							/>
						))}
					</span>
					<span className="whitespace-nowrap text-xs text-muted-foreground tabular-nums">
						{cleanCount} of {cleanNeeded} clean
					</span>
				</div>
			</div>

			<div className="flex flex-wrap items-center justify-between gap-4 border-t p-4">
				<div className="flex flex-wrap items-center gap-3">
					{stamp && (
						<span className="inline-flex items-center gap-2 text-sm text-muted-foreground">
							<ClockIcon className="size-4 shrink-0" aria-hidden />
							<time dateTime={stamp.toISOString()}>{formatTimestamp(stamp, state)}</time>
						</span>
					)}
					{onRate &&
						statusValues(FEEDBACK_USEFULNESS_DEFS).map((value) => {
							const { icon: Icon, label } = FEEDBACK_USEFULNESS_DEFS[value];
							const pressed = usefulness === value;
							// The one being written says so; the other only waits.
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
 * The strip counted by what its pieces are: "three pull requests", "three merge requests" at
 * GitLab, and "four pieces of work" when the pieces are not all of one kind — the count says how
 * many were seen, and the noun is claimed only where every piece bears it out. The kind is the
 * one they share, for the icon beside the label.
 */
function countedStrip(refs: ReviewedWorkRef[]): { kind?: string; text: string } {
	const [first] = refs;
	const oneKind = first !== undefined && refs.every((ref) => ref.kind === first.kind);
	if (!oneKind) return { text: count(refs.length, "piece of work", "pieces of work") };
	const provider = refs.every((ref) => ref.provider === first.provider)
		? first.provider
		: undefined;
	return { kind: first.kind, text: countedWork(first.kind, refs.length, provider) };
}

/**
 * One item of the strip, in the order it is drawn: a piece of evidence with its date, or a clean
 * piece without one.
 */
interface StripPiece extends Pick<ReviewedWorkOutcome, "ref" | "outcome"> {
	key: string;
	date?: string;
}

/**
 * The strip's outcome icon, saying what the observation row says for the same outcome. An icon
 * that stands alone as a tooltip's trigger: no chrome of its own, a ring when focused, and its
 * name for a screen reader, since the icon is all that tells the good from the bad.
 */
interface WorkOutcomeProps {
	outcome: PracticeStandingObservation["outcome"];
}

function WorkOutcome({ outcome }: WorkOutcomeProps) {
	const def = OBSERVATION_OUTCOME_PRESENTATION[OBSERVATION_OUTCOME_OF_WORK[outcome]];
	const OutcomeIcon = def.icon;
	return (
		<StatusTooltip
			def={def}
			render={<button type="button" />}
			// The icon is 14 px, so the hit area is widened a step beyond the constant's.
			className={cn(
				HIT_AREA_24,
				"inline-flex cursor-help items-center rounded-sm before:-inset-1.5",
				FOCUS_RING,
			)}
		>
			<OutcomeIcon className={cn("size-3.5 shrink-0", def.className)} aria-hidden />
			<span className="sr-only">{def.label}: </span>
		</StatusTooltip>
	);
}

interface WorkDateProps {
	/** An ISO date-time. */
	value: string;
}

function WorkDate({ value }: WorkDateProps) {
	const date = asDate(value);
	return (
		date && (
			<time dateTime={date.toISOString()} className="text-muted-foreground tabular-nums">
				{formatShortDay(date)}
			</time>
		)
	);
}
