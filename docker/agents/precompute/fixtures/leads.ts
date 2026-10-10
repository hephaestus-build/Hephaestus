// Leads the runner keeps and leads it drops: an undeclared kind, a line the change does not hold, a
// record outside the context folder.
import { definePrecompute } from "../lib/precompute.ts";

export default definePrecompute({
	meta: {
		kinds: {
			"restates-code": "An added comment that may only restate the code beside it.",
			"record-row": "A captured record row.",
		},
	},
	run: async () => ({
		leads: [
			{ at: { change: "src/cart.ts", line: 2, side: "NEW" }, kind: "restates-code" },
			{ at: { change: "src/cart.ts", line: 2, side: "NEW" }, kind: "undeclared-kind" },
			{ at: { change: "src/cart.ts", line: 400, side: "NEW" }, kind: "restates-code" },
			{ at: { record: "../../../../../../etc/hostname", line: 1 }, kind: "record-row" },
			{ at: { record: "comments.json", line: 2 }, kind: "record-row" },
		],
		facts: { checked: true },
	}),
});
