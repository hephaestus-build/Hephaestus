import { definePrecompute } from "../lib/precompute.ts";

export default definePrecompute({
	meta: {
		models: { decision: "required" },
		kinds: { "never-shown": "Never shown: the script cannot run without its model." },
	},
	run: async () => ({ leads: [] }),
});
