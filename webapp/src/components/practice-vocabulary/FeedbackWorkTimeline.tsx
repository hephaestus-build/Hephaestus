import { type ComponentProps, Fragment, type ReactNode, useLayoutEffect, useRef } from "react";

import { cn } from "cn";
import { InlineLink } from "@/components/common/InlineLink";
import { statusToneClass } from "@/components/common/status-def";
import { formatDay, formatShortDay } from "@/lib/dates";

import { OUTCOME_DEFS, type Outcome } from "./outcome-defs";
import type { TimelinePiece } from "./practice-feedback-timeline";

/** What a screen reader hears for each outcome on the timeline, in the student's words. */
const OUTCOME_WORDS: Record<Outcome, string> = {
	MET: "clean",
	NOT_MET: "needed attention",
	DOES_NOT_APPLY: "did not apply",
	COULD_NOT_DETERMINE: "not decided",
};

/**
 * Half the thin line between two places on the timeline, from the edge of one to the middle of the
 * gap: green where it joins two clean pieces of the run, dashed towards the places still missing,
 * absent where the line ends. It stops short of the marker, so no marker needs a ground to cover it.
 */
type Joint = "clean" | "plain" | "missing" | "none";

/**
 * One dash pattern for the line to the places still missing, 3 px dash and 3 px gap, drawn as a
 * gradient rather than a dashed border, whose dashes the browser fits to each half on its own. The
 * two halves of a joint meet in the middle of the gap between two places: the half after a place
 * runs its pattern back from that middle starting with a gap, the half before the next place runs
 * it on from the middle starting with a dash, so across the middle the pattern goes on unbroken.
 */
const DASHES = {
	/** The half before a place: from the middle, dash first. */
	before: "bg-[repeating-linear-gradient(to_right,currentColor_0_3px,transparent_3px_6px)]",
	/** The half after a place: back from the middle, gap first. */
	after: "bg-[repeating-linear-gradient(to_left,transparent_0_3px,currentColor_3px_6px)]",
} as const;

function JointLine({ kind, side }: { kind: Joint; side: "before" | "after" }) {
	if (kind === "missing") {
		return (
			<span
				aria-hidden
				data-joint="missing"
				className={cn("h-px flex-1 text-muted-foreground/40", DASHES[side])}
			/>
		);
	}
	return (
		<span
			aria-hidden
			data-joint={kind}
			className={cn(
				"h-px flex-1",
				kind === "clean" && "bg-success",
				kind === "plain" && "bg-border",
			)}
		/>
	);
}

/** One place on the timeline: its marker on the line, and what is said of it under the line. */
function Place({
	left,
	right,
	marker,
	children,
	...props
}: {
	left: Joint;
	right: Joint;
	marker: ReactNode;
	children?: ReactNode;
} & ComponentProps<"li">) {
	return (
		<li className="flex min-w-14 flex-1 flex-col items-center gap-1 text-center" {...props}>
			<span aria-hidden className="flex h-4 w-full items-center gap-1.5">
				<JointLine kind={left} side="before" />
				{marker}
				<JointLine kind={right} side="after" />
			</span>
			{children}
		</li>
	);
}

/**
 * Older work the line leaves out: a short stretch of line led by "…" before the first piece shown,
 * with no count, since the pattern is in the newest pieces. A screen reader hears that there is
 * earlier work.
 */
function EarlierWork() {
	return (
		<li data-earlier-work="" className="flex flex-none flex-col items-center">
			<span aria-hidden className="flex h-4 w-8 items-center gap-1">
				<span className="text-xs leading-none text-muted-foreground">…</span>
				<JointLine kind="plain" side="after" />
			</span>
			<span className="sr-only">Earlier work not shown</span>
		</li>
	);
}

/**
 * The moment the feedback was created, between the evidence and the work since: a thin upright
 * tick standing on the line, with a caption under it. The line runs through it without a break,
 * solid up to the tick and in the kind of what follows after it. It takes only the room its caption
 * needs, and a screen reader hears each piece's own "before this feedback" instead.
 */
function FeedbackCreated({ right }: { right: Joint }) {
	return (
		<li
			aria-hidden
			data-feedback-created=""
			className="flex flex-none flex-col items-center gap-1 text-center"
		>
			{/* No gap and no padding on the line's row, so both halves meet the tick. */}
			<span className="flex h-4 w-full items-center">
				<JointLine kind="plain" side="before" />
				<span data-feedback-created-tick="" className="h-3 w-px shrink-0 bg-muted-foreground" />
				<JointLine kind={right} side="after" />
			</span>
			{/* One word a line, so the mark stays as narrow as a piece of work. */}
			<span className="w-min px-1 text-xs leading-tight text-muted-foreground">
				Feedback created
			</span>
		</li>
	);
}

/**
 * The reviewed work behind the feedback, oldest to newest along one thin line across the width:
 * each piece the registry's icon for its outcome in its tone (a check in a circle for clean, a
 * wrench for needed attention, as the profile and the admin pages draw them), with nothing behind
 * it, evenly spaced, with its link and its day under it. A thin upright mark captioned "Feedback
 * created" parts the evidence from the work since. A clean piece before it is drawn muted, as it
 * does not count towards resolving the feedback. The clean run since the feedback is joined in
 * green, and the pieces still missing to resolve it wait as small hollow dots at the end, the last
 * one marked "resolves", so the line shows what the meter counts. It is an ordered list, and each
 * item reads "#17, 28 August, needed attention"; a piece before the feedback adds "before this
 * feedback". A narrow screen scrolls the line inside its own box, newest in view.
 */
export function FeedbackWorkTimeline({
	pieces,
	cleanKeys,
	earlier,
	missing,
	createdAt,
}: {
	pieces: TimelinePiece[];
	createdAt: Date;
	cleanKeys: Set<string>;
	/** How many older pieces the line leaves out; any at all draws the "…" stretch, without the number. */
	earlier: number;
	/** The clean pieces still missing to resolve the feedback; zero once it can no longer resolve. */
	missing: number;
}) {
	const scroller = useRef<HTMLDivElement>(null);
	const isClean = (index: number) => {
		const piece = pieces[index];
		return piece !== undefined && cleanKeys.has(piece.key);
	};
	const between = (index: number): Joint =>
		isClean(index) && isClean(index + 1) ? "clean" : "plain";
	const last = pieces.length - 1;
	// Compare recorded review times; evidence ownership alone does not establish chronology.
	const firstSince = pieces.findIndex((piece) => piece.date.getTime() >= createdAt.getTime());
	const split = firstSince === -1 ? pieces.length : firstSince;
	const hasDivider = split > 0;
	const tail: Joint = missing > 0 ? "missing" : "none";
	const before = (index: number): Joint => {
		if (index > 0) {
			return between(index - 1);
		}
		return earlier > 0 ? "plain" : "none";
	};
	const after = (index: number): Joint => {
		if (hasDivider && index === split - 1) {
			return "plain";
		}
		if (index < last) {
			return between(index);
		}
		return tail;
	};

	// The newest work is what matters, so a line wider than the screen opens at its end.
	useLayoutEffect(() => {
		const { current } = scroller;
		if (current !== null) {
			current.scrollLeft = current.scrollWidth;
		}
	}, []);

	if (pieces.length === 0) {
		return null;
	}
	return (
		<div ref={scroller} className="relative z-10 -mx-1 overflow-x-auto px-1 pt-0.5 pb-1">
			<ol aria-label="Where it happened" className="flex w-max min-w-full">
				{earlier > 0 && <EarlierWork />}
				{pieces.map((piece, index) => {
					// The outcome's own icon in its own tone, as the profile and the admin pages draw it. Clean
					// work before the feedback is muted: only the work since counts towards resolving it.
					const def = OUTCOME_DEFS[piece.outcome];
					const Icon = def.icon;
					const beforeFeedback = index < split;
					return (
						<Fragment key={piece.key}>
							{hasDivider && index === split && <FeedbackCreated right="plain" />}
							<Place
								left={before(index)}
								right={after(index)}
								marker={
									<Icon
										data-outcome={piece.outcome}
										data-before-feedback={beforeFeedback ? "" : undefined}
										className={cn(
											"size-4 shrink-0",
											beforeFeedback && piece.outcome === "MET"
												? "text-muted-foreground"
												: statusToneClass(def.badgeVariant),
										)}
									/>
								}
							>
								<span className="flex flex-col leading-tight">
									<InlineLink
										href={piece.ref.url ?? undefined}
										external
										className="text-xs font-medium whitespace-nowrap"
									>
										{piece.ref.label}
									</InlineLink>
									<time
										dateTime={piece.date.toISOString()}
										className="text-xs whitespace-nowrap text-muted-foreground tabular-nums"
									>
										<span aria-hidden>{formatShortDay(piece.date)}</span>
										<span className="sr-only">
											, {formatDay(piece.date)}, {OUTCOME_WORDS[piece.outcome]}
											{beforeFeedback && ", before this feedback"}
										</span>
									</time>
								</span>
							</Place>
						</Fragment>
					);
				})}
				{hasDivider && split === pieces.length && <FeedbackCreated right={tail} />}
				{Array.from({ length: missing }, (_, index) => (
					// The places the run still has to fill: drawn, not read, as the meter says it.
					<Place
						key={`missing ${index}`}
						aria-hidden
						left="missing"
						right={index < missing - 1 ? "missing" : "none"}
						marker={
							<span className="size-2.5 shrink-0 rounded-full border border-muted-foreground/50" />
						}
					>
						{index === missing - 1 && (
							<span className="text-xs whitespace-nowrap text-muted-foreground">resolves</span>
						)}
					</Place>
				))}
			</ol>
		</div>
	);
}
