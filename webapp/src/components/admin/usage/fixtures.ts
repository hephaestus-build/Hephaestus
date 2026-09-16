import type { FxRateInfo, WorkspaceLlmUsageReport } from "@/api/types.gen";

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
		ownProviderTotalCostUsd: 0,
		instanceBudgetVerdict: "WITHIN",
		ownProviderBudgetVerdict: "WITHIN",
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

/** The same workspace with a $10 cap on a provider of its own, which Heph and issue reviews ran on. */
export function withOwnProvider(report: WorkspaceLlmUsageReport): WorkspaceLlmUsageReport {
	return {
		...report,
		ownProviderMonthlyBudgetUsd: 10,
		ownProviderTotalCostUsd: 2.4,
		byJobType: report.byJobType.map((row) =>
			row.jobType === "MENTOR_TURN"
				? { ...row, ownProviderTotalCostUsd: 1.92 }
				: row.jobType === "ISSUE_REVIEW"
					? { ...row, ownProviderTotalCostUsd: 0.48 }
					: row,
		),
		byDay: report.byDay.map((row, index) =>
			index === 1
				? { ...row, ownProviderTotalCostUsd: 0.48 }
				: index === 3
					? { ...row, ownProviderTotalCostUsd: 1.92 }
					: row,
		),
	};
}

export const eurRate: FxRateInfo = {
	currencyCode: "EUR",
	ratePerUsd: 0.878966,
	rateDate: daysBefore(1),
	source: "ECB",
};
