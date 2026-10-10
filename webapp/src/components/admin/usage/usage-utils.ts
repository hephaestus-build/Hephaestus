import type { LlmUsageByJobType, WorkspaceLlmUsageReport } from "@/api/types.gen";
import { PURSES, type Purse } from "@/components/practice-vocabulary/purse-defs";
import { asDate, type DateLike } from "@/lib/dates";

export type LlmJobType = LlmUsageByJobType["jobType"];

export const JOB_TYPE_LABELS: Record<LlmJobType, string> = {
	PULL_REQUEST_REVIEW: "Pull request review",
	ISSUE_REVIEW: "Issue review",
	CONVERSATION_REVIEW: "Conversation review",
	DOCUMENT_REVIEW: "Document review",
	MENTOR_TURN: "Heph turn",
};

/** The two spend fields every usage row and total carries, one per purse. */
export interface PurseSpend {
	instanceTotalCostUsd: number;
	ownProviderTotalCostUsd: number;
}

export function spendOf(row: PurseSpend, purse: Purse): number {
	return purse === "SHARED" ? row.instanceTotalCostUsd : row.ownProviderTotalCostUsd;
}

/** The cap fields a workspace's usage report and an instance usage row both carry. */
export type PurseCaps = Pick<
	WorkspaceLlmUsageReport,
	| "instanceTotalCostUsd"
	| "instanceMonthlyBudgetUsd"
	| "instancePaused"
	| "instanceBudgetVerdict"
	| "ownProviderTotalCostUsd"
	| "ownProviderMonthlyBudgetUsd"
	| "ownProviderPaused"
	| "ownProviderBudgetVerdict"
	| "ownProviderInUse"
>;

/** One purse's spend against its own cap. */
export interface PurseCap {
	spendUsd: number;
	capUsd: number | undefined;
	paused: boolean;
	verdict: WorkspaceLlmUsageReport["instanceBudgetVerdict"];
}

/**
 * One purse's cap as every surface shows it. A cap on an own provider that is not in use holds
 * nothing back. The server still reports a $0 cap as reached, so without this gate the screen would
 * say "cap reached" beside "connect a provider".
 */
export function purseCap(usage: PurseCaps, purse: Purse): PurseCap {
	if (purse === "SHARED") {
		return {
			spendUsd: usage.instanceTotalCostUsd,
			capUsd: usage.instanceMonthlyBudgetUsd,
			paused: usage.instancePaused,
			verdict: usage.instanceBudgetVerdict,
		};
	}
	if (!usage.ownProviderInUse) {
		return {
			spendUsd: usage.ownProviderTotalCostUsd,
			capUsd: undefined,
			paused: false,
			verdict: "WITHIN",
		};
	}
	return {
		spendUsd: usage.ownProviderTotalCostUsd,
		capUsd: usage.ownProviderMonthlyBudgetUsd,
		paused: usage.ownProviderPaused,
		verdict: usage.ownProviderBudgetVerdict,
	};
}

const SHARED_ONLY: readonly Purse[] = ["SHARED"];

/**
 * The purses a workspace's usage shows: shared models always, its own provider when the server says
 * that purse is in use. The spend and cap fields cannot tell a month of $0.00 calls, or a provider
 * connected before its first call, from no provider at all. One array per answer, so a table can key
 * its column model on it.
 */
export function pursesOf(
	usage: Pick<WorkspaceLlmUsageReport, "ownProviderInUse">,
): readonly Purse[] {
	return usage.ownProviderInUse ? PURSES : SHARED_ONLY;
}

/**
 * A purse's spend over a count of runs or reviews. `null` where there is nothing to average: no runs,
 * or no priced spend beside calls with no price, whose $0.00 would claim a cost nobody knows.
 */
export function averageSpend(
	totalUsd: number,
	count: number,
	unpricedCount: number,
): number | null {
	if (count === 0 || (totalUsd === 0 && unpricedCount > 0)) {
		return null;
	}
	return totalUsd / count;
}

/** The note under *Precompute models by practice*, on both consoles. */
export function precomputeByPracticeDescription(isCurrentMonth: boolean): string {
	return `Decision, embedding and reranking calls from finished reviews${isCurrentMonth ? ", to date" : ""}. Chat calls from scripts are in each review’s cost. A review that ran several scripts counts once in the total.`;
}

/** Current calendar month in UTC as ISO `yyyy-MM`. */
export function currentMonthUtc(): string {
	return new Date().toISOString().slice(0, 7);
}

/** Equality, never `>=`: a month past this one would otherwise get a live cap editor and rate. */
export function isCurrentMonthUtc(month: string): boolean {
	return month === currentMonthUtc();
}

/** ISO `yyyy-MM` compares lexicographically. Stays false *past* this month, unlike `!isCurrentMonthUtc`. */
export function canStepForwardFrom(month: string): boolean {
	return month < currentMonthUtc();
}

export function addMonths(month: string, delta: number): string {
	const [yearStr, monthStr] = month.split("-");
	const date = new Date(Date.UTC(Number(yearStr), Number(monthStr) - 1 + delta, 1));
	return date.toISOString().slice(0, 7);
}

export function formatMonthLabel(month: string): string {
	const [yearStr, monthStr] = month.split("-");
	return new Date(Date.UTC(Number(yearStr), Number(monthStr) - 1, 1)).toLocaleDateString(
		undefined,
		{ month: "long", year: "numeric", timeZone: "UTC" },
	);
}

/** A day bucket as `Jul 22`. An unparseable day renders a dash rather than `Invalid Date`. */
export function formatUsageDay(value: DateLike): string {
	const date = asDate(value);
	if (!date) {
		return "–";
	}
	return date.toLocaleDateString(undefined, {
		month: "short",
		day: "numeric",
		timeZone: "UTC",
	});
}

export function formatDayLabel(date: Date): string {
	return date.toLocaleDateString(undefined, {
		month: "long",
		day: "numeric",
		timeZone: "UTC",
	});
}

/** The day a pause lifts by itself: the first of the month after `month`, in UTC. */
export function budgetResetDayLabel(month: string): string {
	const next = addMonths(month, 1);
	const [yearStr, monthStr] = next.split("-");
	return formatDayLabel(new Date(Date.UTC(Number(yearStr), Number(monthStr) - 1, 1)));
}

/**
 * Share of a cap consumed. A $0 cap is a supported state ("paused immediately") and reads as 100%.
 *
 * Display only, and that is a rule: money is exact decimal on the server and binary64 here, so
 * nothing this returns may decide anything. Whether work is held back is `paused` on the payload.
 */
export function budgetUsedPercent(spendUsd: number, capUsd: number): number;
export function budgetUsedPercent(spendUsd: number, capUsd: number | undefined): number | undefined;
export function budgetUsedPercent(
	spendUsd: number,
	capUsd: number | undefined,
): number | undefined {
	if (capUsd == null) {
		return undefined;
	}
	return capUsd > 0 ? (spendUsd / capUsd) * 100 : 100;
}

export const BUDGET_WARN_PERCENT = 80;

export interface BudgetProjection {
	/** Spend at month end if the month's average daily pace holds. */
	projectedMonthEndUsd: number;
	/** `null` when the projected pace never reaches the cap. */
	reachedOn: Date | null;
}

/** One busy afternoon would project a wildly wrong month, so a projection needs some month behind it. */
const MIN_DAYS_ELAPSED_TO_PROJECT = 3;

/**
 * Straight-line burn-rate projection for a capped month: `spend / daysElapsed * daysInMonth`.
 * `null` means "say nothing" — the denominator is garbage, not a guess worth showing.
 */
export function projectBudget(
	spendUsd: number,
	capUsd: number | undefined,
	month: string,
	now: Date,
): BudgetProjection | null {
	if (capUsd == null || capUsd <= 0 || spendUsd <= 0) {
		return null;
	}
	if (now.toISOString().slice(0, 7) !== month) {
		return null;
	}
	const daysElapsed = now.getUTCDate();
	if (daysElapsed < MIN_DAYS_ELAPSED_TO_PROJECT) {
		return null;
	}
	const [yearStr, monthStr] = month.split("-");
	const year = Number(yearStr);
	const monthIndex = Number(monthStr) - 1;
	// Day 0 of the following month is the last day of this one.
	const daysInMonth = new Date(Date.UTC(year, monthIndex + 1, 0)).getUTCDate();
	const dailyRate = spendUsd / daysElapsed;
	const projectedMonthEndUsd = dailyRate * daysInMonth;
	if (projectedMonthEndUsd < capUsd) {
		return { projectedMonthEndUsd, reachedOn: null };
	}
	const dayReached = Math.min(Math.max(Math.ceil(capUsd / dailyRate), daysElapsed), daysInMonth);
	return {
		projectedMonthEndUsd,
		reachedOn: new Date(Date.UTC(year, monthIndex, dayReached)),
	};
}

export function formatTokens(value: number | undefined): string {
	if (value == null) {
		return "—";
	}
	return value.toLocaleString();
}

/**
 * The first column of a usage table stays in view while the figures scroll sideways under it. Its
 * rows are `static`: a hover tint would stop at this cell's opaque ground.
 *
 * The cell stacks above the scrolled cells, so a positioned sort icon does not paint over it. A rule
 * on its right edge marks where the figures go under it, so a cut figure does not read as a whole one.
 * The rule is a pseudo-element: in a collapsed table, a cell's own border stays behind when the cell
 * sticks.
 */
export const FIRST_COLUMN =
	"sticky left-0 z-1 bg-card after:absolute after:inset-y-0 after:right-0 after:w-px after:bg-border";

/**
 * Every cell of a total row. The row's sticky label needs an opaque ground, and the footer's own
 * `bg-muted/50` is translucent, so each cell paints the same opaque ground.
 */
export const TOTAL_CELL = "bg-muted";

/**
 * The column that counts runs with no price, or reviews with no price in the table by practice: the
 * runs or reviews with at least one usage row that has no price. It wraps onto two lines where the
 * table is short of room, instead of widening it. Its minimum width keeps it at two lines, not one
 * word per line, and the two lines are balanced.
 */
export const UNPRICED_RUNS = {
	label: "No price set",
	className: "min-w-24 whitespace-normal text-balance",
};

/** Token and call counts: below `lg` they give way to the money, which is what the page is for. */
export const WIDE_ONLY = "hidden lg:table-cell";
