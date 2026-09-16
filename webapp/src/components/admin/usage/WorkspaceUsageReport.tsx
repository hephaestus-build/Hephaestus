import { Link } from "@tanstack/react-router";
import { CircleAlert, CircleDollarSign } from "lucide-react";
import { type ReactNode, useId } from "react";

import type { WorkspaceLlmUsageReport } from "@/api/types.gen";
import { BudgetExhaustedAlert } from "@/components/admin/ai/BudgetExhaustedAlert";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button, buttonVariants } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Empty, EmptyContent, EmptyHeader, EmptyMedia, EmptyTitle } from "@/components/ui/empty";
import { formatCapUsd, formatCostUsd } from "@/lib/money";

import { BudgetPaceAlert } from "./BudgetPaceAlert";
import { CapIsNotMonthScoped } from "./CapIsNotMonthScoped";
import { CAP_STATE_LABELS, CapMeter, capState } from "./CapMeter";
import {
	type Fx,
	FxAmount,
	type FxConversion,
	FxDisclosure,
	spendConversion,
	spendOfCapConversion,
} from "./fx";
import { LlmUsageByDayTable, LlmUsageByJobTypeTable } from "./LlmUsageBreakdownTables";
import { budgetUsedPercent, formatMonthLabel, projectBudget } from "./usage-utils";

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
	const sharedSpend = report.instanceTotalCostUsd;
	const providerSpend = report.ownProviderTotalCostUsd;
	const sharedBudget = report.instanceMonthlyBudgetUsd;
	const providerCap = report.ownProviderMonthlyBudgetUsd;
	const sharedPercent = budgetUsedPercent(sharedSpend, sharedBudget);
	const providerPercent = budgetUsedPercent(providerSpend, providerCap);
	const { unpricedEventCount } = report;
	const providerPaused = isCurrentMonth && report.ownProviderPaused;
	const sharedPaused = isCurrentMonth && report.instancePaused;
	const providerWarning =
		capState(providerPercent, providerPaused, isCurrentMonth) === "near" ? providerPercent : null;
	const sharedWarning =
		capState(sharedPercent, sharedPaused, isCurrentMonth) === "near" ? sharedPercent : null;
	const hasUsage =
		report.byJobType.length > 0 || report.byDay.length > 0 || sharedSpend > 0 || providerSpend > 0;
	const hasProviderCapOrSpend = providerCap != null || providerSpend > 0;

	const fx: Fx = report.fx;
	const sharedTitleFx =
		sharedBudget == null
			? spendConversion(sharedSpend, fx)
			: spendOfCapConversion(sharedSpend, sharedBudget, fx);
	const providerTitleFx =
		providerCap == null
			? spendConversion(providerSpend, fx)
			: spendOfCapConversion(providerSpend, providerCap, fx);
	const hasConversion =
		sharedTitleFx != null ||
		providerTitleFx != null ||
		spendConversion(sharedSpend, fx) != null ||
		spendConversion(providerSpend, fx) != null;

	return (
		<>
			{providerPaused && (
				<BudgetExhaustedAlert
					scope="own"
					verdict={report.ownProviderBudgetVerdict}
					month={month}
					unpricedEventCount={unpricedEventCount}
					context="usage"
					workspaceSlug={workspaceSlug}
					onEditOwnProviderCap={onEditOwnProviderCap}
				/>
			)}
			{sharedPaused && (
				<BudgetExhaustedAlert
					scope="shared"
					verdict={report.instanceBudgetVerdict}
					month={month}
					unpricedEventCount={unpricedEventCount}
					context="usage"
					workspaceSlug={workspaceSlug}
				/>
			)}

			{providerWarning != null && (
				<BudgetPaceAlert
					scope="provider"
					percent={providerWarning}
					spendUsd={providerSpend}
					capUsd={providerCap}
					projection={projectBudget(providerSpend, providerCap, month, now)}
					fx={fx}
				/>
			)}
			{sharedWarning != null && (
				<BudgetPaceAlert
					scope="shared"
					percent={sharedWarning}
					spendUsd={sharedSpend}
					capUsd={sharedBudget}
					projection={projectBudget(sharedSpend, sharedBudget, month, now)}
					fx={fx}
				/>
			)}

			{unpricedEventCount > 0 && (
				<Alert variant="warning" role="status">
					<CircleAlert aria-hidden />
					<AlertTitle>
						{unpricedEventCount === 1
							? "1 run isn't counted in these totals"
							: `${unpricedEventCount.toLocaleString()} runs aren't counted in these totals`}
					</AlertTitle>
					<AlertDescription>
						<p>
							They have no price set, so real spend may be higher. Add prices for your own models in{" "}
							<AiModelsLink workspaceSlug={workspaceSlug} />; for shared models, ask your host.
						</p>
					</AlertDescription>
				</Alert>
			)}

			<div className="grid gap-4 md:grid-cols-2">
				<CapCard
					purse="shared"
					isCurrentMonth={isCurrentMonth}
					spendUsd={sharedSpend}
					capUsd={sharedBudget}
					paused={sharedPaused}
					titleFx={sharedTitleFx}
				/>
				{hasProviderCapOrSpend ? (
					<CapCard
						purse="provider"
						isCurrentMonth={isCurrentMonth}
						spendUsd={providerSpend}
						capUsd={providerCap}
						paused={providerPaused}
						titleFx={providerTitleFx}
					>
						{isCurrentMonth ? (
							<Button variant="outline" size="sm" onClick={onEditOwnProviderCap}>
								{providerCap == null ? "Set cap" : "Change cap"}
							</Button>
						) : (
							<CapIsNotMonthScoped subject="cap" />
						)}
					</CapCard>
				) : (
					<NoProviderCard
						isCurrentMonth={isCurrentMonth}
						workspaceSlug={workspaceSlug}
						onEditOwnProviderCap={onEditOwnProviderCap}
					/>
				)}
			</div>

			{hasUsage ? (
				<>
					<Card>
						<CardHeader>
							<CardTitle>By run type</CardTitle>
						</CardHeader>
						<CardContent>
							<LlmUsageByJobTypeTable report={report} fx={fx} />
						</CardContent>
					</Card>

					<Card>
						<CardHeader>
							<CardTitle>By day</CardTitle>
						</CardHeader>
						<CardContent>
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
								<LlmUsageByDayTable report={report} fx={fx} />
							)}
						</CardContent>
					</Card>
				</>
			) : (
				<Empty variant="outlined">
					<EmptyHeader>
						<EmptyMedia variant="icon">
							<CircleDollarSign />
						</EmptyMedia>
						<EmptyTitle>No AI usage in {formatMonthLabel(month)}</EmptyTitle>
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
		</>
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

interface CapHeadlineProps {
	spendUsd: number;
	capUsd: number | undefined;
	titleFx: FxConversion | null;
}

function CapHeadline({ spendUsd, capUsd, titleFx }: CapHeadlineProps) {
	return (
		<CardTitle className="text-2xl tabular-nums">
			{formatCostUsd(spendUsd)}
			{(capUsd != null || titleFx != null) && (
				<span className="text-base font-normal text-muted-foreground">
					{capUsd != null && <> of {formatCapUsd(capUsd)}</>}
					<FxAmount conversion={titleFx} />
				</span>
			)}
		</CardTitle>
	);
}

const PURSE_COPY = {
	shared: {
		spendLabel: "Shared-model spend",
		capDescription: "Shared-model budget · set by your host",
		noCapDescription: "No shared-model budget set by your host",
		meterLabel: "Shared-model budget used",
	},
	provider: {
		spendLabel: "Your provider spend",
		capDescription: "Provider cap · set by you, billed by your provider",
		noCapDescription: "No provider cap set · billed to you by your provider",
		meterLabel: "Your provider cap used",
	},
} as const;

interface CapCardProps {
	/** Whose money the card counts: the host's shared budget or the workspace's own provider cap. */
	purse: keyof typeof PURSE_COPY;
	isCurrentMonth: boolean;
	spendUsd: number;
	capUsd: number | undefined;
	paused: boolean;
	titleFx: FxConversion | null;
	/** The control that acts on the cap, for the purse the reader owns. */
	children?: ReactNode;
}

function CapCard({
	purse,
	isCurrentMonth,
	spendUsd,
	capUsd,
	paused,
	titleFx,
	children,
}: CapCardProps) {
	const labelId = useId();
	const { spendLabel, capDescription, noCapDescription, meterLabel } = PURSE_COPY[purse];
	return (
		<Card role="region" aria-labelledby={labelId}>
			<CardHeader>
				<CardDescription id={labelId}>
					{isCurrentMonth ? `${spendLabel} so far` : spendLabel}
				</CardDescription>
				<CapHeadline spendUsd={spendUsd} capUsd={capUsd} titleFx={titleFx} />
				<CardDescription>{capUsd == null ? noCapDescription : capDescription}</CardDescription>
			</CardHeader>
			<CardContent className="space-y-3">
				{capUsd != null && (
					<CapMeterWithCaption
						paused={paused}
						isCurrentMonth={isCurrentMonth}
						spendUsd={spendUsd}
						capUsd={capUsd}
						label={meterLabel}
					/>
				)}
				{children}
			</CardContent>
		</Card>
	);
}

interface NoProviderCardProps {
	isCurrentMonth: boolean;
	workspaceSlug: string;
	onEditOwnProviderCap: () => void;
}

function NoProviderCard({
	isCurrentMonth,
	workspaceSlug,
	onEditOwnProviderCap,
}: NoProviderCardProps) {
	const labelId = useId();
	return (
		<Card role="region" aria-labelledby={labelId}>
			<CardHeader>
				<CardDescription id={labelId}>Your provider spend</CardDescription>
				<CardTitle className="text-2xl tabular-nums">{formatCostUsd(0)}</CardTitle>
				<CardDescription>
					No provider cap set · nothing has run on a provider of your own
				</CardDescription>
			</CardHeader>
			<CardContent className="space-y-3">
				<p className="text-sm text-muted-foreground">
					Connect your own provider in <AiModelsLink workspaceSlug={workspaceSlug} /> to bill AI
					work to your own account.
				</p>
				{isCurrentMonth ? (
					<Button variant="outline" size="sm" onClick={onEditOwnProviderCap}>
						Set cap
					</Button>
				) : (
					<CapIsNotMonthScoped subject="cap" />
				)}
			</CardContent>
		</Card>
	);
}

interface CapMeterWithCaptionProps {
	paused: boolean;
	isCurrentMonth: boolean;
	spendUsd: number;
	capUsd: number;
	label: string;
}

function CapMeterWithCaption({
	paused,
	isCurrentMonth,
	spendUsd,
	capUsd,
	label,
}: CapMeterWithCaptionProps) {
	const percent = budgetUsedPercent(spendUsd, capUsd);
	const state = capState(percent, paused, isCurrentMonth);
	return (
		<div className="space-y-1.5">
			<CapMeter
				percent={percent}
				paused={paused}
				spendUsd={spendUsd}
				capUsd={capUsd}
				label={label}
			/>
			<p className="text-sm text-muted-foreground tabular-nums">
				{Math.round(percent)}% used{state != null && ` · ${CAP_STATE_LABELS[state]}`}
			</p>
		</div>
	);
}
