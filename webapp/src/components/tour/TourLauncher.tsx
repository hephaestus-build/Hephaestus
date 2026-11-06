import {
	ArrowRightIcon,
	CheckIcon,
	ChevronRightIcon,
	ClockIcon,
	type LucideIcon,
	ListOrderedIcon,
	MessageCircleIcon,
	XIcon,
} from "lucide-react";
import { AnimatePresence, motion, useReducedMotion } from "motion/react";
import {
	type KeyboardEvent,
	type ReactNode,
	useEffect,
	useId,
	useLayoutEffect,
	useRef,
	useState,
} from "react";

import { cn } from "cn";
import { HephIcon } from "@/components/brand/HephIcon";
import { Button } from "@/components/ui/button";
import { isTanstackDevtoolsEnabled } from "@/runtime/devtools/TanstackDevtools";

import { type TourSection, tourMinutes, tourMinutesShort, tourPartLabel } from "./ProductTour";
import { registerTourLauncher, useHephChat } from "./tour-launcher-store";

const ROW_CLASS =
	"group/row flex w-full rounded-xl text-left transition-colors duration-150 outline-none focus-visible:ring-2 focus-visible:ring-mentor/40 focus-visible:ring-inset";
/** The next part's row, a light accent wash; stronger in the dark, where a light wash disappears. */
const NEXT_TINT = "bg-mentor/6 hover:bg-mentor/10 dark:bg-mentor/12 dark:hover:bg-mentor/18";
const HOVER_TINT = "hover:bg-muted/70 dark:hover:bg-muted/50";
/** Every section's ring: one size, one stroke, the number in its middle. */
const RING_SIZE = 28;
const RING_STROKE = 2;
const RING_RADIUS = (RING_SIZE - RING_STROKE) / 2;
const RING_LENGTH = 2 * Math.PI * RING_RADIUS;
/** A part's letter: a, b, c and so on. */
const letter = (at: number) => String.fromCodePoint(97 + at);
/** Heph's button and the card's avatar are one element that travels between the two places. */
const HEPH_LAYOUT_ID = "heph-tour-launcher";
/** Heph's button, bottom right, that the card grows out of and shrinks back into. */
const BUTTON = 56;
/** Opening and closing are one motion: the frame grows, the button travels, the content is there from the start. */
const OPEN_S = 0.15;
const EASE_OUT = [0.2, 0.9, 0.3, 1] as const;
/** A fresh tick: the ring fills once the card stands, then the tick comes in. */
const FILL_S = 0.4;
const FILL_DELAY_S = 0.12;
const OPEN_CLIP = "inset(0px 0px 0px 0px round 16px)";
/** The label over Heph's button fades in once, a moment after the page, after a first visit's card. */
const LABEL_DELAY_MS = 900;
const EASING = `cubic-bezier(${EASE_OUT.join(",")})`;

/**
 * The card's opening: the frame and the clip of its content grow from the button's square, bottom
 * right, in the same frame and along the card's real size, so the content is there from the start.
 */
function growOut(frame: HTMLElement, content: HTMLElement) {
	// Test DOMs without the Web Animations API open the card at once.
	if (!("animate" in frame) || typeof frame.animate !== "function") {
		return;
	}
	const options = { duration: OPEN_S * 1000, easing: EASING };
	frame.animate(
		[
			{ width: `${BUTTON}px`, height: `${BUTTON}px`, borderRadius: `${BUTTON / 2}px` },
			{ width: "100%", height: "100%", borderRadius: "16px" },
		],
		options,
	);
	content.animate(
		[
			{
				clipPath: `inset(calc(100% - ${BUTTON}px) 0 0 calc(100% - ${BUTTON}px) round ${BUTTON / 2}px)`,
			},
			{ clipPath: OPEN_CLIP },
		],
		options,
	);
}

/**
 * A section's number in a thin ring that fills green with its finished parts; with all of them
 * finished, a full green ring around a green tick. A fresh part fills its share once as the card opens.
 */
function ProgressRing({
	number,
	share,
	from = share,
	next = false,
}: {
	number: number;
	share: number;
	/** The share before the part just finished; the ring fills from it once. */
	from?: number;
	/** The section holds the reader's next part: its ring and number take the accent. */
	next?: boolean;
}) {
	const reduceMotion = useReducedMotion() === true;
	const all = share >= 1;
	const fills = !reduceMotion && from < share;
	const offset = (value: number) => RING_LENGTH * (1 - Math.min(value, 1));
	return (
		<span className="relative flex size-7 shrink-0 items-center justify-center">
			<svg
				aria-hidden
				viewBox={`0 0 ${RING_SIZE} ${RING_SIZE}`}
				className="absolute inset-0 size-full -rotate-90"
			>
				<circle
					cx={RING_SIZE / 2}
					cy={RING_SIZE / 2}
					r={RING_RADIUS}
					fill="none"
					strokeWidth={RING_STROKE}
					className={next ? "stroke-mentor/40" : "stroke-border"}
				/>
				{share > 0 && (
					<motion.circle
						cx={RING_SIZE / 2}
						cy={RING_SIZE / 2}
						r={RING_RADIUS}
						fill="none"
						strokeWidth={RING_STROKE}
						strokeLinecap="round"
						strokeDasharray={RING_LENGTH}
						initial={{ strokeDashoffset: offset(fills ? from : share) }}
						animate={{ strokeDashoffset: offset(share) }}
						transition={{ duration: FILL_S, delay: FILL_DELAY_S, ease: "easeOut" }}
						className="stroke-success"
					/>
				)}
			</svg>
			{all ? (
				<motion.span
					className="flex"
					initial={fills ? { scale: 0.4, opacity: 0 } : false}
					animate={{ scale: 1, opacity: 1 }}
					transition={{ duration: 0.2, delay: FILL_DELAY_S + FILL_S - 0.1, ease: EASE_OUT }}
				>
					<CheckIcon aria-hidden className="size-3.5 text-success" strokeWidth={3} />
				</motion.span>
			) : (
				<span
					className={cn(
						"text-xs leading-none font-semibold tabular-nums",
						next ? "text-mentor" : "text-muted-foreground",
					)}
				>
					{number}
				</span>
			)}
		</span>
	);
}

/** A part's mark: its letter in a small ring, or a green tick once finished; one size for all. */
function PartMark({
	at,
	done,
	fresh,
	next,
}: {
	at: number;
	done: boolean;
	/** Just finished: the tick comes in once as the card opens. */
	fresh: boolean;
	next: boolean;
}) {
	const reduceMotion = useReducedMotion() === true;
	if (done) {
		return (
			<motion.span
				className="flex size-5 shrink-0 items-center justify-center rounded-full bg-success text-success-foreground"
				initial={fresh && !reduceMotion ? { scale: 0.4, opacity: 0 } : false}
				animate={{ scale: 1, opacity: 1 }}
				transition={{ duration: 0.25, delay: FILL_DELAY_S, ease: EASE_OUT }}
			>
				<CheckIcon aria-hidden className="size-3" strokeWidth={3} />
			</motion.span>
		);
	}
	return (
		<span className="relative flex size-5 shrink-0 items-center justify-center">
			<svg aria-hidden viewBox="0 0 20 20" className="absolute inset-0 size-full">
				<circle
					cx="10"
					cy="10"
					r="9.25"
					fill="none"
					strokeWidth="1.5"
					className={next ? "stroke-mentor/40" : "stroke-border"}
				/>
			</svg>
			<span
				className={cn(
					"text-2xs leading-none font-semibold",
					next ? "text-mentor" : "text-muted-foreground",
				)}
			>
				{letter(at)}
			</span>
		</span>
	);
}

/** One muted icon and its text, such as a clock and "3 min". */
function Meta({ icon: Icon, children }: { icon: LucideIcon; children: ReactNode }) {
	return (
		<span className="inline-flex items-center gap-1">
			<Icon aria-hidden className="size-3.5 shrink-0" />
			{children}
		</span>
	);
}

/** The blue chevron at a row's right edge, always there, so every row reads as a way in. */
function RowChevron() {
	return (
		<ChevronRightIcon
			aria-hidden
			strokeWidth={2.25}
			className="size-4 shrink-0 text-mentor/75 transition-[color,translate] duration-150 group-hover/row:translate-x-0.5 group-hover/row:text-mentor group-focus-visible/row:text-mentor"
		/>
	);
}

/** One row of the start card: a section, or a part under it; each starts the tour there. */
interface Row {
	key: string;
	/** The part the row starts at. */
	part: string;
}

/** Focuses a row without moving anything behind the card, and keeps it in the list's view. */
function focusRow(row: HTMLElement | null | undefined, list: HTMLElement | null) {
	if (!row) {
		return;
	}
	row.focus({ preventScroll: true });
	if (list === null) {
		return;
	}
	const rowBox = row.getBoundingClientRect();
	const listBox = list.getBoundingClientRect();
	if (rowBox.top < listBox.top) {
		list.scrollTop -= listBox.top - rowBox.top + 8;
	} else if (rowBox.bottom > listBox.bottom) {
		list.scrollTop += rowBox.bottom - listBox.bottom + 8;
	}
}

/**
 * The reader's next part, the first not yet finished; the row that marks it (a section taken as one
 * block is its own row); and the foot button's words for it.
 */
function nextItem(sections: readonly TourSection[], done: readonly string[]) {
	const parts = sections.flatMap((each) => each.parts);
	const nextPart = parts.find((part) => !done.includes(part.id));
	if (nextPart === undefined) {
		return { nextPart, nextKey: undefined, nextLabel: "" };
	}
	const split = (sections.find((each) => each.name === nextPart.section)?.parts.length ?? 1) > 1;
	return {
		nextPart,
		nextKey: split ? nextPart.id : nextPart.section,
		nextLabel: parts.some((part) => done.includes(part.id))
			? `Continue: ${tourPartLabel(nextPart)}`
			: "Start the tour",
	};
}

/**
 * Room at the end of a page that shows Heph's launcher, so the page's last content scrolls clear of
 * the button and its label in the bottom right corner.
 */
export function TourLauncherRoom() {
	return <div aria-hidden className="h-24 shrink-0" />;
}

export interface TourLauncherProps {
	sections: readonly TourSection[];
	/** The parts the reader finished, by id; each shows a green tick, each section a ring. */
	done: readonly string[];
	/** The whole tour is finished, so the button stops asking for attention. */
	finished: boolean;
	open: boolean;
	/**
	 * The first visit: the card opens small, with one line and one button that grows it into the
	 * sections; later opens show the sections at once.
	 */
	invite?: boolean;
	/** The reader just finished the whole tour: the card says so once, above every tick. */
	justFinished?: boolean;
	/** The part the reader just finished, by id: its tick fills in once as the card opens. */
	fresh?: string;
	onOpenChange: (open: boolean) => void;
	/** Starts the tour at a part, or from the beginning without one. */
	onStart: (part?: string) => void;
}

/**
 * Local demo: Heph's button bottom right, as the tour's entry. While the tour is not finished it
 * pulses gently. A press sends the button up to the card's top left corner, where it becomes Heph's
 * avatar, while the card grows out of its place in one short motion, its content there from the
 * first frame. The card lists the sections on one level, each with a ring that fills as it is
 * finished and a blue chevron as the way in; a press on a row starts it. A section with several
 * parts lists them under it, each to start by itself. One button at the foot goes on with the next
 * part not yet finished, whose row is marked. Where Heph's chat is on, the card also opens it.
 */
export function TourLauncher({
	sections,
	done,
	finished,
	open,
	invite = false,
	justFinished = false,
	fresh,
	onOpenChange,
	onStart,
}: TourLauncherProps) {
	const reduceMotion = useReducedMotion() === true;
	const openChat = useHephChat();
	const rootRef = useRef<HTMLDivElement>(null);
	const buttonRef = useRef<HTMLButtonElement>(null);
	const rowRefs = useRef<(HTMLButtonElement | null)[]>([]);
	const [hovered, setHovered] = useState(false);
	const [active, setActive] = useState(0);
	const buttonId = useId();
	// The label shows a moment after the page, and at once whenever the card closes after that.
	const [labelArrived, setLabelArrived] = useState(false);
	// With reduced motion the label is simply there.
	const labelShown = labelArrived || reduceMotion;
	useEffect(() => {
		if (reduceMotion) {
			return;
		}
		const timer = window.setTimeout(() => setLabelArrived(true), LABEL_DELAY_MS);
		return () => window.clearTimeout(timer);
	}, [reduceMotion]);
	// The small first-visit state, until the reader asks to look through the sections.
	const [small, setSmall] = useState(invite);
	// The card takes its content's height, up to the window's; past it the list scrolls between the
	// fixed head and foot. The three are measured apart, as the list's box is the clipped one.
	const headRef = useRef<HTMLDivElement>(null);
	const bodyRef = useRef<HTMLDivElement>(null);
	const footRef = useRef<HTMLDivElement>(null);
	const listRef = useRef<HTMLDivElement>(null);
	const [contentHeight, setContentHeight] = useState<number | undefined>(undefined);
	const [windowHeight, setWindowHeight] = useState(() =>
		typeof window === "undefined" ? 840 : window.innerHeight,
	);
	// The foot holds the button to the next part and Heph's chat; with neither it goes.
	const hasFoot =
		openChat !== undefined ||
		sections.some((each) => each.parts.some((part) => !done.includes(part.id)));
	useLayoutEffect(() => {
		if (!open) {
			return;
		}
		// The small state has no foot, and the sections only one where Heph's chat is on.
		const parts = [
			headRef.current,
			bodyRef.current,
			small || !hasFoot ? null : footRef.current,
		].filter((each): each is HTMLDivElement => each !== null);
		const measure = () => setContentHeight(parts.reduce((sum, each) => sum + each.offsetHeight, 0));
		measure();
		const observer = new ResizeObserver(measure);
		for (const each of parts) {
			observer.observe(each);
		}
		const onResize = () => setWindowHeight(window.innerHeight);
		window.addEventListener("resize", onResize);
		return () => {
			observer.disconnect();
			window.removeEventListener("resize", onResize);
		};
	}, [open, small, hasFoot]);
	// Focus goes back to Heph's button once it is bottom right again.
	const returnFocus = useRef(false);
	const doneIn = (each: TourSection) => each.parts.filter((part) => done.includes(part.id)).length;
	// One flat list of rows, sections with their parts under them, for the arrow keys.
	const rows: Row[] = sections.flatMap((each) => {
		const head: Row = { key: each.name, part: each.parts[0]?.id ?? "" };
		return each.parts.length > 1
			? [head, ...each.parts.map((part) => ({ key: part.id, part: part.id }))]
			: [head];
	});
	const rowIndex = new Map(rows.map((row, at) => [row.key, at]));
	const { nextPart, nextKey, nextLabel } = nextItem(sections, done);
	const firstOpenRow = nextKey === undefined ? 0 : (rowIndex.get(nextKey) ?? 0);

	// Each open starts at the first row not yet finished, also when the tour hands back to the overview.
	const [wasOpen, setWasOpen] = useState(open);
	if (open !== wasOpen) {
		setWasOpen(open);
		if (open) {
			setActive(firstOpenRow);
			// The window may have changed size while the card was closed.
			setWindowHeight(window.innerHeight);
		}
	}

	useEffect(() => registerTourLauncher(), []);

	// Focus moves in at once, so the reader can choose with the arrow keys or press Enter to go on.
	// The first visit's invitation opens by itself and leaves focus on the page.
	useEffect(() => {
		if (open && !small) {
			focusRow(rowRefs.current[firstOpenRow], listRef.current);
		}
	}, [open, small, firstOpenRow]);

	// The opening runs before the first paint, so the card never shows at its full size first.
	const frameRef = useRef<HTMLDivElement>(null);
	const contentRef = useRef<HTMLDivElement>(null);
	useLayoutEffect(() => {
		if (open && !reduceMotion && frameRef.current !== null && contentRef.current !== null) {
			growOut(frameRef.current, contentRef.current);
		}
	}, [open, reduceMotion]);

	const close = (focusButton: boolean) => {
		returnFocus.current = focusButton;
		onOpenChange(false);
	};

	// Escape closes; a press outside closes, as a popover does.
	useEffect(() => {
		if (!open) {
			return;
		}
		const onKey = (event: globalThis.KeyboardEvent) => {
			if (event.key !== "Escape") {
				return;
			}
			event.stopPropagation();
			close(true);
		};
		const onPointer = (event: PointerEvent) => {
			if (event.target instanceof Node && rootRef.current?.contains(event.target) !== true) {
				close(false);
			}
		};
		window.addEventListener("keydown", onKey);
		document.addEventListener("pointerdown", onPointer);
		return () => {
			window.removeEventListener("keydown", onKey);
			document.removeEventListener("pointerdown", onPointer);
		};
	});

	const move = (event: KeyboardEvent<HTMLButtonElement>, from: number) => {
		let step = 0;
		if (event.key === "ArrowDown") {
			step = 1;
		} else if (event.key === "ArrowUp") {
			step = -1;
		}
		if (step === 0) {
			return;
		}
		event.preventDefault();
		const next = (from + step + rows.length) % rows.length;
		setActive(next);
		focusRow(rowRefs.current[next], listRef.current);
	};

	const toggle = () => {
		if (open) {
			close(true);
			return;
		}
		setSmall(false);
		onOpenChange(true);
	};

	const start = (part?: string) => {
		onOpenChange(false);
		onStart(part);
	};

	const rowProps = (at: number) => ({
		ref: (element: HTMLButtonElement | null) => {
			rowRefs.current[at] = element;
		},
		tabIndex: at === active ? 0 : -1,
		onFocus: () => setActive(at),
		onKeyDown: (event: KeyboardEvent<HTMLButtonElement>) => move(event, at),
		onClick: () => start(rows[at]?.part),
	});

	const travel = { layout: true, transition: { duration: OPEN_S, ease: EASE_OUT } } as const;
	const layoutId = reduceMotion ? undefined : HEPH_LAYOUT_ID;
	const width = small ? 300 : 420;
	const maxHeight = windowHeight - 120;
	const cardHeight = contentHeight === undefined ? undefined : Math.min(contentHeight, maxHeight);
	const sizeTransition = { duration: reduceMotion ? 0 : OPEN_S, ease: EASE_OUT };
	// Closing runs the opening backwards: the frame and the clip of the content shrink into the button.
	const closedClip = `inset(${(cardHeight ?? BUTTON) - BUTTON}px 0px 0px ${width - BUTTON}px round ${BUTTON / 2}px)`;
	const shrink = { duration: OPEN_S, ease: EASE_OUT };
	let headline = "Want a tour? Pick a section.";
	let subline: string | undefined;
	if (small) {
		headline = "Want a tour?";
		subline = "A few minutes, step by step.";
	} else if (justFinished) {
		headline = "You finished the tour.";
		subline = "Pick any part to see it again.";
	}
	const sectionLabel = (each: TourSection, number: number) => {
		const finishedParts = doneIn(each);
		const label = `Start section ${number + 1}, ${each.name}, ${each.count} steps, ${tourMinutes(each.count)}`;
		if (each.parts.length > 1) {
			return `${label}, ${finishedParts} of ${each.parts.length} parts finished`;
		}
		return `${label}${finishedParts === 1 ? ", finished" : ""}`;
	};

	return (
		<div
			ref={rootRef}
			className={cn(
				"fixed right-6 z-50 size-14",
				// The router's devtools sit in the same corner while developing.
				isTanstackDevtoolsEnabled ? "bottom-16" : "bottom-6",
			)}
		>
			<AnimatePresence initial={false}>
				{open && (
					<motion.div
						key="card"
						role="dialog"
						aria-label="Tour of the Practice profile"
						className="absolute right-0 bottom-0 flex flex-col"
						initial={{ width, height: cardHeight }}
						animate={{ width, height: cardHeight }}
						// Holds the card while its frame and content shrink back into the button.
						exit={{
							opacity: reduceMotion ? 0 : 1,
							transition: { duration: reduceMotion ? 0 : OPEN_S },
						}}
						transition={{ width: sizeTransition, height: sizeTransition }}
					>
						{/* The frame grows out of the button's place, bottom right, into the whole card. */}
						<motion.div
							ref={frameRef}
							aria-hidden
							className="absolute right-0 bottom-0 size-full rounded-2xl border bg-background shadow-2xl"
							exit={
								reduceMotion
									? undefined
									: { width: BUTTON, height: BUTTON, borderRadius: BUTTON / 2, transition: shrink }
							}
						/>
						{/* The content grows with the frame, clipped to it, so it is there from the first frame. */}
						<motion.div
							ref={contentRef}
							className="relative flex h-full flex-col overflow-hidden rounded-2xl"
							exit={
								reduceMotion ? undefined : { clipPath: [OPEN_CLIP, closedClip], transition: shrink }
							}
						>
							<div ref={headRef} className="flex shrink-0 items-start gap-3 px-4 pt-4 pb-3">
								<motion.button
									type="button"
									layoutId={layoutId}
									{...travel}
									aria-label="Close the tour sections"
									onClick={() => close(true)}
									className="flex size-10 shrink-0 items-center justify-center rounded-full bg-mentor text-mentor-foreground shadow-sm"
								>
									<HephIcon size={30} pad={3} strokeWidth={1.6} animated={false} />
								</motion.button>
								<div className="flex min-w-0 flex-1 flex-col gap-0.5">
									<p className="text-xs leading-4 font-medium text-mentor">Heph</p>
									<p className="text-base leading-snug font-semibold text-balance">{headline}</p>
									{subline !== undefined && (
										<p className="text-sm leading-snug text-muted-foreground">{subline}</p>
									)}
								</div>
								<Button
									variant="ghost"
									size="icon-sm"
									className="-mt-0.5 -mr-1.5"
									aria-label="Close"
									onClick={() => close(true)}
								>
									<XIcon aria-hidden />
								</Button>
							</div>
							{small && (
								<div ref={bodyRef} className="shrink-0 px-4 pt-1 pb-4">
									<Button
										ref={(element) => {
											rowRefs.current[0] = element;
										}}
										className="w-full"
										onClick={() => {
											setSmall(false);
											setActive(firstOpenRow);
										}}
									>
										Look through the sections
										<ChevronRightIcon aria-hidden data-icon="inline-end" />
									</Button>
								</div>
							)}
							{!small && (
								<>
									<div ref={listRef} className="min-h-0 flex-1 overflow-y-auto overscroll-contain">
										<div
											ref={bodyRef}
											role="group"
											aria-label="Tour sections"
											className="flex flex-col gap-0.5 px-2 pb-2"
										>
											{sections.map((each, number) => {
												const finishedParts = doneIn(each);
												const split = each.parts.length > 1;
												const sectionRow = rowIndex.get(each.name) ?? 0;
												const freshHere =
													fresh !== undefined && each.parts.some((part) => part.id === fresh);
												const share = finishedParts / each.parts.length;
												return (
													<div key={each.name} className="flex flex-col gap-0.5">
														<button
															type="button"
															{...rowProps(sectionRow)}
															aria-label={sectionLabel(each, number)}
															className={cn(
																ROW_CLASS,
																"items-center gap-2.5 py-2 pr-3 pl-3.5",
																nextKey === each.name ? NEXT_TINT : HOVER_TINT,
															)}
														>
															<ProgressRing
																number={number + 1}
																share={share}
																from={freshHere ? (finishedParts - 1) / each.parts.length : share}
																next={nextPart?.section === each.name}
															/>
															<span className="ml-2 flex min-w-0 flex-1 flex-col gap-0.5">
																<span className="flex h-5 items-center justify-between gap-3">
																	<span className="truncate text-sm leading-5 font-medium">
																		{each.name}
																	</span>
																	<span className="flex shrink-0 items-center gap-3 text-xs text-muted-foreground tabular-nums">
																		<Meta icon={ListOrderedIcon}>{each.count} steps</Meta>
																		<Meta icon={ClockIcon}>{tourMinutesShort(each.count)}</Meta>
																	</span>
																</span>
																<span className="truncate text-xs leading-4 text-muted-foreground">
																	{each.line}
																</span>
															</span>
															<RowChevron />
														</button>
														{split && (
															<ul
																aria-label={`Parts of ${each.name}`}
																className="flex flex-col gap-0.5"
															>
																{each.parts.map((part, at) => {
																	const partDone = done.includes(part.id);
																	const partRow = rowIndex.get(part.id) ?? 0;
																	return (
																		<li key={part.id}>
																			<button
																				type="button"
																				{...rowProps(partRow)}
																				aria-label={`Start part ${letter(at)}, ${part.name}, ${part.count} ${part.count === 1 ? "step" : "steps"}${partDone ? ", finished" : ""}`}
																				className={cn(
																					ROW_CLASS,
																					"items-center gap-2.5 py-1.5 pr-3 pl-15",
																					nextKey === part.id ? NEXT_TINT : HOVER_TINT,
																				)}
																			>
																				<PartMark
																					at={at}
																					done={partDone}
																					fresh={fresh === part.id}
																					next={nextKey === part.id}
																				/>
																				<span
																					className={cn(
																						"min-w-0 flex-1 truncate text-sm",
																						nextKey === part.id && "font-medium",
																					)}
																				>
																					{part.name}
																				</span>
																				<span className="shrink-0 text-xs text-muted-foreground tabular-nums">
																					{part.count} {part.count === 1 ? "step" : "steps"}
																				</span>
																				<RowChevron />
																			</button>
																		</li>
																	);
																})}
															</ul>
														)}
													</div>
												);
											})}
										</div>
									</div>
									{/* One clear way on: the next part not yet finished; Heph's chat beside it where it is on. */}
									{hasFoot && (
										<div
											ref={footRef}
											className="flex shrink-0 items-center gap-2 border-t px-4 py-3"
										>
											{openChat && (
												<Button
													variant="ghost"
													size="sm"
													onClick={() => {
														onOpenChange(false);
														openChat();
													}}
												>
													<MessageCircleIcon aria-hidden data-icon="inline-start" />
													Ask Heph
												</Button>
											)}
											{nextPart !== undefined && (
												<Button
													className="ml-auto min-w-0 flex-1 justify-center"
													onClick={() => start(nextPart.id)}
												>
													<span className="truncate">{nextLabel}</span>
													<ArrowRightIcon aria-hidden data-icon="inline-end" />
												</Button>
											)}
										</div>
									)}
								</>
							)}
						</motion.div>
					</motion.div>
				)}
			</AnimatePresence>
			{/* A quiet word over the button until the tour is finished; a press on it presses the button. */}
			{!open && !finished && (
				<label
					htmlFor={buttonId}
					aria-hidden
					className={cn(
						"absolute right-0 bottom-full mb-4 flex cursor-pointer items-center rounded-full border bg-background px-3 py-1 text-xs font-medium whitespace-nowrap text-muted-foreground shadow-sm transition-[opacity,color] duration-200 select-none hover:text-mentor motion-reduce:transition-none",
						labelShown ? "opacity-100" : "pointer-events-none opacity-0",
					)}
				>
					Take a guided tour
					{/* The bubble's tail, over the button's middle. */}
					<span
						aria-hidden
						className="absolute right-[24px] -bottom-1 size-2 rotate-45 border-r border-b bg-background"
					/>
				</label>
			)}
			{!open && (
				<motion.button
					id={buttonId}
					ref={(element) => {
						buttonRef.current = element;
						if (element !== null && returnFocus.current) {
							returnFocus.current = false;
							element.focus({ preventScroll: true });
						}
					}}
					type="button"
					layoutId={layoutId}
					{...travel}
					data-tour-start=""
					aria-expanded={false}
					aria-haspopup="dialog"
					aria-label={finished ? "Heph: tour sections" : "Heph: take a guided tour"}
					// The label over the button says it while the tour is not finished.
					title={finished ? "Tour sections" : undefined}
					onClick={toggle}
					onPointerEnter={() => setHovered(true)}
					onPointerLeave={() => setHovered(false)}
					className="group/launcher absolute inset-0 flex items-center justify-center rounded-full bg-mentor text-mentor-foreground shadow-lg transition-shadow duration-200 hover:shadow-xl focus-visible:ring-4 focus-visible:ring-mentor/30 focus-visible:outline-none"
				>
					{/* A soft ring every two seconds until the tour is finished; still on hover and for reduced motion. */}
					{!finished && !hovered && !reduceMotion && (
						<motion.span
							aria-hidden
							className="absolute inset-0 rounded-full bg-mentor/40"
							initial={{ scale: 1, opacity: 0.8 }}
							animate={{ scale: 1.45, opacity: 0 }}
							transition={{ duration: 1.4, repeat: Infinity, repeatDelay: 0.6, ease: "easeOut" }}
						/>
					)}
					<HephIcon size={40} pad={4} strokeWidth={1.6} animated={false} />
				</motion.button>
			)}
		</div>
	);
}
