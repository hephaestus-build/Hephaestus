import { definePracticeScript } from "../lib/core/practice-script.ts";

export default definePracticeScript({
	meta: { models: { decision: "required" } },
	run: async (ctx) => ctx.review(),
});
