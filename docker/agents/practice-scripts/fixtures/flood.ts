import { definePracticeScript, embedMany } from "@hephaestus/practice-scripts/models";

export default definePracticeScript({
	meta: { models: { embedding: "required" } },
	async run(ctx) {
		await Promise.all(
			Array.from({ length: 33 }, async (_, n) =>
				embedMany({ model: ctx.models.embedding, values: [String(n)] }),
			),
		);
		return ctx.review();
	},
});
