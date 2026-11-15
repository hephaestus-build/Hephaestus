import { expect, it } from "vitest";

import { budgets } from "./journey-budgets";

// `settle()` reports reads and layout shifts sorted and without duplicates, and the journeys compare
// with strict equality. A budget that is not in that form can never pass, so this fails at once, in
// the unit tier, instead of in the browser suite that only a merge group may run.
it.each(Object.entries(budgets))(
	"keeps the %s budget in the form a journey reports",
	(_, budget) => {
		for (const list of [budget.reads, budget.moved]) {
			expect(list).toStrictEqual([...new Set(list)].toSorted());
		}
	},
);
