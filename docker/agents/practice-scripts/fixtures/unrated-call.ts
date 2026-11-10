import { definePracticeScript, embedMany, step } from "../lib/core/practice-script.ts";

export default definePracticeScript({
	meta: { models: { embedding: "required" } },
	async run(ctx) {
		const started = performance.now();
		const outcome = await step("slow", async () =>
			embedMany({
				model: ctx.models.embedding,
				values: ["x"],
				abortSignal: AbortSignal.timeout(1000),
			}),
		);
		return ctx.review({
			facts: {
				reason: "unrated" in outcome ? outcome.unrated : "rated",
				fast: performance.now() - started < 3000,
			},
		});
	},
});
