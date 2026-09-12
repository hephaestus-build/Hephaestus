import { format } from "date-fns";
import { AlertCircleIcon, DatabaseIcon, SearchIcon, TriangleAlertIcon } from "lucide-react";
import { Fragment, useState } from "react";

import { cn } from "cn";
import type { SyncResourceCount, SyncResourceState } from "@/api/types.gen";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { RelativeTime } from "@/components/common/RelativeTime";
import { SortButton } from "@/components/common/SortButton";
import { Button } from "@/components/ui/button";
import {
	Empty,
	EmptyDescription,
	EmptyHeader,
	EmptyMedia,
	EmptyTitle,
} from "@/components/ui/empty";
import { HoverCard, HoverCardContent, HoverCardTrigger } from "@/components/ui/hover-card";
import { Input } from "@/components/ui/input";
import { Progress } from "@/components/ui/progress";
import {
	Table,
	TableBody,
	TableCell,
	TableFooter,
	TableHead,
	TableHeader,
	TableRow,
} from "@/components/ui/table";
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { asDate } from "@/lib/dates";

import { freshnessTone, stateLabel } from "./sync-format";
import { TableRowsSkeleton } from "./TableRowsSkeleton";

type ClassKey = SyncResourceCount["key"];

interface ClassColumn {
	key: string;
	label: string;
	/**
	 * The wire classes this column reports. More than one only for Comments, where the issue/review
	 * split is an implementation detail of the mirror rather than a distinction an admin acts on — the
	 * two are the same pipeline in the same repository, and the split is still spelled out in the cell's
	 * tooltip so a stalled half is never hidden.
	 */
	sourceKeys: ClassKey[];
}

/** The count columns, in reading order, and the fold from wire classes to them. */
const CLASS_COLUMNS: ClassColumn[] = [
	{ key: "issues", label: "Issues", sourceKeys: ["issues"] },
	{ key: "pullRequests", label: "PRs", sourceKeys: ["pullRequests"] },
	{ key: "reviews", label: "Reviews", sourceKeys: ["reviews"] },
	{ key: "comments", label: "Comments", sourceKeys: ["issueComments", "reviewComments"] },
	{ key: "commits", label: "Commits", sourceKeys: ["commits"] },
	{ key: "messages", label: "Messages", sourceKeys: ["messages"] },
	{ key: "documents", label: "Documents", sourceKeys: ["documents"] },
];

/** The classes an SCM repository mirrors — see {@link SyncResourcesTableProps.expectedClassKeys}. */
export const SCM_CLASS_KEYS: ClassKey[] = [
	"issues",
	"pullRequests",
	"reviews",
	"issueComments",
	"reviewComments",
	"commits",
];

/** The sibling volume above which a class summing to 0 reads as a silently broken pipeline, not an empty one. */
const PIPELINE_BREAK_SIBLING_MIN = 50;

function columnsFor(classKeys: Iterable<ClassKey>): ClassColumn[] {
	const present = new Set(classKeys);
	return CLASS_COLUMNS.filter((column) => column.sourceKeys.some((key) => present.has(key)));
}

function classKeysOf(resources: SyncResourceState[]): ClassKey[] {
	return resources.flatMap((resource) => resource.counts.map((count) => count.key));
}

/** The counts a column reports for one resource, in the column's own class order. */
function membersOf(resource: SyncResourceState, column: ClassColumn): SyncResourceCount[] {
	return column.sourceKeys.flatMap((key) => resource.counts.filter((count) => count.key === key));
}

/** A column's total mirrored rows for one resource — the sum across its source classes. */
function columnTotal(resource: SyncResourceState, column: ClassColumn): number {
	return membersOf(resource, column).reduce((sum, member) => sum + member.count, 0);
}

function absolute(date: Date): string {
	return format(date, "d MMM yyyy, HH:mm:ss");
}

/** The per-class watermarks a resource actually carries — for SCM, only issues and pull requests. */
function watermarksOf(resource: SyncResourceState): { label: string; date: Date }[] {
	return resource.counts.flatMap((count) => {
		const date = asDate(count.lastSyncedAt);
		return date ? [{ label: count.label, date }] : [];
	});
}

/** The classes present in the breakdown that carry no per-class watermark of their own. */
function untrackedLabelsOf(resource: SyncResourceState): string[] {
	return resource.counts.filter((count) => asDate(count.lastSyncedAt) == null).map((c) => c.label);
}

function joinLabels(labels: string[]): string {
	const lower = labels.map((label) => label.toLowerCase());
	if (lower.length <= 1) return lower[0] ?? "";
	return `${lower.slice(0, -1).join(", ")} and ${lower[lower.length - 1]}`;
}

/**
 * Whether a resource's per-class watermarks disagree: at least one class fresh while another is stale.
 *
 * This is the one real per-class failure the counts cannot show — the resource's headline `lastSyncedAt`
 * is the newest of its watermarks, so a repository whose PRs stopped days ago still reads "minutes ago".
 * A marker in the freshness cell restores the fact the rollup hides; the hover names the laggard.
 */
function hasWatermarkDivergence(
	resource: SyncResourceState,
	syncIntervalSeconds: number | undefined,
): boolean {
	if (syncIntervalSeconds == null || syncIntervalSeconds <= 0) return false;
	const tones = watermarksOf(resource).map((w) => freshnessTone(w.date, syncIntervalSeconds));
	const healthy = tones.some((tone) => tone === "fresh");
	const atRisk = tones.some((tone) => tone === "stale" || tone === "veryStale");
	return healthy && atRisk;
}

function isErrorState(resource: SyncResourceState): boolean {
	return resource.state.toUpperCase() === "ERROR" || resource.lastError != null;
}

function isBackfilling(resource: SyncResourceState): boolean {
	return resource.backfillPercent != null && resource.backfillPercent < 100;
}

/** A resource the admin should look at: it errored, or it is behind (or never made) its schedule. */
function isAttention(
	resource: SyncResourceState,
	syncIntervalSeconds: number | undefined,
): boolean {
	if (isErrorState(resource)) return true;
	const tone = freshnessTone(resource.lastSyncedAt, syncIntervalSeconds);
	return tone === "stale" || tone === "veryStale" || tone === "never";
}

/**
 * The triage tier that puts the one broken repository among seventy at the top by default:
 * error → veryStale → stale → never → backfilling → fresh/unknown. A user-clicked column sort overrides.
 */
function triageRank(resource: SyncResourceState, syncIntervalSeconds: number | undefined): number {
	if (isErrorState(resource)) return 0;
	const tone = freshnessTone(resource.lastSyncedAt, syncIntervalSeconds);
	if (tone === "veryStale") return 1;
	if (tone === "stale") return 2;
	if (tone === "never") return 3;
	if (isBackfilling(resource)) return 4;
	return 5;
}

type SortKey = "name" | "lastSynced" | (string & {});
interface SortState {
	key: SortKey;
	dir: "asc" | "desc";
}

function defaultDir(key: SortKey): "asc" | "desc" {
	// Name reads A–Z; the freshness column reads oldest-first, so a manual sort mirrors triage intent;
	// counts read largest-first, where the volume is.
	return key === "name" || key === "lastSynced" ? "asc" : "desc";
}

function compareResources(
	a: SyncResourceState,
	b: SyncResourceState,
	sortState: SortState | null,
	columns: ClassColumn[],
	syncIntervalSeconds: number | undefined,
): number {
	if (!sortState) {
		const rankDelta = triageRank(a, syncIntervalSeconds) - triageRank(b, syncIntervalSeconds);
		return rankDelta !== 0 ? rankDelta : a.name.localeCompare(b.name);
	}
	let delta = 0;
	if (sortState.key === "name") {
		delta = a.name.localeCompare(b.name);
	} else if (sortState.key === "lastSynced") {
		// A never-synced resource is the oldest reading there is, so it sorts first ascending.
		const da = asDate(a.lastSyncedAt)?.getTime() ?? Number.NEGATIVE_INFINITY;
		const db = asDate(b.lastSyncedAt)?.getTime() ?? Number.NEGATIVE_INFINITY;
		delta = da - db;
	} else {
		const column = columns.find((c) => c.key === sortState.key);
		delta = column ? columnTotal(a, column) - columnTotal(b, column) : 0;
	}
	if (delta === 0) delta = a.name.localeCompare(b.name);
	return sortState.dir === "asc" ? delta : -delta;
}

/**
 * A sortable column header. Three clicks cycle a column: default direction, its opposite, then back to
 * the triage default. The `aria-sort` on the cell keeps the state legible to assistive tech.
 */
function SortableHeadCell({
	label,
	sortKey,
	align = "left",
	className,
	sortState,
	onSort,
}: {
	label: string;
	sortKey: SortKey;
	align?: "left" | "right";
	className?: string;
	sortState: SortState | null;
	onSort?: (key: SortKey) => void;
}) {
	const active = sortState?.key === sortKey;
	const ariaSort = active ? (sortState.dir === "asc" ? "ascending" : "descending") : "none";

	if (!onSort) {
		return (
			<TableHead className={cn("capitalize", align === "right" && "text-right", className)}>
				{label}
			</TableHead>
		);
	}

	return (
		<TableHead aria-sort={ariaSort} className={cn(align === "right" && "text-right", className)}>
			<SortButton
				sorted={active ? sortState.dir : false}
				onToggle={() => onSort(sortKey)}
				reverse={align === "right"}
			>
				<span className="capitalize">{label}</span>
			</SortButton>
		</TableHead>
	);
}

function ResourcesTableHeader({
	columns,
	resourceNoun,
	sortState = null,
	onSort,
}: {
	columns: ClassColumn[];
	resourceNoun: string;
	sortState?: SortState | null;
	onSort?: (key: SortKey) => void;
}) {
	return (
		<TableHeader className="sticky top-0 z-10 bg-card">
			<TableRow>
				<SortableHeadCell
					label={resourceNoun}
					sortKey="name"
					sortState={sortState}
					onSort={onSort}
				/>
				<SortableHeadCell
					label="Last synced"
					sortKey="lastSynced"
					sortState={sortState}
					onSort={onSort}
				/>
				{columns.map((column) => (
					<SortableHeadCell
						key={column.key}
						label={column.label}
						sortKey={column.key}
						align="right"
						sortState={sortState}
						onSort={onSort}
					/>
				))}
				<TableHead className="w-0 text-right">
					<span className="sr-only">Error</span>
				</TableHead>
			</TableRow>
		</TableHeader>
	);
}

/**
 * Name, external id and — while one is running — the backfill.
 *
 * SCM providers set `name` and `externalId` to the same `nameWithOwner`, so the secondary line and the
 * hover's mono line are printed only when they genuinely differ (Slack's `#channel` vs its id, Outline's
 * title vs its collection id). The id is never lost — the hover still carries it — only not repeated.
 */
function ResourceNameCell({
	resource,
	resourceNoun,
}: {
	resource: SyncResourceState;
	resourceNoun: string;
}) {
	const percent = resource.backfillPercent;
	const isBackfillingRow = percent != null && percent < 100;
	const completedThrough = asDate(resource.backfillCompletedThrough);
	const showExternalId = resource.externalId !== resource.name;

	return (
		<TableCell>
			<HoverCard>
				<HoverCardTrigger
					render={
						<button
							type="button"
							className="block min-w-0 cursor-help rounded-sm text-left outline-none focus-visible:ring-[3px] focus-visible:ring-ring/50"
						/>
					}
				>
					<span className="flex items-center gap-1.5">
						<StatusDot state={resource.state} />
						<span className="max-w-[26ch] truncate font-medium">{resource.name}</span>
					</span>
					{showExternalId && (
						<span className="block max-w-[26ch] truncate text-muted-foreground font-mono text-xs">
							{resource.externalId}
						</span>
					)}
				</HoverCardTrigger>
				<HoverCardContent className="w-80 space-y-1.5">
					<p className="font-medium wrap-anywhere">{resource.name}</p>
					{showExternalId && (
						<p className="text-muted-foreground font-mono text-xs wrap-anywhere">
							{resource.externalId}
						</p>
					)}
					<dl className="grid grid-cols-[7rem_1fr] gap-x-3 gap-y-1 text-muted-foreground">
						<dt>State</dt>
						<dd className="text-foreground">{stateLabel(resource.state)}</dd>
						{resource.itemCount != null && (
							<>
								<dt>Items</dt>
								<dd className="text-foreground tabular-nums">
									{resource.itemCount.toLocaleString()}
								</dd>
							</>
						)}
						{resource.upstreamCount != null && (
							<>
								<dt>Upstream</dt>
								<dd className="text-foreground tabular-nums">
									{resource.upstreamCount.toLocaleString()} items
								</dd>
							</>
						)}
						{completedThrough && (
							<>
								<dt>Backfilled to</dt>
								<dd className="text-foreground tabular-nums">{absolute(completedThrough)}</dd>
							</>
						)}
					</dl>
					{!completedThrough && !isBackfillingRow && (
						<p className="text-muted-foreground text-xs">
							No backfill has run for this {resourceNoun}.
						</p>
					)}
				</HoverCardContent>
			</HoverCard>
			{isBackfillingRow && (
				<div className="mt-1 flex items-center gap-2">
					<Progress
						value={percent}
						className="h-1 w-24"
						aria-label={`Backfill progress for ${resource.name}`}
					/>
					<span className="text-muted-foreground text-xs tabular-nums">
						Backfilling · {percent}%
					</span>
				</div>
			)}
		</TableCell>
	);
}

/**
 * The row's status marker. Only the states that qualify the row's numbers get a mark; the word itself
 * stays available to a screen reader, which cannot see a coloured dot.
 */
function StatusDot({ state }: { state: string }) {
	const normalized = state.toUpperCase();
	const tone =
		normalized === "ERROR"
			? "bg-destructive"
			: normalized === "PENDING"
				? "bg-muted-foreground"
				: undefined;
	if (!tone) return null;
	return (
		<>
			<span className={cn("size-1.5 shrink-0 rounded-full", tone)} aria-hidden />
			<span className="sr-only">{stateLabel(state)}</span>
		</>
	);
}

/**
 * The one freshness column: the resource's `lastSyncedAt`, toned against the connection's cadence.
 *
 * The hover carries the per-class watermarks that actually exist (issues, pull requests) and names the
 * classes that ride along with the same pass and keep none of their own — so a missing per-class time
 * never reads as "this class stopped". When the two tracked watermarks disagree, a marker warns that the
 * headline reading is newer than one of the classes behind it.
 */
function LastSyncedCell({
	resource,
	resourceNoun,
	syncIntervalSeconds,
}: {
	resource: SyncResourceState;
	resourceNoun: string;
	syncIntervalSeconds?: number;
}) {
	const tone = freshnessTone(resource.lastSyncedAt, syncIntervalSeconds);
	const watermarks = watermarksOf(resource);
	const untracked = untrackedLabelsOf(resource);
	const divergent = hasWatermarkDivergence(resource, syncIntervalSeconds);

	return (
		<TableCell>
			<HoverCard>
				<HoverCardTrigger
					render={
						<button
							type="button"
							className="inline-flex cursor-help items-center gap-1 rounded-sm text-xs outline-none focus-visible:ring-[3px] focus-visible:ring-ring/50"
						/>
					}
				>
					<RelativeTime
						value={resource.lastSyncedAt}
						tone={tone}
						tooltip={false}
						fallback="Never"
					/>
					{divergent && (
						<TriangleAlertIcon
							className="size-3.5 text-warning"
							aria-label="A tracked class is further behind than this reading"
						/>
					)}
				</HoverCardTrigger>
				<HoverCardContent className="w-80 space-y-1.5">
					<p className="font-medium">Last synced</p>
					{watermarks.length === 0 ? (
						<p className="text-muted-foreground">This {resourceNoun} has not synced yet.</p>
					) : (
						<dl className="grid grid-cols-[1fr_auto] gap-x-4 gap-y-1">
							{watermarks.map((watermark) => (
								<Fragment key={watermark.label}>
									<dt className="text-muted-foreground">{watermark.label}</dt>
									<dd className="text-foreground tabular-nums">{absolute(watermark.date)}</dd>
								</Fragment>
							))}
						</dl>
					)}
					{untracked.length > 0 && (
						<p className="text-muted-foreground text-xs">
							No separate watermark is kept for {joinLabels(untracked)} — they are written by the
							same sync pass.
						</p>
					)}
				</HoverCardContent>
			</HoverCard>
		</TableCell>
	);
}

/**
 * One class's count for one resource. A judgement earns colour, but a bare count does not — zero is
 * broken on some repositories and normal on others — so a zero (or an absent class) is a faint dot that
 * lets the real volumes pop rather than a tinted number. The Comments column spells its issue/review
 * split out in a tooltip.
 */
function ClassCountCell({
	resource,
	column,
}: {
	resource: SyncResourceState;
	column: ClassColumn;
}) {
	const members = membersOf(resource, column);
	const total = members.reduce((sum, member) => sum + member.count, 0);

	if (total === 0) {
		return (
			<TableCell className="text-right">
				<span className="text-muted-foreground/50" aria-hidden>
					·
				</span>
				<span className="sr-only">0 {column.label}</span>
			</TableCell>
		);
	}

	if (members.length > 1) {
		return (
			<TableCell className="text-right">
				<Tooltip>
					<TooltipTrigger className="cursor-help tabular-nums">
						{total.toLocaleString()}
					</TooltipTrigger>
					<TooltipContent>
						<span className="tabular-nums">
							{members
								.map((member) => `${member.count.toLocaleString()} ${member.label.toLowerCase()}`)
								.join(" · ")}
						</span>
					</TooltipContent>
				</Tooltip>
			</TableCell>
		);
	}

	return <TableCell className="text-right tabular-nums">{total.toLocaleString()}</TableCell>;
}

/** The last error, as a read-only peek: it reveals, it does not act, and it traps no focus. */
function ResourceErrorCell({ resource }: { resource: SyncResourceState }) {
	return (
		<TableCell className="text-right">
			{resource.lastError && (
				<HoverCard>
					<HoverCardTrigger
						render={
							<button
								type="button"
								aria-label={`Error for ${resource.name}`}
								className="inline-flex cursor-help rounded-sm outline-none focus-visible:ring-[3px] focus-visible:ring-ring/50"
							/>
						}
					>
						<AlertCircleIcon className="size-4 text-destructive" aria-hidden />
					</HoverCardTrigger>
					<HoverCardContent className="w-80 wrap-anywhere">{resource.lastError}</HoverCardContent>
				</HoverCard>
			)}
		</TableCell>
	);
}

/**
 * The fleet ledger row: per-class sums across every resource. A class summing to 0 while a sibling has
 * real volume is the silent-broken-pipeline signal, flagged here so it reads at a glance rather than
 * only per row. Disabled for single-class integrations, which have no sibling to compare against.
 */
function TotalsFooter({
	resources,
	columns,
	resourceNoun,
	resourceNounPlural,
}: {
	resources: SyncResourceState[];
	columns: ClassColumn[];
	resourceNoun: string;
	resourceNounPlural: string;
}) {
	const sums = columns.map((column) => ({
		column,
		sum: resources.reduce((total, resource) => total + columnTotal(resource, column), 0),
	}));
	const canWarn = columns.length > 1;

	// Solid `bg-muted` (not `bg-card`): keeps the shadcn footer's distinct muted tint so the totals read
	// as a summary rather than one more data row, while staying opaque enough that scrolling rows don't
	// bleed through the sticky footer (the `bg-muted/50` default would). `border-t-2` sets it apart.
	return (
		<TableFooter className="sticky bottom-0 z-10 border-t-2 bg-muted">
			<TableRow className="hover:bg-transparent">
				<TableCell className="font-medium capitalize">All {resourceNounPlural}</TableCell>
				<TableCell />
				{sums.map(({ column, sum }) => {
					const broken =
						canWarn &&
						sum === 0 &&
						sums.some(
							(other) => other.column.key !== column.key && other.sum >= PIPELINE_BREAK_SIBLING_MIN,
						);
					if (broken) {
						return (
							<TableCell key={column.key} className="text-right text-warning">
								<Tooltip>
									<TooltipTrigger className="inline-flex cursor-help items-center gap-1">
										<TriangleAlertIcon className="size-3.5" aria-hidden />
										<span className="tabular-nums">0</span>
									</TooltipTrigger>
									<TooltipContent>
										No {column.label.toLowerCase()} mirrored in any {resourceNoun} — the{" "}
										{column.label.toLowerCase()} pipeline may not be running.
									</TooltipContent>
								</Tooltip>
							</TableCell>
						);
					}
					return (
						<TableCell key={column.key} className="text-right tabular-nums">
							{sum.toLocaleString()}
						</TableCell>
					);
				})}
				<TableCell className="w-0" />
			</TableRow>
		</TableFooter>
	);
}

export interface SyncResourcesTableProps {
	resources: SyncResourceState[];
	isLoading?: boolean;
	isError?: boolean;
	error?: unknown;
	onRetry?: () => void;
	resourceNoun: string;
	resourceNounPlural: string;
	/**
	 * The connection's reconciliation cadence, from `ConnectionSyncStatus.syncIntervalSeconds`. Omit it
	 * and freshness is printed but never judged — an age is only stale relative to a schedule, and the
	 * server sends `null` rather than guess one for an irregular cron.
	 */
	syncIntervalSeconds?: number;
	/**
	 * The classes this connection mirrors, as the caller knows them. The columns are the union of this
	 * and what the data reports, so a header that promises five columns while the rows are skeletons does
	 * not resolve into a different five, and a connection whose resources have all never synced still
	 * shows its class columns rather than collapsing to nothing.
	 */
	expectedClassKeys?: ClassKey[];
}

/**
 * The per-resource sync ledger: which repositories are current, how much of each class they mirror, and
 * — the failure this surface exists to catch — the fleet-level zero where one pipeline quietly stopped.
 *
 * Counts are the columns because counts are the abundant, real fact (all six SCM classes report a real
 * number, where only issues and pull requests carry a watermark). "0 comments next to 3,410 issues" is
 * the genuine silent-broken signal, and it reads across the whole fleet at once — triaged so the one
 * broken repository among seventy is row one, searchable, faceted by what needs attention, and totalled.
 */
export function SyncResourcesTable({
	resources,
	isLoading = false,
	isError = false,
	error,
	onRetry,
	resourceNoun,
	resourceNounPlural,
	syncIntervalSeconds,
	expectedClassKeys,
}: SyncResourcesTableProps) {
	const [query, setQuery] = useState("");
	const [facet, setFacet] = useState<"all" | "attention" | "fresh">("all");
	const [sortState, setSortState] = useState<SortState | null>(null);

	if (isError) {
		return (
			<QueryErrorAlert
				error={error}
				title={`We couldn't load the ${resourceNoun} sync state`}
				onRetry={onRetry}
			/>
		);
	}

	if (isLoading) {
		const columns = columnsFor(expectedClassKeys ?? []);
		return (
			<Table>
				<ResourcesTableHeader columns={columns} resourceNoun={resourceNoun} />
				<TableRowsSkeleton
					columns={["w-40", "w-24", ...columns.map(() => "w-12"), null]}
					rows={5}
				/>
			</Table>
		);
	}

	if (resources.length === 0) {
		return (
			<Empty className="border border-dashed">
				<EmptyHeader>
					<EmptyMedia variant="icon">
						<DatabaseIcon />
					</EmptyMedia>
					<EmptyTitle>No {resourceNounPlural} synced yet</EmptyTitle>
					<EmptyDescription>
						Synced {resourceNounPlural} and their per-class counts appear here once a sync job runs.
					</EmptyDescription>
				</EmptyHeader>
			</Empty>
		);
	}

	const columns = columnsFor([...(expectedClassKeys ?? []), ...classKeysOf(resources)]);
	const colSpan = columns.length + 3;

	const attentionCount = resources.filter((r) => isAttention(r, syncIntervalSeconds)).length;
	const freshCount = resources.length - attentionCount;
	const totalItems = resources.reduce((sum, r) => sum + (r.itemCount ?? 0), 0);

	// The facet ToggleGroup only mounts while there is something to attend to, but `facet` state is
	// independent: a sync that clears the last attention row unmounts the toggle without resetting
	// `facet`, which would otherwise collapse the table to "no match". Fall back to "all" whenever the
	// toggle is not shown.
	const effectiveFacet = attentionCount > 0 ? facet : "all";

	const normalizedQuery = query.trim().toLowerCase();
	const searched = normalizedQuery
		? resources.filter(
				(r) =>
					r.name.toLowerCase().includes(normalizedQuery) ||
					r.externalId.toLowerCase().includes(normalizedQuery),
			)
		: resources;
	const faceted =
		effectiveFacet === "attention"
			? searched.filter((r) => isAttention(r, syncIntervalSeconds))
			: effectiveFacet === "fresh"
				? searched.filter((r) => !isAttention(r, syncIntervalSeconds))
				: searched;
	const visible = [...faceted].sort((a, b) =>
		compareResources(a, b, sortState, columns, syncIntervalSeconds),
	);

	const clearFilters = () => {
		setQuery("");
		setFacet("all");
	};

	const onSort = (key: SortKey) => {
		setSortState((prev) => {
			if (!prev || prev.key !== key) return { key, dir: defaultDir(key) };
			if (prev.dir === defaultDir(key)) return { key, dir: prev.dir === "asc" ? "desc" : "asc" };
			return null;
		});
	};

	return (
		<div className="space-y-3">
			<div className="flex flex-wrap items-center gap-3">
				<div className="relative min-w-56 max-w-xs flex-1">
					<SearchIcon
						className="pointer-events-none absolute top-1/2 left-2.5 size-4 -translate-y-1/2 text-muted-foreground"
						aria-hidden
					/>
					<Input
						type="search"
						value={query}
						onChange={(event) => setQuery(event.target.value)}
						placeholder={`Search ${resourceNounPlural}…`}
						aria-label={`Search ${resourceNounPlural}`}
						className="pl-8"
					/>
				</div>

				{attentionCount > 0 && (
					<ToggleGroup
						value={[facet]}
						onValueChange={(value) => {
							const next = value.length > 0 ? value[value.length - 1] : "all";
							setFacet(next === "attention" || next === "fresh" ? next : "all");
						}}
						variant="outline"
						size="sm"
						role="toolbar"
						aria-label={`Filter ${resourceNounPlural} by status`}
					>
						<ToggleGroupItem value="all">All</ToggleGroupItem>
						<ToggleGroupItem value="attention">Attention ({attentionCount})</ToggleGroupItem>
						<ToggleGroupItem value="fresh">Fresh ({freshCount})</ToggleGroupItem>
					</ToggleGroup>
				)}

				{/* Also the filter's live region: it already carries the `n of N` fact while filtering, so it
				    announces the result to assistive tech without a second, duplicated node to keep in sync. */}
				<p
					role="status"
					aria-live="polite"
					className="ml-auto text-muted-foreground text-sm tabular-nums"
				>
					{normalizedQuery ? (
						<>
							{visible.length} of {resources.length} {resourceNounPlural}
						</>
					) : (
						<>
							{resources.length} {resourceNounPlural} · {totalItems.toLocaleString()} items
						</>
					)}
				</p>
			</div>

			{/* The cap and vertical scroll go on the table's own scroll container (see Table's
			    containerClassName), so the sticky header and totals footer clip and stick against it. */}
			<Table containerClassName="max-h-[70vh] overflow-y-auto rounded-md border">
				<ResourcesTableHeader
					columns={columns}
					resourceNoun={resourceNoun}
					sortState={sortState}
					onSort={onSort}
				/>
				<TableBody>
					{visible.length === 0 ? (
						<TableRow className="hover:bg-transparent">
							<TableCell colSpan={colSpan} className="h-24 text-center text-muted-foreground">
								{normalizedQuery ? (
									<>
										No {resourceNounPlural} match “{query.trim()}”.
									</>
								) : (
									<>No {resourceNounPlural} match the current filter.</>
								)}{" "}
								<Button variant="ghost" size="sm" onClick={clearFilters}>
									Clear filter
								</Button>
							</TableCell>
						</TableRow>
					) : (
						visible.map((resource) => (
							<TableRow key={resource.id}>
								<ResourceNameCell resource={resource} resourceNoun={resourceNoun} />
								<LastSyncedCell
									resource={resource}
									resourceNoun={resourceNoun}
									syncIntervalSeconds={syncIntervalSeconds}
								/>
								{columns.map((column) => (
									<ClassCountCell key={column.key} resource={resource} column={column} />
								))}
								<ResourceErrorCell resource={resource} />
							</TableRow>
						))
					)}
				</TableBody>
				{resources.length > 1 && (
					<TotalsFooter
						resources={resources}
						columns={columns}
						resourceNoun={resourceNoun}
						resourceNounPlural={resourceNounPlural}
					/>
				)}
			</Table>
		</div>
	);
}
