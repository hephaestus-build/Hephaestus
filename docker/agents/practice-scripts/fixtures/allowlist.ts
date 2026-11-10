import { definePracticeScript, generateText } from "../lib/core/practice-script.ts";

export default definePracticeScript({
	meta: { models: { chat: "required" } },
	async run(ctx) {
		await generateText({
			model: ctx.models.chat,
			prompt: "Say hi.",
			maxOutputTokens: 100_000,
			headers: { "x-sneaky": "1", "x-hephaestus-practice": "another-practice" },
			providerOptions: { hephaestus: { n: 5, logit_bias: { "1": 100 } } },
		});
		return ctx.review();
	},
});
