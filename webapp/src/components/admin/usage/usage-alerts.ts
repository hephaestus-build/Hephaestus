import type { WorkspaceLlmUsageReport } from "@/api/types.gen";
import type { Purse } from "@/components/practice-vocabulary/purse-defs";

import { capState } from "./CapMeter";
import { budgetUsedPercent, purseCap } from "./usage-utils";

export type UsageAlert =
	| { kind: "NEAR_CAP"; purse: Purse; percent: number; spendUsd: number; capUsd: number }
	| { kind: "UNAVAILABLE_COST"; uses: number };

/** The own provider comes first: a workspace admin can act on that cap directly. */
const NEAR_CAP_ORDER: readonly Purse[] = ["OWN_PROVIDER", "SHARED"];

/**
 * The warnings that one workspace's usage report raises, in display order, for both consoles. A
 * paused purse raises no pace warning: each console says that it stopped in its own way.
 */
export function usageAlerts(
	report: WorkspaceLlmUsageReport,
	isCurrentMonth: boolean,
): UsageAlert[] {
	const nearCaps = NEAR_CAP_ORDER.flatMap((purse): UsageAlert[] => {
		const { spendUsd, capUsd, paused } = purseCap(report, purse);
		if (capUsd == null) {
			return [];
		}
		const percent = budgetUsedPercent(spendUsd, capUsd);
		if (capState(percent, paused, isCurrentMonth) !== "NEAR") {
			return [];
		}
		return [{ kind: "NEAR_CAP", purse, percent, spendUsd, capUsd }];
	});
	const { unpricedEventCount } = report;
	return unpricedEventCount > 0
		? [...nearCaps, { kind: "UNAVAILABLE_COST", uses: unpricedEventCount }]
		: nearCaps;
}
