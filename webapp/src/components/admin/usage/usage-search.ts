import { z } from "zod";

import { currentMonthUtc } from "./usage-utils";

const ISO_MONTH = /^\d{4}-(?:0[1-9]|1[0-2])$/u;

/**
 * URL state for the AI-usage surfaces. `.catch()` rather than a hard rejection: a hand-typed or stale
 * `?month=` must still open the report on a sensible month instead of an error page.
 */
export const usageSearchSchema = z.object({
	/**
	 * ISO `yyyy-MM`, UTC. Absent means *the current month*, so a bare `…/admin/usage` link keeps
	 * meaning "this month" forever. Clamped to now: a link must not reach a state the stepper cannot.
	 */
	month: z
		.string()
		.regex(ISO_MONTH)
		// ISO yyyy-MM compares lexicographically, so this is a real "no later than now".
		.transform((value) => (value > currentMonthUtc() ? currentMonthUtc() : value))
		.optional()
		.catch(undefined),
});

export type UsageSearch = z.infer<typeof usageSearchSchema>;

export function monthOf(search: UsageSearch): string {
	return search.month ?? currentMonthUtc();
}

export const USAGE_SEARCH_PARAMS: (keyof UsageSearch)[] = ["month"];
