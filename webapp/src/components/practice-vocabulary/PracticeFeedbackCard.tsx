import { Meter } from "@base-ui/react/meter";
import { ArrowRightIcon, CheckIcon, ClockIcon, FootprintsIcon, PackageIcon } from "lucide-react";
import { type ComponentType, type ReactNode, useId } from "react";

import { cn } from "cn";
import type { ItemRef } from "@/api/types.gen";
import { FOCUS_RING } from "@/components/common/focus";
import { InfoTip } from "@/components/common/InfoTip";
import { InlineLink } from "@/components/common/InlineLink";
import { ResponseButton, toneOf } from "@/components/common/ResponseButton";
import { ResponseCommentBand } from "@/components/common/ResponseCommentBand";
import { statusValues } from "@/components/common/status-def";
import { StatusBadge } from "@/components/common/StatusBadge";
import { TipPopover } from "@/components/common/TipPopover";
import { UNTRUSTED_MARKDOWN_PROSE, UntrustedMarkdown } from "@/components/common/UntrustedMarkdown";
import { useNow } from "@/components/common/use-now";
import { pillClasses } from "@/components/practice-vocabulary/group-visuals";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { formatDay, formatDayTime, formatShortDay } from "@/lib/dates";
import { hasText } from "@/lib/text";

import { UNVERIFIED_HISTORY_NOTE } from "./claim-currentness";
import { FEEDBACK_RESOLUTION_DEFS, type FeedbackResolution } from "./feedback-resolution-defs";
import { FEEDBACK_STATE_DEFS, type FeedbackState, isOpenFeedback } from "./feedback-state-defs";
import { count, type FeedbackTextSegment, linkWork } from "./feedback-text";
import { FEEDBACK_USEFULNESS_DEFS, type FeedbackUsefulness } from "./feedback-usefulness-defs";
import { type WorkSeenIn, workSeenIn } from "./feedback-work-kind";
import { FeedbackText } from "./FeedbackText";
import { FeedbackWorkTimeline } from "./FeedbackWorkTimeline";
import type { Outcome } from "./outcome-defs";
import { cleanProgress, timelinePieces } from "./practice-feedback-timeline";
import { PracticeInGroup } from "./PracticeInGroup";

export type FeedbackBand = "comment" | "disagreement";

/** The control whose write is in flight, so "Saving…" lands on it and nowhere else. */
export type ResponseControl = "rating" | "answer" | "send" | "disagree";

/**
 * An answer that resolves the feedback, the card's own way to close it beside the work coming back
 * clean. A disagreement leaves the feedback open, so it has its own Disagree button.
 */
export type ResolvingAnswer = Exclude<FeedbackResolution, "DISAGREE">;

/**
 * One piece of reviewed work on the card's timeline — the evidence behind the feedback — with
 * what the review saw on it where the record says so. Practice-page evidence carries no outcome,
 * and the timeline invents none for it.
 */
export interface ReviewedWorkOutcome {
	/** The work by number — "!425" — linked to its page when the provider has one. */
	ref: ItemRef;
	/** When the work was reviewed; the timeline orders by it and shows it as the short day, "28 Aug". */
	date: Date;
	outcome?: Outcome;
}

/**
 * One piece of work that came back clean on the practice since the feedback was prepared. It has
 * no outcome of its own: being here is what the timeline marks it with.
 */
export interface CleanWork {
	ref: ItemRef;
	/** When the piece was reviewed; the timeline orders and dates it by this. */
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
	unverifiedHistory?: boolean;
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
	 * The evidence behind the feedback; the timeline shows the newest, oldest to newest, in any
	 * order given, and marks that older ones exist with one short "…" stretch at its start.
	 */
	reviewedWork: ReviewedWorkOutcome[];
	/**
	 * The clean work since, oldest first and at most `cleanNeeded` long; each piece carries the day
	 * it was reviewed, so the timeline places it among the evidence by time rather than after it. The
	 * meter fills by it, and once resolved these are the pieces that resolved it.
	 */
	cleanWork: CleanWork[];
	cleanNeeded: number;
	nextStep: string;
	/**
	 * How the feedback resolves, or how it did: on an open card the meter's tooltip and
	 * description, on any other the sentence under "Work reviewed for this practice".
	 */
	condition: FeedbackTextSegment[];
	state: FeedbackState;
	/** When the feedback was created or, once `state` is resolved or closed, when that happened. */
	timestamp: Date;
	/** Immutable preparation time; the footer timestamp may instead describe a later closure. */
	preparedAt: Date;
}

/** The card props that let one piece of feedback be rated and answered. */
export interface FeedbackRatingProps {
	usefulness?: FeedbackUsefulness;
	/**
	 * The reader's answer, the one standing or the one being written. Addressed and Not applicable
	 * press their button; a disagreement presses neither, and either press replaces it.
	 */
	resolution?: FeedbackResolution;
	/**
	 * The band open under the footer, at most one: a rating press opens `comment`, a press on
	 * Disagree opens `disagreement`, and Send and Skip close either.
	 */
	openBand?: FeedbackBand;
	/** A response is being written, so every response control waits. */
	isPending?: boolean;
	/**
	 * The control to say "Saving…" on, once a write has outlasted the delay that keeps a quick one
	 * silent. The caller owns it, because two cards for one piece of feedback share one write.
	 */
	saving?: ResponseControl;
	/**
	 * A press on a rating button, the chosen one included: the caller decides that a second press
	 * withdraws. Without it the card has no rating buttons, as a control nobody can answer is hidden
	 * rather than disabled.
	 */
	onRate?: (usefulness: FeedbackUsefulness) => void;
	onSendComment?: (comment: string) => void;
	onSkipComment?: () => void;
	/** Without it the card has no Disagree button. The caller decides what a press on a standing disagreement does. */
	onDisagree?: () => void;
	onSendDisagreement?: (comment: string) => void;
	/**
	 * A press on Addressed or Not applicable, the chosen one included: the caller decides that a
	 * second press takes the answer back. Without it the card has none of these buttons, and neither
	 * has a card the work resolved or a practice change closed, which no answer can reopen.
	 */
	onResolve?: (answer: ResolvingAnswer) => void;
}

export interface PracticeFeedbackCardProps extends FeedbackRatingProps {
	card: PracticeFeedbackCardEntry;
	/** Opens the practice's own words; without it, like its siblings, the button is not drawn. */
	onLearnMore?: () => void;
	onOpenPractice?: (slug: string) => void;
	onOpenGroup?: (slug: string) => void;
	className?: string;
}

const RESOLVING_ANSWERS: ResolvingAnswer[] = ["ADDRESSED", "NOT_APPLICABLE"];

/**
 * The two answers in the student's words, each with what it does, said in its tooltip and as its
 * description. The registry's "Addressed" and "Not applicable" read as statuses, not as something
 * to press. Both close the card as "Marked by
 * you" and the clean work count goes on (`practice-review-glossary.mdx` § How feedback resolves);
 * only "Not applicable" also holds back the same claim on the same work in a later review
 * (`FeedbackResponseSuppressionFilter`), so only its line says so. A press on the chosen answer
 * takes it back.
 */
const ANSWER_WORDS: Record<ResolvingAnswer, { label: string; effect: string }> = {
	ADDRESSED: {
		label: "I fixed this",
		effect: "The card moves to Marked by you, and your next work confirms it.",
	},
	NOT_APPLICABLE: {
		label: "Does not fit my work",
		effect:
			"The card moves to Marked by you, and a later review of the same work does not send you this point again.",
	},
};

const DisagreeIcon = FEEDBACK_RESOLUTION_DEFS.DISAGREE.icon;

const DISAGREE = {
	label: "Disagree",
	description: "Tell workspace admins what is wrong in this feedback.",
} as const;

/**
 * The rule behind the (i) beside the zone's title on an open card, with the card's own number:
 * "Resolves when three pieces of reviewed work come back clean." The meter's line above its track
 * says the same in numbers, so the rule waits until asked for.
 */
function resolveRule(cleanNeeded: number): string {
	const pieces = count(cleanNeeded, "piece of reviewed work", "pieces of reviewed work");
	return `Resolves when ${pieces} ${cleanNeeded === 1 ? "comes" : "come"} back clean.`;
}

/**
 * The footer's phrase: a piece of feedback that is still open says when it was written, to the
 * minute — "Created 9 September, 2:10 pm" — and one the work resolved says the day it happened —
 * "Resolved 9 September" — because the minute of a resolution is the review's, not the reader's.
 */
function formatTimestamp(date: Date, state: FeedbackState, today: Date): string {
	const def = FEEDBACK_STATE_DEFS[state];
	return isOpenFeedback(state)
		? `Created ${formatDayTime(date, today)}`
		: `${def.stamp ?? def.label} ${formatDay(date)}`;
}

/**
 * The card is one target that opens the practice: "Learn more about this practice" stretches its
 * press over the whole card (the stretched-link pattern), and every other control in it, the work's
 * links and the scrolling timeline sit above that layer, so they still take their own press.
 */
const ABOVE_CARD_LINK =
	"[&_:is(a,button,textarea,input,[tabindex]):not([data-card-link])]:relative [&_:is(a,button,textarea,input,[tabindex]):not([data-card-link])]:z-10";

/**
 * The state that invites the press, while the pointer is on the card or the keyboard focuses its
 * link: the focus ring around the whole card, and (see `CardTint`) a light tint.
 */
const CARD_LINK_STATE =
	"has-[[data-card-link]:focus-visible]:border-ring has-[[data-card-link]:focus-visible]:ring-3 has-[[data-card-link]:focus-visible]:ring-ring/50";

/**
 * The card's three section headings, "Why this was raised", "Next step" and "Your last reviewed
 * work": one size, weight and colour, each one step (`gap-1`) above what it names, so they read as
 * one set.
 */
const ZONE_HEADING = "text-sm font-semibold text-foreground";

/**
 * The card's edge and its inner rules. Students read the inner rules as the card's end when both
 * had one strength, so the outer edge is the one strong line, with a faint shadow, and the rules
 * inside are half as strong.
 */
const CARD_EDGE = "rounded-xl border border-foreground/15 bg-background shadow-sm";
const INNER_RULE = "border-border/50";

const workKey = (ref: { kind: string; id: string | number }) => `${ref.kind} ${ref.id}`;

function UnverifiedHistoryNotice({ unverified }: { unverified?: boolean }) {
	return unverified === true ? (
		<p className="text-xs text-muted-foreground">{UNVERIFIED_HISTORY_NOTE}</p>
	) : null;
}

/**
 * One piece of practice feedback in zones a reader takes in at a glance. The top says what it is
 * about, its explanation under "Why this was raised"; the tinted band under it, what to do next;
 * "Work reviewed for this practice", where the practice was seen on the left, and on the right how close
 * the clean work is to resolving it and the reader's own way to close it; and the footer, when it
 * was written and how the reader rates it. Thin rules separate
 * them: above and below the band, and above the footer. Students in the first interview round found
 * the earlier card dense and its parts hard to tell apart; these zones answer that. A withdrawn card
 * keeps the head and the footer only, with the notice in between.
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
		preparedAt,
		unverifiedHistory,
	},
	usefulness,
	resolution,
	openBand,
	isPending = false,
	saving,
	onRate,
	onSendComment,
	onSkipComment,
	onDisagree,
	onSendDisagreement,
	onResolve,
	onLearnMore,
	onOpenPractice,
	onOpenGroup,
	className,
}: PracticeFeedbackCardProps) {
	const headingId = useId();
	const responseId = useId();
	const shownTimestamp = formatTimestamp(timestamp, state, new Date(useNow()));
	const resolved = state === "resolved";
	const closed = state === "closed";
	const open = isOpenFeedback(state);
	const answerable = open || state === "marked";
	const GroupIcon = group?.icon ?? PackageIcon;
	const groupPill = hasText(group?.color) ? pillClasses(group.color) : undefined;
	const cleanCount = cleanWork.length;
	const knownWork = [...reviewedWork.map((piece) => piece.ref), ...cleanWork.map((c) => c.ref)];
	// The timeline shows the newest pieces only, oldest to newest, at most `TIMELINE_WORK_LIMIT`
	// whatever the history; what falls out of it is one "…" stretch at its start, which needs no
	// count: the pattern is in the newest pieces.
	const shown = timelinePieces(reviewedWork, cleanWork);
	const earlier = reviewedWork.length + cleanWork.length - shown.length;
	const cleanKeys = new Set(cleanWork.map((piece) => `${workKey(piece.ref)} clean`));
	const missing = resolved || closed ? 0 : Math.max(cleanNeeded - cleanCount, 0);
	// The kind and number of the work it was seen on, from the work itself; a withdrawn card says
	// nothing about the work.
	const seenIn =
		state === "withdrawn" ? undefined : workSeenIn(reviewedWork.map((piece) => piece.ref));
	// What the feedback is about: the practice, its group, the work it was seen on, and where the
	// feedback stands.
	const head = (
		<div className="flex flex-wrap items-center justify-between gap-x-4 gap-y-2">
			<PracticeInGroup
				practiceName={practiceName}
				onOpenPractice={onOpenPractice && (() => onOpenPractice(practiceSlug))}
				groupName={group?.name ?? "Unassigned"}
				groupIcon={GroupIcon}
				groupPill={groupPill}
				onOpenGroup={group && onOpenGroup && (() => onOpenGroup(group.slug))}
			/>
			<span className="ml-auto inline-flex flex-wrap items-center gap-2">
				{seenIn && (
					// The count stays short in the head at any number of works; a hover, focus or tap names
					// them, linked as the timeline below links them.
					<TipPopover
						label={seenIn.label}
						align="end"
						wide
						trigger={
							<button
								type="button"
								// The words first, then the card's headline: a page of cards has one name per chip.
								aria-label={`${seenIn.label}: ${headline}`}
								data-work-seen-in=""
								className={cn(
									"inline-flex cursor-help items-center gap-1.5 rounded-full border px-2 py-0.5 text-xs text-muted-foreground hover:text-foreground data-popup-open:text-foreground",
									FOCUS_RING,
								)}
							/>
						}
						content={<RecurringWorks seenIn={seenIn} reviewedWork={reviewedWork} />}
					>
						<seenIn.icon className="size-3.5 shrink-0" aria-hidden />
						{seenIn.label}
					</TipPopover>
				)}
				<StatusBadge
					def={FEEDBACK_STATE_DEFS[state]}
					className={FEEDBACK_STATE_DEFS[state].className}
				/>
			</span>
		</div>
	);

	if (state === "withdrawn") {
		// What it said was taken back as wrong, so none of it is shown: not its words, its evidence, a
		// next step, a meter that could read as progress, or buttons to answer it.
		return (
			<article
				aria-labelledby={headingId}
				className={cn("flex flex-col overflow-hidden", CARD_EDGE, className)}
			>
				<div className="flex flex-col gap-3 p-4">
					{head}
					<h3 id={headingId} className="max-w-3xl text-lg leading-snug font-semibold">
						This feedback was withdrawn
					</h3>
					<p className="max-w-3xl text-sm text-foreground">
						A workspace admin took it back because what it said was wrong. It says nothing about how
						your work stands on this practice.
					</p>
				</div>
				<div
					className={cn(
						"flex flex-wrap items-center gap-x-4 gap-y-2 border-t px-4 py-2.5 text-xs",
						INNER_RULE,
					)}
				>
					<span className="inline-flex items-center gap-1.5 text-muted-foreground">
						<ClockIcon className="size-3.5 shrink-0" aria-hidden />
						<time dateTime={timestamp.toISOString()}>{shownTimestamp}</time>
					</span>
				</div>
			</article>
		);
	}

	return (
		<article
			aria-labelledby={headingId}
			className={cn(
				"group/card relative isolate flex flex-col overflow-hidden",
				CARD_EDGE,
				onLearnMore && ["cursor-pointer", ABOVE_CARD_LINK, CARD_LINK_STATE],
				state === "new" && "border-mentor/35 bg-linear-160 from-mentor/5 to-background to-55%",
				resolved && "border-success/35 bg-linear-160 from-success/5 to-background to-55%",
				className,
			)}
		>
			{onLearnMore && <CardTint />}
			{/* What it is about. */}
			<div className="flex flex-col gap-3 p-4">
				{head}
				<h3 id={headingId} className="max-w-3xl text-lg leading-snug font-semibold">
					{headline}
				</h3>
				<UnverifiedHistoryNotice unverified={unverifiedHistory} />
				{/*
				 * The whole explanation under a heading that names it: it is short, and a clamp only hid the
				 * reason behind a click.
				 */}
				<div className="flex flex-col gap-1">
					<h4 className={ZONE_HEADING}>Why this was raised</h4>
					<div className={cn(UNTRUSTED_MARKDOWN_PROSE, "max-w-3xl text-sm text-foreground")}>
						<UntrustedMarkdown
							renderText={(value) => <FeedbackText segments={linkWork(value, knownWork)} />}
						>
							{body}
						</UntrustedMarkdown>
					</div>
				</div>
				{onLearnMore && (
					// Muted at rest; while the card is hovered or this link focused it is a link as the app draws
					// one everywhere (`InlineLink`): in the accent and underlined, one pixel thick at the
					// same offset. On a screen without hover it is in the accent from the start, as nothing
					// would turn it.
					<InlineLink
						onClick={onLearnMore}
						data-card-link=""
						className={cn(
							"group/card-link inline-flex items-center gap-1 self-end text-sm font-medium text-muted-foreground",
							"after:absolute after:inset-0 after:content-['']",
							"group-hover/card:text-mentor group-hover/card:underline focus-visible:text-mentor [@media(hover:none)]:text-mentor",
						)}
					>
						Learn more about this practice
						<ArrowRightIcon
							aria-hidden
							className="size-3.5 shrink-0 transition-transform group-hover/card:translate-x-0.5 group-focus-visible/card-link:translate-x-0.5"
						/>
					</InlineLink>
				)}
			</div>

			<NextStepBand resolved={resolved} nextStep={nextStep} />

			{/*
			 * Work reviewed for this practice: on the left, where the practice was seen, along the timeline; on the
			 * right, one compact block for the status and the action: how close the clean work is to
			 * resolving it, then the reader's own way to close it, left-aligned inside the block and the
			 * block set to the right. A narrow card stacks the block under the timeline, full width. A
			 * rule under the band parts the zone from the band.
			 */}
			<div className="@container/progress px-4 py-3">
				<div className="grid gap-x-10 gap-y-4 @xl/progress:grid-cols-[minmax(0,1fr)_auto]">
					<div className="flex min-w-0 flex-col gap-2.5">
						<div className="flex flex-col gap-1">
							{/* The rule behind an (i) on an open card; once it no longer holds, the closing sentence in sight. */}
							<div className="flex items-center gap-1">
								<h4 className={ZONE_HEADING}>Work reviewed for this practice</h4>
								{open && (
									// One (i) per card on a page of cards: the headline tells the buttons apart.
									<InfoTip label="How this feedback resolves" nameContext={headline}>
										<p data-progress-rule="">{resolveRule(cleanNeeded)}</p>
									</InfoTip>
								)}
							</div>
							{!open && (
								<p data-progress-rule="" className="text-xs text-muted-foreground">
									<FeedbackText segments={condition} onOpenPractice={onOpenPractice} />
								</p>
							)}
						</div>
						<FeedbackWorkTimeline
							pieces={shown}
							createdAt={preparedAt}
							cleanKeys={cleanKeys}
							earlier={earlier}
							missing={missing}
						/>
					</div>
					{(!closed || (onResolve && answerable)) && (
						// One compact block: the meter with its count on one line, then the reader's own way to
						// close the card. Its left edges align; on a wide card it stands right of the timeline,
						// as wide as its answers, and on a narrow one it takes the full width under it.
						<div
							data-progress-status=""
							className="flex w-full flex-col gap-3 @xl/progress:w-max @xl/progress:min-w-56 @xl/progress:self-start @xl/progress:justify-self-end"
						>
							{!closed && (
								<CleanMeter
									cleanCount={cleanCount}
									cleanNeeded={cleanNeeded}
									label={cleanProgress({ cleanCount, cleanNeeded })}
									description={open ? <FeedbackText segments={condition} /> : undefined}
								/>
							)}
							{onResolve && answerable && (
								<div className="flex flex-col gap-1.5">
									<span id={responseId} className="text-xs text-muted-foreground">
										Or mark it yourself
									</span>
									<div
										role="group"
										aria-labelledby={responseId}
										className="flex flex-wrap items-center gap-2"
									>
										{RESOLVING_ANSWERS.map((value) => {
											const def = FEEDBACK_RESOLUTION_DEFS[value];
											const pressed = resolution === value;
											return (
												<Answer
													key={value}
													value={value}
													tone={toneOf(def.badgeVariant)}
													pressed={pressed}
													saving={pressed && saving === "answer"}
													disabled={isPending}
													onClick={() => onResolve(value)}
												/>
											);
										})}
									</div>
								</div>
							)}
						</div>
					)}
				</div>
			</div>

			{/* When it was written, and how the reader rates it. */}
			<div
				className={cn(
					"flex flex-wrap items-center justify-between gap-x-4 gap-y-2 border-t px-4 py-2.5 text-xs",
					INNER_RULE,
				)}
			>
				<div className="flex min-w-0 flex-wrap items-center gap-x-4 gap-y-1.5">
					<span className="inline-flex items-center gap-1.5 text-muted-foreground">
						<ClockIcon className="size-3.5 shrink-0" aria-hidden />
						<time dateTime={timestamp.toISOString()}>{shownTimestamp}</time>
					</span>
				</div>
				{(onRate !== undefined || onDisagree !== undefined) && (
					<div
						role="group"
						aria-label="Rate this feedback"
						className="ml-auto flex items-center gap-0.5"
					>
						{onRate &&
							statusValues(FEEDBACK_USEFULNESS_DEFS).map((value) => {
								const def = FEEDBACK_USEFULNESS_DEFS[value];
								const pressed = usefulness === value;
								const savingHere = pressed && saving === "rating";
								return (
									<Reaction
										key={value}
										label={savingHere ? "Saving…" : def.label}
										description={def.description}
										tone={toneOf(def.badgeVariant)}
										pressed={pressed}
										disabled={isPending}
										onClick={() => onRate(value)}
									>
										{savingHere ? <Spinner /> : <def.icon aria-hidden />}
									</Reaction>
								);
							})}
						{onDisagree && (
							<Reaction
								label={saving === "disagree" ? "Saving…" : DISAGREE.label}
								description={DISAGREE.description}
								tone={toneOf(FEEDBACK_RESOLUTION_DEFS.DISAGREE.badgeVariant)}
								pressed={resolution === "DISAGREE"}
								disabled={isPending}
								onClick={onDisagree}
							>
								{saving === "disagree" ? <Spinner /> : <DisagreeIcon aria-hidden />}
							</Reaction>
						)}
					</div>
				)}
			</div>

			{/* The bands take their own presses, above the card's link. */}
			<div className="relative z-10 flex flex-col">
				{openBand === "disagreement" && (
					<ResponseCommentBand
						key="disagreement"
						name="Why you disagree"
						label="What is wrong in this feedback?"
						placeholder="One or two sentences on what is wrong"
						required
						audience="Workspace admins read your sentence, not the card, and can correct its observations or withdraw it. The card stays open until then."
						isPending={isPending}
						sending={saving === "send"}
						onSend={onSendDisagreement}
						onSkip={onSkipComment}
					/>
				)}
				{openBand === "comment" && usefulness !== undefined && (
					<ResponseCommentBand
						key={usefulness}
						{...FEEDBACK_USEFULNESS_DEFS[usefulness].note}
						isPending={isPending}
						sending={saving === "send"}
						onSend={onSendComment}
						onSkip={onSkipComment}
					/>
				)}
			</div>
		</article>
	);
}

/**
 * What to do: the card's call to action. A calm mentor wash with a 2 px accent on its left edge,
 * drawn as its own strip and not as a border, so it meets the rules above and below square instead
 * of in a mitred corner, and the band stays unpositioned under the card's link. Once the work
 * resolved the feedback, the band wears the success wash and leads with the green tick instead.
 */
function NextStepBand({ resolved, nextStep }: { resolved: boolean; nextStep: string }) {
	return (
		<div
			data-next-step=""
			className={cn(
				"flex border-y",
				INNER_RULE,
				resolved ? "bg-success/10 dark:bg-success/15" : "bg-mentor/5 dark:bg-mentor/10",
			)}
		>
			{!resolved && (
				<span aria-hidden data-next-step-edge="" className="w-0.5 shrink-0 bg-mentor" />
			)}
			<div
				className={cn(
					"grid min-w-0 flex-1 grid-cols-[auto_minmax(0,1fr)] items-start gap-x-4 py-3 pr-4",
					resolved ? "pl-4" : "pl-3.5",
				)}
			>
				<span className="flex h-5 items-center" aria-hidden>
					{resolved ? (
						<span className="inline-flex size-4 items-center justify-center rounded-full bg-success">
							<CheckIcon className="size-3 text-success-foreground" strokeWidth={3} />
						</span>
					) : (
						// Footprints: the steps to take next, not the card's state, which its badge says.
						<FootprintsIcon className="size-4 text-mentor" />
					)}
				</span>
				<div className="flex min-w-0 flex-col gap-1">
					{/* The heading stays in the set's own colour; the wash, the edge and the icon carry the accent. */}
					<h4 className={ZONE_HEADING}>Next step</h4>
					<div className={cn(UNTRUSTED_MARKDOWN_PROSE, "max-w-3xl text-sm text-foreground")}>
						<UntrustedMarkdown>{nextStep}</UntrustedMarkdown>
					</div>
				</div>
			</div>
		</div>
	);
}

/** The card's zones while the feedback loads. */
export function PracticeFeedbackCardSkeleton() {
	return (
		<div aria-hidden className={cn("flex flex-col overflow-hidden", CARD_EDGE)}>
			<div className="flex flex-col gap-3 p-4">
				<div className="flex flex-wrap items-center justify-between gap-4">
					<div className="flex items-center gap-3">
						<Skeleton className="h-5 w-48 rounded-full" />
						<Skeleton className="h-5 w-36 rounded-full" />
					</div>
					<Skeleton className="h-5 w-16 rounded-full" />
				</div>
				<Skeleton className="h-7 w-full max-w-md" />
				<div className="flex flex-col gap-1">
					<Skeleton className="h-5 w-32" />
					<Skeleton className="h-5 w-full max-w-3xl" />
					<Skeleton className="h-5 w-3/5" />
				</div>
				<Skeleton className="h-5 w-52 self-end" />
			</div>
			<div
				className={cn(
					"flex flex-col gap-1 border-y bg-mentor/5 px-4 py-3 pl-12 dark:bg-mentor/10",
					INNER_RULE,
				)}
			>
				<Skeleton className="h-5 w-20" />
				<Skeleton className="h-5 w-full max-w-xl" />
			</div>
			<div className="grid gap-x-10 gap-y-4 px-4 py-3 sm:grid-cols-[minmax(0,1fr)_auto]">
				<div className="flex flex-col gap-2.5">
					<Skeleton className="h-5 w-44" />
					<Skeleton className="h-4 w-64 max-w-full" />
					<Skeleton className="h-10 w-full" />
				</div>
				<div className="flex flex-col gap-3 sm:w-56">
					<Skeleton className="h-6 w-full" />
					<Skeleton className="h-8 w-full" />
				</div>
			</div>
			<div
				className={cn(
					"flex flex-wrap items-center justify-between gap-4 border-t px-4 py-2.5",
					INNER_RULE,
				)}
			>
				<Skeleton className="h-5 w-48" />
				<Skeleton className="h-8 w-28" />
			</div>
		</div>
	);
}

/**
 * The clean work in a row: one muted line in words above a segment bar that takes the block's
 * width, set like "Or mark it yourself" above the answers, so the block reads as label and control
 * twice. On an open card its tooltip carries
 * the sentence on how the feedback resolves, which stays the meter's description for a screen
 * reader; the meter then takes focus, so a keyboard opens the tooltip too.
 */
function CleanMeter({
	cleanCount,
	cleanNeeded,
	label,
	description,
}: {
	cleanCount: number;
	cleanNeeded: number;
	label: string;
	description?: ReactNode;
}) {
	const descriptionId = useId();
	const root: Meter.Root.Props = {
		value: cleanCount,
		max: cleanNeeded,
		"aria-label": "Clean work in a row",
		"aria-describedby": description === undefined ? undefined : descriptionId,
		getAriaValueText: () => label,
		className: cn(
			"flex w-full flex-col gap-1.5 rounded-sm",
			description !== undefined && FOCUS_RING,
		),
	};
	const parts = (
		<>
			<Meter.Value className="text-xs text-muted-foreground tabular-nums">
				{() => label}
			</Meter.Value>
			<Meter.Track className="flex h-2 min-w-24 gap-1">
				{Array.from({ length: cleanNeeded }, (_, index) => (
					<span
						key={index}
						className={cn(
							"h-2 flex-1 rounded-full",
							index < cleanCount ? "bg-success" : "bg-border",
						)}
					/>
				))}
			</Meter.Track>
		</>
	);
	if (description === undefined) {
		return <Meter.Root {...root}>{parts}</Meter.Root>;
	}
	return (
		<>
			<Tooltip>
				<TooltipTrigger render={<Meter.Root {...root} tabIndex={0} />}>{parts}</TooltipTrigger>
				<TooltipContent className="max-w-xs">{description}</TooltipContent>
			</Tooltip>
			<span id={descriptionId} className="sr-only">
				{description}
			</span>
		</>
	);
}

/**
 * One answer as a button with its words on it. What it does stays out of sight until asked for: the
 * tooltip says it on hover and focus, and a screen reader hears the same line as the button's
 * description. The pressed answer adds that a second press takes it back.
 */
function Answer({
	value,
	tone,
	pressed,
	saving,
	disabled,
	onClick,
}: {
	value: ResolvingAnswer;
	tone: ReturnType<typeof toneOf>;
	pressed: boolean;
	saving: boolean;
	disabled: boolean;
	onClick: () => void;
}) {
	const descriptionId = useId();
	const { label, effect } = ANSWER_WORDS[value];
	const description = pressed ? `${effect} Press it again to take it back.` : effect;
	return (
		<>
			<Tooltip>
				<TooltipTrigger
					render={
						<ResponseButton
							tone={tone}
							pressed={pressed}
							disabled={disabled}
							aria-describedby={descriptionId}
							className="bg-background"
							onClick={onClick}
						/>
					}
				>
					{saving && <Spinner />}
					{saving ? "Saving…" : label}
				</TooltipTrigger>
				<TooltipContent className="max-w-xs">{description}</TooltipContent>
			</Tooltip>
			<span id={descriptionId} className="sr-only">
				{description}
			</span>
		</>
	);
}

/** One rating as a compact icon button, named by its words and explained in its tooltip. */
function Reaction({
	label,
	description,
	tone,
	pressed,
	disabled,
	onClick,
	children,
}: {
	label: string;
	description: string;
	tone: ReturnType<typeof toneOf>;
	pressed: boolean;
	disabled: boolean;
	onClick: () => void;
	children: ReactNode;
}) {
	const descriptionId = useId();
	return (
		<>
			<Tooltip>
				<TooltipTrigger
					render={
						<ResponseButton
							quiet
							size="icon-sm"
							tone={tone}
							pressed={pressed}
							disabled={disabled}
							aria-label={label}
							aria-describedby={descriptionId}
							onClick={onClick}
						/>
					}
				>
					{children}
				</TooltipTrigger>
				<TooltipContent className="max-w-xs">
					<span className="font-semibold">{label}</span> · {description}
				</TooltipContent>
			</Tooltip>
			<span id={descriptionId} className="sr-only">
				{description}
			</span>
		</>
	);
}

/**
 * The hover and focus tint: a layer under the card's content and over its ground, washes included,
 * so the new and resolved gradients show through. The card isolates its stacking, so the layer sits
 * above the card's own ground and never takes a press.
 */
function CardTint() {
	return (
		<span
			aria-hidden
			data-card-tint=""
			className="pointer-events-none absolute inset-0 -z-10 bg-muted/0 transition-colors group-hover/card:bg-muted/25 group-has-[[data-card-link]:focus-visible]/card:bg-muted/25"
		/>
	);
}

/**
 * The works a pattern recurs in, as the chip's panel lists them: a short heading, then one line per
 * work, newest first, its number linked to the work, its title and repository muted after it, and
 * the day it was reviewed.
 */
function RecurringWorks({
	seenIn,
	reviewedWork,
}: {
	seenIn: WorkSeenIn<ItemRef>;
	reviewedWork: ReviewedWorkOutcome[];
}) {
	const lastSeen = (ref: ItemRef) =>
		Math.max(
			...reviewedWork
				.filter((piece) => workKey(piece.ref) === workKey(ref))
				.map((piece) => piece.date.getTime()),
		);
	const works = [...seenIn.pieces].sort((a, b) => lastSeen(b) - lastSeen(a));
	return (
		<div className="flex min-w-0 flex-col gap-1.5">
			<p className="font-semibold text-foreground">Recurring in {seenIn.these}</p>
			<ul className="flex min-w-0 flex-col divide-y divide-border/60 text-sm">
				{works.map((ref) => {
					const seen = lastSeen(ref);
					return (
						<li key={workKey(ref)} className="flex min-w-0 items-baseline gap-2 py-1.5">
							<InlineLink
								href={ref.url ?? undefined}
								external
								className="shrink-0 font-medium whitespace-nowrap"
							>
								{ref.label}
							</InlineLink>
							<span className="min-w-0 flex-1 truncate text-muted-foreground">
								{[ref.title, ref.container].filter(hasText).join(" · ")}
							</span>
							{Number.isFinite(seen) && (
								<time
									dateTime={new Date(seen).toISOString()}
									className="shrink-0 text-xs text-muted-foreground tabular-nums"
								>
									{formatShortDay(new Date(seen))}
								</time>
							)}
						</li>
					);
				})}
			</ul>
		</div>
	);
}
