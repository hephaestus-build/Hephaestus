import { definePracticeScript, generateText, step } from "../lib/core/practice-script.ts";

export default definePracticeScript({
	meta: { models: { chat: "required" } },
	async run(ctx) {
		const outcomes = await Promise.all(
			[1, 2, 3, 4].map(async (n) =>
				step(`call ${n}`, async () =>
					generateText({ model: ctx.models.chat, prompt: `Say ${n}.`, maxOutputTokens: 1000 }),
				),
			),
		);
		return ctx.review({
			facts: {
				answered: outcomes.filter((o) => "value" in o).length,
				refused: outcomes.filter((o) => "unrated" in o && o.unrated === "budget").length,
			},
		});
	},
});
