// A model call that never answers, next to a lead that plain code found. The call ends unrated
// before the deadline, and the script still has the second it takes to return the lead.
import { setTimeout as sleep } from "node:timers/promises";

import { definePrecompute, embedMany, step } from "../lib/precompute.ts";

export default definePrecompute({
	meta: { models: { embedding: "required" }, kinds: { found: "A line that plain code found." } },
	async run(ctx) {
		const outcome = await step("slow", async () =>
			embedMany({ model: ctx.models.embedding, values: ["x"] }),
		);
		await sleep(1000);
		return {
			leads: [{ at: { change: "src/cart.ts", line: 2, side: "NEW" }, kind: "found" }],
			facts: { reason: "unrated" in outcome ? outcome.unrated : "rated" },
		};
	},
});
