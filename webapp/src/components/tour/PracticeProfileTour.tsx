import { Dialog } from "@base-ui/react/dialog";
import {
	ArrowLeftIcon,
	ArrowRightIcon,
	CheckIcon,
	ChevronRightIcon,
	RotateCcwIcon,
	XIcon,
} from "lucide-react";
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
import { useMediaQuery } from "usehooks-ts";

import { cn } from "cn";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { pageHeading } from "@/components/onboarding/PublicActivityNotice";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";

import {
	suggestedChapter,
	type TourChapter,
	type TourChapterId,
	tourChapters,
	type TourFacts,
	type TourStep,
} from "./tour-chapters";
import { useTourProgress } from "./tour-progress";
import { TOUR_STATIONS } from "./tour-scenes";
import styles from "./tour.module.css";

export interface PracticeProfileTourProps extends Omit<TourFacts, "openedAt"> {
	/** The chapter an offer on the page asked for; without it the tour opens on its menu. */
	startChapter?: TourChapterId;
	onClose: () => void;
}

/** What the window says when it shows no step: its menu, or why the tour has to wait for the page. */
const MENU_WORDS: Record<PracticeProfileTourProps["page"]["state"]["status"], string> = {
	ready: "Choose what you want to know. You can leave at any time and continue later.",
	loading: "Your Practice profile is still loading. The tour goes on when it is ready.",
	error: "The tour shows your own Practice profile, and that page could not load.",
};

type View = { kind: "menu" } | { kind: "step"; chapter: TourChapterId; step: string };

/**
 * The guided tour of the Practice profile: chapters that a person chooses, each a window with a stage
 * of real components beside the words. A dialog, because the person asked for this and it is the one
 * task on screen (`webapp/DESIGN.md` § Design principles, 3). It opens only when someone starts it.
 */
export function PracticeProfileTour({ startChapter, onClose, ...facts }: PracticeProfileTourProps) {
	const [openedAt] = useState(() => new Date());
	const chapters = tourChapters({ ...facts, openedAt });
	const tour = useTourProgress();
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

	// A control that held focus can leave with its step. Focus then goes to the way on, so it never
	// falls out of the window.
	useEffect(() => {
		const active = document.activeElement;
		if (active === null || active === document.body || !active.isConnected) {
			primary.current?.focus({ preventScroll: true });
		}
	});

	const show = (chapter: TourChapterId, step: string) => {
		// A new step starts with its words in view on a small screen, where the window scrolls as a whole.
		if (body.current) {
			body.current.scrollTop = 0;
		}
		setView({ kind: "step", chapter, step });
	};
	const showStep = (chapter: TourChapter, step: TourStep) => {
		tour.stopAt(chapter.id, step.id);
		show(chapter.id, step.id);
	};

	const chapter =
		view.kind === "step" ? chapters.find((each) => each.id === view.chapter) : undefined;
	const stepIndex =
		view.kind === "step" && chapter ? chapter.steps.findIndex((each) => each.id === view.step) : -1;
	// A step can leave while it is open, when the data it stands on changes. The menu then takes over.
	const step =
		stepIndex >= 0 && facts.page.state.status === "ready"
			? chapter?.steps.at(stepIndex)
			: undefined;
	const nextChapter = chapter && chapters.at(chapters.indexOf(chapter) + 1);
	const onLastStep = chapter !== undefined && stepIndex === chapter.steps.length - 1;
	const finishLabel = nextChapter ? `Next chapter: ${nextChapter.name}` : "Done";
	const primaryLabel = onLastStep ? finishLabel : "Next";

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

	return (
		<Dialog.Root
			open
			modal
			onOpenChange={(open) => {
				if (!open) {
					onClose();
				}
			}}
		>
			<Dialog.Portal>
				<Dialog.Backdrop className={cn(styles.enter, "fixed inset-0 z-50 bg-black/50")} />
				<Dialog.Popup
					initialFocus={primary}
					finalFocus={() => {
						if (closedByLink.current) {
							return false;
						}
						// Safari leaves focus on the body after a click, and an offer can have left the page.
						return opener instanceof HTMLElement && opener !== document.body && opener.isConnected
							? opener
							: pageHeading(document.body);
					}}
					onKeyDown={onKeyDown}
					onClickCapture={onClickCapture}
					className={cn(
						styles.enter,
						"fixed inset-0 z-50 m-auto flex h-dvh w-full flex-col bg-background text-sm focus-visible:outline-hidden sm:rounded-xl sm:ring-1 sm:ring-foreground/10",
						step === undefined
							? "sm:h-fit sm:max-h-[calc(100dvh-3rem)] sm:w-[min(38rem,calc(100vw-3rem))]"
							: "sm:h-[min(56rem,calc(100dvh-3rem))] sm:w-[min(76rem,calc(100vw-3rem))]",
					)}
				>
					<header className="flex items-center justify-between gap-3 border-b px-4 py-3">
						<div className="flex min-w-0 flex-col">
							<Dialog.Title className="font-semibold">Guided tour</Dialog.Title>
							{step && chapter && (
								<span className="text-xs text-muted-foreground">{chapter.name}</span>
							)}
						</div>
						<Dialog.Close
							render={<Button variant="ghost" size="icon-sm" />}
							aria-label="Close the tour"
						>
							<XIcon aria-hidden />
						</Dialog.Close>
					</header>
					<div
						ref={body}
						className={cn(
							"min-h-0 flex-1 gap-4 overflow-y-auto p-4",
							step === undefined
								? "flex flex-col"
								: "grid grid-rows-[auto_minmax(16rem,1fr)] lg:grid-cols-[minmax(0,1fr)_22rem] lg:grid-rows-1",
						)}
					>
						{/*
						 * One live region for the whole life of the window, so the first step of a chapter is
						 * announced as well: a region that mounts with its text says nothing. The words come
						 * first for a screen reader and on a small screen, and sit beside the stage on a wide one.
						 */}
						<Dialog.Description
							render={<div />}
							aria-live="polite"
							aria-atomic
							className={cn("flex flex-col gap-3", step !== undefined && "lg:order-last")}
						>
							{chapter && step ? (
								<>
									<span className="sr-only">
										{chapter.name}. Step {stepIndex + 1} of {chapter.steps.length}.
									</span>
									{chapter.steps.some((each) => each.station !== undefined) && (
										<Stations current={step.station} />
									)}
									<StepWords key={step.id} title={step.title}>
										{step.body}
									</StepWords>
								</>
							) : (
								<span className="text-muted-foreground">{MENU_WORDS[facts.page.state.status]}</span>
							)}
						</Dialog.Description>
						{step && <StepStage step={step} />}
						{step === undefined && facts.page.state.status === "loading" && (
							<Skeleton className="h-48 w-full" />
						)}
						{step === undefined && facts.page.state.status === "error" && (
							<QueryErrorAlert
								error={facts.page.state.error}
								title="We could not load your practices"
								onRetry={facts.page.state.onRetry}
							/>
						)}
						{step === undefined && facts.page.state.status === "ready" && (
							<Menu
								chapters={chapters}
								suggested={suggestedChapter(facts.page.feedbackCards)}
								progress={tour.progress}
								onReset={tour.reset}
								onChoose={(choice, stored) => showStep(choice, stored)}
								firstRef={primary}
							/>
						)}
					</div>
					{chapter && step && (
						<footer className="flex flex-wrap items-center gap-2 border-t px-4 py-3">
							<Button variant="ghost" size="sm" onClick={() => setView({ kind: "menu" })}>
								All chapters
							</Button>
							<span className="ml-auto text-xs text-muted-foreground tabular-nums">
								Step {stepIndex + 1} of {chapter.steps.length}
							</span>
							{stepIndex > 0 && (
								<Button variant="outline" size="sm" onClick={goBack}>
									<ArrowLeftIcon aria-hidden />
									Back
								</Button>
							)}
							<Button ref={primary} size="sm" onClick={goOn}>
								{primaryLabel}
								{(stepIndex < chapter.steps.length - 1 || nextChapter) && (
									<ArrowRightIcon aria-hidden />
								)}
							</Button>
						</footer>
					)}
				</Dialog.Popup>
			</Dialog.Portal>
		</Dialog.Root>
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
								<span className="flex min-w-0 flex-1 flex-col gap-0.5">
									<span className="flex flex-wrap items-center gap-2 font-medium">
										{chapter.name}
										{chapter.id === suggested && !done && !resumes && (
											<Badge variant="muted">Start here</Badge>
										)}
										{resumes && <Badge variant="muted">Continue at step {storedIndex + 1}</Badge>}
										{done && !resumes && (
											<span className="inline-flex items-center gap-1 text-xs font-normal text-muted-foreground">
												<CheckIcon className="size-3.5" aria-hidden />
												Done
											</span>
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

/** The caption, the stage and the way to play the stage's scene again. */
function StepStage({ step }: { step: TourStep }) {
	const [replays, setReplays] = useState(0);
	const still = useMediaQuery("(prefers-reduced-motion: reduce)", { initializeWithValue: true });

	return (
		<div className="flex min-h-0 flex-col gap-2">
			<div className="flex min-h-7 items-center justify-between gap-2">
				<span className="text-xs font-medium text-muted-foreground">{step.caption}</span>
				{step.plays === true && !still && (
					<Button variant="quiet" size="xs" onClick={() => setReplays((count) => count + 1)}>
						<RotateCcwIcon aria-hidden />
						Play again
					</Button>
				)}
			</div>
			<Stage key={`${step.stageKey}:${replays}`} step={step} />
		</div>
	);
}

/**
 * The picture for a step. One key per stage, so the steps on one page keep it mounted and the
 * highlight moves, while a new stage starts at its own top and plays its scene once.
 */
function Stage({ step }: { step: TourStep }) {
	const scroller = useRef<HTMLDivElement>(null);
	const { slot } = step;

	// One part lit at a time. The attribute stays on the element only while its step lasts, and the
	// stage's own scroll brings the part into view: an inert stage has nothing to focus.
	useLayoutEffect(() => {
		const stage = scroller.current;
		if (slot === undefined || stage === null) {
			return;
		}
		const part = stage.querySelector<HTMLElement>(`[data-tour-slot="${slot}"]`);
		if (part === null) {
			return;
		}
		part.dataset.tourActive = "";
		stage.scrollTop += part.getBoundingClientRect().top - stage.getBoundingClientRect().top - 16;
		return () => {
			delete part.dataset.tourActive;
		};
	}, [slot]);

	return (
		// The scroller stays outside `inert`: a reader on a keyboard can still scroll the picture.
		<div
			ref={scroller}
			// oxlint-disable-next-line jsx-a11y/no-noninteractive-tabindex -- a scrollable region needs a keyboard way to scroll it (WCAG 2.1.1)
			tabIndex={0}
			role="region"
			aria-label={step.caption}
			className={cn(
				styles.stage,
				"relative flex min-h-0 flex-1 overflow-auto rounded-lg border p-4 focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-hidden",
				slot === undefined ? "bg-sidebar" : "bg-background",
			)}
		>
			<div
				inert={step.live !== true}
				// A short scene rests in the middle. A real page starts at its top, as it does on the page.
				className={cn("w-full min-w-0", slot === undefined && "m-auto max-w-3xl")}
			>
				{step.stage}
			</div>
		</div>
	);
}

function StepWords({ title, children }: { title: string; children: ReactNode }) {
	return (
		<div className={cn(styles.enter, "flex flex-col gap-2")}>
			<h3 className="text-base font-semibold">{title}</h3>
			<div className="flex flex-col gap-2 text-sm text-muted-foreground">{children}</div>
		</div>
	);
}

/** The chain from work to profile, with the step's place in it. The step's words say the same. */
function Stations({ current }: { current?: number }) {
	return (
		<ol aria-hidden className="flex flex-wrap items-center gap-x-1 gap-y-1">
			{TOUR_STATIONS.map(({ label, Icon }, index) => (
				<li
					key={label}
					title={label}
					className={cn(
						"flex items-center gap-1 rounded-full px-1.5 py-0.5 text-xs transition-colors motion-reduce:transition-none",
						index === current
							? "bg-highlight/10 font-medium text-highlight"
							: "text-muted-foreground",
					)}
				>
					<Icon className="size-3.5" />
					<span className={index === current ? "inline" : "sr-only"}>{label}</span>
				</li>
			))}
		</ol>
	);
}
