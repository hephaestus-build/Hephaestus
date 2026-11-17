import { Dialog } from "@base-ui/react/dialog";
import {
	ArrowLeftIcon,
	ArrowRightIcon,
	CheckIcon,
	ChevronRightIcon,
	RotateCcwIcon,
	XIcon,
} from "lucide-react";
import { motion } from "motion/react";
import {
	type KeyboardEvent,
	type MouseEvent,
	type ReactNode,
	type RefObject,
	useEffect,
	useLayoutEffect,
	useRef,
	useState,
} from "react";

import { cn } from "cn";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { pageHeading } from "@/components/onboarding/PublicActivityNotice";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";

import { exampleProfile } from "./example-profile";
import {
	spotCaption,
	suggestedChapter,
	type TourChapter,
	type TourChapterId,
	tourChapters,
	type TourFacts,
	type TourStep,
} from "./tour-chapters";
import { useTourProgress } from "./tour-progress";
import { Stations, useStill } from "./tour-scenes";
import {
	ClickHint,
	GuideArrow,
	NARROW,
	placeGuide,
	Spotlight,
	useSpot,
	type Viewport,
} from "./tour-spotlight";
import { TOUR_START_SELECTOR } from "./tour-start";
import { EXAMPLE_CAPTION, TourExampleLayer } from "./TourExampleLayer";
import styles from "./tour.module.css";

export interface PracticeProfileTourProps extends TourFacts {
	/** The reader's account, whose finished chapters and place this browser keeps; absent, it keeps none. */
	accountId: string | undefined;
	/** The chapter an offer on the page asked for; without it the tour opens on its menu. */
	startChapter?: TourChapterId;
	/**
	 * A step of Get started asked for this one chapter: its last step ends the tour, so the list opens
	 * again with the chapter marked, instead of the tour running on into the next chapter.
	 */
	oneChapter?: boolean;
	onClose: () => void;
}

type PageStatus = PracticeProfileTourProps["page"]["state"]["status"];

/** What the guide says when it shows no step: its menu, or why the tour has to wait for the page. */
const MENU_WORDS: Record<PageStatus, string> = {
	ready: "Choose what you want to know. You can leave at any time and continue later.",
	loading: "Your Practice profile is still loading. The tour goes on when it is ready.",
	error: "The tour shows your own Practice profile, and that page could not load.",
};

type View = { kind: "menu" } | { kind: "step"; chapter: TourChapterId; step: string };

const MARGIN = 16;
/** The room the example's caption takes at the top of the screen. */
const CAPTION_ROOM = 44;

/** How wide the guide is: the menu and a scene in the middle, words beside a lit part. */
function guideWidth(step: TourStep | undefined, viewport: Viewport): number {
	const room = Math.max(0, viewport.width - MARGIN * 2);
	if (step === undefined) {
		return Math.min(608, room);
	}
	if (step.spot === undefined) {
		return Math.min(960, room);
	}
	return viewport.width < NARROW ? room : Math.min(380, room);
}

function readViewport(): Viewport {
	return { width: window.innerWidth, height: window.innerHeight };
}

/**
 * A control that held focus can leave with its step, and Base UI can park focus on the popup itself,
 * which covers the screen and shows no focus. Focus then goes to the way on, so it never falls out
 * of the guide.
 */
function useFocusStaysInGuide(primary: RefObject<HTMLButtonElement | null>) {
	useEffect(() => {
		const active = document.activeElement;
		const lost =
			!(active instanceof HTMLElement) ||
			active === document.body ||
			!active.isConnected ||
			Object.hasOwn(active.dataset, "tourGuide");
		if (lost) {
			primary.current?.focus({ preventScroll: true });
		}
	});
}

/**
 * Where focus goes when the tour closes: back to what started it. A step of Get started on another
 * page is gone by then, and Safari leaves focus on the body after a click; focus then goes to where
 * the tour starts again, so the reader sees where the tour lives.
 */
function returnFocusTo(opener: Element | null): HTMLElement | null {
	if (opener instanceof HTMLElement && opener !== document.body && opener.isConnected) {
		return opener;
	}
	return document.querySelector<HTMLElement>(TOUR_START_SELECTOR) ?? pageHeading(document.body);
}

/**
 * The chapter and the step the view shows. A step can leave while it is open, when the data it
 * stands on changes, or wait while the page reloads; the menu then takes over.
 */
function locate(view: View, chapters: TourChapter[], ready: boolean) {
	const chapter =
		view.kind === "step" ? chapters.find((each) => each.id === view.chapter) : undefined;
	const stepIndex =
		view.kind === "step" && chapter ? chapter.steps.findIndex((each) => each.id === view.step) : -1;
	const step = stepIndex >= 0 && ready ? chapter?.steps.at(stepIndex) : undefined;
	return { chapter, stepIndex, step };
}

/** What the primary button says on a step: on, to the next chapter, or done. */
function primaryLabel(onLastStep: boolean, nextChapter: TourChapter | undefined): string {
	if (!onLastStep) {
		return "Next";
	}
	return nextChapter === undefined ? "Done" : `Next chapter: ${nextChapter.name}`;
}

/**
 * The guided tour of the Practice profile: chapters that a person chooses, told by a guide that
 * points at the real page. Where the reader has data, it lights the parts of their own page; where
 * they have none, and for the levels under the page, it opens the real components over an example
 * that says it is one. It is a dialog that traps focus without locking the page's scroll, because the
 * tour scrolls the page to the part it explains (`webapp/DESIGN.md` § Design principles, 3).
 */
export function PracticeProfileTour({
	accountId,
	startChapter,
	oneChapter = false,
	onClose,
	...facts
}: PracticeProfileTourProps) {
	const [example] = useState(() => exampleProfile(new Date(), facts.provider));
	const chapters = tourChapters(facts, example);
	const tour = useTourProgress(accountId);
	const still = useStill();
	const [view, setView] = useState<View>(() => {
		const chapter = chapters.find((each) => each.id === startChapter);
		const first = chapter?.steps.at(0);
		return chapter === undefined || first === undefined
			? { kind: "menu" }
			: { kind: "step", chapter: chapter.id, step: first.id };
	});
	const primary = useRef<HTMLButtonElement>(null);
	// Read at mount, while focus is still on what started the tour.
	const [opener] = useState(() => document.activeElement);
	const closedByLink = useRef(false);
	const body = useRef<HTMLDivElement>(null);
	const [examplePage, setExamplePage] = useState<HTMLDivElement | null>(null);

	useFocusStaysInGuide(primary);

	const { chapter, stepIndex, step } = locate(view, chapters, facts.page.state.status === "ready");
	const stepKey = step && chapter ? `${chapter.id}:${step.id}` : "menu";
	const spot = useSpot({
		stepKey,
		spot: step?.spot,
		examplePage,
		padding: step?.padding ?? 10,
		still,
	});
	const nextChapter = oneChapter
		? undefined
		: chapter && chapters.at(chapters.indexOf(chapter) + 1);
	const onLastStep = chapter !== undefined && stepIndex === chapter.steps.length - 1;

	const show = (chapterId: TourChapterId, stepId: string) => {
		// A new step starts with its words in view, where the guide scrolls as a whole.
		if (body.current) {
			body.current.scrollTop = 0;
		}
		setView({ kind: "step", chapter: chapterId, step: stepId });
	};
	const showStep = (target: TourChapter, next: TourStep) => {
		tour.stopAt(target.id, next.id);
		show(target.id, next.id);
	};
	const goBack = () => {
		const previous = stepIndex > 0 ? chapter?.steps.at(stepIndex - 1) : undefined;
		if (chapter && previous) {
			showStep(chapter, previous);
		}
	};
	const goOn = () => {
		if (!chapter) {
			return;
		}
		const following = chapter.steps.at(stepIndex + 1);
		if (following) {
			showStep(chapter, following);
			return;
		}
		tour.finish(chapter.id);
		const opening = nextChapter?.steps.at(0);
		if (nextChapter && opening) {
			show(nextChapter.id, opening.id);
		} else {
			onClose();
		}
	};
	const openChapter = (target: TourChapter) => {
		const first = target.steps.at(0);
		if (first) {
			showStep(target, first);
		}
	};

	const onKeyDown = (event: KeyboardEvent<HTMLElement>) => {
		// A held key must not run through the chapters, and Alt+Left belongs to the browser's history.
		const modified = event.altKey || event.ctrlKey || event.metaKey || event.shiftKey;
		if (step === undefined || event.repeat || modified) {
			return;
		}
		if (event.target instanceof Element && event.target.closest("a")) {
			return;
		}
		if (event.key === "ArrowRight") {
			goOn();
		} else if (event.key === "ArrowLeft") {
			goBack();
		}
	};

	// A link in the tour leads to the reader's own data, so the tour gets out of its way.
	const onClickCapture = (event: MouseEvent<HTMLElement>) => {
		// A modified click opens the link elsewhere, so the reader stays here.
		if (event.metaKey || event.ctrlKey || event.shiftKey || event.button !== 0) {
			return;
		}
		if (event.target instanceof Element && event.target.closest("a[href]:not([target])")) {
			closedByLink.current = true;
			onClose();
		}
	};

	const settling = spot.status === "settling";
	const lit = spot.status === "found" ? spot.box : null;
	const menuWords = MENU_WORDS[facts.page.state.status];

	return (
		<>
			{step?.example !== undefined && (
				<TourExampleLayer view={step.example} example={example} pageRef={setExamplePage} />
			)}
			<Dialog.Root
				open
				modal="trap-focus"
				disablePointerDismissal
				onOpenChange={(open) => {
					if (!open) {
						onClose();
					}
				}}
			>
				<Dialog.Portal>
					{/* Over the page and its levels, so a press outside the guide reaches nothing under it. */}
					<Dialog.Backdrop className="fixed inset-0 z-[1000]" />
					<Dialog.Popup
						data-tour-guide=""
						data-spot={spot.status}
						initialFocus={primary}
						finalFocus={() => (closedByLink.current ? false : returnFocusTo(opener))}
						onKeyDown={onKeyDown}
						onClickCapture={onClickCapture}
						className="pointer-events-none fixed inset-0 z-[1000] focus-visible:outline-hidden"
					>
						<Spotlight box={lit} still={still} />
						{/* Over the dimming and the example's levels, so the caption stays in sight on every
						    example step. Top left, over the dimmed page, where no level's path sits. The step's
						    words say it too, for a screen reader. */}
						{step?.example !== undefined && (
							<p
								aria-hidden
								data-tour-example-caption=""
								className="pointer-events-auto absolute top-2 left-4 rounded-full bg-highlight px-3 py-1 text-xs font-medium whitespace-nowrap text-highlight-foreground shadow-md"
							>
								{EXAMPLE_CAPTION}
							</p>
						)}
						{step?.clickHint === true && lit !== null && <ClickHint box={lit} />}
						<Guide
							step={step}
							lit={lit}
							settling={settling}
							still={still}
							reserveTop={step?.example !== undefined}
						>
							{/* A div, not a header: a header outside a sectioning element is a second banner landmark. */}
							<div className="flex items-start justify-between gap-3 border-b px-4 py-3">
								<div className="flex min-w-0 flex-col gap-2">
									<Dialog.Title className="font-semibold">Guided tour</Dialog.Title>
									{step && chapter && (
										<ChapterNav
											chapters={chapters}
											current={chapter.id}
											done={tour.progress.done}
											onChoose={openChapter}
										/>
									)}
								</div>
								<Dialog.Close
									render={<Button variant="ghost" size="icon-sm" />}
									aria-label="Close the tour"
								>
									<XIcon aria-hidden />
								</Dialog.Close>
							</div>
							<div
								ref={body}
								// A keyboard reaches the words of a long step, where the guide scrolls (WCAG 2.1.1).
								tabIndex={step === undefined ? undefined : 0}
								role={step === undefined ? undefined : "region"}
								aria-label={step?.title}
								className="flex min-h-0 flex-1 flex-col gap-3 overflow-y-auto p-4 focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-hidden focus-visible:ring-inset"
							>
								{/*
								 * One live region for the whole life of the guide, so the first step of a chapter is
								 * announced as well: a region that mounts with its text says nothing.
								 */}
								<Dialog.Description
									render={<div />}
									aria-live="polite"
									aria-atomic
									className="flex flex-col gap-3"
								>
									{chapter && step ? (
										<StepWords chapter={chapter} step={step} stepKey={stepKey} index={stepIndex} />
									) : (
										<span className="text-muted-foreground">{menuWords}</span>
									)}
								</Dialog.Description>
								{step?.scene !== undefined && <StepScene step={step} still={still} />}
								{step === undefined && (
									<MenuView
										status={facts.page.state}
										chapters={chapters}
										suggested={suggestedChapter(facts.page.feedbackCards)}
										progress={tour.progress}
										onReset={tour.reset}
										onChoose={showStep}
										firstRef={primary}
									/>
								)}
							</div>
							{chapter && step ? (
								<StepFooter
									index={stepIndex}
									total={chapter.steps.length}
									primaryLabel={primaryLabel(onLastStep, nextChapter)}
									onward={!onLastStep || nextChapter !== undefined}
									onMenu={() => setView({ kind: "menu" })}
									onBack={goBack}
									onNext={goOn}
									primary={primary}
								/>
							) : (
								<footer className="flex border-t px-4 py-3">
									<Dialog.Close render={<Button variant="ghost" size="sm" className="ml-auto" />}>
										Close
									</Dialog.Close>
								</footer>
							)}
						</Guide>
					</Dialog.Popup>
				</Dialog.Portal>
			</Dialog.Root>
		</>
	);
}

/**
 * The guide's box, with the arrow to the lit part. It glides to its next place, so a step change
 * never jumps; while the part arrives, it waits where it stood with the new words already in it.
 */
function Guide({
	step,
	lit,
	settling,
	still,
	reserveTop,
	children,
}: {
	step: TourStep | undefined;
	lit: Parameters<typeof placeGuide>[0];
	settling: boolean;
	still: boolean;
	/** Keeps the top of the screen clear for the example's caption. */
	reserveTop: boolean;
	children: ReactNode;
}) {
	const guide = useRef<HTMLDivElement>(null);
	const [viewport, setViewport] = useState(readViewport);
	const [height, setHeight] = useState(0);
	const [lastPlace, setLastPlace] = useState<{ top: number; left: number } | null>(null);

	useEffect(() => {
		const update = () => setViewport(readViewport());
		window.addEventListener("resize", update);
		return () => window.removeEventListener("resize", update);
	}, []);

	// The guide takes its content's height; the placement follows it as words and scenes change.
	useLayoutEffect(() => {
		const element = guide.current;
		if (element === null) {
			return;
		}
		setHeight(element.offsetHeight);
		const observer = new ResizeObserver(() => setHeight(element.offsetHeight));
		observer.observe(element);
		return () => observer.disconnect();
	}, []);

	const width = guideWidth(step, viewport);
	const placement = placeGuide(lit, width, height, viewport, reserveTop ? CAPTION_ROOM : MARGIN);
	if (!settling && (lastPlace?.top !== placement.top || lastPlace.left !== placement.left)) {
		setLastPlace({ top: placement.top, left: placement.left });
	}
	const place = settling && lastPlace !== null ? lastPlace : placement;

	return (
		<>
			{!settling && placement.arrow !== null && <GuideArrow arrow={placement.arrow} />}
			<motion.div
				ref={guide}
				initial={false}
				animate={{ x: place.left, y: place.top }}
				transition={still ? { duration: 0 } : { duration: 0.28, ease: [0.05, 0.7, 0.1, 1] }}
				className={cn(
					styles.enter,
					"pointer-events-auto absolute top-0 left-0 flex max-h-(--tour-max-h) w-(--tour-w) flex-col overflow-hidden rounded-xl border bg-background text-sm shadow-2xl",
				)}
				style={{
					"--tour-w": `${width}px`,
					"--tour-max-h": `${Math.max(0, viewport.height - (reserveTop ? CAPTION_ROOM : MARGIN) - MARGIN)}px`,
				}}
			>
				{children}
			</motion.div>
		</>
	);
}

/** The step's words, led for a screen reader by its chapter and its place in it. */
function StepWords({
	chapter,
	step,
	stepKey,
	index,
}: {
	chapter: TourChapter;
	step: TourStep;
	stepKey: string;
	index: number;
}) {
	const caption = spotCaption(step);
	return (
		<>
			<span className="sr-only">
				{chapter.name}. Step {index + 1} of {chapter.steps.length}.
			</span>
			{chapter.steps.some((each) => each.station !== undefined) && (
				<Stations current={step.station} />
			)}
			{caption !== undefined && (
				<span className="text-xs font-medium text-muted-foreground">{caption}.</span>
			)}
			<div key={stepKey} className={cn(styles.enter, "flex flex-col gap-2")}>
				<h3 className="text-base font-semibold text-balance">{step.title}</h3>
				<div className="flex flex-col gap-2 text-muted-foreground">{step.body}</div>
			</div>
		</>
	);
}

/** The scene a step shows in the guide, with the way to play it again. */
function StepScene({ step, still }: { step: TourStep; still: boolean }) {
	const [replays, setReplays] = useState(0);
	return (
		<div className="flex flex-col gap-2">
			{step.plays === true && !still && (
				<Button
					variant="quiet"
					size="xs"
					className="self-end"
					onClick={() => setReplays((count) => count + 1)}
				>
					<RotateCcwIcon aria-hidden />
					Play again
				</Button>
			)}
			<div
				key={`${step.sceneKey ?? step.id}:${replays}`}
				inert={step.live !== true}
				className="rounded-lg border bg-sidebar p-4"
			>
				{step.scene}
			</div>
		</div>
	);
}

/** The way through a chapter: back to the menu, the place in the chapter, Back and the way on. */
function StepFooter({
	index,
	total,
	primaryLabel: label,
	onward,
	onMenu,
	onBack,
	onNext,
	primary,
}: {
	index: number;
	total: number;
	primaryLabel: string;
	/** Whether the way on leads further, so it shows an arrow. */
	onward: boolean;
	onMenu: () => void;
	onBack: () => void;
	onNext: () => void;
	primary: RefObject<HTMLButtonElement | null>;
}) {
	return (
		<footer className="flex flex-wrap items-center gap-2 border-t px-4 py-3">
			<Button variant="ghost" size="sm" onClick={onMenu}>
				All chapters
			</Button>
			<StepCount index={index} total={total} />
			{/* One group at the end; on a narrow guide it wraps onto its own line, and Back above Next. */}
			<span className="ml-auto flex flex-wrap items-center justify-end gap-2">
				{index > 0 && (
					<Button variant="outline" size="sm" onClick={onBack}>
						<ArrowLeftIcon aria-hidden />
						Back
					</Button>
				)}
				<Button ref={primary} size="sm" onClick={onNext}>
					{label}
					{onward && <ArrowRightIcon aria-hidden />}
				</Button>
			</span>
		</footer>
	);
}

function StepCount({ index, total }: { index: number; total: number }) {
	return (
		<span className="ml-auto flex items-center gap-2 text-xs text-muted-foreground tabular-nums">
			Step {index + 1} of {total}
			<span aria-hidden className="h-1 w-12 overflow-hidden rounded-full bg-muted">
				<span
					className="block h-full w-(--tour-progress) rounded-full bg-highlight"
					style={{ "--tour-progress": `${((index + 1) / total) * 100}%` }}
				/>
			</span>
		</span>
	);
}

/** The list of chapters once the page has loaded, and what stands in for it until then. */
function MenuView({
	status,
	...menu
}: { status: PracticeProfileTourProps["page"]["state"] } & Parameters<typeof Menu>[0]) {
	if (status.status === "loading") {
		return <Skeleton className="h-48 w-full" />;
	}
	if (status.status === "error") {
		return (
			<QueryErrorAlert
				error={status.error}
				title="We could not load your practices"
				onRetry={status.onRetry}
			/>
		);
	}
	return <Menu {...menu} />;
}

function ChapterNav({
	chapters,
	current,
	done,
	onChoose,
}: {
	chapters: TourChapter[];
	current: TourChapterId;
	done: string[];
	onChoose: (chapter: TourChapter) => void;
}) {
	return (
		<nav aria-label="Chapters">
			<ol className="flex flex-wrap items-center gap-1">
				{chapters.map((chapter, index) => {
					const isCurrent = chapter.id === current;
					const finished = done.includes(chapter.id);
					return (
						<li key={chapter.id} className="flex items-center gap-1">
							{index > 0 && <span aria-hidden className="h-px w-2 bg-border" />}
							<button
								type="button"
								aria-current={isCurrent ? "step" : undefined}
								onClick={() => onChoose(chapter)}
								className={cn(
									"flex h-6 items-center gap-1.5 rounded-full text-xs whitespace-nowrap transition-colors focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-hidden motion-reduce:transition-none",
									isCurrent
										? "bg-highlight/10 pr-2.5 pl-1 font-semibold text-highlight"
										: "w-6 justify-center text-muted-foreground hover:bg-muted",
								)}
							>
								<span className="sr-only">Chapter </span>
								<span
									className={cn(
										"flex size-4.5 items-center justify-center rounded-full tabular-nums",
										isCurrent ? "bg-highlight text-highlight-foreground" : "border",
									)}
								>
									{finished && !isCurrent ? (
										<>
											<CheckIcon className="size-3" aria-hidden />
											<span className="sr-only">{index + 1}</span>
										</>
									) : (
										index + 1
									)}
								</span>
								<span className={isCurrent ? undefined : "sr-only"}>
									{chapter.name}
									{finished && <span className="sr-only">, done</span>}
								</span>
							</button>
						</li>
					);
				})}
			</ol>
		</nav>
	);
}

function Menu({
	chapters,
	suggested,
	progress,
	onReset,
	onChoose,
	firstRef,
}: {
	chapters: TourChapter[];
	suggested: TourChapterId;
	progress: ReturnType<typeof useTourProgress>["progress"];
	onReset: () => void;
	onChoose: (chapter: TourChapter, step: TourStep) => void;
	firstRef: RefObject<HTMLButtonElement | null>;
}) {
	return (
		<>
			<ul className="flex flex-col gap-2">
				{chapters.map((chapter, index) => {
					const storedIndex =
						progress.place?.chapter === chapter.id
							? chapter.steps.findIndex((step) => step.id === progress.place?.step)
							: -1;
					const resumes = storedIndex > 0;
					const start = chapter.steps.at(Math.max(storedIndex, 0));
					if (start === undefined) {
						return null;
					}
					const done = progress.done.includes(chapter.id);
					return (
						<li key={chapter.id}>
							<button
								type="button"
								ref={index === 0 ? firstRef : undefined}
								onClick={() => onChoose(chapter, start)}
								className="flex w-full items-center gap-3 rounded-lg border p-3 text-left transition-colors hover:bg-muted focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-hidden motion-reduce:transition-none"
							>
								<span
									aria-hidden
									className="flex size-7 shrink-0 items-center justify-center rounded-full border text-xs font-semibold tabular-nums"
								>
									{done && !resumes ? <CheckIcon className="size-3.5" /> : index + 1}
								</span>
								<span className="flex min-w-0 flex-1 flex-col gap-0.5">
									<span className="flex flex-wrap items-center gap-2 font-medium">
										{chapter.name}
										{chapter.id === suggested && !done && !resumes && (
											<Badge variant="muted">Start here</Badge>
										)}
										{resumes && <Badge variant="muted">Continue at step {storedIndex + 1}</Badge>}
										{done && !resumes && (
											<span className="text-xs font-normal text-muted-foreground">Done</span>
										)}
									</span>
									<span className="text-muted-foreground">{chapter.question}</span>
									<span className="text-xs text-muted-foreground">
										{chapter.steps.length} steps
									</span>
								</span>
								<ChevronRightIcon className="size-4 shrink-0 text-muted-foreground" aria-hidden />
							</button>
						</li>
					);
				})}
			</ul>
			{(progress.place !== null || progress.done.length > 0) && (
				<Button variant="ghost" size="sm" className="self-start" onClick={onReset}>
					Start over
				</Button>
			)}
		</>
	);
}
