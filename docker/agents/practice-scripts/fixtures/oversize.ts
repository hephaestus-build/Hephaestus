import type { UnratedReason } from "@hephaestus/practice-scripts";
import {
	definePracticeScript,
	embedMany,
	MODEL_SLOT_CAPS,
	rerank,
	step,
} from "@hephaestus/practice-scripts/models";

const items = (count: number) => Array.from({ length: count }, (_, i) => `item ${i}`);

const outcome = (result: { value: unknown } | { unrated: UnratedReason }) =>
	"unrated" in result ? result.unrated : "rated";

export default definePracticeScript({
	meta: { models: { decision: "required", embedding: "required", reranking: "required" } },
	async run(ctx) {
		const { decision, embedding, reranking } = ctx.models;
		const questions = Object.fromEntries(
			items(MODEL_SLOT_CAPS.decision + 1).map((item) => [
				item,
				{ type: "boolean" as const, instructions: item },
			]),
		);
		const [rerankOver, rerankAt, decideOver, embedOver, embedMany65] = await Promise.all([
			step("rerank over", async () =>
				rerank({ model: reranking, query: "q", documents: items(MODEL_SLOT_CAPS.reranking + 1) }),
			),
			step("rerank at", async () =>
				rerank({ model: reranking, query: "q", documents: items(MODEL_SLOT_CAPS.reranking) }),
			),
			step("decide over", async () =>
				decision.doDecide({ state: [{ type: "text", text: "s" }], questions }),
			),
			step("embed over", async () =>
				embedding.doEmbed({ values: items(MODEL_SLOT_CAPS.embedding + 1) }),
			),
			step("embed many", async () =>
				embedMany({ model: embedding, values: items(MODEL_SLOT_CAPS.embedding + 1) }),
			),
		]);
		return ctx.review({
			facts: {
				rerankOver: outcome(rerankOver),
				rerankAt: outcome(rerankAt),
				decideOver: outcome(decideOver),
				embedOver: outcome(embedOver),
				embedMany: outcome(embedMany65),
			},
		});
	},
});
