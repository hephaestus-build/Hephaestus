import { scm } from "@hephaestus/practice-scripts/scm";

import { definePracticeScript, embedMany, step } from "../lib/core/practice-script.ts";

const { setTimeout: sleep } = process.getBuiltinModule("node:timers/promises");

export default definePracticeScript({
	meta: { models: { embedding: "required" } },
	async run(ctx) {
		const work = scm(ctx, "scm.pull_request");
		const found = await work.change?.cite("src/cart.ts", "NEW", 2);
		if (found === undefined) {
			throw new Error("the change was not captured");
		}
		await step("slow", async () => embedMany({ model: ctx.models.embedding, values: ["x"] }));
		await sleep(500);
		return {
			outcome: "COULD_NOT_DETERMINE",
			summary: "The added comment was not compared with the others",
			explanation: "Plain code found the comment. The embedding that compares it gave no answer.",
			citations: [found],
			evidence: {
				openQuestions: [
					{
						openQuestion: "Does the comment repeat another one?",
						wouldSettleIt: "An embedding of the added comments.",
						citation: found,
					},
				],
			},
		};
	},
});
