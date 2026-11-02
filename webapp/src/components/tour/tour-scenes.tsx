import {
	ArrowRightIcon,
	FileCodeIcon,
	GitPullRequestIcon,
	LayersIcon,
	ListChecksIcon,
	MessageSquareTextIcon,
	MessagesSquareIcon,
	RepeatIcon,
	UserRoundIcon,
} from "lucide-react";
import type { ReactNode } from "react";

import { cn } from "cn";
import type { PracticeStanding } from "@/api/types.gen";
import { statusValues } from "@/components/common/status-def";
import { StatusBadge } from "@/components/common/StatusBadge";
import { OUTCOME_DEFS } from "@/components/practice-vocabulary/outcome-defs";
import { PRACTICE_STANDING_DEFS } from "@/components/practice-vocabulary/practice-group-standing-defs";
import { PRACTICE_TREND_DEFS } from "@/components/practice-vocabulary/practice-trend-defs";
import {
	PracticeFeedbackCard,
	type PracticeFeedbackCardEntry,
} from "@/components/practice-vocabulary/PracticeFeedbackCard";
import { WorkspaceSplitBar } from "@/components/practices-across-the-workspace/WorkspaceSplitBar";
import type { ItemProvider } from "@/lib/item-kinds";

import {
	EXAMPLE_OBSERVATIONS,
	EXAMPLE_SPLIT,
	EXAMPLE_WORK,
	exampleWorkLabel,
} from "./tour-example";
import styles from "./tour.module.css";

/** Its handlers never run: the stage is inert. They make the card draw the controls that a step names. */
const noop = () => undefined;

const FRAME = "rounded-xl border bg-card p-3 text-sm";

/** The stations from a piece of work to the Practice profile. */
export const TOUR_STATIONS = [
	{ label: "Your work", Icon: GitPullRequestIcon },
	{ label: "Practices", Icon: ListChecksIcon },
	{ label: "Observations", Icon: FileCodeIcon },
	{ label: "Feedback", Icon: MessageSquareTextIcon },
	{ label: "Your profile", Icon: UserRoundIcon },
];

export function WorkScene({ noun, provider }: { noun: string; provider?: ItemProvider }) {
	return (
		<div className={cn(styles.rise, FRAME, "flex flex-col gap-3")}>
			<p className="text-base font-semibold">
				{EXAMPLE_WORK.title}{" "}
				<span className="font-normal text-muted-foreground">
					{exampleWorkLabel(EXAMPLE_WORK.number, provider)}
				</span>
			</p>
			<p className="text-xs text-muted-foreground">
				An open {noun}, with {EXAMPLE_WORK.files} files changed
			</p>
			<div className="grid gap-3 sm:grid-cols-2">
				<div className="flex flex-col gap-1">
					<span className="text-xs font-medium text-muted-foreground">Description</span>
					<span>{EXAMPLE_WORK.description}</span>
				</div>
				<div className="flex flex-col gap-1">
					<span className="text-xs font-medium text-muted-foreground">Commits</span>
					{EXAMPLE_WORK.commits.map((commit) => (
						<span key={commit}>{commit}</span>
					))}
				</div>
			</div>
		</div>
	);
}

/** Many practices, then one picked out: a review looks at one practice at a time. */
export function PracticesScene({ names }: { names: string[] }) {
	return (
		<ul className="flex flex-wrap gap-1.5">
			{names.map((name, index) => (
				<li
					key={name}
					className={cn(
						"rounded-full px-2.5 py-1 text-xs",
						index === 0
							? [styles.picked, "bg-highlight text-highlight-foreground"]
							: "bg-secondary text-secondary-foreground",
					)}
					style={{ "--tour-delay": "600ms" }}
				>
					{name}
				</li>
			))}
		</ul>
	);
}

/** The practices gather under their groups, one group after the other. */
export function GroupsScene({ groups }: { groups: { name: string; practices: string[] }[] }) {
	return (
		<div className="grid gap-3 sm:grid-cols-2">
			{groups.map((group, index) => (
				<div
					key={group.name}
					className={cn(styles.rise, FRAME, "flex flex-col gap-2")}
					style={{ "--tour-delay": `${index * 160}ms` }}
				>
					<span className="font-semibold">{group.name}</span>
					<ul className="flex flex-wrap gap-1">
						{group.practices.map((practice) => (
							<li
								key={practice}
								className="rounded-full bg-secondary px-2 py-0.5 text-xs text-secondary-foreground"
							>
								{practice}
							</li>
						))}
					</ul>
				</div>
			))}
		</div>
	);
}

export function ObservationsScene() {
	return (
		<ul className="flex flex-col gap-2">
			{EXAMPLE_OBSERVATIONS.map((row, index) => (
				<li
					key={row.practice}
					className={cn(styles.rise, FRAME, "flex flex-col gap-1.5")}
					style={{ "--tour-delay": `${index * 200}ms` }}
				>
					<span className="flex flex-wrap items-center gap-2 font-medium">
						<StatusBadge def={OUTCOME_DEFS[row.outcome]} />
						{row.practice}
					</span>
					<span className="text-muted-foreground">{row.evidence}</span>
				</li>
			))}
		</ul>
	);
}

const DESTINATIONS = [
	{
		Icon: GitPullRequestIcon,
		title: "On the work",
		text: "A comment about this one piece of work.",
	},
	{
		Icon: UserRoundIcon,
		title: "On your Practice profile",
		text: "A feedback card when a problem repeats across your work.",
	},
	{
		Icon: MessagesSquareIcon,
		title: "In a conversation",
		text: "Hephaestus can show feedback when it fits what you ask.",
	},
];

export function DestinationsScene() {
	return (
		<ul className="grid gap-3 sm:grid-cols-3">
			{DESTINATIONS.map(({ Icon, title, text }, index) => (
				<li
					key={title}
					className={cn(styles.rise, FRAME, "flex flex-col gap-1.5")}
					style={{ "--tour-delay": `${index * 200}ms` }}
				>
					<Icon className="size-4 text-muted-foreground" aria-hidden />
					<span className="font-semibold">{title}</span>
					<span className="text-muted-foreground">{text}</span>
				</li>
			))}
		</ul>
	);
}

/** Two pieces of work with the same problem, then the one card about the pattern. */
export function PatternScene({ provider }: { provider?: ItemProvider }) {
	return (
		<div className="grid items-center gap-3 sm:grid-cols-[1fr_auto_1fr]">
			<ul className="flex flex-col gap-2">
				{[17, 19].map((number, index) => (
					<li
						key={number}
						className={cn(styles.rise, FRAME, "flex items-center gap-2")}
						style={{ "--tour-delay": `${index * 200}ms` }}
					>
						<StatusBadge def={OUTCOME_DEFS.NOT_MET} />
						{exampleWorkLabel(number, provider)}
					</li>
				))}
			</ul>
			<ArrowRightIcon
				className={cn(styles.rise, "hidden size-5 text-highlight sm:block")}
				style={{ "--tour-delay": "500ms" }}
				aria-hidden
			/>
			<div
				className={cn(styles.rise, FRAME, "flex flex-col gap-1.5 border-highlight/35")}
				style={{ "--tour-delay": "700ms" }}
			>
				<RepeatIcon className="size-4 text-highlight" aria-hidden />
				<span className="font-semibold">Descriptions say what changed, not why</span>
				<span className="text-muted-foreground">One card, with one thing to try next.</span>
			</div>
		</div>
	);
}

export const PROFILE_PARTS = [
	["Your standing and trend", "For each practice and each group"],
	["Your feedback cards", "Each with one next step"],
	["Your observations", "For each practice, with the evidence"],
	["The guide of each practice", "Why it matters and what good looks like"],
] as const;

export function ProfileScene() {
	return (
		<ul className="flex flex-col gap-2">
			{PROFILE_PARTS.map(([title, detail], index) => (
				<li
					key={title}
					className={cn(styles.rise, FRAME, "flex flex-col")}
					style={{ "--tour-delay": `${index * 160}ms` }}
				>
					<span className="font-semibold">{title}</span>
					<span className="text-muted-foreground">{detail}</span>
				</li>
			))}
		</ul>
	);
}

/** A feedback card in the stage, drawn with all the controls that the steps name. */
export function CardScene({
	card,
	rating,
}: {
	card: PracticeFeedbackCardEntry;
	rating?: Pick<Parameters<typeof PracticeFeedbackCard>[0], "usefulness" | "resolution">;
}) {
	return (
		<PracticeFeedbackCard
			card={card}
			usefulness={rating?.usefulness}
			resolution={rating?.resolution}
			onRate={noop}
			onDisagree={noop}
			onResolve={noop}
		/>
	);
}

/**
 * The levels as a schematic, not as the real panels: each arrives over the one before it, so the
 * reader sees that they stack and that closing one returns to the one under it.
 */
export function LevelsScene({ levels }: { levels: string[] }) {
	return (
		<ol className="flex flex-col gap-2">
			{levels.map((level, index) => (
				<li
					key={level}
					className={cn(
						styles.slide,
						FRAME,
						"ms-(--tour-indent) flex items-center gap-2 font-medium",
					)}
					style={{ "--tour-delay": `${index * 280}ms`, "--tour-indent": `${index * 1.5}rem` }}
				>
					<LayersIcon className="size-4 shrink-0 text-muted-foreground" aria-hidden />
					<span className="min-w-0">{level}</span>
				</li>
			))}
		</ol>
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

export const PRACTICE_TABS = [
	["Feedback", "The feedback card for this practice, if there is one."],
	["Observations", "Every observation of this practice on your work, with its evidence."],
	[
		"Guide",
		"Why the practice matters and what good looks like, where it has something to explain.",
	],
] as const;

export function PracticeTabsScene() {
	return (
		<ul className="grid gap-3 sm:grid-cols-3">
			{PRACTICE_TABS.map(([title, text], index) => (
				<li
					key={title}
					className={cn(styles.rise, FRAME, "flex flex-col gap-1")}
					style={{ "--tour-delay": `${index * 160}ms` }}
				>
					<span className="font-semibold">{title}</span>
					<span className="text-muted-foreground">{text}</span>
				</li>
			))}
		</ul>
	);
}

export function SplitScene() {
	return (
		<div className={cn(styles.rise, FRAME, "flex flex-col gap-2")}>
			<span className="font-semibold">Packaging work for review</span>
			{/* No `yourStanding`: its You bracket would state a standing for the reader. */}
			<WorkspaceSplitBar split={EXAMPLE_SPLIT} />
		</div>
	);
}

/** A real way out of the tour, for the step that hands the reader over to their own data. */
export function HandOverScene({ children }: { children: ReactNode }) {
	return <div className="flex flex-col items-start gap-3">{children}</div>;
}

/** Practice names, without repeats: a practice can be listed under more than one group. */
export function practiceNames(practices: PracticeStanding[]): string[] {
	return [...new Set(practices.map((practice) => practice.name))];
}
