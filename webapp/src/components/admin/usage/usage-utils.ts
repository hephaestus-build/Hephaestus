import type { LlmUsageByJobType } from "@/api/types.gen";
import { asDate, type DateLike } from "@/lib/dates";

export type LlmJobType = LlmUsageByJobType["jobType"];

export const JOB_TYPE_LABELS: Record<LlmJobType, string> = {
	PULL_REQUEST_REVIEW: "PR review",
	ISSUE_REVIEW: "Issue review",
	CONVERSATION_REVIEW: "Conversation review",
	DOCUMENT_REVIEW: "Document review",
	MENTOR_TURN: "Mentor turn",
};

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
