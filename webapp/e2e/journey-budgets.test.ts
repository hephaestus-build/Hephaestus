import { expect, it } from "vitest";

import { budgets } from "./journey-budgets";

// `settle()` reports every read, sorted, and each layout shift once, sorted; the journeys compare
// with strict equality. A budget that is not in that form can never pass, so this fails at once, in
// the unit tier, instead of in the browser suite that only a merge group may run.
it.each(Object.entries(budgets))(
	"keeps the %s budget in the form a journey reports",
	(_, budget) => {
		expect(budget.reads).toStrictEqual(budget.reads.toSorted());
		expect(budget.moved).toStrictEqual([...new Set(budget.moved)].toSorted());
	},
);
