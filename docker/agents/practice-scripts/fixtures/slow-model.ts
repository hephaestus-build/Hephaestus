import { setTimeout as sleep } from "node:timers/promises";
import { scm } from "../kits/scm/index.ts";

import { definePracticeScript, embedMany, step } from "../lib/core/practice-script.ts";

export default definePracticeScript({
	meta: { models: { embedding: "required" } },
	async run(ctx) {
		const domain = scm(ctx);
		const found = await domain.files?.change?.cite("src/cart.ts", "NEW", 2);
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
