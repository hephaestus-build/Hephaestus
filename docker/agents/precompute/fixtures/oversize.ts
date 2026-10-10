// Calls that hold more items than one call to their model may: the runner refuses them before the
// proxy. A rerank at the cap reaches the proxy, and `embedMany` splits its values into calls that fit.
import { MODEL_SLOT_CAPS } from "../lib/contract.ts";
import { definePrecompute, embedMany, rerank, step, type Reason } from "../lib/precompute.ts";

const items = (count: number) => Array.from({ length: count }, (_, i) => `item ${i}`);

const outcome = (result: { value: unknown } | { unrated: Reason }) =>
	"unrated" in result ? result.unrated : "rated";

export default definePrecompute({
	meta: {
		models: { decision: "required", embedding: "required", reranking: "required" },
		kinds: { unused: "Not used." },
	},
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
			step("decide over", async () => decision.doDecide({ state: "s", questions })),
			step("embed over", async () =>
				embedding.doEmbed({ values: items(MODEL_SLOT_CAPS.embedding + 1) }),
			),
			step("embed many", async () =>
				embedMany({ model: embedding, values: items(MODEL_SLOT_CAPS.embedding + 1) }),
			),
		]);
		return {
			leads: [],
			facts: {
				rerankOver: outcome(rerankOver),
				rerankAt: outcome(rerankAt),
				decideOver: outcome(decideOver),
				embedOver: outcome(embedOver),
				embedMany: outcome(embedMany65),
			},
		};
	},
});
