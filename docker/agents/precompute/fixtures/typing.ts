// Compiled, never run: the slot types follow `meta.models`.
import type { Experimental_DecisionModel, LanguageModel } from "ai";

import { definePrecompute } from "../lib/precompute.ts";

export default definePrecompute({
	meta: { models: { decision: "required", chat: "optional" }, kinds: { unused: "Not used." } },
	async run(ctx) {
		const decision: Experimental_DecisionModel = ctx.models.decision;
		// @ts-expect-error an optional slot may be undefined
		const chat: LanguageModel = ctx.models.chat;
		// @ts-expect-error an undeclared slot does not exist
		void ctx.models.embedding;
		void decision;
		void chat;
		return { leads: [] };
	},
});
