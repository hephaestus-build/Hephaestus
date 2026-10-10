import {
	createColumnHelper,
	FlexRender,
	functionalUpdate,
	type SortingState,
	useTable,
} from "@tanstack/react-table";
import { ChevronDown, ChevronRight, CircleDollarSign, Info, Search } from "lucide-react";
// oxlint-disable-next-line no-restricted-imports -- TanStack Table keys its caches on identity, which the compiler memoises as an optimisation rather than promises; each `useMemo` below says what breaks without it.
import { type ReactNode, useMemo } from "react";

import { cn } from "cn";
import type { AdminWorkspaceLlmUsage, WorkspaceLlmUsageReport } from "@/api/types.gen";
import { TableRowsSkeleton } from "@/components/admin/integrations/TableRowsSkeleton";
import { type DataTableFeatures, dataTableFeatures } from "@/components/common/data-table";
import { DataTableHeader } from "@/components/common/DataTableHeader";
import { FilterToolbar } from "@/components/common/FilterToolbar";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { StatusBadge } from "@/components/common/StatusBadge";
import { TablePagination } from "@/components/common/TablePagination";
import { Section } from "@/components/layout/Section";
import { CAP_STATE_DEFS, type CapState } from "@/components/practice-vocabulary/cap-state-defs";
import type { Purse } from "@/components/practice-vocabulary/purse-defs";
import { Button } from "@/components/ui/button";
import {
	Empty,
	EmptyDescription,
	EmptyHeader,
	EmptyMedia,
	EmptyTitle,
} from "@/components/ui/empty";
import { InputGroup, InputGroupAddon, InputGroupInput } from "@/components/ui/input-group";
import { Label } from "@/components/ui/label";
import { Table, TableBody, TableCaption, TableCell, TableRow } from "@/components/ui/table";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { formatCapUsd, formatCostUsd } from "@/lib/money";

import { BudgetPaceAlert } from "./BudgetPaceAlert";
import { CapIsNotMonthScoped } from "./CapIsNotMonthScoped";
import { CapMeter, capState } from "./CapMeter";
import { type Fx, FxDisclosure, FxSpendLine, spendConversion } from "./fx";
import { LlmUsageByDayTable, LlmUsageByJobTypeTable } from "./LlmUsageBreakdownTables";
import { LlmUsageByPracticeTable } from "./LlmUsageByPracticeTable";
import { MoneyCell } from "./MoneyCell";
import { PurseHeading, PurseLeaf } from "./PurseHeading";
import {
	budgetUsedPercent,
	FIRST_COLUMN,
	precomputeByPracticeDescription,
	projectBudget,
	purseCap,
	pursesOf,
} from "./usage-utils";

/** The columns the workspaces table sorts by, as the address names them. */
export const INSTANCE_USAGE_SORTS = [
	"workspace",
	"sharedSpend",
	"ownProviderSpend",
	"runs",
] as const;
export type InstanceUsageSort = (typeof INSTANCE_USAGE_SORTS)[number];

/** What the reader chose to see of the workspaces table: the route keeps it in the address. */
export interface AdminInstanceUsageView {
	/** Matched against workspace names. */
	q: string;
	sort: InstanceUsageSort;
	desc: boolean;
	/** Zero-based. */
	page: number;
}

export const INSTANCE_USAGE_PAGE_SIZE = 25;

/** The longest search the address keeps, so the field never takes more than that. */
export const INSTANCE_USAGE_SEARCH_MAX_LENGTH = 200;

export interface AdminInstanceLlmUsageTableProps {
	rows: AdminWorkspaceLlmUsage[];
	/** ISO `yyyy-MM`. */
	month: string;
	/** The instant the projection is measured against. */
	now: Date;
	fx?: Fx;
	/** UTC. The verdicts read *current* caps, so only the current month can show a real pause. */
	isCurrentMonth: boolean;
	isLoading: boolean;
	error: unknown;
	onRetry?: () => void;
	view: AdminInstanceUsageView;
	onViewChange: (patch: Partial<AdminInstanceUsageView>) => void;
	expandedWorkspaceSlug: string | null;
	detailReport?: WorkspaceLlmUsageReport;
	isDetailLoading: boolean;
	detailError: unknown;
	onRetryDetail?: () => void;
	onToggleDetails: (workspace: AdminWorkspaceLlmUsage) => void;
	onEditSharedModelBudget: (workspace: AdminWorkspaceLlmUsage) => void;
}

function detailPanelId(workspaceSlug: string): string {
	return `workspace-usage-details-${workspaceSlug}`;
}

/**
 * One money stream (shared models or the workspace's provider) measured against its own cap. The two
 * streams are different people's money and are never summed.
 */
interface CapUsage {
	cap?: number;
	spend: number;
	/** Share of the cap consumed. Can exceed 100. */
	percent?: number;
	paused: boolean;
	/** Worst first; empty for a cap with room left and every run priced. */
	states: CapState[];
}

function capUsage(row: AdminWorkspaceLlmUsage, purse: Purse, isCurrentMonth: boolean): CapUsage {
	const { capUsd: cap, spendUsd: spend, verdict, ...live } = purseCap(row, purse);
	const paused = isCurrentMonth && live.paused;
	const percent = budgetUsedPercent(spend, cap);
	const state = capState(percent, paused, isCurrentMonth);
	const unpriced = isCurrentMonth && verdict === "UNVERIFIABLE";
	return {
		cap,
		spend,
		percent,
		paused,
		states: [...(state == null ? [] : [state]), ...(unpriced ? ["UNPRICED" as const] : [])],
	};
}

const columnHelper = createColumnHelper<DataTableFeatures, AdminWorkspaceLlmUsage>();

const CAP_HELP: Record<Purse, { header: string; help: string; meter: string }> = {
	SHARED: {
		header: "Budget",
		help: "The monthly cap you set on the spend you pay for.",
		meter: "Shared-model budget",
	},
	OWN_PROVIDER: {
		header: "Cap",
		help: "The workspace’s own money. Only its admins can change this.",
		meter: "Provider cap",
	},
};

/** The view's sort, or the default where the column it names is not on screen, such as a stale address. */
function shownSort(
	sort: InstanceUsageSort,
	desc: boolean,
	showsOwnProvider: boolean,
): SortingState {
	return sort === "ownProviderSpend" && !showsOwnProvider
		? [{ id: "sharedSpend", desc: true }]
		: [{ id: sort, desc }];
}

/** The provider cap is read-only here by design: it is the workspace's own money. */
export function AdminInstanceLlmUsageTable({
	rows,
	month,
	now,
	fx,
	isCurrentMonth,
	isLoading,
	error,
	onRetry,
	view,
	onViewChange,
	expandedWorkspaceSlug,
	detailReport,
	isDetailLoading,
	detailError,
	onRetryDetail,
	onToggleDetails,
	onEditSharedModelBudget,
}: AdminInstanceLlmUsageTableProps) {
	const purses = pursesOf({ ownProviderInUse: rows.some((row) => row.ownProviderInUse) });
	const showsOwnProvider = purses.includes("OWN_PROVIDER");
	// Identity matters as much as the value: `useTable` republishes controlled state on every render,
	// so a fresh array here writes the sorting atom every time the parent re-renders.
	const sorting = useMemo(
		() => shownSort(view.sort, view.desc, showsOwnProvider),
		[view.sort, view.desc, showsOwnProvider],
	);
	// The table's `data`, keyed the same way `columns` is: the core row model is cached against this
	// array's identity. Name order underneath, so workspaces that tie on the sorted column read
	// alphabetically.
	const data = useMemo(
		() => [...rows].sort((a, b) => a.displayName.localeCompare(b.displayName)),
		[rows],
	);

	// TanStack Table caches its column model, and every row model derived from it, against this
	// array's identity.
	const columns = useMemo(
		() =>
			columnHelper.columns([
				columnHelper.accessor("displayName", {
					id: "workspace",
					header: "Workspace",
					cell: ({ row }) => (
						<>
							<div className="font-medium">{row.original.displayName}</div>
							<div className="font-mono text-xs text-muted-foreground">
								{row.original.workspaceSlug}
							</div>
						</>
					),
					meta: { className: cn(FIRST_COLUMN, "w-full") },
				}),
				...purses.map((purse) =>
					columnHelper.group({
						id: purse,
						header: () => <PurseHeading purse={purse} />,
						columns: columnHelper.columns([
							columnHelper.accessor(
								(row) =>
									purse === "SHARED" ? row.instanceTotalCostUsd : row.ownProviderTotalCostUsd,
								{
									id: purse === "SHARED" ? "sharedSpend" : "ownProviderSpend",
									header: () => <PurseLeaf purse={purse}>Spend</PurseLeaf>,
									cell: ({ getValue }) => (
										<>
											<MoneyCell>{formatCostUsd(getValue())}</MoneyCell>
											<FxSpendLine usd={getValue()} fx={fx} />
										</>
									),
									enableGlobalFilter: false,
									sortDescFirst: true,
									meta: { numeric: true },
								},
							),
							columnHelper.display({
								id: purse === "SHARED" ? "sharedCap" : "ownProviderCap",
								header: () => (
									<HelpHeader help={CAP_HELP[purse].help}>
										<PurseLeaf purse={purse}>{CAP_HELP[purse].header}</PurseLeaf>
									</HelpHeader>
								),
								cell: ({ row }) => (
									<CapCell
										purse={purse}
										usage={capUsage(row.original, purse, isCurrentMonth)}
										label={CAP_HELP[purse].meter}
										workspace={row.original.displayName}
									/>
								),
								meta: { numeric: true },
							}),
						]),
					}),
				),
				columnHelper.accessor("events", {
					id: "runs",
					header: "Runs",
					cell: ({ getValue }) => getValue().toLocaleString(),
					enableGlobalFilter: false,
					sortDescFirst: true,
					meta: { numeric: true },
				}),
				columnHelper.display({
					id: "actions",
					header: () => <span className="sr-only">Actions</span>,
					cell: ({ row }) => (
						<RowActions
							workspace={row.original}
							isExpanded={expandedWorkspaceSlug === row.original.workspaceSlug}
							isCurrentMonth={isCurrentMonth}
							onToggleDetails={onToggleDetails}
							onEditSharedModelBudget={onEditSharedModelBudget}
						/>
					),
				}),
			]),
		[purses, fx, isCurrentMonth, expandedWorkspaceSlug, onToggleDetails, onEditSharedModelBudget],
	);

	// Pages are cut from the filtered and sorted rows here rather than by the table, so a page past
	// the last one, typed or left by a narrower filter, reads as the last page without rewriting the
	// address.
	const table = useTable({
		features: dataTableFeatures,
		data,
		columns,
		onSortingChange: (updater) => {
			const [next] = functionalUpdate(updater, sorting);
			const sort = INSTANCE_USAGE_SORTS.find((id) => id === next?.id) ?? "sharedSpend";
			onViewChange({ sort, desc: next?.desc ?? true, page: 0 });
		},
		onGlobalFilterChange: (updater) => {
			// The library types `globalFilter` as `any`, so the value is narrowed on the way back out.
			const next: unknown = functionalUpdate(updater, view.q.trim());
			onViewChange({ q: typeof next === "string" ? next : "", page: 0 });
		},
		state: { sorting, globalFilter: view.q.trim() },
	});
	const matching = table.getPrePaginatedRowModel().rows;
	const pageCount = Math.max(1, Math.ceil(matching.length / INSTANCE_USAGE_PAGE_SIZE));
	const page = Math.min(view.page, pageCount - 1);

	if (error != null) {
		return <QueryErrorAlert error={error} title="We could not load AI usage" onRetry={onRetry} />;
	}
	if (rows.length === 0 && !isLoading) {
		return (
			<Empty variant="outlined">
				<EmptyHeader>
					<EmptyMedia variant="icon">
						<CircleDollarSign />
					</EmptyMedia>
					<EmptyTitle>No workspaces on this instance yet</EmptyTitle>
					<EmptyDescription>Usage appears here after you create a workspace.</EmptyDescription>
				</EmptyHeader>
			</Empty>
		);
	}

	// Detail lives *beside* the table, not in a `colSpan` row: nested, its breakdown tables would open
	// a horizontal scroller inside the table's — two-dimensional scrolling (WCAG 2.2 SC 1.4.10).
	const expandedRow = rows.find((row) => row.workspaceSlug === expandedWorkspaceSlug);
	const hasConversion = rows.some(
		(row) =>
			spendConversion(row.instanceTotalCostUsd, fx) != null ||
			spendConversion(row.ownProviderTotalCostUsd, fx) != null,
	);
	const visibleRows = matching.slice(
		page * INSTANCE_USAGE_PAGE_SIZE,
		(page + 1) * INSTANCE_USAGE_PAGE_SIZE,
	);
	const hasFilter = view.q.trim() !== "";

	return (
		<div className="space-y-8">
			<div className="space-y-4">
				<FilterToolbar hasFilter={hasFilter} onReset={() => onViewChange({ q: "", page: 0 })}>
					<InputGroup className="w-full sm:w-72">
						<Label htmlFor="instance-usage-search" className="sr-only">
							Search workspaces
						</Label>
						<InputGroupAddon>
							<Search aria-hidden />
						</InputGroupAddon>
						<InputGroupInput
							id="instance-usage-search"
							type="search"
							placeholder="Search by name…"
							maxLength={INSTANCE_USAGE_SEARCH_MAX_LENGTH}
							value={view.q}
							onChange={(event) => table.setGlobalFilter(event.target.value)}
						/>
					</InputGroup>
				</FilterToolbar>
				{!isCurrentMonth && <CapIsNotMonthScoped subject="budget" />}
				<Table bordered>
					<TableCaption className="sr-only">
						Per-workspace AI spend for the selected month
					</TableCaption>
					<DataTableHeader table={table} />
					{isLoading ? (
						<TableRowsSkeleton
							rows={5}
							columns={table.getVisibleLeafColumns().map((column) => ({
								width: column.id === "actions" ? null : "w-16",
								numeric: column.columnDef.meta?.numeric,
								className: column.columnDef.meta?.className,
							}))}
						/>
					) : (
						<TableBody>
							{visibleRows.length === 0 && (
								<TableRow variant="static">
									<TableCell
										colSpan={table.getVisibleLeafColumns().length}
										className="h-24 text-center text-muted-foreground"
									>
										No workspaces match your search
									</TableCell>
								</TableRow>
							)}
							{visibleRows.map((row) => (
								<TableRow key={row.id} variant="static">
									{row.getVisibleCells().map((cell) => {
										const { meta } = cell.column.columnDef;
										return (
											<TableCell
												key={cell.id}
												numeric={meta?.numeric}
												className={cn(meta?.numeric === true && "text-right", meta?.className)}
											>
												<FlexRender cell={cell} />
											</TableCell>
										);
									})}
								</TableRow>
							))}
						</TableBody>
					)}
				</Table>
				<TablePagination
					page={page}
					totalPages={pageCount}
					onPageChange={(next) => onViewChange({ page: next })}
				/>
			</div>

			{expandedRow != null && (
				<WorkspaceUsageDetails
					workspace={expandedRow}
					report={detailReport}
					isLoading={isDetailLoading}
					error={detailError}
					onRetry={onRetryDetail}
					fx={fx}
					month={month}
					now={now}
					isCurrentMonth={isCurrentMonth}
				/>
			)}

			{hasConversion && <FxDisclosure fx={fx} isCurrentMonth={isCurrentMonth} />}
		</div>
	);
}

interface RowActionsProps {
	workspace: AdminWorkspaceLlmUsage;
	isExpanded: boolean;
	isCurrentMonth: boolean;
	onToggleDetails: (workspace: AdminWorkspaceLlmUsage) => void;
	onEditSharedModelBudget: (workspace: AdminWorkspaceLlmUsage) => void;
}

function RowActions({
	workspace,
	isExpanded,
	isCurrentMonth,
	onToggleDetails,
	onEditSharedModelBudget,
}: RowActionsProps) {
	const budgetAction = workspace.instanceMonthlyBudgetUsd == null ? "Set budget" : "Change budget";
	return (
		<div className="flex justify-end gap-2">
			<Button
				variant="outline"
				size="sm"
				aria-expanded={isExpanded}
				// The panel is unmounted while collapsed; a constant IDREF would dangle.
				aria-controls={isExpanded ? detailPanelId(workspace.workspaceSlug) : undefined}
				aria-label={`Details for ${workspace.displayName}`}
				onClick={() => onToggleDetails(workspace)}
			>
				{isExpanded ? <ChevronDown aria-hidden /> : <ChevronRight aria-hidden />}
				Details
			</Button>
			{/* Current month only: a budget is not month-scoped, so editing one from a closed month
			    would quietly change what runs today. */}
			{isCurrentMonth && (
				<Button
					variant="outline"
					size="sm"
					// Must start with the visible label for speech control (WCAG SC 2.5.3).
					aria-label={`${budgetAction} for ${workspace.displayName} (shared models)`}
					onClick={() => onEditSharedModelBudget(workspace)}
				>
					{budgetAction}
				</Button>
			)}
		</div>
	);
}

type CapScope = "shared" | "provider";

interface CapPace {
	scope: CapScope;
	spend: number;
	cap?: number;
	percent: number;
}

function pacesWorthWarningAbout(
	report: WorkspaceLlmUsageReport | undefined,
	isCurrentMonth: boolean,
): CapPace[] {
	if (report == null) {
		return [];
	}
	const streams = (
		[
			["shared", "SHARED"],
			["provider", "OWN_PROVIDER"],
		] as const
	).map(([scope, purse]) => {
		const { spendUsd, capUsd, paused } = purseCap(report, purse);
		return { scope, spend: spendUsd, cap: capUsd, paused };
	});
	return streams.flatMap(({ paused, ...stream }) => {
		const percent = budgetUsedPercent(stream.spend, stream.cap);
		if (percent == null || capState(percent, paused, isCurrentMonth) !== "NEAR") {
			return [];
		}
		return [{ ...stream, percent }];
	});
}

interface WorkspaceUsageDetailsProps {
	workspace: AdminWorkspaceLlmUsage;
	report?: WorkspaceLlmUsageReport;
	isLoading: boolean;
	error: unknown;
	onRetry?: () => void;
	/** The table's own rate, not the detail report's: nothing enforces that the two responses agree. */
	fx: Fx;
	month: string;
	now: Date;
	isCurrentMonth: boolean;
}

/**
 * The same sections as the workspace's own usage page, in the same order, each table stacked at full
 * width: side by side they would each be too narrow to avoid a horizontal scroller of their own,
 * which is two-dimensional scrolling (WCAG 2.2 SC 1.4.10).
 */
function WorkspaceUsageDetails({
	workspace,
	report,
	isLoading,
	error,
	onRetry,
	fx,
	month,
	now,
	isCurrentMonth,
}: WorkspaceUsageDetailsProps) {
	const loaded = isLoading ? undefined : report;
	// The row's own figures, already on screen, so the skeleton has the columns the report will.
	const purses = pursesOf(workspace);
	const paces = pacesWorthWarningAbout(report, isCurrentMonth);
	return (
		<Section
			id={detailPanelId(workspace.workspaceSlug)}
			title={workspace.displayName}
			description="Usage details"
			size="lg"
			className="space-y-6 border-t pt-6"
		>
			{error == null ? (
				<div className="space-y-8">
					{paces.length > 0 && (
						<div className="space-y-4">
							{paces.map((pace) => (
								<BudgetPaceAlert
									key={pace.scope}
									scope={pace.scope}
									subjectName={workspace.displayName}
									percent={pace.percent}
									spendUsd={pace.spend}
									capUsd={pace.cap}
									projection={projectBudget(pace.spend, pace.cap, month, now)}
									fx={fx}
								/>
							))}
						</div>
					)}
					<Section level={3} title="By run type">
						<LlmUsageByJobTypeTable report={loaded} purses={purses} />
					</Section>
					{/* Only once loaded: most workspaces have no precompute calls, and a skeleton that
					    then vanishes moves more than a section that appears among the others. */}
					{loaded != null && loaded.byPractice.length > 0 && (
						<Section
							level={3}
							title="Precompute models by practice"
							description={precomputeByPracticeDescription(isCurrentMonth)}
						>
							<LlmUsageByPracticeTable report={loaded} purses={purses} />
						</Section>
					)}
					<Section level={3} title="By day">
						<LlmUsageByDayTable report={loaded} purses={purses} />
					</Section>
				</div>
			) : (
				<QueryErrorAlert
					error={error}
					title={`We could not load usage details for ${workspace.displayName}`}
					onRetry={onRetry}
				/>
			)}
		</Section>
	);
}

interface HelpHeaderProps {
	children: ReactNode;
	help: string;
}

/** `min-h-6` is SC 2.5.8's 24 px minimum target — a header line box alone leaves the trigger short. */
function HelpHeader({ children, help }: HelpHeaderProps) {
	return (
		<Tooltip>
			<TooltipTrigger className="inline-flex min-h-6 cursor-help items-center gap-1 font-medium">
				{children}
				<Info className="size-3" aria-hidden />
			</TooltipTrigger>
			<TooltipContent>{help}</TooltipContent>
		</Tooltip>
	);
}

interface CapCellProps {
	purse: Purse;
	usage: CapUsage;
	label: string;
	workspace: string;
}

/**
 * With no cap, a purse can still have calls with no price, and that state is not left out. The cell
 * is at least as wide as a meter needs and grows to its longest badge, so no state is cut short.
 */
function CapCell({ purse, usage, label, workspace }: CapCellProps) {
	const percent = usage.percent ?? 0;
	return (
		<div className="ml-auto flex min-w-28 flex-col items-end gap-1">
			{usage.cap == null ? (
				<span className="text-muted-foreground">—</span>
			) : (
				<>
					<MoneyCell>{formatCapUsd(usage.cap)}</MoneyCell>
					<CapMeter
						percent={percent}
						paused={usage.paused}
						spendUsd={usage.spend}
						capUsd={usage.cap}
						label={`${label} used by ${workspace}`}
					/>
					<span className="text-xs text-muted-foreground">{Math.round(percent)}% used</span>
				</>
			)}
			{usage.states.map((state) => (
				<StatusBadge key={state} def={CAP_STATE_DEFS[purse][state]} />
			))}
		</div>
	);
}
