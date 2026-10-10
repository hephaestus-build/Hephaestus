// A definition script that uses every model slot, each optional. Steps that do not depend on each
// other start together, so a slow model costs only its own part.
import {
	addedComments,
	agent,
	classify,
	commentKinds,
	cosineSimilarity,
	definePrecompute,
	embedMany,
	generateText,
	Output,
	rerank,
	step,
	z,
	type AddedComment,
	type Facts,
	type Lead,
	type PrecomputeContext,
	type Unrated,
} from "../lib/precompute.ts";

// The comment kinds that deserve a look. Code finds the marked open work, so `untracked-todo` is
// code's kind here.
const NEEDS_A_LOOK = new Set([
	"restates-code",
	"commented-out-code",
	"narrates-change",
	"temporary-stub",
	"misleading",
]);

const OPEN_WORK = /\b(?:TODO|FIXME|HACK|XXX)\b/u;
/** A tracking reference as `commentKinds` defines one: an issue number, a ticket key or a URL. */
const TRACKING = /#\d+|\b[A-Z][A-Z0-9]+-\d+\b|https?:\/\/\S+/u;

/** How many pairs of vectors are nearly the same direction. */
function similarPairs(vectors: readonly number[][]): number {
	let similar = 0;
	for (const [i, a] of vectors.entries()) {
		for (const b of vectors.slice(i + 1)) {
			if (cosineSimilarity(a, b) > 0.9) {
				similar += 1;
			}
		}
	}
	return similar;
}

const meta = {
	models: {
		chat: "optional",
		decision: "optional",
		embedding: "optional",
		reranking: "optional",
	},
	tokens: 80_000,
	kinds: {
		"untracked-todo": "A TODO without a tracking reference.",
		"comment-kind": "An added comment that a model rated as one that may need a second look.",
		declaration: "Where an agent found the declaration that the change relies on.",
		"most-unfinished": "The added comment that a reranker ranked closest to unfinished work.",
	},
} as const;

type Context = PrecomputeContext<typeof meta.models>;

/** What one part of the script found. */
interface Part {
	leads?: Lead[];
	unrated?: Unrated[];
	facts?: Facts;
}

async function commentKindLeads(ctx: Context, unmarked: AddedComment[]): Promise<Part> {
	const { ratings, unrated } = await classify({
		model: ctx.models.decision,
		set: commentKinds,
		items: unmarked,
		what: "added comments without a TODO marker",
	});
	const leads: Lead[] = [];
	for (const [comment, rating] of ratings) {
		if (NEEDS_A_LOOK.has(rating.value)) {
			leads.push({
				at: comment.at,
				kind: "comment-kind",
				rating: { value: rating.value, score: rating.score },
			});
		}
	}
	return { leads, unrated };
}

async function mostUnfinished(ctx: Context, comments: AddedComment[]): Promise<Part> {
	const { reranking } = ctx.models;
	if (reranking === undefined || comments.length === 0) {
		return {};
	}
	const ranked = await step("rerank", async () =>
		rerank({
			model: reranking,
			query: "temporary or unfinished work that should not be merged",
			documents: comments.map((c) => c.text),
			topN: 1,
		}),
	);
	if ("unrated" in ranked) {
		return {
			unrated: [{ what: "rerank of the added comments", count: 1, reason: ranked.unrated }],
		};
	}
	const top = ranked.value.ranking[0];
	const comment = top === undefined ? undefined : comments[top.originalIndex];
	return comment === undefined ? {} : { leads: [{ at: comment.at, kind: "most-unfinished" }] };
}

async function similarComments(ctx: Context, comments: AddedComment[]): Promise<Part> {
	const { embedding } = ctx.models;
	if (embedding === undefined || comments.length < 2) {
		return {};
	}
	const embedded = await step("embed", async () =>
		embedMany({ model: embedding, values: comments.map((c) => c.text) }),
	);
	if ("unrated" in embedded) {
		return {
			unrated: [
				{
					what: "embedding of the added comments",
					count: comments.length,
					reason: embedded.unrated,
				},
			],
		};
	}
	const vectors = embedded.value.embeddings;
	return {
		facts: {
			embeddingDimensions: vectors[0]?.length ?? 0,
			similarCommentPairs: similarPairs(vectors),
		},
	};
}

async function changeKind(ctx: Context): Promise<Part> {
	const { chat } = ctx.models;
	if (chat === undefined) {
		return {};
	}
	// `output` throws when the answer has none, so it is read inside the step.
	const kind = await step("change kind", async () => {
		const { output } = await generateText({
			model: chat,
			output: Output.choice({ options: ["code", "documentation", "configuration", "mixed"] }),
			prompt: `Which kind of change adds these files: ${[...ctx.change.keys()].join(", ")}?`,
		});
		return output;
	});
	return "value" in kind
		? { facts: { changeKind: kind.value } }
		: { unrated: [{ what: "kind of the change", count: 1, reason: kind.unrated }] };
}

async function declaration(ctx: Context): Promise<Part> {
	const { chat } = ctx.models;
	if (chat === undefined) {
		return {};
	}
	const found = await step("locate", async () =>
		agent({
			model: chat,
			tools: ctx.tools,
			instructions: "You locate code in a repository. Use the tools to look; do not guess.",
			prompt:
				"Find where the function parseDiff is declared. Submit the file path relative to the repository root and the 1-based line of the declaration.",
			schema: z.object({ file: z.string(), line: z.int().positive() }),
			maxSteps: 8,
			signal: ctx.signal,
		}),
	);
	if ("unrated" in found) {
		return {
			unrated: [{ what: "agent search for the declaration", count: 1, reason: found.unrated }],
		};
	}
	const { output, steps } = found.value;
	return {
		leads: [
			{
				at: { file: output.file, line: output.line },
				kind: "declaration",
				facts: { agentSteps: steps },
			},
		],
	};
}

export default definePrecompute({
	meta,
	async run(ctx) {
		const comments = addedComments(ctx.change);
		const untrackedTodos: Lead[] = [];
		const unmarked: AddedComment[] = [];
		for (const comment of comments) {
			if (!OPEN_WORK.test(comment.text)) {
				unmarked.push(comment);
			} else if (!TRACKING.test(comment.text)) {
				untrackedTodos.push({ at: comment.at, kind: "untracked-todo" });
			}
		}
		const parts = await Promise.all([
			commentKindLeads(ctx, unmarked),
			mostUnfinished(ctx, comments),
			similarComments(ctx, comments),
			changeKind(ctx),
			declaration(ctx),
		]);
		const facts: Facts = { comments: comments.length };
		for (const part of parts) {
			Object.assign(facts, part.facts);
		}
		return {
			leads: [...untrackedTodos, ...parts.flatMap((p) => p.leads ?? [])],
			facts,
			unrated: parts.flatMap((p) => p.unrated ?? []),
			directions: ["Check each lead against the criterion before you cite it."],
		};
	},
});
