import {
	ArrowRightIcon,
	FileCodeIcon,
	GitPullRequestIcon,
	MessageSquareTextIcon,
	MousePointerClickIcon,
	RepeatIcon,
	RotateCcwIcon,
	SearchIcon,
	UserRoundIcon,
	XIcon,
} from "lucide-react";
import { Fragment, type ReactNode, useEffect, useState } from "react";

import { HephMark } from "@/components/brand/HephaestusLogo";
import {
	PracticeTabsList,
	PracticeTabsRail,
	PracticeTabsTrigger,
} from "@/components/common/practice-tabs";
import { StatusBadge } from "@/components/common/StatusBadge";
import { PracticeGuideMarkdown } from "@/components/practice-guidance/PracticeGuideMarkdown";
import { AllPracticeGroupsTable } from "@/components/practice-profile/AllPracticeGroupsTable";
import { composeOverview } from "@/components/practice-profile/compose-overview";
import { PracticeProfilePage } from "@/components/practice-profile/PracticeProfilePage";
import { ReviewsTable } from "@/components/practice-profile/ReviewsTable";
import {
	getGroupVisual,
	groupMark,
	PracticeIcon,
} from "@/components/practice-vocabulary/group-visuals";
import { OUTCOME_DEFS } from "@/components/practice-vocabulary/outcome-defs";
import {
	DEFAULT_PRACTICE_GROUP_SORT,
	sortPracticeGroups,
} from "@/components/practice-vocabulary/practice-group-list-order";
import {
	PRACTICE_GROUP_STANDING_DEFS,
	PRACTICE_STANDING_DEFS,
} from "@/components/practice-vocabulary/practice-group-standing-defs";
import { PracticeFeedbackCard } from "@/components/practice-vocabulary/PracticeFeedbackCard";
import { PracticeGroupStandingRing } from "@/components/practice-vocabulary/PracticeGroupStandingRing";
import { PracticePill, PracticeTitlePill } from "@/components/practice-vocabulary/PracticePill";
import { PracticesAcrossTheWorkspacePage } from "@/components/practices-across-the-workspace/PracticesAcrossTheWorkspacePage";
import { Tabs } from "@/components/ui/tabs";
import { useFeedbackRatings } from "@/stories/feedback-ratings";
import { ALL_FEEDBACK_CARDS } from "@/stories/practice-feedback-cards-story-mock-data";
import { bundledGuidance, bundledPractice } from "@/stories/practice-guidance-story-mock-data";
import {
	groups,
	groupStandings,
	LATEST_REVIEW,
	OVERVIEW_FIXTURE,
	packagingGroup,
	practicesByGroup,
	practiceStandings,
} from "@/stories/practice-profile-story-mock-data";
import {
	ACROSS_WORKSPACE,
	ACROSS_WORKSPACE_TILES,
} from "@/stories/practices-across-the-workspace-story-data";
import { profileReviews } from "@/stories/profile-reviews-story-mock-data";

import type { PracticeTab } from "@/components/practice-profile/practice-profile-search";
import { TourAcrossLevel, type TourLevel, TourLevels } from "./tour-levels";

const noop = () => {};

/**
 * The few movements the tour keeps: a fade in, the groups forming, and a level sliding in. They fill
 * backwards only, so a later change of opacity or scale on the same element still shows.
 */
const SCENE_CSS = `
@keyframes tour-in { from { opacity: 0; transform: translateY(8px); } to { opacity: 1; transform: none; } }
@keyframes tour-grow { from { transform: scaleX(0); } to { transform: none; } }
@keyframes tour-drawer { from { transform: translateX(100%); } to { transform: none; } }
.tour-in { animation: tour-in .24s cubic-bezier(.2,.9,.3,1) backwards; }
.tour-grow { animation: tour-grow .2s ease-out backwards; }
.tour-drawer { animation: tour-drawer .55s cubic-bezier(.2,.8,.2,1) both; }
@media (prefers-reduced-motion: reduce) { .tour-scene * { animation: none !important; transition: none !important; } }
`;

function SceneStyles() {
	return <style>{SCENE_CSS}</style>;
}

function delay(seconds: number) {
	return { animationDelay: `${seconds}s` };
}

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
		<div className="tour-in overflow-hidden rounded-xl border bg-background text-sm">
			<SceneStyles />
			<div className="flex flex-col gap-1.5 border-b px-4 py-3">
				<div className="flex items-center gap-2">
					<span className="text-base font-semibold">Add caching to the course list</span>
					<span className="text-base text-muted-foreground">#42</span>
				</div>
				<div className="flex items-center gap-2 text-xs text-muted-foreground">
					<span className="flex items-center gap-1 rounded-full bg-green-600 px-2 py-0.5 font-medium text-white">
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
								<span className="text-green-700">+{file.added}</span>{" "}
								<span className="text-red-700">−{file.removed}</span>
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
			window.setTimeout(() => setPicked(true), 120),
			window.setTimeout(() => setOpen(true), 340),
		];
		return () => timers.forEach((timer) => window.clearTimeout(timer));
	}, []);
	const all = groups.flatMap((group) => practicesByGroup[group.slug] ?? []);
	return (
		<div className="flex flex-col gap-4">
			<SceneStyles />
			<div className="flex flex-wrap gap-1.5">
				{all.map((practice) => {
					const chosen = practice.slug === describe?.slug;
					return (
						<span
							key={practice.slug}
							className={`relative flex items-center gap-1 rounded-full px-2.5 py-1 text-xs transition-all duration-200 ${
								chosen && picked
									? "bg-mentor text-white ring-4 ring-mentor/20"
									: "bg-secondary text-secondary-foreground"
							}`}
							style={{ opacity: picked && !chosen ? 0.35 : 1 }}
						>
							{chosen && picked && <SearchIcon className="size-3.5" />}
							{practice.name}
						</span>
					);
				})}
				<span className="px-2 py-1 text-xs text-muted-foreground">and more</span>
			</div>
			{open && (
				<div className="tour-in flex flex-col items-start gap-2 rounded-xl border bg-background p-4">
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
	for (let at = 0; at < row; at++) top += CLUSTER_HEADER + clusterRows(at) * ROW + 12;
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
	const [run, setRun] = useState(0);
	useEffect(() => {
		setClustered(false);
		const timer = window.setTimeout(() => setClustered(true), 80);
		return () => window.clearTimeout(timer);
	}, [run]);
	const flatHeight = Math.ceil(clusterChips.length / FLAT_COLUMNS) * ROW;
	const clusterHeight = clusterTop(Math.ceil(clusterGroups.length / CLUSTER_COLUMNS));
	return (
		<div className="flex flex-col items-center gap-2">
			<SceneStyles />
			<div
				className="relative"
				style={{
					height: Math.max(flatHeight, clusterHeight) + 4,
					width: CLUSTER_COLUMNS * (CLUSTER_WIDTH + 20) - 20,
				}}
			>
				{clusterGroups.map((group, groupAt) => {
					const visual = getGroupVisual(group.icon, group.color);
					const mark = groupMark(visual);
					const count = (practicesByGroup[group.slug] ?? []).length;
					return (
						<div
							key={group.slug}
							className="absolute rounded-2xl border bg-background transition-opacity duration-200"
							style={{
								left: (groupAt % CLUSTER_COLUMNS) * (CLUSTER_WIDTH + 20),
								top: clusterTop(Math.floor(groupAt / CLUSTER_COLUMNS)),
								width: CLUSTER_WIDTH,
								height: CLUSTER_HEADER + count * ROW + 6,
								opacity: clustered ? 1 : 0,
								transitionDelay: clustered ? "0.3s" : "0s",
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
							className="absolute top-0 left-0 flex items-center gap-1.5 rounded-full bg-secondary px-3 text-xs whitespace-nowrap text-secondary-foreground"
							style={{
								height: CHIP_HEIGHT,
								width: clustered ? CLUSTER_WIDTH - 16 : FLAT_WIDTH,
								transform: `translate(${where.x}px, ${where.y}px)`,
								transition: "transform .42s cubic-bezier(.4,0,.2,1), width .42s",
							}}
						>
							<PracticeIcon
								className={`size-3.5 shrink-0 transition-opacity duration-200 ${mark.ink}`}
								style={{ opacity: clustered ? 1 : 0 }}
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
			<SceneStyles />
			<div className="tour-in flex items-center gap-2 text-sm">
				<GitPullRequestIcon className="size-4 text-green-600" />
				<span className="font-medium">#42 Add caching to the course list</span>
				<ArrowRightIcon className="size-4 text-muted-foreground" />
				<span className="text-muted-foreground">I look at it practice by practice</span>
			</div>
			<div className="flex flex-col gap-2">
				{OBSERVATIONS.map((row, at) => (
					<div
						key={row.practice}
						className="tour-in grid grid-cols-[7rem_1fr] items-start gap-4 rounded-xl border bg-background p-3"
						style={delay(at * 0.08)}
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
		<span className="flex size-6 shrink-0 items-center justify-center rounded-full bg-mentor text-xs font-semibold text-white">
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
		const timer = window.setTimeout(() => setFocus(true), 320);
		return () => window.clearTimeout(timer);
	}, []);
	return (
		<div className="grid grid-cols-2 items-start gap-6">
			<SceneStyles />
			<div
				className="tour-in flex flex-col gap-2 transition-opacity duration-250"
				style={{ opacity: focus ? 0.35 : 1 }}
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
						AI-generated feedback. Answer it or disagree in Hephaestus.
					</span>
				</div>
				<span className="text-xs text-muted-foreground">About this one pull request.</span>
			</div>
			<div
				className="tour-in flex flex-col gap-2 rounded-2xl"
				style={delay(0.08)}
			>
				<span className="flex items-center gap-2 text-sm font-semibold">
					<Numbered n={2} />
					<UserRoundIcon className="size-4" /> On your profile, as a feedback card
				</span>
				<div
					className={`rounded-xl transition-shadow duration-250 ${focus ? "ring-4 ring-mentor/30" : ""}`}
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
			<SceneStyles />
			<div className="flex flex-col gap-2">
				{PATTERN.map((work, at) => (
					<div
						key={work.number}
						className="tour-in flex items-center gap-2 rounded-xl border bg-background p-2.5 text-sm"
						style={delay(at * 0.07)}
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
			<ArrowRightIcon className="tour-in size-6 text-mentor" style={delay(0.22)} />
			<div className="tour-in flex flex-col gap-2" style={delay(0.3)}>
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
			<SceneStyles />
			<ol className="flex flex-col gap-2">
				{items.map(([title, detail], at) => (
					<li
						key={title}
						className="tour-in flex gap-3 rounded-xl border bg-background p-2.5"
						style={delay(at * 0.06)}
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
				className="tour-in h-60 w-6 text-mentor"
				style={delay(0.26)}
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
				className="tour-in flex flex-col items-center gap-2 rounded-2xl border bg-background px-6 py-5"
				style={delay(0.32)}
			>
				<span className="flex size-12 items-center justify-center rounded-full bg-mentor text-white">
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
	const [run, setRun] = useState(0);
	useEffect(() => {
		setShown(1);
		setPointing(false);
		setBack(false);
		setPageClick(false);
		setPageBack(false);
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
		return () => timers.forEach((timer) => window.clearTimeout(timer));
	}, [run]);
	const caption = pageBack
		? "A click on the page beside the levels closed the last one too."
		: pageClick
			? "Or click the page on the left, beside the levels…"
			: back
				? "A click on the crumb “All practice groups” closed the two levels above it."
				: pointing
					? "Now a click on a crumb goes back…"
					: `${shown} ${shown === 1 ? "level" : "levels"} open`;
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
					const top = depth === shown - 1;
					return (
						<div
							key={title}
							className="absolute inset-y-0 right-0 flex flex-col gap-2 border-l bg-background p-4 shadow-xl transition-transform duration-500"
							style={{
								width: `${82 - depth * 8}%`,
								transform: open ? "none" : "translateX(105%)",
							}}
						>
							<div className="flex items-center gap-1 text-[11px] text-muted-foreground">
								{LAYERS.slice(0, depth + 1).map((crumb, crumbAt) => (
									<span key={crumb} className="flex items-center gap-1">
										{crumbAt > 0 && <span>›</span>}
										<span
											className={
												crumbAt === depth
													? "font-medium text-foreground"
													: crumbAt === 1 && pointing && top
														? "rounded bg-mentor/15 px-1 text-mentor"
														: undefined
											}
										>
											{crumb}
										</span>
										{crumbAt === 1 && pointing && top && (
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

/** The example Practice profile at full size, as it fills up after a few reviews. */
function ProfileContent() {
	const { ratingProps } = useFeedbackRatings();
	return (
		<PracticeProfilePage
			hideTour
			cardDetailsOpen
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
			<SceneStyles />
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
					onOpenGroup={noop}
				/>
			</FullPage>
			<div
				aria-hidden
				className="pointer-events-none fixed inset-0 bg-scrim transition-opacity duration-500"
				style={{ opacity: level ? 1 : 0 }}
			/>
			<TourAcrossLevel open={level} />
		</>
	);
}

/** Heph greets in its own window, the same size the explanations use. */
export function WelcomePanel() {
	return (
		<div className="flex flex-col items-center gap-4 text-center">
			<SceneStyles />
			<span className="tour-in flex size-24 items-center justify-center rounded-full bg-mentor/10 ring-1 ring-mentor/25">
				<HephMark className="size-16" />
			</span>
			<span className="tour-in max-w-md text-sm text-muted-foreground" style={delay(0.06)}>
				Hephaestus gives you feedback on how you work in your course project: not on whether your
				code runs, but on practices such as describing your changes or testing them.
			</span>
		</div>
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
			<SceneStyles />
			{TOUR_STAGES.map((stage, at) => (
				<Fragment key={stage.label}>
					{at > 0 && (
						<span className="flex w-8 shrink-0 items-center">
							<span
								className="tour-grow h-0.5 w-full origin-left rounded-full bg-mentor/50"
								style={delay(at * 0.08 - 0.04)}
							/>
						</span>
					)}
					<div
						className="tour-in flex flex-1 flex-col items-center gap-2 rounded-2xl border bg-background p-3 text-center"
						style={delay(at * 0.08)}
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
			<SceneStyles />
			<div className="flex flex-col gap-2">
				<span className="text-xs font-semibold tracking-wide text-muted-foreground uppercase">
					Practice level: one standing each
				</span>
				{GROUP_EXAMPLE.map((practice, at) => (
					<div
						key={practice.name}
						className="tour-in flex items-center justify-between gap-3 rounded-xl border bg-background px-3 py-1.5"
						style={delay(at * 0.05)}
					>
						<span className="truncate text-sm">{practice.name}</span>
						<StatusBadge def={PRACTICE_STANDING_DEFS[practice.standing]} />
					</div>
				))}
			</div>
			<ArrowRightIcon className="tour-in size-6 text-mentor" style={delay(0.24)} />
			<div className="tour-in flex flex-col items-center gap-3" style={delay(0.32)}>
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
export function ProfileWithLevels({
	levels = [],
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
				className="pointer-events-none fixed inset-0 bg-scrim transition-opacity duration-500"
				style={{ opacity: levels.length > 0 ? 1 : 0 }}
			/>
			<TourLevels levels={levels} practiceTab={practiceTab} />
		</>
	);
}
