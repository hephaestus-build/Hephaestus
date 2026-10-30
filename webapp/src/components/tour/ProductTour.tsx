import {
	ArrowLeftIcon,
	ArrowRightIcon,
	MousePointerClickIcon,
	RotateCcwIcon,
	XIcon,
} from "lucide-react";
import { motion } from "motion/react";
import {
	type ComponentType,
	Fragment,
	isValidElement,
	type ReactNode,
	type RefObject,
	useEffect,
	useEffectEvent,
	useLayoutEffect,
	useRef,
	useState,
} from "react";
import { createPortal } from "react-dom";

import { cn } from "cn";
import { HephMark } from "@/components/brand/HephaestusLogo";
import { Button } from "@/components/ui/button";

import styles from "./tour.module.css";

/** What a step highlights: an element, or a region that behaves like one. */
export type TourTarget = Pick<Element, "getBoundingClientRect" | "scrollIntoView">;

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
	target?: (root: ParentNode) => TourTarget | null;
	/** The chapter the step belongs to; Heph shows the chapters as numbered steps to jump between. */
	chapter?: string;
	/** Where the step is in the flow from work to profile, as an index into the tour's stages. */
	stage?: number;
	/** Room around the highlighted element, in pixels. */
	padding?: number;
	/** The target sits in the topmost drawer level the example page opened. */
	inLevel?: boolean;
	/** The small stage icons arrive here, moving up from the overview the step before showed. */
	stageIntro?: boolean;
	/** Shows a pointer pressing the highlighted spot, for a click the tour makes on the reader's behalf. */
	clickHint?: boolean;
	/** A fixed height for the panel, so the explanations that follow each other keep one size. */
	panelHeight?: number;
	/** The finished tour's last word: no chapters, no counter, only Done. */
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
}

interface Box {
	top: number;
	left: number;
	width: number;
	height: number;
}

interface Placement {
	top: number;
	left: number;
	width: number;
}

interface Arrow {
	from: [number, number];
	to: [number, number];
}

interface Chapter {
	name: string;
	first: number;
}

const CALLOUT_WIDTH = 380;
const GAP = 44;
const MARGIN = 16;
const NO_STAGES: TourStage[] = [];

/** Closing before the end first shows where the tour can be found again. */
const LEAVING_STEP: TourStep = {
	title: "No problem. You find the tour here",
	body: "Whenever you want to see it, click “Take the tour”. You can also go back to where you left off.",
	padding: 6,
};

function measure(element: TourTarget | null | undefined, padding: number): Box | null {
	if (element === null || element === undefined) {
		return null;
	}
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

function overlaps(a: Box, b: Box): boolean {
	return (
		a.left < b.left + b.width &&
		b.left < a.left + a.width &&
		a.top < b.top + b.height &&
		b.top < a.top + a.height
	);
}

/** The chapters in the order the steps reach them, each with its first step. */
function chaptersOf(steps: TourStep[]): Chapter[] {
	const list: Chapter[] = [];
	for (const [at, candidate] of steps.entries()) {
		const name = candidate.chapter;
		if (name !== undefined && name !== "" && !list.some((chapter) => chapter.name === name)) {
			list.push({ name, first: at });
		}
	}
	return list;
}

/** Where the step's target is looked for: the topmost level, the example page, or the real page. */
function stepRoot(step: TourStep, page: HTMLDivElement | null): ParentNode {
	if (step.inLevel === true) {
		const top = [...document.querySelectorAll('[data-slot="drawer-popup"]')].at(-1);
		if (top !== undefined) {
			return top;
		}
	}
	if (step.page !== undefined && page !== null) {
		return page;
	}
	return document;
}

/** The app's own content area, under its top bar and beside its sidebar. */
function contentArea(): Box | null {
	const main =
		document.querySelector('[data-slot="sidebar-inset"] > main') ??
		document.querySelector('[data-slot="sidebar-inset"]');
	if (main === null) {
		return null;
	}
	const area = main.getBoundingClientRect();
	return { top: area.top, left: area.left, width: area.width, height: area.height };
}

/** What the example page under a step shows: its scene and the levels open over it. */
function pageStateKey(step: TourStep): string | null {
	if (step.page === undefined) {
		return null;
	}
	const props: Record<string, unknown> = isValidElement<Record<string, unknown>>(step.page)
		? step.page.props
		: {};
	return JSON.stringify([
		step.sceneKey,
		props.levels ?? null,
		// A tab change shows at once; only a level opening or closing slides.
		props.level ?? null,
	]);
}

function calloutWidthFor(hasPanel: boolean, windowed: boolean, vw: number): number {
	if (hasPanel) {
		return Math.min(1000, vw - MARGIN * 4);
	}
	return windowed ? 500 : CALLOUT_WIDTH;
}

/**
 * Where Heph's box goes beside the highlighted element, and the arrow from it. Beside the element
 * when there is room, else below or above it.
 */
function placeBeside(
	box: Box,
	width: number,
	height: number,
	viewport: { width: number; height: number },
): Placement & { arrow: Arrow | null } {
	const { width: vw, height: vh } = viewport;
	const right = box.left + box.width + GAP + width < vw - MARGIN;
	const left = box.left - GAP - width > MARGIN;
	let top: number;
	let at: number;
	let arrow: Arrow;
	if (right || left) {
		// Beside the element, level with its top part; the arrow runs straight across.
		at = right ? box.left + box.width + GAP : box.left - GAP - width;
		const anchorY = box.top + Math.min(box.height, 120) / 2;
		top = Math.min(Math.max(MARGIN, anchorY - 48), vh - height - MARGIN);
		const y = Math.min(Math.max(anchorY, top + 24), top + height - 24);
		arrow = right
			? { from: [at - 4, y], to: [box.left + box.width + 4, y] }
			: { from: [at + width + 4, y], to: [box.left - 4, y] };
	} else {
		// No room beside it: below it, else above it, the arrow straight up or down.
		const centerX = Math.min(Math.max(box.left + box.width / 2, MARGIN + 40), vw - MARGIN - 40);
		at = Math.min(Math.max(MARGIN, centerX - width / 2), vw - width - MARGIN);
		const below = box.top + box.height + GAP + height < vh - MARGIN;
		top = below ? box.top + box.height + GAP : Math.max(MARGIN, box.top - GAP - height);
		arrow = below
			? { from: [centerX, top - 4], to: [centerX, box.top + box.height + 4] }
			: { from: [centerX, top + height + 4], to: [centerX, box.top - 4] };
	}
	const [x1, y1] = arrow.from;
	const [x2, y2] = arrow.to;
	// No arrow where it would cross the callout or point at something the callout covers.
	const hidden =
		overlaps({ top, left: at, width, height }, box) || Math.hypot(x2 - x1, y2 - y1) < 16;
	return { top, left: at, width, arrow: hidden ? null : arrow };
}

function samePlacement(a: Placement | null, b: Placement): boolean {
	return a !== null && a.top === b.top && a.left === b.left && a.width === b.width;
}

/**
 * A guided tour told by Heph. Explanations open Heph as a calm window in the middle of the dimmed
 * screen; the walk through the page shows an example page in full size, dims it, keeps the one
 * thing to look at bright and lets Heph point at it from beside it. It starts only when asked.
 */
export function ProductTour({ open, ...props }: ProductTourProps) {
	// Each opening starts a fresh run at the first step.
	if (!open) {
		return null;
	}
	return <TourRun {...props} />;
}

function TourRun({
	steps,
	stages = NO_STAGES,
	restartTarget,
	onClose: close,
}: Omit<ProductTourProps, "open">) {
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
	// Where Heph's box last stood while its step was settled.
	const [lastPlaced, setLastPlaced] = useState<Placement | null>(null);
	const lastPageKey = useRef<string | null>(null);
	const finalStep = steps.at(-1);
	const step: TourStep | undefined = leaving ? LEAVING_STEP : steps.at(index);
	const target = leaving ? restartTarget : step?.target;
	const chapters = chaptersOf(steps);

	const onClose = () => {
		if (!leaving && restartTarget !== undefined && step !== finalStep && restartTarget() !== null) {
			setLeaving(true);
		} else {
			setLeaving(false);
			close();
		}
	};

	// Each stop scrolls its element into the middle, then measures it where it settled; it measures
	// again whenever the window resizes or anything scrolls.
	useLayoutEffect(() => {
		if (step === undefined) {
			return;
		}
		const find = () => target?.(stepRoot(step, pageRef.current));
		const update = () => {
			setViewport({ width: window.innerWidth, height: window.innerHeight });
			setInset(contentArea());
			setBox(step.panel === undefined ? measure(find(), step.padding ?? 10) : null);
		};
		const scroll = () => find()?.scrollIntoView({ block: "center", behavior: "smooth" });
		// Wait only when the page under Heph changes (a level opens or closes); else glide on at once.
		const pageKey = pageStateKey(step);
		const pageChanged = pageKey !== lastPageKey.current;
		lastPageKey.current = pageKey;
		const wait = pageChanged && target !== undefined && step.panel === undefined;
		setSettled(!wait);
		scroll();
		update();
		const timers = [150, 400, 700, 1100, 1600].map((ms) => window.setTimeout(update, ms));
		timers.push(window.setTimeout(scroll, 600));
		if (wait) {
			timers.push(
				window.setTimeout(() => {
					update();
					setSettled(true);
				}, 750),
			);
		}
		window.addEventListener("resize", update);
		window.addEventListener("scroll", update, true);
		return () => {
			for (const timer of timers) {
				window.clearTimeout(timer);
			}
			window.removeEventListener("resize", update);
			window.removeEventListener("scroll", update, true);
		};
	}, [step, target]);

	// The box takes its content's height; a ResizeObserver follows text and panels as they change.
	useLayoutEffect(() => {
		const content = calloutRef.current;
		if (content === null) {
			return;
		}
		setCalloutHeight(content.offsetHeight);
		const observer = new ResizeObserver(() => setCalloutHeight(content.offsetHeight));
		observer.observe(content);
		return () => observer.disconnect();
	}, []);

	const last = leaving || index === steps.length - 1;
	const next = () => {
		if (last) {
			onClose();
		} else {
			setIndex((at) => at + 1);
		}
	};
	const back = () => setIndex((at) => Math.max(0, at - 1));

	const onKey = useEffectEvent((event: KeyboardEvent) => {
		if (event.key === "Escape") {
			onClose();
		} else if (event.key === "ArrowRight" || event.key === "Enter") {
			next();
		} else if (event.key === "ArrowLeft") {
			back();
		}
	});
	useEffect(() => {
		window.addEventListener("keydown", onKey);
		return () => window.removeEventListener("keydown", onKey);
	}, []);

	const hasPanel = step?.panel !== undefined;
	const spotlight = !hasPanel && settled ? box : null;
	const calloutWidth = calloutWidthFor(hasPanel, spotlight === null, viewport.width);
	const beside =
		spotlight === null ? null : placeBeside(spotlight, calloutWidth, calloutHeight, viewport);
	const current: Placement = beside ?? {
		top: Math.max(MARGIN, (viewport.height - calloutHeight) / 2),
		left: (viewport.width - calloutWidth) / 2,
		width: calloutWidth,
	};
	// While a level opens, Heph waits where it was, faded out, instead of flying to the center and back.
	if (settled && !samePlacement(lastPlaced, current)) {
		setLastPlaced({ top: current.top, left: current.left, width: current.width });
	}
	const placed = settled ? current : (lastPlaced ?? current);

	if (step === undefined) {
		return null;
	}
	const currentChapter = chapters.findIndex((chapter) => chapter.name === step.chapter);

	return (
		<>
			{step.page !== undefined && <PageLayer step={step} inset={inset} pageRef={pageRef} />}
			{createPortal(
				<div
					className="fixed inset-0 z-[1000]"
					role="dialog"
					aria-modal="true"
					aria-label="Tour of the Practice profile"
				>
					<Spotlight box={spotlight} arrow={beside?.arrow ?? null} onClose={onClose} />
					{step.clickHint === true && box !== null && settled && <ClickHint box={box} />}
					{/* Heph's box glides to its next place and size, so a step change never jumps. */}
					<motion.div
						className={cn(
							"absolute overflow-hidden rounded-2xl border bg-background shadow-2xl",
							settled ? "pointer-events-auto" : "pointer-events-none",
						)}
						initial={false}
						animate={{
							top: placed.top,
							left: placed.left,
							width: placed.width,
							height: calloutHeight,
							opacity: settled ? 1 : 0,
						}}
						transition={{ duration: 0.35, ease: [0.2, 0.8, 0.2, 1] }}
					>
						<div ref={calloutRef} className="flex flex-col gap-3 p-5">
							{!leaving && step.end !== true && (
								<ChapterNav
									chapters={chapters}
									current={currentChapter}
									onJump={setIndex}
									onClose={onClose}
								/>
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
											hasPanel ? "text-lg" : "text-base",
										)}
									>
										{step.title}
									</h2>
								</div>
							</div>
							<div className="text-sm leading-relaxed text-pretty text-muted-foreground">
								{step.body}
							</div>
							{hasPanel && (
								<TourPanel
									step={step}
									stages={stages}
									replay={replay}
									onReplay={() => setReplay((count) => count + 1)}
								/>
							)}
							<CalloutFooter
								end={step.end === true}
								leaving={leaving}
								index={index}
								total={steps.length}
								last={last}
								onBack={back}
								onNext={next}
								onClose={onClose}
								onReturn={() => setLeaving(false)}
							/>
						</div>
					</motion.div>
				</div>,
				document.body,
				"tour-overlay",
			)}
		</>
	);
}

/**
 * The example page in place of the reader's own, beside the app's sidebar. It sits under the app's
 * drawers (z-50), so the levels it opens slide over it as they do in the app; Heph's dimming and
 * callout sit over both.
 */
function PageLayer({
	step,
	inset,
	pageRef,
}: {
	step: TourStep;
	inset: Box | null;
	pageRef: RefObject<HTMLDivElement | null>;
}) {
	return createPortal(
		<div
			className={cn(
				"fixed z-40 flex flex-col bg-background",
				inset === null ? "inset-0" : "top-(--tour-top) right-0 bottom-0 left-(--tour-left)",
			)}
			style={
				inset === null
					? undefined
					: { "--tour-top": `${inset.top}px`, "--tour-left": `${inset.left}px` }
			}
		>
			<span className="pointer-events-none absolute top-2 right-3 z-10 rounded-full bg-mentor/10 px-2.5 py-0.5 text-2xs font-medium text-mentor">
				{step.pageLabel ?? "Example"}
			</span>
			<div
				ref={pageRef}
				key={step.sceneKey}
				className={cn(styles.scene, "relative min-h-0 flex-1")}
			>
				{step.page}
			</div>
		</div>,
		document.body,
		"tour-page",
	);
}

/** The dimmed screen with one bright spot, its ring, and the arrow from Heph's box to it. */
function Spotlight({
	box,
	arrow,
	onClose,
}: {
	box: Box | null;
	arrow: Arrow | null;
	onClose: () => void;
}) {
	return (
		<svg className="pointer-events-auto absolute inset-0 size-full" aria-hidden onClick={onClose}>
			<defs>
				<mask id="tour-cutout">
					<rect width="100%" height="100%" className={styles.maskKeep} />
					{box !== null && (
						<rect
							x={box.left}
							y={box.top}
							width={box.width}
							height={box.height}
							rx={14}
							className={cn(styles.maskCut, "transition-all duration-300")}
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
					<path d="M 0 0 L 10 5 L 0 10 z" className="fill-provider-onemphasis-foreground" />
				</marker>
			</defs>
			<rect width="100%" height="100%" className={styles.dim} mask="url(#tour-cutout)" />
			{box !== null && (
				<rect
					x={box.left}
					y={box.top}
					width={box.width}
					height={box.height}
					rx={14}
					fill="none"
					strokeWidth={3}
					className="stroke-provider-onemphasis-foreground transition-all duration-300"
				/>
			)}
			{arrow !== null && (
				<line
					x1={arrow.from[0]}
					y1={arrow.from[1]}
					x2={arrow.to[0]}
					y2={arrow.to[1]}
					strokeWidth={2.5}
					strokeLinecap="round"
					markerEnd="url(#tour-arrowhead)"
					className="stroke-provider-onemphasis-foreground"
				/>
			)}
		</svg>
	);
}

/** A pointer pressing the highlighted spot, for a click the tour makes on the reader's behalf. */
function ClickHint({ box }: { box: Box }) {
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
				className={cn(
					styles.ripple,
					"absolute -inset-3 rounded-full border-2 border-provider-onemphasis-foreground",
				)}
			/>
			<MousePointerClickIcon className="relative size-7 text-provider-onemphasis-foreground drop-shadow" />
		</span>
	);
}

function chapterTone(at: number, current: number): string {
	if (at === current) {
		return "bg-mentor/10 pr-2.5 pl-1 font-semibold text-mentor";
	}
	return cn(
		"w-6 justify-center hover:bg-muted",
		at < current ? "text-foreground" : "text-muted-foreground",
	);
}

/** The chapters as numbered steps: the current one names itself, the others show a number. */
function ChapterNav({
	chapters,
	current,
	onJump,
	onClose,
}: {
	chapters: Chapter[];
	current: number;
	onJump: (index: number) => void;
	onClose: () => void;
}) {
	return (
		<div className="flex items-center justify-between gap-3">
			<nav aria-label="Tour chapters" className="flex min-w-0 items-center gap-1">
				{chapters.map((chapter, at) => (
					<Fragment key={chapter.name}>
						{at > 0 && <span aria-hidden className="h-px w-3 shrink-0 bg-border" />}
						<button
							type="button"
							title={chapter.name}
							onClick={() => onJump(chapter.first)}
							className={cn(
								"flex h-6 shrink-0 items-center gap-1.5 rounded-full text-2xs whitespace-nowrap transition-colors",
								chapterTone(at, current),
							)}
						>
							<span
								className={cn(
									"flex size-4.5 items-center justify-center rounded-full text-2xs tabular-nums",
									at === current ? "bg-mentor text-mentor-foreground" : "border",
								)}
							>
								{at + 1}
							</span>
							{at === current && chapter.name}
						</button>
					</Fragment>
				))}
			</nav>
			<div className="flex shrink-0 items-center gap-2">
				<Button variant="ghost" size="icon-sm" aria-label="Close the tour" onClick={onClose}>
					<XIcon aria-hidden />
				</Button>
			</div>
		</div>
	);
}

/** Heph's own window content: the stage track, the step's panel, and a button to play it again. */
function TourPanel({
	step,
	stages,
	replay,
	onReplay,
}: {
	step: TourStep;
	stages: TourStage[];
	replay: number;
	onReplay: () => void;
}) {
	const height = step.panelHeight;
	return (
		<div
			className={cn(
				styles.scene,
				"flex flex-col gap-4 overflow-hidden rounded-xl border bg-sidebar/50 p-5 transition-[height] duration-300 ease-out",
				height !== undefined && height > 0 && "h-(--tour-panel-height)",
			)}
			style={
				height !== undefined && height > 0 ? { "--tour-panel-height": `${height}px` } : undefined
			}
		>
			{step.stage !== undefined && stages.length > 0 && (
				<StageTrack stages={stages} at={step.stage} intro={step.stageIntro === true} />
			)}
			<div
				key={`${step.sceneKey ?? ""}-${replay}`}
				className="flex min-h-0 w-full flex-1 items-start justify-center pt-1"
			>
				<div className="w-full">{step.panel}</div>
			</div>
			<div className="-mt-2 flex shrink-0 justify-end">
				<button
					type="button"
					aria-label="Play the animation again"
					title="Play again"
					onClick={onReplay}
					className="flex size-7 items-center justify-center rounded-full border bg-background text-muted-foreground shadow-xs hover:text-mentor"
				>
					<RotateCcwIcon className="size-3.5" />
				</button>
			</div>
		</div>
	);
}

/** The way on: Done at the end, back to the tour while leaving, else the counter and Back and Next. */
function CalloutFooter({
	end,
	leaving,
	index,
	total,
	last,
	onBack,
	onNext,
	onClose,
	onReturn,
}: {
	end: boolean;
	leaving: boolean;
	index: number;
	total: number;
	last: boolean;
	onBack: () => void;
	onNext: () => void;
	onClose: () => void;
	onReturn: () => void;
}) {
	if (end) {
		return (
			<div className="flex items-center justify-end pt-1">
				<Button size="sm" onClick={onClose}>
					Done
				</Button>
			</div>
		);
	}
	if (leaving) {
		return (
			<div className="flex items-center justify-end gap-2 pt-1">
				<Button variant="ghost" size="sm" onClick={onReturn}>
					<ArrowLeftIcon aria-hidden data-icon="inline-start" />
					Back to the tour
				</Button>
				<Button size="sm" onClick={onClose}>
					Got it
				</Button>
			</div>
		);
	}
	return (
		<div className="flex items-center justify-between gap-2 pt-1">
			<div className="flex items-center gap-2" aria-label={`Step ${index + 1} of ${total}`}>
				<span className="text-xs text-muted-foreground tabular-nums">
					{index + 1} / {total}
				</span>
				<span className="h-1 w-16 overflow-hidden rounded-full bg-muted">
					<span
						className="block h-full w-(--tour-progress) rounded-full bg-mentor transition-[width] duration-300"
						style={{ "--tour-progress": `${((index + 1) / total) * 100}%` }}
					/>
				</span>
			</div>
			<div className="flex items-center gap-2">
				{index > 0 && (
					<Button variant="ghost" size="sm" onClick={onBack}>
						<ArrowLeftIcon aria-hidden data-icon="inline-start" />
						Back
					</Button>
				)}
				<Button size="sm" onClick={onNext}>
					{last ? "Done" : "Next"}
					{!last && <ArrowRightIcon aria-hidden data-icon="inline-end" />}
				</Button>
			</div>
		</div>
	);
}

/** The stations from work to profile, inside Heph's window: the current one names itself. */
function StageTrack({ stages, at, intro }: { stages: TourStage[]; at: number; intro: boolean }) {
	return (
		<div
			aria-label={`Stage ${at + 1} of ${stages.length}: ${stages.at(at)?.label ?? ""}`}
			className={cn("flex shrink-0 items-center justify-center gap-1.5", intro && styles.stagesIn)}
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
							stageTone(index, at),
						)}
					>
						<span className="flex size-5 items-center justify-center">
							<stage.Icon className="size-3" />
						</span>
						{index === at && (
							<span className="text-2xs font-medium whitespace-nowrap">
								{index + 1} · {stage.label}
							</span>
						)}
					</span>
				</Fragment>
			))}
		</div>
	);
}

function stageTone(index: number, at: number): string {
	if (index === at) {
		return "bg-mentor pr-2.5 pl-0.5 text-mentor-foreground ring-2 ring-mentor/15";
	}
	if (index < at) {
		return "w-6 justify-center bg-mentor/10 text-mentor";
	}
	return "w-6 justify-center bg-muted text-muted-foreground/60";
}
