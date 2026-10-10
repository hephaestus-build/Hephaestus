import { definePrecompute } from "../lib/precompute.ts";

export default definePrecompute({
	meta: { kinds: { unused: "Not used." } },
	// @ts-expect-error a result whose leads are not a list is refused by the runner too
	run: async () => ({ leads: "everything is fine" }),
});
