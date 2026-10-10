// Four calls at once against a ceiling that holds two reservations: two run, two are refused.
import { definePrecompute, generateText, step } from "../lib/precompute.ts";

export default definePrecompute({
	meta: { models: { chat: "required" }, tokens: 2300, kinds: { unused: "Not used." } },
	async run(ctx) {
		const outcomes = await Promise.all(
			[1, 2, 3, 4].map(async (n) =>
				step(`call ${n}`, async () =>
					generateText({ model: ctx.models.chat, prompt: `Say ${n}.`, maxOutputTokens: 1000 }),
				),
			),
		);
		return {
			leads: [],
			facts: {
				answered: outcomes.filter((o) => "value" in o).length,
				refused: outcomes.filter((o) => "unrated" in o && o.unrated === "budget").length,
			},
		};
	},
});
