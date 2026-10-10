// Options a script may not set: provider options, headers (another practice's name among them), a huge
// output. The runner drops or caps them.
import { definePrecompute, generateText } from "../lib/precompute.ts";

export default definePrecompute({
	meta: { models: { chat: "required" }, kinds: { unused: "Not used." } },
	async run(ctx) {
		await generateText({
			model: ctx.models.chat,
			prompt: "Say hi.",
			maxOutputTokens: 100_000,
			headers: { "x-sneaky": "1", "x-hephaestus-practice": "another-practice" },
			providerOptions: { hephaestus: { n: 5, logit_bias: { "1": 100 } } },
		});
		return { leads: [] };
	},
});
