import {
	ArrowLeftIcon,
	ArrowRightIcon,
	ListIcon,
	MousePointerClickIcon,
	RotateCcwIcon,
	XIcon,
} from "lucide-react";
import { motion } from "motion/react";
import {
	type ComponentType,
	type CSSProperties,
	Fragment,
	type ReactNode,
	useCallback,
	useEffect,
	useLayoutEffect,
	useMemo,
	useRef,
	useState,
} from "react";
import { createPortal } from "react-dom";

import { cn } from "cn";
import { HephMark } from "@/components/brand/HephaestusLogo";
import { Button } from "@/components/ui/button";

/** One stop of the tour: what Heph says, and what the reader looks at meanwhile. */
export interface TourStep {
	title: string;
	body: ReactNode;
	/**
	 * Something Heph shows inside its own window, such as a small diagram. A step with a panel opens
	 * Heph as a centered window over the dimmed page and points at nothing.
	 */
	panel?: ReactNode;
	/** Steps with the same key share one mounted panel or page, so its animation plays once. */
	sceneKey?: string;
	/** An example page in full size, in place of the reader's own; it scrolls by itself. */
	page?: ReactNode;
	/** The label over the example page, such as "Example: your Practice profile". */
	pageLabel?: string;
	/** Finds the element to highlight, inside the example page when there is one, else on the real page. */
	target?: (root: ParentNode) => Element | null;
	/** The section the step belongs to; its parts are set by step ranges, see `TourSection`. */
	chapter?: string;
	/** Where the step is in the flow from work to profile, as an index into the tour's stages. */
	stage?: number;
	/** Room around the highlighted element, in pixels. */
	padding?: number;
	/** The target sits in the topmost drawer level the example page opened. */
	inLevel?: boolean;
	/** The small stage icons arrive here, moving up from the overview the step before showed. */
	stageIntro?: boolean;
	/**
	 * The reader clicks the highlighted element to go on, as in the app: the element takes real
	 * clicks and keys, a pointer pressing it shows the way, and Next gives way to a quiet "Skip".
	 */
	clickHint?: boolean;
	/** A fixed height for the panel, so the explanations that follow each other keep one size. */
	panelHeight?: number;
	/** The finished tour's last word: no section, no counter, only Done. */
	end?: boolean;
}

/** One station of the flow from a piece of work to the profile, shown as a small icon. */
export interface TourStage {
	label: string;
	Icon: ComponentType<{ className?: string }>;
}

export interface ProductTourProps {
	steps: TourStep[];
	stages?: TourStage[];
	/** Where the tour can be started again; closing it early points there once before it goes. */
	restartTarget?: () => Element | null;
	open: boolean;
	onClose: () => void;
	/** The step the tour opens at, such as a part's first step. */
	startAt?: number;
	/** The tour's parts in order; the end of each hands back to the overview, see `onComplete`. */
	parts?: readonly TourPart[];
	/** The reader reached a part's last step. */
	onPartDone?: (part: string) => void;
	/** The reader reached the tour's last word. */
	onFinish?: () => void;
	/** With it, the tour's last word offers to go back to the overview. */
	onBackToOverview?: () => void;
	/**
	 * The reader finished a part, or the tour's last step: the tour closes and hands over, for example
	 * to the overview with the part's fresh tick and the next part as its way on. Without it, a part's
	 * end goes straight on and Finish only closes the tour.
	 */
	onComplete?: (part?: string) => void;
}

/** One part of a section: a run of steps the reader can start by itself. */
export interface TourPart {
	/** Unique across the tour: the section and the part's name. */
	id: string;
	section: string;
	name: string;
	/** What the part shows, in one line. */
	line: string;
	first: number;
	last: number;
	count: number;
}

/** One section of a tour, with its parts in order. */
export interface TourSection {
	name: string;
	/** What the section shows, in one line. */
	line: string;
	first: number;
	last: number;
	count: number;
	parts: TourPart[];
}

/**
 * A section as a tour defines it: its parts by the first and last step index of each, or, for a
 * section the reader takes as one block, its own first and last step.
 */
export type TourSectionOutline = { name: string; line: string } & (
	| { parts: readonly { name: string; line: string; first: number; last: number }[] }
	| { first: number; last: number }
);

/** The tour's sections and parts, with ids and step counts, from its outline. */
export function tourSections(outline: readonly TourSectionOutline[]): TourSection[] {
	return outline.map((section) => {
		// A section without parts is one part of its own name, so progress and the overview
		// treat both alike.
		const outlined =
			"parts" in section
				? section.parts
				: [{ name: section.name, line: section.line, first: section.first, last: section.last }];
		const parts = outlined.map((part) => ({
			id: "parts" in section ? `${section.name}/${part.name}` : section.name,
			section: section.name,
			name: part.name,
			line: part.line,
			first: part.first,
			last: part.last,
			count: part.last - part.first + 1,
		}));
		const first = parts[0]?.first ?? 0;
		const last = parts.at(-1)?.last ?? first;
		return { name: section.name, line: section.line, first, last, count: last - first + 1, parts };
	});
}

/** A part's name with its section's, once only for a section taken as one block. */
export function tourPartLabel(part: TourPart): string {
	return part.name === part.section ? part.section : `${part.section} · ${part.name}`;
}

/** About 15 seconds a step, rounded up to whole minutes. */
export function tourMinutes(steps: number): string {
	return `about ${tourMinutesShort(steps)}`;
}

/** The same, short, where a clock icon already says "about": "3 min". */
export function tourMinutesShort(steps: number): string {
	return `${Math.max(1, Math.ceil((steps * 15) / 60))} min`;
}

interface Box {
	top: number;
	left: number;
	width: number;
	height: number;
}

const NO_PARTS: readonly TourPart[] = [];
const CALLOUT_WIDTH = 380;
const GAP = 44;
const MARGIN = 16;
/** Heph's box glides to its next place in this time, from the press on. */
const GLIDE_S = 0.18;
/** A level the example page opens slides in for about this long before Heph points into it. */
const SETTLE_MS = 320;

function measure(element: Element | null | undefined, padding: number): Box | null {
	if (!element) return null;
	const rect = element.getBoundingClientRect();
	if (rect.width === 0 && rect.height === 0) return null;
	const top = Math.max(rect.top - padding, 4);
	const left = Math.max(rect.left - padding, 4);
	const bottom = Math.min(rect.bottom + padding, window.innerHeight - 4);
	const right = Math.min(rect.right + padding, window.innerWidth - 4);
	if (bottom <= top || right <= left) return null;
	return { top, left, width: right - left, height: bottom - top };
}

function overlaps(a: Box, b: Box): boolean {
	return (
		a.left < b.left + b.width &&
		b.left < a.left + a.width &&
		a.top < b.top + b.height &&
		b.top < a.top + a.height
	);
}

/**
 * A guided tour told by Heph. Explanations open Heph as a calm window in the middle of the dimmed
 * screen; the walk through the page shows an example page in full size, dims it, keeps the one
 * thing to look at bright and lets Heph point at it from beside it. It starts only when asked.
 */
export function ProductTour({
	steps,
	stages = [],
	restartTarget,
	open,
	onClose: close,
	startAt = 0,
	parts = NO_PARTS,
	onPartDone,
	onFinish,
	onBackToOverview,
	onComplete,
}: ProductTourProps) {
	const [index, setIndex] = useState(0);
	// Closing before the end first shows where the tour can be found again.
	const [leaving, setLeaving] = useState(false);
	const [box, setBox] = useState<Box | null>(null);
	const [viewport, setViewport] = useState({ width: 0, height: 0 });
	const calloutRef = useRef<HTMLDivElement>(null);
	const pageRef = useRef<HTMLDivElement>(null);
	const [calloutHeight, setCalloutHeight] = useState(220);
	// The callout and the light wait until the step's page or level has settled, so nothing slides.
	const [settled, setSettled] = useState(false);
	const [inset, setInset] = useState<Box | null>(null);
	// Each press plays the panel's animation again, by mounting it anew.
	const [replay, setReplay] = useState(0);
	const lastPageKey = useRef<string | null>(null);
	const lastPlaced = useRef<{ top: number; left: number; width: number } | null>(null);
	const finalStep = steps[steps.length - 1];
	const leavingStep = useMemo<TourStep>(
		() => ({
			title: "No problem. You find the tour here",
			body: "Whenever you want to see it, click Heph at the bottom right and pick a section or one of its parts. Your finished parts keep their tick.",
			target: restartTarget,
			padding: 6,
		}),
		[restartTarget],
	);
	const partAt = parts.findIndex((part) => part.first <= index && index <= part.last);
	const part = parts[partAt];
	const nextPart = part === undefined ? undefined : parts[partAt + 1];
	const step: TourStep | undefined = leaving ? leavingStep : steps[index];
	const onClose = useCallback(() => {
		if (!leaving && restartTarget && step !== finalStep && restartTarget()) setLeaving(true);
		else {
			setLeaving(false);
			close();
		}
	}, [leaving, restartTarget, step, finalStep, close]);

	useEffect(() => {
		if (open) {
			setIndex(Math.min(Math.max(startAt, 0), steps.length - 1));
			setLeaving(false);
		}
	}, [open, startAt, steps.length]);

	// A part counts as finished once its last step showed; the end step finishes the tour.
	const reached = open && !leaving ? steps[index] : undefined;
	useEffect(() => {
		if (reached === undefined) {
			return;
		}
		if (reached.end === true) {
			onFinish?.();
		}
		const finishedPart = parts.find((each) => steps[each.last] === reached);
		if (finishedPart !== undefined) {
			onPartDone?.(finishedPart.id);
		}
	}, [reached, parts, steps, onPartDone, onFinish]);

	const root = useCallback((): ParentNode => {
		if (step?.inLevel) {
			const popups = document.querySelectorAll('[data-slot="drawer-popup"]');
			const top = popups[popups.length - 1];
			if (top) return top;
		}
		return (step?.page && pageRef.current) || document;
	}, [step]);

	const update = useCallback(() => {
		if (!step) return;
		setViewport({ width: window.innerWidth, height: window.innerHeight });
		// The app's own content area, under its top bar and beside its sidebar.
		const main =
			document.querySelector('[data-slot="sidebar-inset"] > main') ??
			document.querySelector('[data-slot="sidebar-inset"]');
		const area = main?.getBoundingClientRect();
		// The app's top bar scrolls away with the page; a scrolled page puts the example right at the top,
		// not above the window, so its elements stay in view.
		setInset(
			area
				? { top: Math.max(area.top, 0), left: area.left, width: area.width, height: area.height }
				: null,
		);
		setBox(step.panel ? null : measure(step.target?.(root()), step.padding ?? 10));
	}, [step, root]);

	// Each stop scrolls its element into the middle, then measures it where it settled.
	useLayoutEffect(() => {
		if (!open || !step) return;
		const scroll = () =>
			step.target?.(root())?.scrollIntoView({ block: "center", behavior: "smooth" });
		// Wait only when the page under Heph changes (a level opens or closes); else glide on at once.
		const pageKey = pageStateKey(step);
		const pageChanged = pageKey !== lastPageKey.current;
		lastPageKey.current = pageKey;
		const wait = pageChanged && step.target !== undefined && step.panel === undefined;
		setSettled(!wait);
		scroll();
		update();
		const timers = [100, 250, SETTLE_MS, 700, 1100, 1600].map((ms) =>
			window.setTimeout(update, ms),
		);
		timers.push(window.setTimeout(scroll, 600));
		if (wait) {
			timers.push(
				window.setTimeout(() => {
					update();
					setSettled(true);
				}, SETTLE_MS),
			);
		}
		return () => timers.forEach((timer) => window.clearTimeout(timer));
	}, [open, step, update, root]);

	useEffect(() => {
		if (!open) return;
		window.addEventListener("resize", update);
		window.addEventListener("scroll", update, true);
		return () => {
			window.removeEventListener("resize", update);
			window.removeEventListener("scroll", update, true);
		};
	}, [open, update]);

	// The box takes its content's height; a ResizeObserver follows text and panels as they change.
	useLayoutEffect(() => {
		const content = calloutRef.current;
		if (!content) return;
		setCalloutHeight(content.offsetHeight);
		const observer = new ResizeObserver(() => setCalloutHeight(content.offsetHeight));
		observer.observe(content);
		return () => observer.disconnect();
	}, [open, leaving]);

	const last = leaving || index === steps.length - 1;
	const partEnd = !leaving && part !== undefined && part.last === index;
	// A part's end hands back to the overview at once, with no question in between.
	const handsOver = !leaving && onComplete !== undefined && (last || partEnd);
	const next = useCallback(() => {
		if (handsOver) {
			onComplete(part?.id);
		} else if (last) {
			onClose();
		} else {
			setIndex((at) => at + 1);
		}
	}, [handsOver, onComplete, part, last, onClose]);
	const back = useCallback(() => setIndex((at) => Math.max(0, at - 1)), []);
	const toOverview = useCallback(() => {
		setLeaving(false);
		onBackToOverview?.();
	}, [onBackToOverview]);

	// A click step: the highlighted element itself is the way on, once Heph stands beside it.
	const overlayRef = useRef<HTMLDivElement>(null);
	const clickable =
		open &&
		step?.clickHint === true &&
		step.panel === undefined &&
		!leaving &&
		settled &&
		box !== null;
	// The link or button the element is or holds or sits in, so it can take focus and keys.
	const clickTarget = useCallback((): Element | null => {
		const element = step?.target?.(root()) ?? null;
		if (!(element instanceof HTMLElement)) {
			return element;
		}
		return element.closest("a, button") ?? element.querySelector("a, button") ?? element;
	}, [step, root]);

	// The press reaches the element, so it shows its own hover and focus, but the tour takes it: a
	// link does not leave the page and a level does not close under Heph; the tour goes on instead.
	// Presses on the dimmed page around it do nothing.
	useEffect(() => {
		if (!clickable) {
			return;
		}
		const onTarget = (event: MouseEvent) => {
			if (!(event.target instanceof Node) || overlayRef.current?.contains(event.target) === true) {
				return false;
			}
			const element = clickTarget();
			if (element instanceof Node && element.contains(event.target)) {
				return true;
			}
			return (
				event.clientX >= box.left &&
				event.clientX <= box.left + box.width &&
				event.clientY >= box.top &&
				event.clientY <= box.top + box.height
			);
		};
		const hold = (event: MouseEvent) => {
			if (onTarget(event)) {
				event.preventDefault();
				event.stopPropagation();
			}
		};
		const press = (event: MouseEvent) => {
			if (onTarget(event)) {
				event.preventDefault();
				event.stopPropagation();
				next();
			}
		};
		const holds = ["pointerdown", "mousedown", "pointerup", "mouseup"] as const;
		for (const type of holds) {
			window.addEventListener(type, hold, true);
		}
		window.addEventListener("click", press, true);
		return () => {
			for (const type of holds) {
				window.removeEventListener(type, hold, true);
			}
			window.removeEventListener("click", press, true);
		};
	}, [clickable, box, clickTarget, next]);

	// Focus moves to the element once Heph stands beside it, so Enter or Space press it.
	useEffect(() => {
		if (!clickable) {
			return;
		}
		// Again a moment later: a level that just slid in may still take focus for itself.
		const focus = () => {
			const element = clickTarget();
			if (element instanceof HTMLElement && !element.contains(document.activeElement)) {
				element.focus({ preventScroll: true });
			}
		};
		focus();
		const timers = [150, 400].map((ms) => window.setTimeout(focus, ms));
		return () => {
			for (const timer of timers) {
				window.clearTimeout(timer);
			}
		};
	}, [clickable, clickTarget]);

	useEffect(() => {
		if (!open) return;
		// In the capture phase, so a drawer level the example page opened, which holds focus, does not
		// swallow the keys; Enter on a focused button stays that button's own press.
		const onKey = (event: KeyboardEvent) => {
			const onControl =
				event.target instanceof HTMLElement && event.target.closest("button, a") !== null;
			const element = clickable ? clickTarget() : null;
			// A spot with nothing to focus, such as the page beside the levels, takes the keys from anywhere.
			const onClickTarget =
				clickable &&
				(!(element instanceof HTMLElement) ||
					(event.target instanceof Node && element.contains(event.target)));
			let act: (() => void) | undefined;
			if (onClickTarget && (event.key === "Enter" || event.key === " ")) {
				act = next;
			} else if (event.key === "Escape") {
				act = onClose;
			} else if (event.key === "ArrowRight" || (event.key === "Enter" && !onControl)) {
				act = next;
			} else if (event.key === "ArrowLeft") {
				act = back;
			}
			if (act !== undefined) {
				event.preventDefault();
				event.stopPropagation();
				act();
			}
		};
		window.addEventListener("keydown", onKey, true);
		return () => window.removeEventListener("keydown", onKey, true);
	}, [open, next, back, onClose, clickable, clickTarget]);

	if (!open || !step) return null;

	const { width: vw, height: vh } = viewport;
	const windowed = step.panel !== undefined || !box || !settled;
	const calloutWidth = step.panel
		? Math.min(1000, vw - MARGIN * 4)
		: windowed
			? 500
			: CALLOUT_WIDTH;
	let calloutTop: number;
	let calloutLeft: number;
	let arrow: { from: [number, number]; to: [number, number] } | null = null;
	if (box && !windowed) {
		const right = box.left + box.width + GAP + calloutWidth < vw - MARGIN;
		const left = box.left - GAP - calloutWidth > MARGIN;
		if (right || left) {
			// Beside the element, level with its top part; the arrow runs straight across.
			calloutLeft = right ? box.left + box.width + GAP : box.left - GAP - calloutWidth;
			const anchorY = box.top + Math.min(box.height, 120) / 2;
			calloutTop = Math.min(Math.max(MARGIN, anchorY - 48), vh - calloutHeight - MARGIN);
			const y = Math.min(Math.max(anchorY, calloutTop + 24), calloutTop + calloutHeight - 24);
			arrow = right
				? { from: [calloutLeft - 4, y], to: [box.left + box.width + 4, y] }
				: { from: [calloutLeft + calloutWidth + 4, y], to: [box.left - 4, y] };
		} else {
			// No room beside it: below it, else above it, the arrow straight up or down.
			const centerX = Math.min(Math.max(box.left + box.width / 2, MARGIN + 40), vw - MARGIN - 40);
			calloutLeft = Math.min(
				Math.max(MARGIN, centerX - calloutWidth / 2),
				vw - calloutWidth - MARGIN,
			);
			const below = box.top + box.height + GAP + calloutHeight < vh - MARGIN;
			calloutTop = below
				? box.top + box.height + GAP
				: Math.max(MARGIN, box.top - GAP - calloutHeight);
			arrow = below
				? { from: [centerX, calloutTop - 4], to: [centerX, box.top + box.height + 4] }
				: { from: [centerX, calloutTop + calloutHeight + 4], to: [centerX, box.top - 4] };
		}
		const callout = {
			top: calloutTop,
			left: calloutLeft,
			width: calloutWidth,
			height: calloutHeight,
		};
		const [x1, y1] = arrow.from;
		const [x2, y2] = arrow.to;
		// No arrow where it would cross the callout or point at something the callout covers.
		if (overlaps(callout, box) || Math.hypot(x2 - x1, y2 - y1) < 16) arrow = null;
	} else {
		calloutTop = Math.max(MARGIN, (vh - calloutHeight) / 2);
		calloutLeft = (vw - calloutWidth) / 2;
	}
	// While a level opens, Heph waits where it was, still readable, instead of flying to the center and back.
	const placed = settled
		? { top: calloutTop, left: calloutLeft, width: calloutWidth }
		: (lastPlaced.current ?? { top: calloutTop, left: calloutLeft, width: calloutWidth });
	if (settled) lastPlaced.current = placed;
	let nextLabel = "Next";
	if (last && (leaving || onComplete === undefined)) {
		nextLabel = "Done";
	} else if (last || (partEnd && nextPart === undefined)) {
		nextLabel = "Finish";
	} else if (handsOver && nextPart?.section === part?.section) {
		nextLabel = "Finish part";
	} else if (handsOver) {
		nextLabel = "Finish section";
	}
	// Where the reader is, quietly: the section and its part, and the step inside the part.
	const where = part === undefined ? undefined : tourPartLabel(part);
	const stepInPart = part === undefined ? index + 1 : index - part.first + 1;
	const stepsInPart = part === undefined ? steps.length : part.count;

	// The example page sits under the app's drawers (z-50), so the levels it opens slide over it as
	// they do in the app; Heph's dimming and callout sit over both.
	const pageLayer = step.page
		? createPortal(
				<>
					{/* The example page in place of the reader's own, beside the app's sidebar. */}
					<div
						className="fixed z-40 flex flex-col bg-background"
						style={inset ? { top: inset.top, left: inset.left, right: 0, bottom: 0 } : { inset: 0 }}
					>
						<span className="pointer-events-none absolute top-2 right-3 z-10 rounded-full bg-mentor/10 px-2.5 py-0.5 text-[11px] font-medium text-mentor">
							{step.pageLabel ?? "Example"}
						</span>
						<div ref={pageRef} key={step.sceneKey} className="tour-scene relative min-h-0 flex-1">
							{step.page}
						</div>
					</div>
				</>,
				document.body,
				"tour-page",
			)
		: null;

	const overlay = createPortal(
		<div
			ref={overlayRef}
			className="pointer-events-none fixed inset-0 z-[1000]"
			role="dialog"
			aria-modal="true"
			aria-label="Tour of the Practice profile"
		>
			{/* On a click step four strips around the light hold the dimmed page still and leave the
			    element itself open to the pointer; elsewhere a press on the dimmed page closes the tour. */}
			{clickable ? (
				<ClickHoles box={box} />
			) : (
				<div
					aria-hidden
					className="pointer-events-auto absolute inset-0"
					// A click step never closes from the dimmed page, also while its element settles.
					onClick={step.clickHint === true ? undefined : onClose}
				/>
			)}
			<svg className="pointer-events-none absolute inset-0 size-full" aria-hidden>
				<defs>
					<mask id="tour-cutout">
						<rect width="100%" height="100%" fill="white" />
						{box && !windowed && (
							<rect
								x={box.left}
								y={box.top}
								width={box.width}
								height={box.height}
								rx={14}
								fill="black"
								className="transition-all duration-180"
							/>
						)}
					</mask>
					<marker
						id="tour-arrowhead"
						viewBox="0 0 10 10"
						refX="8"
						refY="5"
						markerWidth="7"
						markerHeight="7"
						orient="auto"
					>
						<path d="M 0 0 L 10 5 L 0 10 z" fill="white" />
					</marker>
				</defs>
				<rect width="100%" height="100%" fill="rgb(17 24 39 / 0.6)" mask="url(#tour-cutout)" />
				{box && !windowed && (
					<rect
						x={box.left}
						y={box.top}
						width={box.width}
						height={box.height}
						rx={14}
						fill="none"
						stroke="white"
						strokeWidth={3}
						className="transition-all duration-180"
					/>
				)}
				{arrow && (
					<line
						x1={arrow.from[0]}
						y1={arrow.from[1]}
						x2={arrow.to[0]}
						y2={arrow.to[1]}
						stroke="white"
						strokeWidth={2.5}
						strokeLinecap="round"
						markerEnd="url(#tour-arrowhead)"
					/>
				)}
			</svg>
			{step.clickHint && box && settled && (
				<span
					aria-hidden
					className="tour-press pointer-events-none absolute"
					style={{
						left: box.left + box.width / 2 - 14,
						top: box.top + Math.min(box.height / 2, 240) - 14,
					}}
				>
					<span className="tour-ripple absolute -inset-3 rounded-full border-2 border-white" />
					<MousePointerClickIcon className="relative size-7 text-white drop-shadow" />
				</span>
			)}
			{/* Heph's box glides to its next place and size, so a step change never jumps. */}
			<motion.div
				className="pointer-events-auto absolute overflow-hidden rounded-2xl border bg-background shadow-2xl"
				initial={false}
				animate={{ ...placed, height: calloutHeight }}
				transition={{ duration: GLIDE_S, ease: [0.2, 0.9, 0.3, 1] }}
			>
				<div ref={calloutRef} className="flex flex-col gap-3 p-5">
					{!leaving && !step.end && (
						<div className="flex items-center justify-between gap-3">
							{/* Where the reader is; the sections are picked on Heph's start card, not here. */}
							<p className="min-w-0 truncate text-xs font-medium text-mentor">{where}</p>
							<div className="flex shrink-0 items-center gap-3">
								<div
									className="flex items-center gap-2"
									aria-label={`Step ${stepInPart} of ${stepsInPart}`}
								>
									<span className="text-xs text-muted-foreground tabular-nums">
										{stepInPart} / {stepsInPart}
									</span>
									<span className="h-1 w-12 overflow-hidden rounded-full bg-muted">
										<span
											className="block h-full rounded-full bg-mentor transition-[width] duration-180"
											style={{ width: `${(stepInPart / stepsInPart) * 100}%` }}
										/>
									</span>
								</div>
								<Button
									variant="ghost"
									size="icon-sm"
									className="-mr-1.5"
									aria-label="Close the tour"
									onClick={onClose}
								>
									<XIcon aria-hidden />
								</Button>
							</div>
						</div>
					)}
					<div className="flex items-start gap-3">
						<span className="flex size-10 shrink-0 items-center justify-center rounded-full bg-mentor/10 ring-1 ring-mentor/25">
							<HephMark className="size-7" />
						</span>
						<div className="flex min-w-0 flex-1 flex-col gap-1">
							<p className="text-xs font-medium text-mentor">Heph</p>
							<h2
								className={cn(
									"leading-snug font-semibold text-balance",
									step.panel ? "text-lg" : "text-base",
								)}
							>
								{step.title}
							</h2>
						</div>
					</div>
					<div className="text-sm leading-relaxed text-pretty text-muted-foreground">
						{step.body}
					</div>
					{step.panel && (
						<div
							className="tour-scene flex flex-col gap-4 overflow-hidden rounded-xl border bg-sidebar/50 p-5 transition-[height] duration-180 ease-out"
							style={step.panelHeight ? { height: step.panelHeight } : undefined}
						>
							{step.stage !== undefined && stages.length > 0 && (
								<StageTrack stages={stages} at={step.stage} intro={step.stageIntro === true} />
							)}
							<div
								key={`${step.sceneKey}-${replay}`}
								className="flex min-h-0 w-full flex-1 items-start justify-center pt-1"
							>
								<div className="w-full">{step.panel}</div>
							</div>
							<div className="-mt-2 flex shrink-0 justify-end">
								<button
									type="button"
									aria-label="Play the animation again"
									title="Play again"
									onClick={() => setReplay((count) => count + 1)}
									className="flex size-7 items-center justify-center rounded-full border bg-background text-muted-foreground shadow-xs hover:text-mentor"
								>
									<RotateCcwIcon className="size-3.5" />
								</button>
							</div>
						</div>
					)}
					{step.end ? (
						<div className="flex items-center justify-end gap-2 pt-1">
							{onBackToOverview && (
								<Button variant="ghost" size="sm" onClick={toOverview}>
									<ListIcon aria-hidden data-icon="inline-start" />
									Back to the overview
								</Button>
							)}
							<Button size="sm" onClick={onClose}>
								Done
							</Button>
						</div>
					) : leaving ? (
						<div className="flex items-center justify-end gap-2 pt-1">
							<Button variant="ghost" size="sm" onClick={() => setLeaving(false)}>
								<ArrowLeftIcon aria-hidden data-icon="inline-start" />
								Back to the tour
							</Button>
							<Button size="sm" onClick={onClose}>
								Got it
							</Button>
						</div>
					) : (
						<div className="flex items-center justify-between gap-2 pt-1">
							{/* The keys that work here, at the buttons they stand for. */}
							<KeyHint enter={step.clickHint === true} />
							{/* Back and Next side by side, one size, so both read as the way on; the arrow keys work too. */}
							<div className="flex items-center gap-2">
								<Button
									variant="outline"
									size="sm"
									className="min-w-24"
									disabled={index === 0}
									onClick={back}
								>
									<ArrowLeftIcon aria-hidden data-icon="inline-start" />
									Back
								</Button>
								{step.clickHint === true ? (
									<span className="flex items-center gap-2 pl-1 text-xs text-muted-foreground">
										Click it to go on
										<button
											type="button"
											onClick={next}
											className="font-medium text-foreground decoration-1 underline-offset-3 outline-none hover:underline focus-visible:underline"
										>
											Skip
										</button>
									</span>
								) : (
									<Button size="sm" className="min-w-24" onClick={next}>
										{nextLabel}
										{!last && !handsOver && <ArrowRightIcon aria-hidden data-icon="inline-end" />}
									</Button>
								)}
							</div>
						</div>
					)}
				</div>
			</motion.div>
			<style>{STAGES_CSS}</style>
		</div>,
		document.body,
		"tour-overlay",
	);
	return (
		<>
			{pageLayer}
			{overlay}
		</>
	);
}

const STAGES_CSS = `@keyframes tour-press { 0%, 60%, 100% { transform: scale(1); } 75% { transform: scale(.82); } } .tour-press { animation: tour-press 1.6s ease-in-out infinite; } @keyframes tour-ripple { 0%, 70% { opacity: 0; transform: scale(.6); } 80% { opacity: .9; } 100% { opacity: 0; transform: scale(1.6); } } .tour-ripple { animation: tour-ripple 1.6s ease-out infinite; } @keyframes tour-stages-in { from { transform: translateY(70px) scale(1.7); opacity: .3; } to { transform: none; opacity: 1; } } .tour-stages-in { animation: tour-stages-in .3s cubic-bezier(.2,.9,.3,1) both; } @media (prefers-reduced-motion: reduce) { .tour-stages-in { animation: none; } }`;

/** One key as a small keycap. */
function Keycap({ children }: { children: ReactNode }) {
	return (
		<kbd className="inline-flex h-5 min-w-5 items-center justify-center rounded-md border border-b-2 bg-muted/40 px-1 font-sans text-2xs leading-none font-medium text-muted-foreground">
			{children}
		</kbd>
	);
}

/** The keys that move the tour, quietly beside its buttons; on a click step also Enter. */
function KeyHint({ enter }: { enter: boolean }) {
	return (
		<span
			className="flex min-w-0 items-center gap-1 text-2xs text-muted-foreground/80"
			title={
				enter
					? "Enter presses the highlighted element. The arrow keys move back and on."
					: "The arrow keys move back and on."
			}
		>
			<span className="sr-only">
				{enter
					? "Enter presses it. The arrow keys move back and on."
					: "The arrow keys move back and on."}
			</span>
			<span aria-hidden className="flex items-center gap-1">
				<Keycap>←</Keycap>
				<Keycap>→</Keycap>
				{enter && <Keycap>Enter</Keycap>}
			</span>
		</span>
	);
}

/** Four strips around the light that take the presses on the dimmed page, so they do nothing. */
function ClickHoles({ box }: { box: Box }) {
	const hole = {
		"--hole-top": `${box.top}px`,
		"--hole-bottom": `${box.top + box.height}px`,
		"--hole-left": `${box.left}px`,
		"--hole-right": `${box.left + box.width}px`,
		"--hole-height": `${box.height}px`,
	} as CSSProperties;
	return (
		// A press on a strip keeps focus where it was, on the element to click.
		<div
			aria-hidden
			className="absolute inset-0"
			style={hole}
			onMouseDown={(event) => event.preventDefault()}
		>
			<div className="pointer-events-auto absolute inset-x-0 top-0 h-(--hole-top)" />
			<div className="pointer-events-auto absolute inset-x-0 top-(--hole-bottom) bottom-0" />
			<div className="pointer-events-auto absolute top-(--hole-top) left-0 h-(--hole-height) w-(--hole-left)" />
			<div className="pointer-events-auto absolute top-(--hole-top) right-0 left-(--hole-right) h-(--hole-height)" />
		</div>
	);
}

/** The stations from work to profile, inside Heph's window: the current one names itself. */
function StageTrack({ stages, at, intro }: { stages: TourStage[]; at: number; intro: boolean }) {
	return (
		<div
			aria-label={`Stage ${at + 1} of ${stages.length}: ${stages[at]?.label}`}
			className={cn("flex shrink-0 items-center justify-center gap-1.5", intro && "tour-stages-in")}
		>
			{stages.map((stage, index) => (
				<Fragment key={stage.label}>
					{index > 0 && (
						<span
							aria-hidden
							className={cn("h-px w-4 rounded-full", index <= at ? "bg-mentor/60" : "bg-border")}
						/>
					)}
					<span
						className={cn(
							"flex h-6 items-center gap-1 rounded-full transition-colors duration-300",
							index === at
								? "bg-mentor pr-2.5 pl-0.5 text-white ring-2 ring-mentor/15"
								: index < at
									? "w-6 justify-center bg-mentor/10 text-mentor"
									: "w-6 justify-center bg-muted text-muted-foreground/60",
						)}
					>
						<span className="flex size-5 items-center justify-center">
							<stage.Icon className="size-3" />
						</span>
						{index === at && (
							<span className="text-[11px] font-medium whitespace-nowrap">
								{index + 1} · {stage.label}
							</span>
						)}
					</span>
				</Fragment>
			))}
		</div>
	);
}

/** What the example page under a step shows: its scene and the levels open over it. */
function pageStateKey(step: TourStep): string | null {
	if (!step.page) return null;
	const props = (step.page as { props?: Record<string, unknown> }).props ?? {};
	return JSON.stringify([
		step.sceneKey,
		props.levels ?? null,
		// A tab change shows at once; only a level opening or closing slides.
		props.level ?? null,
	]);
}
