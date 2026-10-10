// A `meta` the runner refuses: the script fails before it says which models it uses.
import { definePrecompute } from "../lib/precompute.ts";

export default definePrecompute({
	// @ts-expect-error -- "sometimes" is not a need, which is the point of this fixture.
	meta: { models: { decision: "sometimes" }, kinds: { unused: "Not used." } },
	async run() {
		return { leads: [] };
	},
});
