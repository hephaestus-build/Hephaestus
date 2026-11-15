import { MousePointerClickIcon } from "lucide-react";
import { motion } from "motion/react";
import { useEffectEvent, useId, useLayoutEffect, useState } from "react";

import { cn } from "cn";

import type { TourSpot } from "./tour-chapters";

import styles from "./tour.module.css";

/** What a step lights up: an element, or a region that behaves like one. */
export type TourTarget = Pick<Element, "getBoundingClientRect" | "scrollIntoView">;

export interface Box {
	top: number;
	left: number;
	width: number;
	height: number;
}

export interface Arrow {
	from: [number, number];
	to: [number, number];
}

export interface Placement {
	top: number;
	left: number;
	arrow: Arrow | null;
}

export interface Viewport {
	width: number;
	height: number;
}

/**
 * Where a step's target stands: no target, still arriving (a level sliding in, the page scrolling to
 * it), found and lit, or not found at all, when the guide waits in the middle of the screen instead.
 * The walk-every-step story fails on `missing`, so a renamed or lost part shows in a test.
 */
export type SpotStatus = "none" | "settling" | "found" | "missing";

const GAP = 44;
const MARGIN = 16;
/** Below this width the guide is a sheet across the screen, over or under the lit part. */
export const NARROW = 640;
/** How long a target may take to arrive: a level slides in, and a smooth scroll ends. */
const SETTLE_MS = 2500;
/** How long a part may stay out of sight after a scroll before the tour scrolls to it again. */
const RESCROLL_MS = 800;
/** Frames in a row that the target must hold still before the light goes on. */
const STILL_FRAMES = 3;

function measure(element: TourTarget, padding: number): Box | null {
	const rect = element.getBoundingClientRect();
	if (rect.width === 0 && rect.height === 0) {
		return null;
	}
	const top = Math.max(rect.top - padding, 4);
	const left = Math.max(rect.left - padding, 4);
	const bottom = Math.min(rect.bottom + padding, window.innerHeight - 4);
	const right = Math.min(rect.right + padding, window.innerWidth - 4);
	if (bottom <= top || right <= left) {
		return null;
	}
	return { top, left, width: right - left, height: bottom - top };
}

function sameBox(a: Box | null, b: Box | null): boolean {
	if (a === null || b === null) {
		return a === b;
	}
	return (
		Math.abs(a.top - b.top) < 0.5 &&
		Math.abs(a.left - b.left) < 0.5 &&
		Math.abs(a.width - b.width) < 0.5 &&
		Math.abs(a.height - b.height) < 0.5
	);
}

interface Spot {
	status: SpotStatus;
	box: Box | null;
}

/** Parts on the reader's own page, outside the example, its levels and the tour's own guide. */
function onReadersPage(selector: string): Element | null {
	for (const element of document.querySelectorAll(selector)) {
		if (element.closest("[data-tour-example], [data-detail-depth], [data-tour-guide]") === null) {
			return element;
		}
	}
	return null;
}

/** The topmost level the example opened: the deepest of its inert levels. */
function topLevel(): Element | null {
	let top: HTMLElement | null = null;
	for (const level of document.querySelectorAll<HTMLElement>("[data-detail-depth][inert]")) {
		if (top === null || Number(level.dataset.detailDepth) > Number(top.dataset.detailDepth)) {
			top = level;
		}
	}
	return top;
}

/** The strip left of the lowest open level, over the dimmed page: a press there closes every level. */
function besideLevels(): TourTarget | null {
	const levels = [...document.querySelectorAll("[data-detail-depth][inert]")];
	if (levels.length === 0) {
		return null;
	}
	const right = Math.min(...levels.map((level) => level.getBoundingClientRect().left));
	const area = new DOMRect(8, 72, Math.max(0, right - 24), window.innerHeight - 144);
	// The strip is no element of its own, so there is nothing to scroll into view.
	return { getBoundingClientRect: () => area, scrollIntoView: () => undefined };
}

/** The part a step lights, wherever its spot says it is. */
function findSpot(spot: TourSpot, examplePage: HTMLElement | null): TourTarget | null {
	if (spot.on === "beside-levels") {
		return besideLevels();
	}
	const slot = `[data-tour-slot="${spot.slot}"]`;
	if (spot.on === "level") {
		return topLevel()?.querySelector(slot) ?? null;
	}
	const selector = spot.card === undefined ? slot : `[data-tour-card="${spot.card}"] ${slot}`;
	return spot.on === "page"
		? onReadersPage(selector)
		: (examplePage?.querySelector(selector) ?? null);
}

/**
 * Finds a step's target, scrolls it into the middle and lights it once it holds still. It keeps the
 * light on the target while it moves, as a level slides in or the page scrolls.
 */
export function useSpot({
	stepKey,
	spot,
	examplePage,
	padding,
	still,
}: {
	stepKey: string;
	spot: TourSpot | undefined;
	/** The example page, where an example step finds its part. */
	examplePage: HTMLElement | null;
	padding: number;
	still: boolean;
}): Spot {
	// What the last frame saw, for the step it saw it for: a new step is settling until a frame says more.
	const [seen, setSeen] = useState<Spot & { key: string }>({
		key: "",
		status: "settling",
		box: null,
	});
	const hasTarget = spot !== undefined;
	const findTarget = useEffectEvent(() =>
		spot === undefined ? null : findSpot(spot, examplePage),
	);

	useLayoutEffect(() => {
		if (!hasTarget) {
			return;
		}
		const start = performance.now();
		let frame = 0;
		let last: Box | null = null;
		let quiet = 0;
		// When the tour last scrolled the part into view; 0 before the first scroll.
		let scrolledAt = 0;
		let found = false;
		let gaveUp = false;
		// One frame at a time for the whole step: the part can move after it is lit, as a level slides
		// away or the page under it scrolls, and a rectangle a frame costs less than listeners that miss
		// a scroll inside an inert page.
		const tick = () => {
			const now = performance.now();
			const target = findTarget();
			const box = target === null ? null : measure(target, padding);
			// Something can scroll the part out of sight again while it arrives, such as a level that
			// closes during a smooth scroll; the part is then scrolled to again. After that the reader
			// owns the scroll, and a part they scroll away only takes its light with it.
			const arriving = now - start < SETTLE_MS;
			if (
				target !== null &&
				(scrolledAt === 0 || (arriving && box === null && now - scrolledAt > RESCROLL_MS))
			) {
				target.scrollIntoView({
					block: "center",
					inline: "nearest",
					behavior: still ? "instant" : "smooth",
				});
				scrolledAt = now;
			}
			quiet = sameBox(box, last) ? quiet + 1 : 0;
			last = box;
			if (box !== null && (found || quiet >= STILL_FRAMES)) {
				found = true;
				setSeen((previous) =>
					previous.key === stepKey && sameBox(previous.box, box)
						? previous
						: { key: stepKey, status: "found", box },
				);
			} else if (found && box === null) {
				// Out of sight, the light goes with it and the guide waits in the middle.
				setSeen((previous) =>
					previous.box === null ? previous : { key: stepKey, status: "found", box: null },
				);
			} else if (!found && !gaveUp && now - start > SETTLE_MS) {
				gaveUp = true;
				setSeen({ key: stepKey, status: "missing", box: null });
			}
			frame = window.requestAnimationFrame(tick);
		};
		frame = window.requestAnimationFrame(tick);
		return () => window.cancelAnimationFrame(frame);
	}, [stepKey, hasTarget, padding, still]);

	if (!hasTarget) {
		return { status: "none", box: null };
	}
	return seen.key === stepKey ? seen : { status: "settling", box: null };
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
 * Where the guide goes: in the middle without a lit part; beside the part where there is room, else
 * below or above it, with an arrow to it; on a narrow screen, a sheet across the half the part is not in.
 *
 * Base UI's Popover positions against an anchor and could take this over, with its arrow and its
 * collision handling. It was not taken: the guide also stands alone in the middle of the screen and
 * as a sheet on a phone, glides between parts as one box instead of reopening at each, and waits for
 * a part that is still sliding in, so most of the placement would remain around it.
 */
export function placeGuide(
	spot: Box | null,
	width: number,
	height: number,
	viewport: Viewport,
	/** The room the guide leaves at the top of the screen. */
	topMargin = MARGIN,
): Placement {
	const { width: vw, height: vh } = viewport;
	if (spot === null) {
		return {
			top: Math.max(topMargin, (vh - height) / 2),
			left: Math.max(MARGIN, (vw - width) / 2),
			arrow: null,
		};
	}
	if (vw < NARROW) {
		const partInTopHalf = spot.top + spot.height / 2 < vh / 2;
		return {
			top: partInTopHalf ? Math.max(topMargin, vh - height - MARGIN) : topMargin,
			left: MARGIN,
			arrow: null,
		};
	}
	const right = spot.left + spot.width + GAP + width < vw - MARGIN;
	const left = spot.left - GAP - width > MARGIN;
	let top: number;
	let at: number;
	let arrow: Arrow;
	if (right || left) {
		// Beside the part, level with its top; the arrow runs straight across.
		at = right ? spot.left + spot.width + GAP : spot.left - GAP - width;
		const anchorY = spot.top + Math.min(spot.height, 120) / 2;
		top = Math.max(topMargin, Math.min(anchorY - 48, vh - height - MARGIN));
		const y = Math.min(Math.max(anchorY, top + 24), top + height - 24);
		arrow = right
			? { from: [at - 4, y], to: [spot.left + spot.width + 4, y] }
			: { from: [at + width + 4, y], to: [spot.left - 4, y] };
	} else {
		// No room beside it: below it, else above it, the arrow straight up or down.
		const centerX = Math.min(Math.max(spot.left + spot.width / 2, MARGIN + 40), vw - MARGIN - 40);
		at = Math.min(Math.max(MARGIN, centerX - width / 2), vw - width - MARGIN);
		const below = spot.top + spot.height + GAP + height < vh - MARGIN;
		top = below ? spot.top + spot.height + GAP : Math.max(topMargin, spot.top - GAP - height);
		arrow = below
			? { from: [centerX, top - 4], to: [centerX, spot.top + spot.height + 4] }
			: { from: [centerX, top + height + 4], to: [centerX, spot.top - 4] };
	}
	const [x1, y1] = arrow.from;
	const [x2, y2] = arrow.to;
	// No arrow where it would cross the guide or point at something the guide covers.
	const hidden =
		overlaps({ top, left: at, width, height }, spot) || Math.hypot(x2 - x1, y2 - y1) < 16;
	return { top, left: at, arrow: hidden ? null : arrow };
}

/** The dimmed screen with one bright part and its ring. */
export function Spotlight({ box, still }: { box: Box | null; still: boolean }) {
	const id = useId();
	const transition = still
		? { duration: 0 }
		: { duration: 0.28, ease: [0.05, 0.7, 0.1, 1] as const };
	const cut =
		box === null ? undefined : { x: box.left, y: box.top, width: box.width, height: box.height };
	return (
		<svg aria-hidden className="pointer-events-none absolute inset-0 size-full">
			<defs>
				<mask id={`${id}-cutout`}>
					{/* A mask reads luminance, not a theme color: white keeps the dimming, black cuts the hole. */}
					<rect width="100%" height="100%" className="fill-white" />
					{cut !== undefined && (
						<motion.rect
							rx={14}
							initial={false}
							animate={cut}
							transition={transition}
							className="fill-black"
						/>
					)}
				</mask>
			</defs>
			{/* Darker than the drawers' scrim, so the one lit part stands out over a level. */}
			<rect width="100%" height="100%" className="fill-black/60" mask={`url(#${id}-cutout)`} />
			{cut !== undefined && (
				<motion.rect
					rx={14}
					fill="none"
					strokeWidth={3}
					initial={false}
					animate={cut}
					transition={transition}
					className="stroke-highlight"
				/>
			)}
		</svg>
	);
}

/** The arrow from the guide to the lit part. */
export function GuideArrow({ arrow }: { arrow: Arrow }) {
	const id = useId();
	return (
		<svg aria-hidden className="pointer-events-none absolute inset-0 size-full">
			<defs>
				<marker
					id={`${id}-head`}
					viewBox="0 0 10 10"
					refX="8"
					refY="5"
					markerWidth="7"
					markerHeight="7"
					orient="auto"
				>
					<path d="M 0 0 L 10 5 L 0 10 z" className="fill-highlight" />
				</marker>
			</defs>
			<line
				x1={arrow.from[0]}
				y1={arrow.from[1]}
				x2={arrow.to[0]}
				y2={arrow.to[1]}
				strokeWidth={2.5}
				strokeLinecap="round"
				markerEnd={`url(#${id}-head)`}
				className="stroke-highlight"
			/>
		</svg>
	);
}

/** A pointer that presses the lit part once, for a press the tour makes on the reader's behalf. */
export function ClickHint({ box }: { box: Box }) {
	return (
		<span
			aria-hidden
			className={cn(styles.press, "pointer-events-none absolute top-(--tour-y) left-(--tour-x)")}
			style={{
				"--tour-x": `${box.left + box.width / 2 - 14}px`,
				"--tour-y": `${box.top + Math.min(box.height / 2, 240) - 14}px`,
			}}
		>
			<span
				className={cn(styles.ripple, "absolute -inset-3 rounded-full border-2 border-highlight")}
			/>
			<MousePointerClickIcon className="relative size-7 text-highlight drop-shadow" />
		</span>
	);
}
