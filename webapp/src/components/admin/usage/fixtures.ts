import type { FxRateInfo, LlmUsageByJobType, WorkspaceLlmUsageReport } from "@/api/types.gen";

import { daysBefore, STORY_NOW } from "@/stories/story-clock";

/** The month the story clock falls in, as ISO `yyyy-MM`, so a report reads as "this month". */
export const STORY_MONTH = new Date(STORY_NOW).toISOString().slice(0, 7);

/** Noon UTC on `day` of `month`: a day bucket, or a `now` a set number of days into the month. */
export function dayOfMonth(month: string, day: number): Date {
	const [yearStr, monthStr] = month.split("-");
	return new Date(Date.UTC(Number(yearStr), Number(monthStr) - 1, day, 12));
}

/**
 * A workspace that has only run on shared models. The rows divide cleanly by their run counts —
 * $8.20 over 41 runs is $0.20 — so an average a test asserts is one a reader can check by hand.
 */
export function usageReport(month: string = STORY_MONTH): WorkspaceLlmUsageReport {
	return {
		month,
		instanceMonthlyBudgetUsd: 25,
		ownProviderMonthlyBudgetUsd: undefined,
		instanceTotalCostUsd: 13.48,
		instanceUnmeasured: { calls: 0, upperBoundUsd: 0 },
		ownProviderUnmeasured: { calls: 0, upperBoundUsd: 0 },
		ownProviderTotalCostUsd: 0,
		instanceBudgetVerdict: "WITHIN",
		ownProviderBudgetVerdict: "WITHIN",
		ownProviderInUse: false,
		instancePaused: false,
		ownProviderPaused: false,
		unpricedEventCount: 0,
		byJobType: [
			{
				jobType: "PULL_REQUEST_REVIEW",
				instanceTotalCostUsd: 8.2,
				ownProviderTotalCostUsd: 0,
				unpricedEventCount: 0,
				inputTokens: 1_204_331,
				outputTokens: 88_412,
				cacheReadTokens: 640_112,
				cacheWriteTokens: 120_034,
				totalCalls: 312,
				events: 41,
			},
			{
				jobType: "MENTOR_TURN",
				instanceTotalCostUsd: 3.84,
				ownProviderTotalCostUsd: 0,
				unpricedEventCount: 0,
				inputTokens: 402_118,
				outputTokens: 61_240,
				cacheReadTokens: 210_400,
				cacheWriteTokens: 44_020,
				totalCalls: 128,
				events: 64,
			},
			{
				jobType: "ISSUE_REVIEW",
				instanceTotalCostUsd: 1.44,
				ownProviderTotalCostUsd: 0,
				unpricedEventCount: 0,
				inputTokens: 150_221,
				outputTokens: 20_114,
				cacheReadTokens: 80_010,
				cacheWriteTokens: 12_450,
				totalCalls: 54,
				events: 12,
			},
		],
		byDay: [
			{
				day: dayOfMonth(month, 1),
				instanceTotalCostUsd: 2.1,
				ownProviderTotalCostUsd: 0,
				unpricedEventCount: 0,
				events: 14,
			},
			{
				day: dayOfMonth(month, 2),
				instanceTotalCostUsd: 4.83,
				ownProviderTotalCostUsd: 0,
				unpricedEventCount: 0,
				events: 31,
			},
			{
				day: dayOfMonth(month, 3),
				instanceTotalCostUsd: 0.92,
				ownProviderTotalCostUsd: 0,
				unpricedEventCount: 0,
				events: 6,
			},
			{
				day: dayOfMonth(month, 6),
				instanceTotalCostUsd: 5.63,
				ownProviderTotalCostUsd: 0,
				unpricedEventCount: 0,
				events: 66,
			},
		],
	};
}

const OWN_PROVIDER_COST_BY_JOB_TYPE: Partial<Record<LlmUsageByJobType["jobType"], number>> = {
	MENTOR_TURN: 1.92,
	ISSUE_REVIEW: 0.48,
};
const OWN_PROVIDER_COST_BY_DAY_INDEX: Partial<Record<number, number>> = { 1: 0.48, 3: 1.92 };

/** The same workspace with a $10 cap on a provider of its own, which Hephaestus and issue reviews ran on. */
export function withOwnProvider(report: WorkspaceLlmUsageReport): WorkspaceLlmUsageReport {
	return {
		...report,
		ownProviderInUse: true,
		ownProviderMonthlyBudgetUsd: 10,
		ownProviderTotalCostUsd: 2.4,
		byJobType: report.byJobType.map((row) => {
			const ownProviderTotalCostUsd = OWN_PROVIDER_COST_BY_JOB_TYPE[row.jobType];
			return ownProviderTotalCostUsd === undefined ? row : { ...row, ownProviderTotalCostUsd };
		}),
		byDay: report.byDay.map((row, index) => {
			const ownProviderTotalCostUsd = OWN_PROVIDER_COST_BY_DAY_INDEX[index];
			return ownProviderTotalCostUsd === undefined ? row : { ...row, ownProviderTotalCostUsd };
		}),
	};
}

type MonthTotals = Pick<
	WorkspaceLlmUsageReport,
	"instanceTotalCostUsd" | "ownProviderTotalCostUsd" | "unpricedEventCount"
>;

// Assign the rounding remainder to the largest share to preserve the total.
function splitLike(values: readonly number[], total: number, digits: number): number[] {
	const sum = values.reduce((acc, value) => acc + value, 0);
	if (sum === 0) {
		return values.map((_, index) => (index === 0 ? total : 0));
	}
	const scale = 10 ** digits;
	const shares = values.map((value) => Math.round(((value * total) / sum) * scale) / scale);
	const largest = shares.indexOf(Math.max(...shares));
	const remainder = total - shares.reduce((acc, share) => acc + share, 0);
	return shares.map((share, index) => (index === largest ? share + remainder : share));
}

function withColumnTotal<Row extends MonthTotals>(
	rows: readonly Row[],
	key: keyof MonthTotals,
	total: number,
): Row[] {
	const shares = splitLike(
		rows.map((row) => row[key]),
		total,
		key === "unpricedEventCount" ? 0 : 2,
	);
	return rows.map((row, index) => ({ ...row, [key]: shares[index] ?? row[key] }));
}

export function withMonthTotals(
	report: WorkspaceLlmUsageReport,
	totals: Partial<MonthTotals>,
): WorkspaceLlmUsageReport {
	let { byJobType, byDay } = report;
	for (const key of [
		"instanceTotalCostUsd",
		"ownProviderTotalCostUsd",
		"unpricedEventCount",
	] as const) {
		const total = totals[key];
		if (total !== undefined) {
			byJobType = withColumnTotal(byJobType, key, total);
			byDay = withColumnTotal(byDay, key, total);
		}
	}
	return { ...report, ...totals, byJobType, byDay };
}

export const eurRate: FxRateInfo = {
	currencyCode: "EUR",
	ratePerUsd: 0.878966,
	rateDate: daysBefore(1),
	source: "ECB",
};
