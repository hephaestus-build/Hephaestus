import { Link } from "@tanstack/react-router";
import { CircleAlert, CircleDollarSign } from "lucide-react";
import type { ReactNode } from "react";

import type { WorkspaceLlmUsageReport } from "@/api/types.gen";
import { BudgetExhaustedAlert } from "@/components/admin/workspace-llm/BudgetExhaustedAlert";
import { StatTile } from "@/components/common/StatTile";
import { StatusBadge } from "@/components/common/StatusBadge";
import { Section } from "@/components/layout/Section";
import { CAP_STATE_DEFS, type CapState } from "@/components/practice-vocabulary/cap-state-defs";
import { PURSE_DEFS, type Purse } from "@/components/practice-vocabulary/purse-defs";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button, buttonVariants } from "@/components/ui/button";
import {
	Empty,
	EmptyContent,
	EmptyDescription,
	EmptyHeader,
	EmptyMedia,
	EmptyTitle,
} from "@/components/ui/empty";
import { formatCapUsd, formatCostUsd } from "@/lib/money";

import { BudgetPaceAlert } from "./BudgetPaceAlert";
import { CapIsNotMonthScoped } from "./CapIsNotMonthScoped";
import { CapMeter, capState } from "./CapMeter";
import {
	type Fx,
	FxApprox,
	type FxConversion,
	FxDisclosure,
	spendConversion,
	spendOfCapConversion,
} from "./fx";
import { LlmUsageByDayTable, LlmUsageByJobTypeTable } from "./LlmUsageBreakdownTables";
import { LlmUsageByPracticeTable } from "./LlmUsageByPracticeTable";
import {
	budgetUsedPercent,
	formatMonthLabel,
	precomputeByPracticeDescription,
	projectBudget,
	purseCap,
	pursesOf,
} from "./usage-utils";

export interface WorkspaceUsageReportProps {
	report: WorkspaceLlmUsageReport;
	month: string;
	isCurrentMonth: boolean;
	workspaceSlug: string;
	onEditOwnProviderCap: () => void;
	/** The instant the projection is measured against. */
	now: Date;
}

export function WorkspaceUsageReport({
	report,
	month,
	isCurrentMonth,
	workspaceSlug,
	onEditOwnProviderCap,
	now,
}: WorkspaceUsageReportProps) {
	const shared = purseCap(report, "SHARED");
	const provider = purseCap(report, "OWN_PROVIDER");
	const sharedSpend = shared.spendUsd;
	const providerSpend = provider.spendUsd;
	const sharedBudget = shared.capUsd;
	const providerCap = provider.capUsd;
	const sharedPercent = budgetUsedPercent(sharedSpend, sharedBudget);
	const providerPercent = budgetUsedPercent(providerSpend, providerCap);
	const { unpricedEventCount } = report;
	const providerPaused = isCurrentMonth && provider.paused;
	const sharedPaused = isCurrentMonth && shared.paused;
	const providerState = capState(providerPercent, providerPaused, isCurrentMonth);
	const sharedState = capState(sharedPercent, sharedPaused, isCurrentMonth);
	const hasUsage =
		report.byJobType.length > 0 || report.byDay.length > 0 || sharedSpend > 0 || providerSpend > 0;
	const purses = pursesOf(report);
	const usesOwnProvider = purses.includes("OWN_PROVIDER");

	const fx: Fx = report.fx;
	const sharedTitleFx =
		sharedBudget == null
			? spendConversion(sharedSpend, fx)
			: spendOfCapConversion(sharedSpend, sharedBudget, fx);
	const providerTitleFx =
		providerCap == null
			? spendConversion(providerSpend, fx)
			: spendOfCapConversion(providerSpend, providerCap, fx);
	const hasConversion = sharedTitleFx != null || providerTitleFx != null;

	const alerts = [
		providerPaused && (
			<BudgetExhaustedAlert
				key="paused-own"
				scope="own"
				verdict={provider.verdict}
				month={month}
				unpricedEventCount={unpricedEventCount}
				context="usage"
				workspaceSlug={workspaceSlug}
				onEditOwnProviderCap={onEditOwnProviderCap}
			/>
		),
		sharedPaused && (
			<BudgetExhaustedAlert
				key="paused-shared"
				scope="shared"
				verdict={shared.verdict}
				month={month}
				unpricedEventCount={unpricedEventCount}
				context="usage"
				workspaceSlug={workspaceSlug}
			/>
		),
		providerState === "NEAR" && providerPercent != null && (
			<BudgetPaceAlert
				key="near-own"
				scope="provider"
				percent={providerPercent}
				spendUsd={providerSpend}
				capUsd={providerCap}
				projection={projectBudget(providerSpend, providerCap, month, now)}
				fx={fx}
			/>
		),
		sharedState === "NEAR" && sharedPercent != null && (
			<BudgetPaceAlert
				key="near-shared"
				scope="shared"
				percent={sharedPercent}
				spendUsd={sharedSpend}
				capUsd={sharedBudget}
				projection={projectBudget(sharedSpend, sharedBudget, month, now)}
				fx={fx}
			/>
		),
		unpricedEventCount > 0 && (
			<Alert key="unpriced" variant="warning" role="status">
				<CircleAlert aria-hidden />
				<AlertTitle>
					{unpricedEventCount === 1
						? "1 run has no price"
						: `${unpricedEventCount.toLocaleString()} runs have no price`}
				</AlertTitle>
				<AlertDescription>
					<p>
						Usage with no price is not counted in these totals, so real spend may be higher. Add
						prices for your own models in <AiModelsLink workspaceSlug={workspaceSlug} />. For shared
						models, ask an instance admin.
					</p>
				</AlertDescription>
			</Alert>
		),
	].filter((alert) => alert !== false);

	return (
		<div className="space-y-8">
			{alerts.length > 0 && <div className="space-y-4">{alerts}</div>}

			<Section
				title={isCurrentMonth ? "Spend this month" : `Spend in ${formatMonthLabel(month)}`}
				// Only the own provider's cap is the workspace's to set, and only once that purse is in use.
				actions={
					isCurrentMonth && usesOwnProvider ? (
						<Button variant="outline" size="sm" onClick={onEditOwnProviderCap}>
							{providerCap == null ? "Set provider cap" : "Change provider cap"}
						</Button>
					) : undefined
				}
			>
				<div className="grid gap-4 md:grid-cols-2">
					<PurseTile
						purse="SHARED"
						spendUsd={sharedSpend}
						capUsd={sharedBudget}
						titleFx={sharedTitleFx}
						meterLabel="Shared-model budget used"
						paused={sharedPaused}
						state={sharedState}
						unpriced={isCurrentMonth && shared.verdict === "UNVERIFIABLE"}
					/>
					{usesOwnProvider ? (
						<PurseTile
							purse="OWN_PROVIDER"
							spendUsd={providerSpend}
							capUsd={providerCap}
							titleFx={providerTitleFx}
							meterLabel="Your provider cap used"
							paused={providerPaused}
							state={providerState}
							unpriced={isCurrentMonth && provider.verdict === "UNVERIFIABLE"}
						/>
					) : (
						<StatTile
							variant="muted"
							icon={<PurseIcon purse="OWN_PROVIDER" />}
							title={PURSE_DEFS.OWN_PROVIDER.label}
							value={formatCostUsd(0)}
						>
							<p className="text-sm text-muted-foreground">
								Work on a provider you connect in <AiModelsLink workspaceSlug={workspaceSlug} /> is
								billed to your own account and shows here.
							</p>
						</StatTile>
					)}
				</div>
				{!isCurrentMonth && <CapIsNotMonthScoped subject="cap" />}
			</Section>

			{hasUsage ? (
				<>
					<Section title="By run type">
						<LlmUsageByJobTypeTable report={report} purses={purses} />
					</Section>

					{report.byPractice.length > 0 && (
						<Section
							title="Precompute models by practice"
							description={precomputeByPracticeDescription(isCurrentMonth)}
						>
							<LlmUsageByPracticeTable
								report={report}
								purses={purses}
								workspaceSlug={workspaceSlug}
							/>
						</Section>
					)}

					<Section title="By day">
						{report.byDay.length === 0 ? (
							<Empty variant="outlined">
								<EmptyHeader>
									<EmptyMedia variant="icon">
										<CircleDollarSign />
									</EmptyMedia>
									<EmptyTitle>No daily breakdown yet</EmptyTitle>
								</EmptyHeader>
							</Empty>
						) : (
							<LlmUsageByDayTable report={report} purses={purses} />
						)}
					</Section>
				</>
			) : (
				<Empty variant="outlined">
					<EmptyHeader>
						<EmptyMedia variant="icon">
							<CircleDollarSign />
						</EmptyMedia>
						<EmptyTitle>No AI usage in {formatMonthLabel(month)}</EmptyTitle>
						<EmptyDescription>
							Spend appears here after practice reviews or Heph use a model.
						</EmptyDescription>
					</EmptyHeader>
					<EmptyContent>
						<Link
							to="/w/$workspaceSlug/admin/models"
							params={{ workspaceSlug }}
							className={buttonVariants({ variant: "outline", size: "sm" })}
						>
							Open AI models
						</Link>
					</EmptyContent>
				</Empty>
			)}

			{hasConversion && <FxDisclosure fx={fx} isCurrentMonth={isCurrentMonth} />}
		</div>
	);
}

function AiModelsLink({ workspaceSlug }: { workspaceSlug: string }) {
	return (
		<Link
			to="/w/$workspaceSlug/admin/models"
			params={{ workspaceSlug }}
			className="underline underline-offset-4"
		>
			AI models
		</Link>
	);
}

function PurseIcon({ purse }: { purse: Purse }) {
	const Icon = PURSE_DEFS[purse].icon;
	return <Icon className="size-4 text-muted-foreground" aria-hidden />;
}

function TileLine({ children }: { children: ReactNode }) {
	return <p className="text-sm text-muted-foreground tabular-nums">{children}</p>;
}

interface PurseTileProps {
	purse: Purse;
	spendUsd: number;
	capUsd: number | undefined;
	titleFx: FxConversion | null;
	/** The meter's accessible name, distinct per purse. */
	meterLabel: string;
	paused: boolean;
	state: CapState | null;
	/** Some of this purse's calls have no price, so its cap cannot be checked. */
	unpriced: boolean;
}

const NO_LIMIT_SET: Record<Purse, string> = {
	SHARED: "No budget set",
	OWN_PROVIDER: "No cap set",
};

/** One purse's spend this month against its own limit. The two tiles are never summed. */
function PurseTile({
	purse,
	spendUsd,
	capUsd,
	titleFx,
	meterLabel,
	paused,
	state,
	unpriced,
}: PurseTileProps) {
	// The workspace's own provider bills it whether or not it has a cap; the shared budget's line says
	// who set it.
	const showsDescription = purse === "OWN_PROVIDER" || capUsd != null;
	return (
		<StatTile
			icon={<PurseIcon purse={purse} />}
			title={PURSE_DEFS[purse].label}
			value={formatCostUsd(spendUsd)}
			qualifier={capUsd == null ? undefined : `of ${formatCapUsd(capUsd)}`}
			detail={
				<>
					{titleFx != null && (
						<TileLine>
							<FxApprox conversion={titleFx} />
						</TileLine>
					)}
					{capUsd == null && <TileLine>{NO_LIMIT_SET[purse]}</TileLine>}
					{showsDescription && <TileLine>{PURSE_DEFS[purse].description}</TileLine>}
				</>
			}
		>
			{capUsd != null && (
				<div className="space-y-2">
					<CapMeter
						percent={budgetUsedPercent(spendUsd, capUsd)}
						paused={paused}
						spendUsd={spendUsd}
						capUsd={capUsd}
						label={meterLabel}
					/>
					<div className="flex flex-wrap items-center justify-between gap-2">
						<span className="text-sm text-muted-foreground tabular-nums">
							{Math.round(budgetUsedPercent(spendUsd, capUsd))}% used
						</span>
						<span className="flex flex-wrap gap-1.5">
							{state != null && <StatusBadge def={CAP_STATE_DEFS[purse][state]} />}
							{unpriced && <StatusBadge def={CAP_STATE_DEFS[purse].UNPRICED} />}
						</span>
					</div>
				</div>
			)}
		</StatTile>
	);
}
