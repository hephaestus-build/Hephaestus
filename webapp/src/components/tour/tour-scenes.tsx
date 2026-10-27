import {
	ArrowRightIcon,
	FileCodeIcon,
	GitPullRequestIcon,
	MessageSquareTextIcon,
	MousePointerClickIcon,
	RepeatIcon,
	SearchIcon,
	UserRoundIcon,
} from "lucide-react";
import { Fragment, type ReactNode, useContext, useEffect, useState } from "react";

import { cn } from "cn";
import { HephMark } from "@/components/brand/HephaestusLogo";
import { StatusBadge } from "@/components/common/StatusBadge";
import type { PracticeTab } from "@/components/practice-profile/practice-profile-search";
import { getGroupVisual, groupMark } from "@/components/practice-vocabulary/group-visuals";
import { OUTCOME_DEFS } from "@/components/practice-vocabulary/outcome-defs";
import {
	PRACTICE_GROUP_STANDING_DEFS,
	PRACTICE_STANDING_DEFS,
} from "@/components/practice-vocabulary/practice-group-standing-defs";
import { PracticeGroupStandingRing } from "@/components/practice-vocabulary/PracticeGroupStandingRing";
import { PracticePill } from "@/components/practice-vocabulary/PracticePill";
import { PracticesAcrossTheWorkspacePage } from "@/components/practices-across-the-workspace/PracticesAcrossTheWorkspacePage";
import { useFeedbackRatings } from "@/stories/feedback-ratings";
import { ALL_FEEDBACK_CARDS } from "@/stories/practice-feedback-cards-story-mock-data";
import {
	groups,
	LATEST_REVIEW,
	packagingGroup,
	practicesByGroup,
	practiceStandings,
} from "@/stories/practice-profile-story-mock-data";
import {
	ACROSS_WORKSPACE,
	ACROSS_WORKSPACE_TILES,
} from "@/stories/practices-across-the-workspace-story-data";

import { TourAcrossLevel, type TourLevel, TourLevels } from "./tour-levels";
import { TourProfilePageContext } from "./tour-profile-page";
import styles from "./tour.module.css";

/** The example pages' handlers: the tour shows them, the reader does not act on them. */
const noop = () => undefined;

function HephStageIcon({ className }: { className?: string }) {
	return <HephMark className={className} />;
}

/** The stations from a piece of work to the profile, as the tour's small icons show them. */
export const TOUR_STAGES = [
	{ label: "Your work", Icon: GitPullRequestIcon },
	{ label: "Practices", Icon: HephStageIcon },
	{ label: "Observations", Icon: FileCodeIcon },
	{ label: "Feedback", Icon: RepeatIcon },
	{ label: "Your profile", Icon: UserRoundIcon },
];

const packagingVisual = getGroupVisual(packagingGroup.icon, packagingGroup.color);
const packagingMark = groupMark(packagingVisual);
const packagingPractices = practicesByGroup[packagingGroup.slug] ?? [];
const describe =
	packagingPractices.find((practice) => practice.slug === "describe-what-and-why") ??
	packagingPractices[0];

const COMMITS = [
	{ sha: "a1f3c9e", subject: "wip" },
	{ sha: "4b72d10", subject: "fix" },
	{ sha: "9e05b44", subject: "add cache to CourseService" },
];

const FILES = [
	{ path: "src/course/CourseService.ts", added: 42, removed: 6 },
	{ path: "src/course/CourseList.tsx", added: 18, removed: 9 },
	{ path: "src/course/cache.ts", added: 37, removed: 0 },
	{ path: "src/course/CourseService.test.ts", added: 12, removed: 2 },
];

/** A pull request as the reader knows it from GitHub or GitLab: what Heph will look at. */
export function WorkPanel() {
	return (
		<div className={cn(styles.fadeIn, "overflow-hidden rounded-xl border bg-background text-sm")}>
			<div className="flex flex-col gap-1.5 border-b px-4 py-3">
				<div className="flex items-center gap-2">
					<span className="text-base font-semibold">Add caching to the course list</span>
					<span className="text-base text-muted-foreground">#42</span>
				</div>
				<div className="flex items-center gap-2 text-xs text-muted-foreground">
					<span className="flex items-center gap-1 rounded-full bg-provider-open-foreground px-2 py-0.5 font-medium text-provider-onemphasis-foreground">
						<GitPullRequestIcon className="size-3" /> Open
					</span>
					<span>
						<span className="font-medium text-foreground">you</span> want to merge 3 commits into{" "}
						<code className="rounded bg-muted px-1">main</code> from{" "}
						<code className="rounded bg-muted px-1">feature/course-cache</code>
					</span>
				</div>
			</div>
			<div className="grid grid-cols-[1.1fr_1fr] divide-x">
				<div className="flex flex-col gap-3 p-4">
					<div className="flex flex-col gap-1">
						<span className="text-xs font-medium text-muted-foreground">Description</span>
						<span className="rounded-lg border bg-muted/30 px-3 py-2">Adds caching.</span>
					</div>
					<div className="flex flex-col gap-1">
						<span className="text-xs font-medium text-muted-foreground">Commits</span>
						{COMMITS.map((commit) => (
							<span key={commit.sha} className="flex items-center gap-2">
								<code className="text-xs text-muted-foreground">{commit.sha}</code>
								<span>{commit.subject}</span>
							</span>
						))}
					</div>
				</div>
				<div className="flex flex-col gap-1 p-4">
					<span className="text-xs font-medium text-muted-foreground">Files changed · 4</span>
					{FILES.map((file) => (
						<span key={file.path} className="flex items-center justify-between gap-3">
							<code className="truncate text-xs">{file.path}</code>
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

/**
 * The many practices first; then a lens picks one out of them and opens it, so it is clear that
 * the explanation now looks at one of many.
 */
export function OnePracticePanel() {
	const [picked, setPicked] = useState(false);
	const [open, setOpen] = useState(false);
	useEffect(() => {
		const timers = [
			window.setTimeout(() => setPicked(true), 1300),
			window.setTimeout(() => setOpen(true), 2200),
		];
		return () => {
			for (const timer of timers) {
				window.clearTimeout(timer);
			}
		};
	}, []);
	const all = groups.flatMap((group) => practicesByGroup[group.slug] ?? []);
	return (
		<div className="flex flex-col gap-4">
			<div className="flex flex-wrap gap-1.5">
				{all.map((practice) => {
					const chosen = practice.slug === describe?.slug;
					return (
						<span
							key={practice.slug}
							className={cn(
								"relative flex items-center gap-1 rounded-full px-2.5 py-1 text-xs transition-all duration-500",
								chosen && picked
									? "bg-mentor text-mentor-foreground ring-4 ring-mentor/20"
									: "bg-secondary text-secondary-foreground",
								picked && !chosen ? "opacity-35" : "opacity-100",
							)}
						>
							{chosen && picked && <SearchIcon className="size-3.5" />}
							{practice.name}
						</span>
					);
				})}
				<span className="px-2 py-1 text-xs text-muted-foreground">and more</span>
			</div>
			{open && (
				<div
					className={cn(
						styles.fadeIn,
						"flex flex-col items-start gap-2 rounded-xl border bg-background p-4",
					)}
				>
					<PracticePill
						name={describe?.name ?? "Describe what changed and why"}
						group={packagingMark}
					/>
					<p className="text-sm">
						<span className="font-medium">Why it matters: </span>
						<span className="text-muted-foreground">
							A reviewer who knows why a change is needed can judge whether it solves the problem.
						</span>
					</p>
					<p className="text-sm">
						<span className="font-medium">What good looks like: </span>
						<span className="text-muted-foreground">
							The description says the problem first, then what changed and how to check it.
						</span>
					</p>
				</div>
			)}
		</div>
	);
}

const CHIP_HEIGHT = 24;
const ROW = CHIP_HEIGHT + 4;
const FLAT_COLUMNS = 3;
const FLAT_WIDTH = 270;
const CLUSTER_COLUMNS = 3;
const CLUSTER_WIDTH = 280;
const CLUSTER_HEADER = 34;

const clusterGroups = groups.filter((group) => (practicesByGroup[group.slug] ?? []).length > 0);
const clusterRows = (row: number) =>
	Math.max(
		...clusterGroups
			.filter((_, at) => Math.floor(at / CLUSTER_COLUMNS) === row)
			.map((group) => (practicesByGroup[group.slug] ?? []).length),
	);
const clusterTop = (row: number) => {
	let top = 0;
	for (let at = 0; at < row; at += 1) {
		top += CLUSTER_HEADER + clusterRows(at) * ROW + 12;
	}
	return top;
};
const clusterChips = clusterGroups
	.flatMap((group, groupAt) =>
		(practicesByGroup[group.slug] ?? []).map((practice, practiceAt) => ({
			practice,
			group,
			groupAt,
			practiceAt,
		})),
	)
	.sort((a, b) => a.practiceAt - b.practiceAt || a.groupAt - b.groupAt);

/** The practices as one loose list, which then sorts itself into practice groups. */
export function GroupPanel() {
	const [clustered, setClustered] = useState(false);
	useEffect(() => {
		const timer = window.setTimeout(() => setClustered(true), 1200);
		return () => window.clearTimeout(timer);
	}, []);
	const flatHeight = Math.ceil(clusterChips.length / FLAT_COLUMNS) * ROW;
	const clusterHeight = clusterTop(Math.ceil(clusterGroups.length / CLUSTER_COLUMNS));
	return (
		<div className="flex flex-col items-center gap-2">
			<div
				className="relative h-(--tour-height) w-(--tour-width)"
				style={{
					"--tour-height": `${Math.max(flatHeight, clusterHeight) + 4}px`,
					"--tour-width": `${CLUSTER_COLUMNS * (CLUSTER_WIDTH + 20) - 20}px`,
				}}
			>
				{clusterGroups.map((group, groupAt) => {
					const visual = getGroupVisual(group.icon, group.color);
					const mark = groupMark(visual);
					const count = (practicesByGroup[group.slug] ?? []).length;
					return (
						<div
							key={group.slug}
							className={cn(
								"absolute top-(--tour-y) left-(--tour-x) h-(--tour-height) w-(--tour-width) rounded-2xl border bg-background transition-opacity duration-500",
								clustered ? "opacity-100 delay-700" : "opacity-0 delay-0",
							)}
							style={{
								"--tour-x": `${(groupAt % CLUSTER_COLUMNS) * (CLUSTER_WIDTH + 20)}px`,
								"--tour-y": `${clusterTop(Math.floor(groupAt / CLUSTER_COLUMNS))}px`,
								"--tour-width": `${CLUSTER_WIDTH}px`,
								"--tour-height": `${CLUSTER_HEADER + count * ROW + 6}px`,
							}}
						>
							<span
								className={`flex items-center gap-2 px-3 pt-2 text-sm font-semibold ${mark.ink}`}
							>
								<visual.Icon className="size-4 shrink-0" />
								<span className="truncate">{group.name}</span>
							</span>
						</div>
					);
				})}
				{clusterChips.map(({ practice, group, groupAt, practiceAt }, at) => {
					const visual = getGroupVisual(group.icon, group.color);
					const mark = groupMark(visual);
					const where = clustered
						? {
								x: (groupAt % CLUSTER_COLUMNS) * (CLUSTER_WIDTH + 20) + 8,
								y:
									clusterTop(Math.floor(groupAt / CLUSTER_COLUMNS)) +
									CLUSTER_HEADER +
									practiceAt * ROW -
									4,
							}
						: {
								x: (at % FLAT_COLUMNS) * (FLAT_WIDTH + 10),
								y: Math.floor(at / FLAT_COLUMNS) * ROW,
							};
					return (
						<div
							key={practice.slug}
							className={cn(
								styles.chip,
								"absolute top-0 left-0 flex h-6 items-center gap-1.5 rounded-full bg-secondary px-3 text-xs whitespace-nowrap text-secondary-foreground",
							)}
							style={{
								"--tour-width": `${clustered ? CLUSTER_WIDTH - 16 : FLAT_WIDTH}px`,
								"--tour-x": `${where.x}px`,
								"--tour-y": `${where.y}px`,
							}}
						>
							<visual.Icon
								className={cn(
									"size-3.5 shrink-0 transition-opacity duration-500",
									mark.ink,
									clustered ? "opacity-100" : "opacity-0",
								)}
							/>
							<span className="truncate">{practice.name}</span>
						</div>
					);
				})}
			</div>
		</div>
	);
}

const OBSERVATIONS = [
	{
		outcome: "NOT_MET" as const,
		practice: "Describe what changed and why",
		evidence: "The description says only “Adds caching.”: what changed, not why it was needed.",
	},
	{
		outcome: "NOT_MET" as const,
		practice: "Write commit subjects a reviewer can follow",
		evidence: "Two of three commit subjects say only “wip” or “fix”.",
	},
	{
		outcome: "MET" as const,
		practice: "Keep the diff reviewable in one sitting",
		evidence: "4 files, 120 changed lines, one concern.",
	},
];

/** For each practice of the course, one observation on the pull request: met or not, with evidence. */
export function ObservationPanel() {
	return (
		<div className="flex flex-col gap-3">
			<div className={cn(styles.fadeIn, "flex items-center gap-2 text-sm")}>
				<GitPullRequestIcon className="size-4 text-provider-open-foreground" />
				<span className="font-medium">#42 Add caching to the course list</span>
				<ArrowRightIcon className="size-4 text-muted-foreground" />
				<span className="text-muted-foreground">I look at it practice by practice</span>
			</div>
			<div className="flex flex-col gap-2">
				{OBSERVATIONS.map((row, at) => (
					<div
						key={row.practice}
						className={cn(
							styles.fadeIn,
							"grid grid-cols-[7rem_1fr] items-start gap-4 rounded-xl border bg-background p-3",
						)}
						style={{ "--tour-delay": `${0.3 + at * 0.35}s` }}
					>
						<StatusBadge def={OUTCOME_DEFS[row.outcome]} />
						<div className="flex flex-col items-start gap-1">
							<PracticePill name={row.practice} group={packagingMark} />
							<span className="text-sm text-muted-foreground">{row.evidence}</span>
						</div>
					</div>
				))}
			</div>
		</div>
	);
}

function Numbered({ n }: { n: number }) {
	return (
		<span className="flex size-6 shrink-0 items-center justify-center rounded-full bg-mentor text-xs font-semibold text-mentor-foreground">
			{n}
		</span>
	);
}

function MiniCard() {
	return (
		<div className="flex flex-col items-start gap-2 rounded-xl border bg-background p-3 text-sm">
			<div className="flex w-full items-center justify-between gap-2">
				<PracticePill name="Describe what changed and why" group={packagingMark} />
				<span className="text-xs text-mentor">New</span>
			</div>
			<span className="font-semibold">Descriptions say what changed, not why</span>
			<span className="border-l-2 border-mentor bg-mentor/5 px-2 py-1 text-xs">
				Next step: start each description with one sentence on the problem it solves.
			</span>
		</div>
	);
}

/**
 * The two places feedback reaches you, numbered. After a moment the second one comes forward: the
 * next step is about it.
 */
export function FeedbackPlacesPanel() {
	const [focus, setFocus] = useState(false);
	useEffect(() => {
		const timer = window.setTimeout(() => setFocus(true), 2600);
		return () => window.clearTimeout(timer);
	}, []);
	return (
		<div className="grid grid-cols-2 items-start gap-6">
			<div
				className={cn(
					styles.fadeIn,
					"flex flex-col gap-2 transition-opacity duration-700",
					focus ? "opacity-35" : "opacity-100",
				)}
			>
				<span className="flex items-center gap-2 text-sm font-semibold">
					<Numbered n={1} />
					<MessageSquareTextIcon className="size-4" /> On the pull request, right after the review
				</span>
				<div className="flex flex-col gap-2 rounded-xl border bg-background p-3 text-sm">
					<span className="flex items-center gap-2 font-medium">
						<HephMark className="size-5" /> Hephaestus{" "}
						<span className="font-normal text-muted-foreground">commented</span>
					</span>
					<span>
						The description says what was added but not why the course list needs a cache. Add one
						sentence on the problem it solves.
					</span>
					<span className="text-xs text-muted-foreground">
						AI-generated feedback. Answer or dispute it in Hephaestus.
					</span>
				</div>
				<span className="text-xs text-muted-foreground">About this one pull request.</span>
			</div>
			<div
				className={cn(
					styles.fadeIn,
					"flex origin-left flex-col gap-2 rounded-2xl transition-transform duration-700",
					focus && styles.lifted,
				)}
				style={{ "--tour-delay": "0.4s" }}
			>
				<span className="flex items-center gap-2 text-sm font-semibold">
					<Numbered n={2} />
					<UserRoundIcon className="size-4" /> On your profile, as a feedback card
				</span>
				<div
					className={cn(
						"rounded-xl transition-shadow duration-700",
						focus && "ring-4 ring-mentor/30",
					)}
				>
					<MiniCard />
				</div>
				<span className="text-xs text-muted-foreground">
					Only when the same problem shows up in two or more of your pull requests.
				</span>
			</div>
		</div>
	);
}

const PATTERN = [
	{ number: 17, title: "Show course list" },
	{ number: 31, title: "Fix score rounding" },
	{ number: 42, title: "Add caching to the course list" },
];

/** The same problem in several pull requests becomes one card; clean work later resolves it. */
export function PatternPanel() {
	return (
		<div className="grid grid-cols-[1fr_auto_1.1fr] items-center gap-4">
			<div className="flex flex-col gap-2">
				{PATTERN.map((work, at) => (
					<div
						key={work.number}
						className={cn(
							styles.fadeIn,
							"flex items-center gap-2 rounded-xl border bg-background p-2.5 text-sm",
						)}
						style={{ "--tour-delay": `${0.2 + at * 0.35}s` }}
					>
						<StatusBadge def={OUTCOME_DEFS.NOT_MET} />
						<span className="font-medium">#{work.number}</span>
						<span className="truncate text-muted-foreground">{work.title}</span>
					</div>
				))}
				<span className="text-xs text-muted-foreground">
					Same practice: Describe what changed and why
				</span>
			</div>
			<ArrowRightIcon
				className={cn(styles.fadeIn, "size-6 text-mentor")}
				style={{ "--tour-delay": "1.3s" }}
			/>
			<div className={cn(styles.fadeIn, "flex flex-col gap-2")} style={{ "--tour-delay": "1.5s" }}>
				<MiniCard />
				<span className="text-xs text-muted-foreground">
					It goes away when three of your next pull requests in a row come back clean, or when you
					mark it as fixed.
				</span>
			</div>
		</div>
	);
}

/** What the profile collects: the parts, held together by one bracket, become your profile. */
export function ProfileContentsPanel() {
	const items = [
		["Every practice with its guide", "why it matters, what good looks like, examples"],
		["Every observation of your work", "per practice, with the evidence"],
		["Repeated patterns as feedback cards", "each with one next step"],
		["Your standing and trend", "per practice and per group"],
	];
	return (
		<div className="grid grid-cols-[1fr_auto_auto] items-center gap-4">
			<ol className="flex flex-col gap-2">
				{items.map(([title, detail], at) => (
					<li
						key={title}
						className={cn(styles.fadeIn, "flex gap-3 rounded-xl border bg-background p-2.5")}
						style={{ "--tour-delay": `${at * 0.25}s` }}
					>
						<span className="flex size-6 shrink-0 items-center justify-center rounded-full bg-mentor/10 text-xs font-semibold text-mentor">
							{at + 1}
						</span>
						<span className="flex flex-col text-sm">
							<span className="font-medium">{title}</span>
							<span className="text-muted-foreground">{detail}</span>
						</span>
					</li>
				))}
			</ol>
			{/* One bracket around all four: they are one place. */}
			<svg
				aria-hidden
				viewBox="0 0 24 240"
				className={cn(styles.fadeIn, "h-60 w-6 text-mentor")}
				style={{ "--tour-delay": "1.2s" }}
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
					styles.fadeIn,
					"flex flex-col items-center gap-2 rounded-2xl border bg-background px-6 py-5",
				)}
				style={{ "--tour-delay": "1.5s" }}
			>
				<span className="flex size-12 items-center justify-center rounded-full bg-mentor text-mentor-foreground">
					<UserRoundIcon className="size-6" />
				</span>
				<span className="text-sm font-semibold">Your Practice profile</span>
				<span className="text-xs text-muted-foreground">only for you</span>
			</div>
		</div>
	);
}

const LAYERS = [
	"Practice profile",
	"All practice groups",
	"Packaging work for review",
	"Describe what changed and why",
];

/**
 * The levels as they behave: each click slides one more over the page; a click on a crumb closes
 * the ones above it again. Plays by itself and can be replayed.
 */
export function LayersPanel() {
	const [shown, setShown] = useState(1);
	const [pointing, setPointing] = useState(false);
	const [back, setBack] = useState(false);
	const [pageClick, setPageClick] = useState(false);
	const [pageBack, setPageBack] = useState(false);
	useEffect(() => {
		const plan: [number, () => void][] = [
			[700, () => setShown(2)],
			[1700, () => setShown(3)],
			[2700, () => setShown(4)],
			[3900, () => setPointing(true)],
			[
				4900,
				() => {
					setPointing(false);
					setShown(2);
					setBack(true);
				},
			],
			[6100, () => setPageClick(true)],
			[
				7100,
				() => {
					setPageClick(false);
					setShown(1);
					setBack(false);
					setPageBack(true);
				},
			],
		];
		const timers = plan.map(([ms, act]) => window.setTimeout(act, ms));
		return () => {
			for (const timer of timers) {
				window.clearTimeout(timer);
			}
		};
	}, []);
	const caption = layersCaption({ shown, pointing, back, pageClick, pageBack });
	return (
		<div className="flex flex-col gap-3">
			<div className="flex justify-between text-xs">
				<span className="text-muted-foreground">{caption}</span>
			</div>
			<div className="relative h-72 overflow-hidden rounded-xl border bg-background">
				{pageClick && (
					<MousePointerClickIcon className="absolute top-32 left-[8%] z-10 size-6 text-mentor" />
				)}
				<div className="p-4 text-sm">
					<span className="text-lg font-semibold">Practice profile</span>
					<div className="mt-3 h-3 w-2/3 rounded bg-muted" />
					<div className="mt-2 h-3 w-1/2 rounded bg-muted" />
					<div className="mt-4 h-24 w-full rounded-lg bg-muted/60" />
				</div>
				{LAYERS.slice(1).map((title, at) => {
					const depth = at + 1;
					const open = depth < shown;
					const pointed = pointing && depth === shown - 1;
					return (
						<div
							key={title}
							className={cn(
								"absolute inset-y-0 right-0 flex w-(--tour-width) flex-col gap-2 border-l bg-background p-4 shadow-xl transition-transform duration-500",
								!open && styles.offRight,
							)}
							style={{ "--tour-width": `${82 - depth * 8}%` }}
						>
							<div className="flex items-center gap-1 text-2xs text-muted-foreground">
								{LAYERS.slice(0, depth + 1).map((crumb, crumbAt) => (
									<span key={crumb} className="flex items-center gap-1">
										{crumbAt > 0 && <span>›</span>}
										<span className={crumbTone(crumbAt, depth, pointed)}>{crumb}</span>
										{crumbAt === 1 && pointed && (
											<MousePointerClickIcon className="size-4 text-mentor" />
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

/** What the levels animation says at each moment of its plan. */
function layersCaption({
	shown,
	pointing,
	back,
	pageClick,
	pageBack,
}: {
	shown: number;
	pointing: boolean;
	back: boolean;
	pageClick: boolean;
	pageBack: boolean;
}): string {
	if (pageBack) {
		return "A click on the page beside the levels closed the last one too.";
	}
	if (pageClick) {
		return "Or click the page on the left, beside the levels…";
	}
	if (back) {
		return "A click on the crumb “All practice groups” closed the two levels above it.";
	}
	if (pointing) {
		return "Now a click on a crumb goes back…";
	}
	return `${shown} ${shown === 1 ? "level" : "levels"} open`;
}

/** The current level's crumb stands out; the crumb about to be clicked is marked. */
function crumbTone(crumbAt: number, depth: number, pointed: boolean): string | undefined {
	if (crumbAt === depth) {
		return "font-medium text-foreground";
	}
	if (crumbAt === 1 && pointed) {
		return "rounded bg-mentor/15 px-1 text-mentor";
	}
	return undefined;
}

/** The example Practice profile at full size, as it fills up after a few reviews. */
function ProfileContent() {
	const { ratingProps } = useFeedbackRatings();
	const PracticeProfilePage = useContext(TourProfilePageContext);
	if (PracticeProfilePage === null) {
		return null;
	}
	return (
		<PracticeProfilePage
			hideTour
			latestReview={LATEST_REVIEW}
			practices={practiceStandings}
			groups={groups}
			feedbackCards={ALL_FEEDBACK_CARDS}
			onOpenGroup={noop}
			onOpenPractice={noop}
			feedbackTab="open"
			onFeedbackTabChange={noop}
			state={{ status: "ready" }}
			ratingProps={ratingProps}
		/>
	);
}

function FullPage({ children }: { children: ReactNode }) {
	return (
		<div className="h-full overflow-y-auto px-4 py-6">
			<div className="mx-auto w-full max-w-6xl">{children}</div>
		</div>
	);
}

export function ProfilePage() {
	return (
		<FullPage>
			<ProfileContent />
		</FullPage>
	);
}

export function AcrossPage({ level = false }: { level?: boolean }) {
	return (
		<>
			<FullPage>
				<PracticesAcrossTheWorkspacePage
					state={{ status: "ready", overview: ACROSS_WORKSPACE }}
					tiles={{ status: "ready", tiles: ACROSS_WORKSPACE_TILES }}
					window="DAYS_30"
					onWindowChange={noop}
					onOpenGroup={noop}
				/>
			</FullPage>
			<div
				aria-hidden
				className={cn(
					"pointer-events-none fixed inset-0 bg-scrim transition-opacity duration-500",
					level ? "opacity-100" : "opacity-0",
				)}
			/>
			<TourAcrossLevel open={level} />
		</>
	);
}

const OVERVIEW_TEXT = [
	"You open a pull request, as usual.",
	"I review it against your course’s practices.",
	"For each practice I note an observation: met or not met.",
	"Feedback goes onto the pull request, and repeated problems onto your profile.",
	"Your profile collects all of it.",
];

/** The whole way at once: the stations appear one after the other, joined as they come. */
export function FlowOverviewPanel() {
	return (
		<div className="flex items-stretch">
			{TOUR_STAGES.map((stage, at) => (
				<Fragment key={stage.label}>
					{at > 0 && (
						<span className="flex w-8 shrink-0 items-center">
							<span
								className={cn(styles.grow, "h-0.5 w-full origin-left rounded-full bg-mentor/50")}
								style={{ "--tour-delay": `${at * 0.45 - 0.15}s` }}
							/>
						</span>
					)}
					<div
						className={cn(
							styles.fadeIn,
							"flex flex-1 flex-col items-center gap-2 rounded-2xl border bg-background p-3 text-center",
						)}
						style={{ "--tour-delay": `${at * 0.45}s` }}
					>
						<span className="flex size-11 items-center justify-center rounded-full bg-mentor/10 text-mentor">
							<stage.Icon className="size-5" />
						</span>
						<span className="text-sm font-semibold">{stage.label}</span>
						<span className="text-xs leading-snug text-muted-foreground">{OVERVIEW_TEXT[at]}</span>
					</div>
				</Fragment>
			))}
		</div>
	);
}

const GROUP_EXAMPLE = [
	{ name: "Scope the change to one concern", standing: "DEVELOPING" as const },
	{ name: "Keep the diff reviewable in one sitting", standing: "MIXED" as const },
	{ name: "Describe what changed and why", standing: "STRENGTH" as const },
	{ name: "Mark the change ready and link its issue", standing: "STRENGTH" as const },
	{ name: "Write commit subjects a reviewer can follow", standing: "STRENGTH" as const },
];

/**
 * The two levels a standing is read on: each practice of a group has its own, and the group's ring
 * and standing add them up.
 */
export function GroupStandingPanel() {
	const counts = { DEVELOPING: 1, MIXED: 1, STRENGTH: 3 };
	return (
		<div className="grid grid-cols-[1.5fr_auto_1fr] items-center gap-5">
			<div className="flex flex-col gap-2">
				<span className="text-xs font-semibold tracking-wide text-muted-foreground uppercase">
					Practice level: one standing each
				</span>
				{GROUP_EXAMPLE.map((practice, at) => (
					<div
						key={practice.name}
						className={cn(
							styles.fadeIn,
							"flex items-center justify-between gap-3 rounded-xl border bg-background px-3 py-1.5",
						)}
						style={{ "--tour-delay": `${at * 0.2}s` }}
					>
						<span className="truncate text-sm">{practice.name}</span>
						<StatusBadge def={PRACTICE_STANDING_DEFS[practice.standing]} />
					</div>
				))}
			</div>
			<ArrowRightIcon
				className={cn(styles.fadeIn, "size-6 text-mentor")}
				style={{ "--tour-delay": "1.1s" }}
			/>
			<div
				className={cn(styles.fadeIn, "flex flex-col items-center gap-3")}
				style={{ "--tour-delay": "1.3s" }}
			>
				<span className="text-xs font-semibold tracking-wide text-muted-foreground uppercase">
					Group level: added up
				</span>
				<div className="flex items-center gap-3">
					<PracticeGroupStandingRing counts={counts} />
					<div className="flex flex-col items-start gap-1.5">
						<span className="text-sm font-semibold">{packagingGroup.name}</span>
						<StatusBadge def={PRACTICE_GROUP_STANDING_DEFS.MIXED} />
						<span className="text-xs text-muted-foreground">
							1 needs attention · 1 mixed · 3 going well
						</span>
					</div>
				</div>
			</div>
		</div>
	);
}

/**
 * The example profile with the app's own levels over it: the steps that share it add and remove
 * levels, and the levels slide in and out as they do in the app.
 */
const NO_LEVELS: TourLevel[] = [];

export function ProfileWithLevels({
	levels = NO_LEVELS,
	practiceTab = "feedback",
}: {
	levels?: TourLevel[];
	practiceTab?: PracticeTab;
}) {
	return (
		<>
			<FullPage>
				<ProfileContent />
			</FullPage>
			{/* The app's backdrop behind open levels; the levels open without one here, so the tour stays usable. */}
			<div
				aria-hidden
				className={cn(
					"pointer-events-none fixed inset-0 bg-scrim transition-opacity duration-500",
					levels.length > 0 ? "opacity-100" : "opacity-0",
				)}
			/>
			<TourLevels levels={levels} practiceTab={practiceTab} />
		</>
	);
}
