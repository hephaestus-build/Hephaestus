import { ChevronDown, ChevronRight, CircleDollarSign, Info } from "lucide-react";

import type { AdminWorkspaceLlmUsage, WorkspaceLlmUsageReport } from "@/api/types.gen";
import { MoneyCell } from "@/components/admin/ai/job-utils";
import { TableRowsSkeleton } from "@/components/admin/integrations/TableRowsSkeleton";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Empty, EmptyHeader, EmptyMedia, EmptyTitle } from "@/components/ui/empty";
import {
	Table,
	TableBody,
	TableCaption,
	TableCell,
	TableHead,
	TableHeader,
	TableRow,
} from "@/components/ui/table";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { formatCapUsd, formatCostUsd } from "@/lib/money";

import { BudgetPaceAlert } from "./BudgetPaceAlert";
import { CapIsNotMonthScoped } from "./CapIsNotMonthScoped";
import { CAP_STATE_LABELS, CapMeter, type CapState, capState } from "./CapMeter";
import { type Fx, FxDisclosure, FxSpendLine, spendConversion } from "./fx";
import { LlmUsageByDayTable, LlmUsageByJobTypeTable } from "./LlmUsageBreakdownTables";
import { budgetUsedPercent, projectBudget } from "./usage-utils";

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
	expandedWorkspaceSlug: string | null;
	detailReport?: WorkspaceLlmUsageReport;
	isDetailLoading: boolean;
	detailError: unknown;
	onRetryDetail?: () => void;
	onToggleDetails: (workspace: AdminWorkspaceLlmUsage) => void;
	onEditSharedModelBudget: (workspace: AdminWorkspaceLlmUsage) => void;
}

const SKELETON_COLUMNS = ["w-32", "w-16", "w-24", "w-16", "w-24", "w-28", "w-12", null];

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
	state: CapState;
	/** The spend shown is a floor: some of this stream's usage has no price set. */
	hasUnpricedUsage: boolean;
}

function capUsage(input: {
	cap?: number;
	spend: number;
	verdict?: WorkspaceLlmUsageReport["instanceBudgetVerdict"];
	paused: boolean;
	isCurrentMonth: boolean;
}): CapUsage {
	const { cap, spend, verdict, paused, isCurrentMonth } = input;
	const percent = budgetUsedPercent(spend, cap);
	const isPaused = isCurrentMonth && paused;
	return {
		cap,
		spend,
		percent,
		paused: isPaused,
		state: capState(percent, isPaused, isCurrentMonth),
		hasUnpricedUsage: isCurrentMonth && verdict === "UNVERIFIABLE",
	};
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
	expandedWorkspaceSlug,
	detailReport,
	isDetailLoading,
	detailError,
	onRetryDetail,
	onToggleDetails,
	onEditSharedModelBudget,
}: AdminInstanceLlmUsageTableProps) {
	if (error != null) {
		return <QueryErrorAlert error={error} title="Couldn't load AI usage" onRetry={onRetry} />;
	}
	if (rows.length === 0 && !isLoading) {
		return (
			<Empty variant="outlined">
				<EmptyHeader>
					<EmptyMedia variant="icon">
						<CircleDollarSign />
					</EmptyMedia>
					<EmptyTitle>No workspaces on this instance yet</EmptyTitle>
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

	return (
		<div className="space-y-4">
			{!isCurrentMonth && <CapIsNotMonthScoped subject="budget" />}
			<Table containerClassName="rounded-md border">
				<TableCaption className="sr-only">
					Per-workspace AI spend for the selected month, most expensive first
				</TableCaption>
				<TableHeader>
					<TableRow>
						<TableHead scope="col">Workspace</TableHead>
						<TableHead scope="col" className="text-right">
							Shared-model spend
						</TableHead>
						<TableHead scope="col" className="text-right">
							<HelpHeader help="The monthly cap you set on the spend you pay for.">
								Shared-model budget
							</HelpHeader>
						</TableHead>
						<TableHead scope="col" className="text-right">
							Provider spend
						</TableHead>
						<TableHead scope="col" className="text-right">
							<HelpHeader help="The workspace's own money. Only its admins can change this.">
								Provider cap
							</HelpHeader>
						</TableHead>
						<TableHead scope="col">Status</TableHead>
						<TableHead scope="col" className="text-right">
							Runs
						</TableHead>
						<TableHead scope="col">
							<span className="sr-only">Actions</span>
						</TableHead>
					</TableRow>
				</TableHeader>
				{isLoading ? (
					<TableRowsSkeleton columns={SKELETON_COLUMNS} rows={5} />
				) : (
					<TableBody>
						{rows.map((row) => {
							const isExpanded = expandedWorkspaceSlug === row.workspaceSlug;
							const shared = capUsage({
								cap: row.instanceMonthlyBudgetUsd,
								spend: row.instanceTotalCostUsd,
								verdict: row.instanceBudgetVerdict,
								paused: row.instancePaused,
								isCurrentMonth,
							});
							const provider = capUsage({
								cap: row.ownProviderMonthlyBudgetUsd,
								spend: row.ownProviderTotalCostUsd,
								verdict: row.ownProviderBudgetVerdict,
								paused: row.ownProviderPaused,
								isCurrentMonth,
							});
							return (
								<TableRow key={row.workspaceSlug}>
									<TableCell>
										<div className="font-medium">{row.displayName}</div>
										<div className="font-mono text-xs text-muted-foreground">
											{row.workspaceSlug}
										</div>
									</TableCell>
									<TableCell numeric className="text-right">
										<MoneyCell>{formatCostUsd(row.instanceTotalCostUsd)}</MoneyCell>
										<FxSpendLine usd={row.instanceTotalCostUsd} fx={fx} />
									</TableCell>
									<CapCell usage={shared} label="Shared-model budget" workspace={row.displayName} />
									<TableCell numeric className="text-right">
										<MoneyCell>{formatCostUsd(row.ownProviderTotalCostUsd)}</MoneyCell>
										<FxSpendLine usd={row.ownProviderTotalCostUsd} fx={fx} />
									</TableCell>
									<CapCell usage={provider} label="Provider cap" workspace={row.displayName} />
									<TableCell>
										<StatusCell
											shared={shared}
											provider={provider}
											isCurrentMonth={isCurrentMonth}
										/>
									</TableCell>
									<TableCell numeric className="text-right">
										{row.events.toLocaleString()}
									</TableCell>
									<TableCell>
										<div className="flex justify-end gap-2">
											<Button
												variant="outline"
												size="sm"
												aria-expanded={isExpanded}
												// The panel is unmounted while collapsed; a constant IDREF would dangle.
												aria-controls={isExpanded ? detailPanelId(row.workspaceSlug) : undefined}
												aria-label={`${isExpanded ? "Hide" : "View"} usage details for ${row.displayName}`}
												onClick={() => onToggleDetails(row)}
											>
												{isExpanded ? <ChevronDown aria-hidden /> : <ChevronRight aria-hidden />}
												Details
											</Button>
											{/* Current month only: a budget is not month-scoped, so editing one from a
											    closed month would quietly change what runs today. */}
											{isCurrentMonth && (
												<Button
													variant="outline"
													size="sm"
													// Must start with the visible label for speech control (WCAG SC 2.5.3).
													aria-label={`Set budget for ${row.displayName} (shared models)`}
													onClick={() => onEditSharedModelBudget(row)}
												>
													Set budget
												</Button>
											)}
										</div>
									</TableCell>
								</TableRow>
							);
						})}
					</TableBody>
				)}
			</Table>

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
	const streams = [
		{
			scope: "shared" as const,
			spend: report.instanceTotalCostUsd,
			cap: report.instanceMonthlyBudgetUsd,
			paused: report.instancePaused,
		},
		{
			scope: "provider" as const,
			spend: report.ownProviderTotalCostUsd,
			cap: report.ownProviderMonthlyBudgetUsd,
			paused: report.ownProviderPaused,
		},
	];
	return streams.flatMap(({ paused, ...stream }) => {
		const percent = budgetUsedPercent(stream.spend, stream.cap);
		if (percent == null || capState(percent, paused, isCurrentMonth) !== "near") {
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
 * The two breakdown tables stack until `xl`: side by side they would each be too narrow to avoid a
 * horizontal scroller of their own, which is two-dimensional scrolling (WCAG 2.2 SC 1.4.10).
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
	const panelId = detailPanelId(workspace.workspaceSlug);
	const paces = pacesWorthWarningAbout(report, isCurrentMonth);
	return (
		<section
			id={panelId}
			aria-labelledby={`${panelId}-heading`}
			className="space-y-4 rounded-md border bg-muted/20 p-4"
		>
			{/* `h2`, not `h3`: `CardTitle` renders a `<div>`, so an `h3` would skip a level (SC 1.3.1). */}
			<h2 id={`${panelId}-heading`} className="font-medium">
				Usage details · {workspace.displayName}
			</h2>
			{error != null ? (
				<QueryErrorAlert
					error={error}
					title={`Couldn't load usage details for ${workspace.displayName}`}
					onRetry={onRetry}
				/>
			) : (
				<>
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
					<div className="grid gap-4 xl:grid-cols-2">
						<section aria-labelledby={`${panelId}-run-type`} className="min-w-0 space-y-2">
							<h3 id={`${panelId}-run-type`} className="font-medium">
								By run type
							</h3>
							<LlmUsageByJobTypeTable report={isLoading ? undefined : report} fx={fx} />
						</section>
						<section aria-labelledby={`${panelId}-day`} className="min-w-0 space-y-2">
							<h3 id={`${panelId}-day`} className="font-medium">
								By day
							</h3>
							<LlmUsageByDayTable report={isLoading ? undefined : report} fx={fx} />
						</section>
					</div>
				</>
			)}
		</section>
	);
}

interface HelpHeaderProps {
	children: string;
	help: string;
}

/** `min-h-6` is SC 2.5.8's 24 px minimum target — a header line box alone leaves the trigger short. */
function HelpHeader({ children, help }: HelpHeaderProps) {
	return (
		<Tooltip>
			<TooltipTrigger className="inline-flex min-h-6 cursor-help items-center gap-1 font-medium">
				{children}
				<Info className="size-3 text-muted-foreground" aria-hidden />
			</TooltipTrigger>
			<TooltipContent>{help}</TooltipContent>
		</Tooltip>
	);
}

interface CapCellProps {
	usage: CapUsage;
	label: string;
	workspace: string;
}

function CapCell({ usage, label, workspace }: CapCellProps) {
	if (usage.cap == null) {
		return (
			<TableCell className="text-right">
				<span className="text-muted-foreground">—</span>
			</TableCell>
		);
	}

	const percent = usage.percent ?? 0;
	const rounded = Math.round(percent);

	return (
		<TableCell className="text-right">
			<div className="ml-auto flex w-24 flex-col items-end gap-1">
				<span className="tabular-nums">
					<MoneyCell>{formatCapUsd(usage.cap)}</MoneyCell>
				</span>
				<CapMeter
					percent={percent}
					paused={usage.paused}
					spendUsd={usage.spend}
					capUsd={usage.cap}
					label={`${label} used by ${workspace}`}
				/>
				{/* The meter's tone never carries the state alone — this line says it in words (SC 1.4.1). */}
				<span className="text-xs text-muted-foreground tabular-nums">
					<MoneyCell>{formatCostUsd(usage.spend)}</MoneyCell> · {rounded}%
					{usage.state != null && ` · ${CAP_STATE_LABELS[usage.state]}`}
				</span>
			</div>
		</TableCell>
	);
}

interface StatusCellProps {
	shared: CapUsage;
	provider: CapUsage;
	isCurrentMonth: boolean;
}

const CAP_SCOPE_LABELS: Record<CapScope, string> = {
	shared: "shared models",
	provider: "own provider",
};

/** Worst first, so a paused cap is never buried under a warning. */
const BADGE_STATES = ["paused", "near"] as const;

const BADGE_VARIANTS: Record<(typeof BADGE_STATES)[number], "destructive" | "warning"> = {
	paused: "destructive",
	near: "warning",
};

function StatusCell({ shared, provider, isCurrentMonth }: StatusCellProps) {
	if (!isCurrentMonth) {
		return <span className="text-muted-foreground">—</span>;
	}

	const streams: [CapScope, CapUsage][] = [
		["shared", shared],
		["provider", provider],
	];
	const badges = BADGE_STATES.flatMap((state) =>
		streams
			.filter(([, usage]) => usage.state === state)
			.map(([scope]) => ({
				key: `${state}-${scope}`,
				label: `${CAP_STATE_LABELS[state]} · ${CAP_SCOPE_LABELS[scope]}`,
				variant: BADGE_VARIANTS[state],
			})),
	);
	const noPriceSet = shared.hasUnpricedUsage || provider.hasUnpricedUsage;

	if (badges.length === 0 && !noPriceSet) {
		return <span className="text-muted-foreground">—</span>;
	}

	return (
		<div className="flex flex-col items-start gap-1">
			{badges.map((badge) => (
				<Badge key={badge.key} variant={badge.variant}>
					{badge.label}
				</Badge>
			))}
			{noPriceSet && <span className="text-warning text-xs">Some runs have no price set</span>}
		</div>
	);
}
