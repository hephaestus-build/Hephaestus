import {
	ArrowRightIcon,
	FileCodeIcon,
	GitPullRequestIcon,
	ListChecksIcon,
	MessageSquareTextIcon,
	MessagesSquareIcon,
	MousePointerClickIcon,
	RepeatIcon,
	SearchIcon,
	UserRoundIcon,
} from "lucide-react";
import { type ReactNode, useEffect, useLayoutEffect, useRef, useState } from "react";
import { useMediaQuery } from "usehooks-ts";

import { cn } from "cn";
import type { PracticeStanding } from "@/api/types.gen";
import { statusValues } from "@/components/common/status-def";
import { StatusBadge } from "@/components/common/StatusBadge";
import { OUTCOME_DEFS } from "@/components/practice-vocabulary/outcome-defs";
import {
	PRACTICE_GROUP_STANDING_DEFS,
	PRACTICE_STANDING_DEFS,
} from "@/components/practice-vocabulary/practice-group-standing-defs";
import { PRACTICE_TREND_DEFS } from "@/components/practice-vocabulary/practice-trend-defs";
import { PracticeGroupStandingRing } from "@/components/practice-vocabulary/PracticeGroupStandingRing";
import { countPracticeStandings } from "@/components/practice-vocabulary/standing-counts";
import type { ItemProvider } from "@/lib/item-kinds";

import { exampleWorkLabel } from "./example-profile";
import { EXAMPLE_OBSERVATIONS, EXAMPLE_WORK } from "./tour-example";
import styles from "./tour.module.css";

/**
 * Whether the reader asked for less motion. Every scene then shows its final state from the start,
 * so nothing it teaches waits for an animation.
 */
export function useStill(): boolean {
	return useMediaQuery("(prefers-reduced-motion: reduce)", { initializeWithValue: true });
}

/** How many beats of a scene have played. Under reduced motion, all of them. */
function useBeats(times: readonly number[]): number {
	const still = useStill();
	const [beats, setBeats] = useState(0);
	useEffect(() => {
		if (still) {
			return;
		}
		const timers = times.map((ms, index) => window.setTimeout(() => setBeats(index + 1), ms));
		return () => {
			for (const timer of timers) {
				window.clearTimeout(timer);
			}
		};
	}, [still, times]);
	return still ? times.length : beats;
}

const FRAME = "rounded-xl border bg-card p-3 text-sm";

/** The stations from a piece of work to the Practice profile. */
export const TOUR_STATIONS = [
	{ label: "Your work", Icon: GitPullRequestIcon },
	{ label: "Practices", Icon: ListChecksIcon },
	{ label: "Observations", Icon: FileCodeIcon },
	{ label: "Feedback", Icon: MessageSquareTextIcon },
	{ label: "Your profile", Icon: UserRoundIcon },
];

/** The whole way at once: the stations appear one after the other, joined as they come. */
export function FlowOverviewScene({ noun }: { noun: string }) {
	const words = [
		`You open a ${noun}, as usual.`,
		"A review checks it against the practices of your workspace.",
		"For each practice, the review records what it saw, with evidence.",
		"Feedback goes on the work. A repeated problem becomes a card.",
		"Your Practice profile brings it together.",
	];
	return (
		<ol className="grid gap-2 sm:grid-cols-5 sm:gap-0">
			{TOUR_STATIONS.map(({ label, Icon }, index) => (
				<li key={label} className="flex items-stretch">
					{index > 0 && (
						<span aria-hidden className="hidden w-4 shrink-0 items-center sm:flex">
							<span
								className={cn(styles.grow, "h-0.5 w-full origin-left rounded-full bg-highlight/50")}
								style={{ "--tour-delay": `${index * 450 - 150}ms` }}
							/>
						</span>
					)}
					<div
						className={cn(
							styles.rise,
							"flex flex-1 items-center gap-3 rounded-2xl border bg-background p-3 sm:flex-col sm:text-center",
						)}
						style={{ "--tour-delay": `${index * 450}ms` }}
					>
						<span className="flex size-10 shrink-0 items-center justify-center rounded-full bg-highlight/10 text-highlight">
							<Icon className="size-5" aria-hidden />
						</span>
						<span className="flex flex-col gap-1">
							<span className="font-semibold">{label}</span>
							<span className="text-xs leading-snug text-muted-foreground">{words[index]}</span>
						</span>
					</div>
				</li>
			))}
		</ol>
	);
}

/** A pull request as the reader knows it from their forge: what a review reads. */
export function WorkScene({ noun, provider }: { noun: string; provider?: ItemProvider }) {
	return (
		<div className={cn(styles.rise, "overflow-hidden rounded-xl border bg-background text-sm")}>
			<div className="flex flex-col gap-1.5 border-b px-4 py-3">
				<p className="text-base font-semibold">
					{EXAMPLE_WORK.title}{" "}
					<span className="font-normal text-muted-foreground">
						{exampleWorkLabel(EXAMPLE_WORK.number, provider)}
					</span>
				</p>
				<p className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
					<span className="inline-flex items-center gap-1 rounded-full bg-provider-open-foreground px-2 py-0.5 font-medium text-provider-onemphasis-foreground">
						<GitPullRequestIcon className="size-3" aria-hidden /> Open
					</span>
					An open {noun} with {EXAMPLE_WORK.commits.length} commits
				</p>
			</div>
			<div className="grid sm:grid-cols-[1.1fr_1fr] sm:divide-x">
				<div className="flex flex-col gap-3 p-4">
					<div className="flex flex-col gap-1">
						<span className="text-xs font-medium text-muted-foreground">Description</span>
						<span className="rounded-lg border bg-muted/30 px-3 py-2">
							{EXAMPLE_WORK.description}
						</span>
					</div>
					<div className="flex flex-col gap-1">
						<span className="text-xs font-medium text-muted-foreground">Commits</span>
						{EXAMPLE_WORK.commits.map((commit) => (
							<span key={commit.sha} className="flex items-center gap-2">
								<code className="text-xs text-muted-foreground">{commit.sha}</code>
								<span>{commit.subject}</span>
							</span>
						))}
					</div>
				</div>
				<div className="flex flex-col gap-1 border-t p-4 sm:border-t-0">
					<span className="text-xs font-medium text-muted-foreground">
						Files changed · {EXAMPLE_WORK.files.length}
					</span>
					{EXAMPLE_WORK.files.map((file) => (
						<span key={file.path} className="flex items-center justify-between gap-3">
							<code className="min-w-0 truncate text-xs">{file.path}</code>
							<span className="shrink-0 text-xs tabular-nums">
								<span className="text-provider-success-foreground">+{file.added}</span>{" "}
								<span className="text-provider-danger-foreground">−{file.removed}</span>
							</span>
						</span>
					))}
				</div>
			</div>
		</div>
	);
}

const PICK_BEATS = [1300, 2200] as const;

/**
 * The many practices first. Then a lens picks one out of them and opens it, so the reader sees that
 * a review looks at one of many at a time. The opened practice shows its own words where it has
 * them, and nothing in their place where it does not.
 */
export function PracticesScene({
	names,
	picked,
}: {
	names: string[];
	picked?: Pick<PracticeStanding, "name" | "whyItMatters" | "whatGoodLooksLike">;
}) {
	const beats = useBeats(PICK_BEATS);
	const chosen = picked?.name ?? names[0];
	const hasWords = picked?.whyItMatters != null || picked?.whatGoodLooksLike != null;
	return (
		<div className="flex flex-col gap-4">
			<ul className="flex flex-wrap gap-1.5">
				{names.map((name) => {
					const isChosen = name === chosen && beats >= 1;
					return (
						<li
							key={name}
							className={cn(
								"flex items-center gap-1 rounded-full px-2.5 py-1 text-xs transition-[opacity,background-color,color] duration-500 motion-reduce:transition-none",
								isChosen
									? "bg-highlight text-highlight-foreground ring-4 ring-highlight/20"
									: "bg-secondary text-secondary-foreground",
								beats >= 1 && !isChosen && "opacity-40",
							)}
						>
							{isChosen && <SearchIcon className="size-3.5" aria-hidden />}
							{name}
						</li>
					);
				})}
			</ul>
			{beats >= 2 && picked !== undefined && hasWords && (
				<div className={cn(styles.rise, FRAME, "flex flex-col gap-2 bg-background")}>
					<span className="font-semibold">{picked.name}</span>
					{picked.whyItMatters != null && (
						<p>
							<span className="font-medium">Why it matters: </span>
							<span className="text-muted-foreground">{picked.whyItMatters}</span>
						</p>
					)}
					{picked.whatGoodLooksLike != null && (
						<p>
							<span className="font-medium">What good looks like: </span>
							<span className="text-muted-foreground">{picked.whatGoodLooksLike}</span>
						</p>
					)}
				</div>
			)}
		</div>
	);
}

const GROUP_BEATS = [1200] as const;
const CHIP = 24;
const ROW = CHIP + 4;
const GAP = 16;
const HEADER = 34;

interface ChipPlace {
	name: string;
	group: number;
	at: number;
}

/**
 * The practices as one loose list, which then sorts itself into its practice groups. The columns
 * follow the width the scene has, so a phone sorts into one column and a wide window into three.
 */
export function GroupsScene({ groups }: { groups: { name: string; practices: string[] }[] }) {
	const beats = useBeats(GROUP_BEATS);
	const clustered = beats >= 1;
	const frame = useRef<HTMLDivElement>(null);
	const [width, setWidth] = useState(0);
	useLayoutEffect(() => {
		const element = frame.current;
		if (element === null) {
			return;
		}
		setWidth(element.clientWidth);
		const observer = new ResizeObserver(() => setWidth(element.clientWidth));
		observer.observe(element);
		return () => observer.disconnect();
	}, []);

	const columns = Math.max(1, Math.min(3, Math.floor((width + GAP) / (200 + GAP))));
	const columnWidth = width === 0 ? 0 : (width - GAP * (columns - 1)) / columns;
	const rowsOf = (row: number) =>
		Math.max(
			0,
			...groups
				.filter((_, at) => Math.floor(at / columns) === row)
				.map((group) => group.practices.length),
		);
	const topOf = (row: number) => {
		let top = 0;
		for (let at = 0; at < row; at += 1) {
			top += HEADER + rowsOf(at) * ROW + 12;
		}
		return top;
	};
	const chips: ChipPlace[] = groups
		.flatMap((group, groupAt) => group.practices.map((name, at) => ({ name, group: groupAt, at })))
		.sort((a, b) => a.at - b.at || a.group - b.group);
	const looseHeight = Math.ceil(chips.length / columns) * ROW;
	const sortedHeight = topOf(Math.ceil(groups.length / columns));

	return (
		<div
			ref={frame}
			className="relative h-(--tour-height) w-full"
			style={{ "--tour-height": `${Math.max(looseHeight, sortedHeight) + 4}px` }}
		>
			{width > 0 &&
				groups.map((group, groupAt) => (
					<div
						key={group.name}
						className={cn(
							"absolute top-(--tour-y) left-(--tour-x) h-(--tour-h) w-(--tour-w) rounded-2xl border bg-background transition-opacity duration-500 motion-reduce:transition-none",
							clustered ? "opacity-100 delay-700" : "opacity-0",
						)}
						style={{
							"--tour-x": `${(groupAt % columns) * (columnWidth + GAP)}px`,
							"--tour-y": `${topOf(Math.floor(groupAt / columns))}px`,
							"--tour-w": `${columnWidth}px`,
							"--tour-h": `${HEADER + group.practices.length * ROW + 6}px`,
						}}
					>
						<span className="block truncate px-3 pt-2 text-sm font-semibold">{group.name}</span>
					</div>
				))}
			{width > 0 &&
				chips.map(({ name, group, at }, index) => {
					const place = clustered
						? {
								x: (group % columns) * (columnWidth + GAP) + 8,
								y: topOf(Math.floor(group / columns)) + HEADER + at * ROW - 4,
								w: columnWidth - 16,
							}
						: {
								x: (index % columns) * (columnWidth + GAP),
								y: Math.floor(index / columns) * ROW,
								w: columnWidth,
							};
					return (
						<div
							key={`${group}:${name}`}
							className="absolute top-0 left-0 flex h-6 w-(--tour-w) translate-x-(--tour-x) translate-y-(--tour-y) items-center rounded-full bg-secondary px-3 text-xs whitespace-nowrap text-secondary-foreground transition-[translate,width] duration-1000 ease-in-out motion-reduce:transition-none"
							style={{
								"--tour-w": `${place.w}px`,
								"--tour-x": `${place.x}px`,
								"--tour-y": `${place.y}px`,
							}}
						>
							<span className="truncate">{name}</span>
						</div>
					);
				})}
		</div>
	);
}

/** For a few practices, one observation each on the example work: its outcome and its evidence. */
export function ObservationsScene({ provider }: { provider?: ItemProvider }) {
	return (
		<div className="flex flex-col gap-3">
			<p className={cn(styles.rise, "flex flex-wrap items-center gap-2 text-sm")}>
				<GitPullRequestIcon className="size-4 text-provider-open-foreground" aria-hidden />
				<span className="font-medium">
					{exampleWorkLabel(EXAMPLE_WORK.number, provider)} {EXAMPLE_WORK.title}
				</span>
				<ArrowRightIcon className="size-4 text-muted-foreground" aria-hidden />
				<span className="text-muted-foreground">one practice at a time</span>
			</p>
			<ul className="flex flex-col gap-2">
				{EXAMPLE_OBSERVATIONS.map((row, index) => (
					<li
						key={row.practice}
						className={cn(
							styles.rise,
							FRAME,
							"grid items-start gap-2 bg-background sm:grid-cols-[8rem_1fr] sm:gap-4",
						)}
						style={{ "--tour-delay": `${300 + index * 350}ms` }}
					>
						<StatusBadge def={OUTCOME_DEFS[row.outcome]} />
						<span className="flex flex-col gap-1">
							<span className="font-medium">{row.practice}</span>
							<span className="text-muted-foreground">{row.evidence}</span>
						</span>
					</li>
				))}
			</ul>
		</div>
	);
}

function Numbered({ n }: { n: number }) {
	return (
		<span className="flex size-6 shrink-0 items-center justify-center rounded-full bg-highlight text-xs font-semibold text-highlight-foreground">
			{n}
		</span>
	);
}

/** The small card the pattern becomes, as a schematic of the real one. */
function MiniCard() {
	return (
		<div className="flex flex-col items-start gap-2 rounded-xl border bg-background p-3 text-sm">
			<span className="font-semibold">Descriptions say what changed, not why</span>
			<span className="border-l-2 border-highlight bg-highlight/5 px-2 py-1 text-xs">
				Next step: start each description with one sentence on the problem it solves.
			</span>
		</div>
	);
}

const PLACES_BEATS = [2600] as const;

/**
 * The three places feedback reaches the reader, numbered. After a moment the second one comes
 * forward, because the next step is about it.
 */
export function DestinationsScene({ noun }: { noun: string }) {
	const focus = useBeats(PLACES_BEATS) >= 1;
	const quiet = cn(
		"transition-opacity duration-700 motion-reduce:transition-none",
		focus && "opacity-50",
	);
	return (
		<ol className="grid items-start gap-4 md:grid-cols-3">
			<li className={cn(styles.rise, quiet, "flex flex-col gap-2")}>
				<span className="flex items-center gap-2 text-sm font-semibold">
					<Numbered n={1} />
					<GitPullRequestIcon className="size-4" aria-hidden /> On the work
				</span>
				<div className={cn(FRAME, "bg-background")}>
					A comment on the {noun}: the description says what was added, but not why the list needs a
					cache.
				</div>
				<span className="text-xs text-muted-foreground">About this one piece of work.</span>
			</li>
			<li
				className={cn(
					styles.rise,
					"flex origin-top flex-col gap-2 transition-transform duration-700 motion-reduce:transition-none",
					focus && "scale-104",
				)}
				style={{ "--tour-delay": "300ms" }}
			>
				<span className="flex items-center gap-2 text-sm font-semibold">
					<Numbered n={2} />
					<UserRoundIcon className="size-4" aria-hidden /> On your Practice profile
				</span>
				<div
					className={cn(
						"rounded-xl transition-shadow duration-700",
						focus && "ring-4 ring-highlight/30",
					)}
				>
					<MiniCard />
				</div>
				<span className="text-xs text-muted-foreground">
					A card, when the same problem repeats across your work.
				</span>
			</li>
			<li
				className={cn(styles.rise, quiet, "flex flex-col gap-2")}
				style={{ "--tour-delay": "600ms" }}
			>
				<span className="flex items-center gap-2 text-sm font-semibold">
					<Numbered n={3} />
					<MessagesSquareIcon className="size-4" aria-hidden /> In a conversation
				</span>
				<div className={cn(FRAME, "bg-background")}>
					When you ask Hephaestus about your work, it can show feedback that fits.
				</div>
				<span className="text-xs text-muted-foreground">Only when it fits what you ask.</span>
			</li>
		</ol>
	);
}

/** The same problem in several pieces of work becomes one card about the pattern. */
export function PatternScene({ provider }: { provider?: ItemProvider }) {
	return (
		<div className="grid items-center gap-4 sm:grid-cols-[1fr_auto_1.1fr]">
			<ul className="flex flex-col gap-2">
				{[17, 19].map((number, index) => (
					<li
						key={number}
						className={cn(styles.rise, FRAME, "flex items-center gap-2 bg-background")}
						style={{ "--tour-delay": `${200 + index * 350}ms` }}
					>
						<StatusBadge def={OUTCOME_DEFS.NOT_MET} />
						<span className="font-medium">{exampleWorkLabel(number, provider)}</span>
						<span className="min-w-0 truncate text-muted-foreground">
							Describe what changed and why
						</span>
					</li>
				))}
			</ul>
			<ArrowRightIcon
				className={cn(styles.rise, "hidden size-6 text-highlight sm:block")}
				style={{ "--tour-delay": "1000ms" }}
				aria-hidden
			/>
			<div className={cn(styles.rise, "flex flex-col gap-2")} style={{ "--tour-delay": "1200ms" }}>
				<RepeatIcon className="size-4 text-highlight" aria-hidden />
				<MiniCard />
			</div>
		</div>
	);
}

/** What the Practice profile holds, each part with its words. Screen readers get them from the step. */
export const PROFILE_PARTS = [
	["Your standing and trend", "For each practice and each group"],
	["Your feedback cards", "Each with one next step"],
	["Your observations", "For each practice, with the evidence"],
	["The guide of each practice", "Why it matters and what good looks like"],
] as const;

/** The parts the profile collects, held together by one bracket: they are one place. */
export function ProfileScene() {
	return (
		<div className="grid items-center gap-4 sm:grid-cols-[1fr_auto_auto]">
			<ol className="flex flex-col gap-2">
				{PROFILE_PARTS.map(([title, detail], index) => (
					<li
						key={title}
						className={cn(styles.rise, FRAME, "flex gap-3 bg-background")}
						style={{ "--tour-delay": `${index * 250}ms` }}
					>
						<span className="flex size-6 shrink-0 items-center justify-center rounded-full bg-highlight/10 text-xs font-semibold text-highlight">
							{index + 1}
						</span>
						<span className="flex flex-col">
							<span className="font-medium">{title}</span>
							<span className="text-muted-foreground">{detail}</span>
						</span>
					</li>
				))}
			</ol>
			<svg
				aria-hidden
				viewBox="0 0 24 240"
				className={cn(styles.rise, "hidden h-60 w-6 text-highlight sm:block")}
				style={{ "--tour-delay": "1200ms" }}
			>
				<path
					d="M2 2 Q 12 2 12 20 L 12 104 Q 12 120 22 120 Q 12 120 12 136 L 12 220 Q 12 238 2 238"
					fill="none"
					stroke="currentColor"
					strokeWidth="2.5"
					strokeLinecap="round"
				/>
			</svg>
			<div
				className={cn(
					styles.rise,
					"flex flex-col items-center gap-2 rounded-2xl border bg-background px-6 py-5",
				)}
				style={{ "--tour-delay": "1500ms" }}
			>
				<span className="flex size-12 items-center justify-center rounded-full bg-highlight text-highlight-foreground">
					<UserRoundIcon className="size-6" aria-hidden />
				</span>
				<span className="text-sm font-semibold">Your Practice profile</span>
			</div>
		</div>
	);
}

const LAYER_BEATS = [700, 1700, 2700, 3900, 4900, 6100, 7100] as const;

/** What the levels schematic says at each beat. The step's words say all of it too. */
function layersCaption(beats: number, levels: string[]): string {
	if (beats >= 7) {
		return "A press on the page beside the panels closed the last one too.";
	}
	if (beats === 6) {
		return "Or press the page on the left, beside the panels…";
	}
	if (beats === 5) {
		return `A press on “${levels[1] ?? ""}” in the path closed the two panels above it.`;
	}
	if (beats === 4) {
		return "A press on a position in the path goes back…";
	}
	const open = Math.min(beats, 3) + 1;
	return `${open} ${open === 1 ? "level" : "levels"} open`;
}

/**
 * The levels as they behave, as a schematic and not the real panels: each press slides one more over
 * the page, and a press on the path above a title closes the ones over it again.
 */
export function LevelsScene({ levels }: { levels: string[] }) {
	const beats = useBeats(LAYER_BEATS);
	// Open panels over the page: one more on each of the first three beats, back to one, then none.
	let shown = Math.min(beats, 3);
	if (beats >= 5) {
		shown = 1;
	}
	if (beats >= 7) {
		shown = 0;
	}
	const pointing = beats === 4;
	const pagePress = beats === 6;
	return (
		<div className="flex flex-col gap-3">
			<p className="text-xs text-muted-foreground">{layersCaption(beats, levels)}</p>
			<div className="relative h-72 overflow-hidden rounded-xl border bg-background">
				{pagePress && (
					<MousePointerClickIcon
						className="absolute top-32 left-[8%] z-10 size-6 text-highlight"
						aria-hidden
					/>
				)}
				<div className="p-4 text-sm">
					<span className="text-lg font-semibold">{levels[0]}</span>
					<div className="mt-3 h-3 w-2/3 rounded bg-muted" />
					<div className="mt-2 h-3 w-1/2 rounded bg-muted" />
					<div className="mt-4 h-24 w-full rounded-lg bg-muted/60" />
				</div>
				{levels.slice(1).map((title, index) => {
					const depth = index + 1;
					const open = depth <= shown;
					const pointed = pointing && depth === shown;
					return (
						<div
							key={title}
							className={cn(
								"absolute inset-y-0 right-0 flex w-(--tour-w) flex-col gap-2 border-l bg-background p-4 shadow-xl transition-transform duration-500 motion-reduce:transition-none",
								!open && "translate-x-[105%]",
							)}
							style={{ "--tour-w": `${82 - depth * 8}%` }}
						>
							<div className="flex flex-wrap items-center gap-1 text-2xs text-muted-foreground">
								{levels.slice(0, depth + 1).map((crumb, crumbAt) => (
									<span key={crumb} className="flex items-center gap-1">
										{crumbAt > 0 && <span aria-hidden>›</span>}
										<span
											className={cn(
												crumbAt === depth && "font-medium text-foreground",
												crumbAt === 1 && pointed && "rounded bg-highlight/15 px-1 text-highlight",
											)}
										>
											{crumb}
										</span>
										{crumbAt === 1 && pointed && (
											<MousePointerClickIcon className="size-4 text-highlight" aria-hidden />
										)}
									</span>
								))}
							</div>
							<span className="font-semibold">{title}</span>
							<div className="h-3 w-3/4 rounded bg-muted" />
							<div className="h-3 w-1/2 rounded bg-muted" />
						</div>
					);
				})}
			</div>
		</div>
	);
}

/**
 * The two levels a standing is read on: each practice of a group has its own, and the group's ring
 * and standing add them up.
 */
export function GroupStandingScene({
	groupName,
	practices,
	standing,
}: {
	groupName: string;
	practices: PracticeStanding[];
	standing: keyof typeof PRACTICE_GROUP_STANDING_DEFS;
}) {
	return (
		<div className="grid items-center gap-5 sm:grid-cols-[1.5fr_auto_1fr]">
			<div className="flex flex-col gap-2">
				<span className="text-xs font-semibold text-muted-foreground">
					Each practice: its own standing
				</span>
				{practices.map((practice, index) => (
					<div
						key={practice.slug}
						className={cn(
							styles.rise,
							"flex items-center justify-between gap-3 rounded-xl border bg-background px-3 py-1.5",
						)}
						style={{ "--tour-delay": `${index * 200}ms` }}
					>
						<span className="min-w-0 truncate text-sm">{practice.name}</span>
						<StatusBadge def={PRACTICE_STANDING_DEFS[practice.standing]} />
					</div>
				))}
			</div>
			<ArrowRightIcon
				className={cn(styles.rise, "hidden size-6 text-highlight sm:block")}
				style={{ "--tour-delay": "1100ms" }}
				aria-hidden
			/>
			<div
				className={cn(styles.rise, "flex flex-col items-center gap-3")}
				style={{ "--tour-delay": "1300ms" }}
			>
				<span className="text-xs font-semibold text-muted-foreground">The group: added up</span>
				<div className="flex items-center gap-3">
					<PracticeGroupStandingRing counts={countPracticeStandings(practices)} />
					<div className="flex flex-col items-start gap-1.5">
						<span className="text-sm font-semibold">{groupName}</span>
						<StatusBadge def={PRACTICE_GROUP_STANDING_DEFS[standing]} />
					</div>
				</div>
			</div>
		</div>
	);
}

export const STANDING_DEFS = statusValues(PRACTICE_STANDING_DEFS).map(
	(value) => PRACTICE_STANDING_DEFS[value],
);
export const TREND_DEFS = statusValues(PRACTICE_TREND_DEFS).map(
	(value) => PRACTICE_TREND_DEFS[value],
);

/** Every standing and trend with its own words, from the registries that the page itself reads. */
export function StandingsScene() {
	return (
		<div className="grid gap-4 md:grid-cols-2">
			<DefinitionList title="Standing" defs={STANDING_DEFS} />
			<DefinitionList title="Trend" defs={TREND_DEFS} />
		</div>
	);
}

function DefinitionList({
	title,
	defs,
}: {
	title: string;
	defs: Parameters<typeof StatusBadge>[0]["def"][];
}) {
	return (
		<div className="flex flex-col gap-2 text-sm">
			<span className="font-semibold">{title}</span>
			<ul className="flex flex-col gap-2">
				{defs.map((def) => (
					<li key={`${def.label}:${def.description}`} className="flex flex-col items-start gap-1">
						<StatusBadge wrap def={def} />
						<span className="text-muted-foreground">{def.description}</span>
					</li>
				))}
			</ul>
		</div>
	);
}

/** A real way out of the tour, for the step that hands the reader over to their own data. */
export function HandOverScene({ children }: { children: ReactNode }) {
	return <div className="flex flex-col items-start gap-3">{children}</div>;
}

/** The chain from work to profile, with the step's place in it. The step's words say the same. */
export function Stations({ current }: { current?: number }) {
	return (
		<ol aria-hidden className="flex flex-wrap items-center gap-x-1 gap-y-1">
			{TOUR_STATIONS.map(({ label, Icon }, index) => (
				<li key={label} title={label} className="flex items-center gap-1">
					{index > 0 && <span className="h-px w-3 bg-border" />}
					<span
						className={cn(
							"flex items-center gap-1 rounded-full px-1.5 py-0.5 text-xs transition-colors motion-reduce:transition-none",
							index === current
								? "bg-highlight/10 font-medium text-highlight"
								: "text-muted-foreground",
						)}
					>
						<Icon className="size-3.5" />
						<span className={index === current ? "inline" : "sr-only"}>{label}</span>
					</span>
				</li>
			))}
		</ol>
	);
}

/** Practice names, without repeats: a practice can be listed under more than one group. */
export function practiceNames(practices: PracticeStanding[]): string[] {
	return [...new Set(practices.map((practice) => practice.name))];
}
