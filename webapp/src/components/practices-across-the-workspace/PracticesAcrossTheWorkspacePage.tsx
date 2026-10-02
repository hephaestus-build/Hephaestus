// The palette this page shares with the practice profile is `webapp/AGENTS.md` § Practice surfaces palette.
import { CircleDashedIcon, LayersIcon, UsersRoundIcon } from "lucide-react";
import { useId } from "react";

import { cn } from "cn";
import type { PracticesAcrossWorkspace } from "@/api/types.gen";
import { FilterToggle } from "@/components/common/FilterToggle";
import type { PanelState } from "@/components/common/panel-state";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { statusToneClass } from "@/components/common/status-def";
import { PRACTICE_GROUP_STANDING_DEFS } from "@/components/practice-vocabulary/practice-group-standing-defs";
import { Button } from "@/components/ui/button";
import { Empty, EmptyDescription, EmptyHeader, EmptyTitle } from "@/components/ui/empty";
import { Field, FieldContent, FieldLabel } from "@/components/ui/field";
import { Skeleton } from "@/components/ui/skeleton";
import { Switch } from "@/components/ui/switch";

import {
	type AcrossWorkspaceWindow,
	ESTIMATE_STANDINGS,
	type Estimate,
	type EstimateSummary,
	groupCount,
	orderGroups,
	summarizeEstimates,
	WINDOW_OPTIONS,
	windowHeading,
	windowPhrase,
} from "./across-workspace-copy";
import { PracticeGroupSplitRow } from "./PracticeGroupSplitRow";
import { WorkspaceTiles } from "./WorkspaceTiles";

export interface PracticesAcrossTheWorkspacePageProps {
	workspaceSlug: string;
	state: PanelState<{ overview: PracticesAcrossWorkspace }>;
	window: AcrossWorkspaceWindow;
	onWindowChange?: (window: AcrossWorkspaceWindow) => void;
	/** Whether the workspace is shown beside the reader's own standing; remembered by the route. */
	showWorkspace: boolean;
	onShowWorkspaceChange?: (show: boolean) => void;
	/** Whether a group asks where the reader thinks they stand before it shows the workspace. */
	askFirst: boolean;
	/**
	 * Whether the reader may estimate at all: an administrator's read-only view of a developer's
	 * page shows it without asking, since nobody there is reflecting on their own work.
	 */
	canEstimate?: boolean;
	onAskFirstChange?: (ask: boolean) => void;
	/** The reader's answers by group slug; they live in the browser and never reach the server. */
	estimates: Readonly<Record<string, Estimate | undefined>>;
	onEstimate?: (groupSlug: string, estimate: Estimate) => void;
	/** Answers every group still asking with Skipped, so the whole page shows at once. */
	onSkipRest?: (groupSlugs: string[]) => void;
	/** Clears every answer, so each group asks again. */
	onStartOver?: () => void;
}

const REASON =
	"Your own standing comes first, then how this workspace splits across the same practice groups. Everyone else appears only as a whole, counted in developers.";

/**
 * Practices across the workspace, design C: reflect first, then reveal. Each practice group first
 * asks where the reader thinks they stand, and only then shows the workspace's split beside the
 * reader's own standing, because a comparison seen first anchors every judgement after it. Rows stay
 * in catalogue order until nothing asks, so the order gives no answer away.
 */
export function PracticesAcrossTheWorkspacePage({
	workspaceSlug,
	state,
	window,
	onWindowChange,
	showWorkspace,
	onShowWorkspaceChange,
	askFirst: askFirstPreference,
	onAskFirstChange,
	canEstimate = true,
	estimates,
	onEstimate,
	onSkipRest,
	onStartOver,
}: PracticesAcrossTheWorkspacePageProps) {
	const askFirst = canEstimate && askFirstPreference;
	const overview = state.status === "ready" ? state.overview : undefined;
	return (
		<div className="flex flex-col gap-8">
			<header className="flex flex-col gap-4">
				<div className="flex items-start gap-3">
					<UsersRoundIcon className="mt-1 size-6 shrink-0 text-muted-foreground" aria-hidden />
					<div className="flex min-w-0 flex-col gap-2">
						<h1 className="text-2xl font-semibold tracking-tight">
							Practices across the workspace
						</h1>
						<p className="max-w-2xl text-sm text-muted-foreground">{REASON}</p>
						<Coverage
							overview={overview}
							showWorkspace={showWorkspace}
							isLoading={state.status === "loading"}
						/>
					</div>
				</div>
				<PreferenceSwitches
					window={window}
					showWorkspace={showWorkspace}
					onShowWorkspaceChange={onShowWorkspaceChange}
					askFirst={askFirst}
					onAskFirstChange={canEstimate ? onAskFirstChange : undefined}
				/>
			</header>

			<section aria-labelledby="window-heading" className="flex flex-col gap-3">
				<div className="flex flex-wrap items-center justify-between gap-3">
					<h2 id="window-heading" className="text-lg font-semibold tracking-tight">
						{windowHeading(window)}
					</h2>
					<FilterToggle
						label="Time range"
						options={WINDOW_OPTIONS}
						value={window}
						onChange={(next) => onWindowChange?.(next)}
					/>
				</div>
				{state.status === "error" ? (
					<QueryErrorAlert
						error={state.error}
						title="Couldn't load the workspace"
						onRetry={state.onRetry}
					/>
				) : (
					<>
						<WorkspaceTiles overview={overview} showWorkspace={showWorkspace} />
						{showWorkspace && overview !== undefined && (
							<p className="text-sm text-muted-foreground">
								The workspace line covers the middle half of the {overview.observedDevelopers}{" "}
								developers observed {windowPhrase(window)}, so it names no one and places no one.
								Every window checks the five developer rule on its own; a window with too few says
								so.
							</p>
						)}
					</>
				)}
			</section>

			{state.status !== "error" && (
				<GroupsSection
					workspaceSlug={workspaceSlug}
					overview={overview}
					window={window}
					showWorkspace={showWorkspace}
					askFirst={askFirst}
					estimates={estimates}
					onEstimate={onEstimate}
					onSkipRest={onSkipRest}
					onStartOver={onStartOver}
				/>
			)}

			<AboutThisPage />
		</div>
	);
}

function Coverage({
	overview,
	showWorkspace,
	isLoading,
}: {
	overview?: PracticesAcrossWorkspace;
	showWorkspace: boolean;
	isLoading: boolean;
}) {
	if (overview === undefined) {
		return isLoading ? <Skeleton className="h-5 w-80" /> : null;
	}
	const reviewed = (
		<span className="font-semibold text-foreground tabular-nums">
			{overview.reviewedWork.yours}
		</span>
	);
	return (
		<p className="text-sm text-muted-foreground">
			{showWorkspace && (
				<>
					<span className="font-semibold text-foreground tabular-nums">
						{overview.observedDevelopers} of {overview.eligibleDevelopers} developers
					</span>{" "}
					observed,{" "}
				</>
			)}
			{reviewed} {overview.reviewedWork.yours === 1 ? "piece" : "pieces"} of your work reviewed,{" "}
			{windowPhrase(overview.window)}.
		</p>
	);
}

function PreferenceSwitches({
	window,
	showWorkspace,
	onShowWorkspaceChange,
	askFirst,
	onAskFirstChange,
}: {
	window: AcrossWorkspaceWindow;
	showWorkspace: boolean;
	onShowWorkspaceChange?: (show: boolean) => void;
	askFirst: boolean;
	onAskFirstChange?: (ask: boolean) => void;
}) {
	const id = useId();
	return (
		<div className="flex flex-col gap-3 rounded-xl border bg-sidebar px-4 py-3 sm:flex-row sm:items-center sm:justify-between sm:gap-6">
			<p className="max-w-[70ch] text-sm text-muted-foreground">
				Shown so you can see which practice groups the developers in this workspace reach{" "}
				{windowPhrase(window)}, and so what is within reach for you. Remembered for you; turn either
				off at any time.
			</p>
			<div className="flex shrink-0 flex-col gap-2">
				<Field orientation="horizontal">
					<Switch
						id={`${id}-show`}
						checked={showWorkspace}
						onCheckedChange={(checked) => onShowWorkspaceChange?.(checked)}
					/>
					<FieldContent>
						<FieldLabel htmlFor={`${id}-show`}>Show the workspace</FieldLabel>
					</FieldContent>
				</Field>
				{onAskFirstChange !== undefined && (
					<Field orientation="horizontal" data-disabled={!showWorkspace || undefined}>
						<Switch
							id={`${id}-ask`}
							checked={askFirst}
							disabled={!showWorkspace}
							onCheckedChange={(checked) => onAskFirstChange(checked)}
						/>
						<FieldContent>
							<FieldLabel htmlFor={`${id}-ask`}>Ask me first</FieldLabel>
						</FieldContent>
					</Field>
				)}
			</div>
		</div>
	);
}

function GroupsSection({
	workspaceSlug,
	overview,
	window,
	showWorkspace,
	askFirst,
	estimates,
	onEstimate,
	onSkipRest,
	onStartOver,
}: {
	workspaceSlug: string;
	overview?: PracticesAcrossWorkspace;
	window: AcrossWorkspaceWindow;
	showWorkspace: boolean;
	askFirst: boolean;
	estimates: Readonly<Record<string, Estimate | undefined>>;
	onEstimate?: (groupSlug: string, estimate: Estimate) => void;
	onSkipRest?: (groupSlugs: string[]) => void;
	onStartOver?: () => void;
}) {
	const groups = overview?.groups ?? [];
	const asks = showWorkspace && askFirst;
	const stillAsking = asks
		? groups
				.filter((group) => estimates[group.groupSlug] === undefined)
				.map((group) => group.groupSlug)
		: [];
	const ordered = orderGroups(groups, stillAsking.length === 0);
	return (
		<section aria-labelledby="groups-heading" className="flex flex-col gap-3">
			<div className="flex flex-col gap-1">
				<h2 id="groups-heading" className="text-lg font-semibold tracking-tight">
					All practice groups
				</h2>
				<p className="max-w-[84ch] text-sm text-muted-foreground">
					{showWorkspace
						? `${askFirst ? "Each practice group first asks where you think you stand, then shows" : "Each practice group shows"} how the developers observed in this workspace ${windowPhrase(window)} split across it. A group name opens your own group on your Practice profile.`
						: "Your own standing in each practice group. A group name opens your own group on your Practice profile."}
				</p>
			</div>
			{overview === undefined && <RowsSkeleton />}
			{overview !== undefined && groups.length === 0 && (
				<Empty variant="outlined">
					<EmptyHeader>
						<EmptyTitle>No practice groups here yet</EmptyTitle>
						<EmptyDescription>
							Once your workspace sets up practice groups, each one appears here with your standing
							in it.
						</EmptyDescription>
					</EmptyHeader>
				</Empty>
			)}
			{overview !== undefined && groups.length > 0 && (
				<>
					{asks && (
						<EstimateCard
							overview={overview}
							window={window}
							estimates={estimates}
							stillAsking={stillAsking}
							onSkipRest={onSkipRest}
							onStartOver={onStartOver}
						/>
					)}
					{showWorkspace && <SplitLegend />}
					<ul
						aria-label="All practice groups"
						className="overflow-hidden rounded-xl border bg-card"
					>
						{ordered.map((group) => {
							const estimate = asks ? estimates[group.groupSlug] : undefined;
							return (
								<PracticeGroupSplitRow
									key={group.groupSlug}
									workspaceSlug={workspaceSlug}
									group={group}
									window={window}
									readerCounted={overview.readerCounted}
									observedDevelopers={overview.observedDevelopers}
									showWorkspace={showWorkspace}
									asking={asks && estimate === undefined}
									estimate={estimate}
									onEstimate={(answer) => onEstimate?.(group.groupSlug, answer)}
								/>
							);
						})}
					</ul>
					{showWorkspace && (
						<p className="flex items-center gap-1.5 text-xs text-muted-foreground">
							<CircleDashedIcon className="size-3.5 shrink-0" aria-hidden />
							The workspace trend appears once enough work is reviewed.
						</p>
					)}
				</>
			)}
		</section>
	);
}

/**
 * The card over the rows, shaped like State of Mind's "Log an emotion": what is asked and how
 * quickly it goes, one stroke per group, and one way out. Once every group is answered it counts
 * where the estimate and the standing read the same, and nothing more: no score, no streak.
 */
function EstimateCard({
	overview,
	window,
	estimates,
	stillAsking,
	onSkipRest,
	onStartOver,
}: {
	overview: PracticesAcrossWorkspace;
	window: AcrossWorkspaceWindow;
	estimates: Readonly<Record<string, Estimate | undefined>>;
	stillAsking: string[];
	onSkipRest?: (groupSlugs: string[]) => void;
	onStartOver?: () => void;
}) {
	const total = overview.groups.length;
	const answered = total - stillAsking.length;
	const done = stillAsking.length === 0;
	const summary = summarizeEstimates(overview.groups, estimates);
	return (
		<div className="flex flex-col gap-3 rounded-xl border bg-card px-4 py-3.5 sm:flex-row sm:items-center sm:justify-between sm:gap-6">
			<div className="flex min-w-0 items-start gap-3">
				<span className="flex size-10 shrink-0 items-center justify-center rounded-lg bg-muted text-muted-foreground">
					<LayersIcon className="size-5" aria-hidden />
				</span>
				<div className="flex min-w-0 flex-col gap-1">
					<h3 className="text-base font-semibold">
						{done ? `Your estimates ${windowPhrase(window)}` : "Where do you think you stand?"}
					</h3>
					<p className="text-sm text-muted-foreground">{estimateLead(summary, answered, total)}</p>
					<span
						role="img"
						aria-label={`${answered} of ${total} answered`}
						className="mt-1 flex flex-wrap gap-1"
					>
						{overview.groups.map((group) => (
							<span
								key={group.groupSlug}
								className={cn(
									"h-1.5 w-5 rounded-full",
									estimates[group.groupSlug] === undefined ? "bg-border" : "bg-muted-foreground",
								)}
							/>
						))}
					</span>
				</div>
			</div>
			{done ? (
				<Button
					variant="outline"
					className="self-start sm:self-center"
					onClick={() => onStartOver?.()}
				>
					Start over
				</Button>
			) : (
				<Button
					variant="outline"
					className="self-start sm:self-center"
					onClick={() => onSkipRest?.(stillAsking)}
				>
					Show all without estimating
				</Button>
			)}
		</div>
	);
}

function estimateLead(summary: EstimateSummary, answered: number, total: number): string {
	if (answered === total) {
		return `You estimated ${groupCount(summary.estimated)} and skipped ${summary.skipped}. In ${summary.same}, your estimate and your latest reviewed work read the same; in ${summary.different}, they read differently; ${summary.nothingToCompare} have no standing to compare yet.`;
	}
	if (answered === 0) {
		return "Before you see the workspace, say where you think you stand in each practice group. One tap each, or skip.";
	}
	return `${answered} of ${total} answered. Each group shows the workspace once you answer it.`;
}

/** The bar's key, the one sentence against the boomerang effect, and the rule behind every count. */
function SplitLegend() {
	return (
		<div className="flex flex-col gap-2 text-sm">
			<p>Where you are Going well, keep the way of working that got you there.</p>
			<ul
				aria-label="Legend"
				className="flex flex-wrap gap-x-4 gap-y-1 text-xs text-muted-foreground"
			>
				{ESTIMATE_STANDINGS.map((standing) => {
					const def = PRACTICE_GROUP_STANDING_DEFS[standing];
					const Icon = def.icon;
					return (
						<li key={standing} className="inline-flex items-center gap-1.5">
							<span
								className={cn("h-2 w-3.5 rounded-sm bg-current", statusToneClass(def.badgeVariant))}
								aria-hidden
							/>
							<Icon className="size-3.5" aria-hidden />
							{def.label}
						</li>
					);
				})}
				<li className="inline-flex items-center gap-1.5">
					<span
						className="h-2 w-3.5 rounded-sm bg-muted ring-1 ring-border ring-inset"
						aria-hidden
					/>
					Not enough to compare yet
				</li>
				<li className="inline-flex items-center gap-1.5">
					<span className="font-semibold text-mentor">You</span>
					your own standing, marked on a split that is shown
				</li>
			</ul>
			<p className="flex items-center gap-1.5 text-xs text-muted-foreground">
				<UsersRoundIcon className="size-3.5 shrink-0" aria-hidden />
				A split appears once every part of it, and those with no standing yet, holds at least five
				developers other than you. A group summarises its practices.
			</p>
		</div>
	);
}

function RowsSkeleton() {
	return (
		<div>
			<ul aria-busy className="overflow-hidden rounded-xl border bg-card">
				{[0, 1, 2, 3].map((index) => (
					<li
						key={index}
						aria-hidden
						className="grid gap-3 border-b px-4 py-3 last:border-b-0 md:grid-cols-[minmax(0,18rem)_minmax(0,1fr)] md:gap-6"
					>
						<Skeleton className="h-5 w-48" />
						<Skeleton className="h-9 w-full" />
					</li>
				))}
			</ul>
			<span className="sr-only">Loading the practice groups</span>
		</div>
	);
}

const ABOUT = [
	{
		title: "What it is",
		body: "A short check you make before you look: for each practice group, where you think you stand, then how your latest reviewed work reads and how the developers in this workspace split.",
	},
	{
		title: "Why it matters",
		body: "Saying what you expect first makes the difference visible to you. Where the two read differently is often the most useful place to look in your own work.",
	},
	{
		title: "How it works",
		body: "Your standing comes from your latest reviewed work. Only you see your estimates: they stay in this browser and never enter a workspace figure. Start over asks you again.",
	},
	{
		title: "What can distort it",
		body: "A group summarises several practices, so one practice can pull it. A group your work has not touched yet reads Not observed yet, whatever you expect.",
	},
] as const;

function AboutThisPage() {
	return (
		<section aria-labelledby="about-heading" className={cn("flex flex-col gap-3 border-t pt-5")}>
			<h2 id="about-heading" className="text-base font-semibold">
				About this page
			</h2>
			<div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
				{ABOUT.map((entry) => (
					<div key={entry.title} className="flex flex-col gap-1">
						<h3 className="text-sm font-semibold text-muted-foreground">{entry.title}</h3>
						<p className="text-sm">{entry.body}</p>
					</div>
				))}
			</div>
		</section>
	);
}
