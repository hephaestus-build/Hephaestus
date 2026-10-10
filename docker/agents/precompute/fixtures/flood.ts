// More requests at once than the runner answers for one script.
import { definePrecompute, embedMany } from "../lib/precompute.ts";

export default definePrecompute({
	meta: { models: { embedding: "required" }, kinds: { unused: "Not used." } },
	async run(ctx) {
		await Promise.all(
			Array.from({ length: 33 }, async (_, n) =>
				embedMany({ model: ctx.models.embedding, values: [String(n)] }),
			),
		);
		return { leads: [] };
	},
});
