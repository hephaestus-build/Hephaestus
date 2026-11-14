import { describe, expect, it } from "vitest";

import type { WorkspaceLlmUsageReport } from "@/api/types.gen";

import { usageReport, withOwnProvider } from "./fixtures";
import { usageAlerts } from "./usage-alerts";

/** $13.48 of a $25 shared budget and $2.40 of a $10 provider cap: neither is near. */
const calm = withOwnProvider(usageReport());

function report(overrides: Partial<WorkspaceLlmUsageReport>): WorkspaceLlmUsageReport {
	return { ...calm, ...overrides };
}

describe("usageAlerts", () => {
	it("raises nothing for a calm month", () => {
		expect(usageAlerts(calm, true)).toStrictEqual([]);
	});

	it("warns about a purse that is near its cap", () => {
		expect(usageAlerts(report({ instanceTotalCostUsd: 21 }), true)).toStrictEqual([
			{ kind: "NEAR_CAP", purse: "SHARED", percent: 84, spendUsd: 21, capUsd: 25 },
		]);
	});

	it("puts the own provider before the shared budget", () => {
		const alerts = usageAlerts(
			report({ instanceTotalCostUsd: 21, ownProviderTotalCostUsd: 9 }),
			true,
		);
		expect(alerts).toStrictEqual([
			expect.objectContaining({ kind: "NEAR_CAP", purse: "OWN_PROVIDER" }),
			expect.objectContaining({ kind: "NEAR_CAP", purse: "SHARED" }),
		]);
	});

	it("raises no pace warning for a paused purse", () => {
		expect(
			usageAlerts(report({ instanceTotalCostUsd: 26, instancePaused: true }), true),
		).toStrictEqual([]);
	});

	it("raises no pace warning for a purse without a cap", () => {
		expect(
			usageAlerts(report({ instanceMonthlyBudgetUsd: undefined, instanceTotalCostUsd: 99 }), true),
		).toStrictEqual([]);
	});

	it("raises no pace warning for a provider that is not in use", () => {
		expect(
			usageAlerts(report({ ownProviderInUse: false, ownProviderTotalCostUsd: 9 }), true),
		).toStrictEqual([]);
	});

	it("raises no pace warning for a closed month, whose caps are not month-scoped", () => {
		expect(usageAlerts(report({ instanceTotalCostUsd: 21 }), false)).toStrictEqual([]);
	});

	it("counts the uses with unavailable cost, last, in any month", () => {
		expect(
			usageAlerts(report({ instanceTotalCostUsd: 21, unpricedEventCount: 7 }), true),
		).toStrictEqual([
			expect.objectContaining({ kind: "NEAR_CAP" }),
			{ kind: "UNAVAILABLE_COST", uses: 7 },
		]);
		expect(usageAlerts(report({ unpricedEventCount: 1 }), false)).toStrictEqual([
			{ kind: "UNAVAILABLE_COST", uses: 1 },
		]);
	});
});
