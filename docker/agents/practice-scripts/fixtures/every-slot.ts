import type { Facts, Omission, ReviewLead, EvidenceLocation } from "@hephaestus/practice-scripts";
import { languageOf, scm, type DiffFile } from "@hephaestus/practice-scripts/scm";
import {
	definePracticeScript,
	agent,
	classify,
	cosineSimilarity,
	embedMany,
	generateText,
	Output,
	rerank,
	step,
	z,
	type QuestionSet,
	type PracticeScriptContext,
} from "../lib/core/practice-script.ts";

export const LABELS = {
	untrackedTodo: "A TODO without a tracking reference.",
	commentKind: "An added comment that a model rated as one that may need a second look.",
	declaration: "Where an agent found the declaration that the change relies on.",
	mostUnfinished: "The added comment that a reranker ranked closest to unfinished work.",
} as const;

const NEEDS_A_LOOK = new Set([
	"restates-code",
	"commented-out-code",
	"narrates-change",
	"temporary-stub",
	"misleading",
]);

const OPEN_WORK = /\b(?:TODO|FIXME|HACK|XXX)\b/u;
const TRACKING = /#\d+|\b[A-Z][A-Z0-9]+-\d+\b|https?:\/\/\S+/u;

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
} as const;

type Context = PracticeScriptContext<typeof meta.models>;

interface Preparation {
	leads?: ReviewLead[];
	unrated?: Omission[];
	facts?: Facts;
}

async function commentKindLeads(ctx: Context, unmarked: AddedComment[]): Promise<Preparation> {
	const { ratings, unrated } = await classify({
		model: ctx.models.decision,
		set: commentKinds,
		items: unmarked,
		what: "added comments without a TODO marker",
	});
	const leads: ReviewLead[] = [];
	for (const [comment, rating] of ratings) {
		if (NEEDS_A_LOOK.has(rating.value)) {
			leads.push({
				at: comment.at,
				label: LABELS.commentKind,
				facts: { kind: rating.value, score: rating.score },
			});
		}
	}
	return { leads, unrated };
}

async function mostUnfinished(ctx: Context, comments: AddedComment[]): Promise<Preparation> {
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
	return comment === undefined ? {} : { leads: [{ at: comment.at, label: LABELS.mostUnfinished }] };
}

async function similarComments(ctx: Context, comments: AddedComment[]): Promise<Preparation> {
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

async function changeKind(
	ctx: Context,
	change: ReadonlyMap<string, DiffFile>,
): Promise<Preparation> {
	const { chat } = ctx.models;
	if (chat === undefined) {
		return {};
	}
	// `output` throws when the answer has none, so it is read inside the step.
	const kind = await step("change kind", async () => {
		const { output } = await generateText({
			model: chat,
			output: Output.choice({ options: ["code", "documentation", "configuration", "mixed"] }),
			prompt: `Which kind of change adds these files: ${[...change.keys()].join(", ")}?`,
		});
		return output;
	});
	return "value" in kind
		? { facts: { changeKind: kind.value } }
		: { unrated: [{ what: "kind of the change", count: 1, reason: kind.unrated }] };
}

async function declaration(ctx: Context): Promise<Preparation> {
	const { chat } = ctx.models;
	if (chat === undefined) {
		return {};
	}
	const found = await step("locate", async () =>
		agent({
			model: chat,
			tools: ctx.evidence.tools,
			instructions: "You locate code in a repository. Use the tools to look; do not guess.",
			prompt:
				"Find where the function parseDiff is declared. Submit the file path relative to the repository root and the 1-based line of the declaration.",
			schema: z.object({ file: z.string(), line: z.int().positive() }),
			maxSteps: 8,
			abortSignal: ctx.abortSignal,
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
				label: LABELS.declaration,
				facts: { agentSteps: steps },
			},
		],
	};
}

export default definePracticeScript({
	meta,
	async run(ctx) {
		const work = scm(ctx, "scm.pull_request");
		const change = work.changedFiles();
		const comments = addedComments(change);
		const untrackedTodos: ReviewLead[] = [];
		const unmarked: AddedComment[] = [];
		for (const comment of comments) {
			if (!OPEN_WORK.test(comment.text)) {
				unmarked.push(comment);
			} else if (!TRACKING.test(comment.text)) {
				untrackedTodos.push({ at: comment.at, label: LABELS.untrackedTodo });
			}
		}
		const parts = await Promise.all([
			commentKindLeads(ctx, unmarked),
			mostUnfinished(ctx, comments),
			similarComments(ctx, comments),
			changeKind(ctx, change),
			declaration(ctx),
		]);
		const facts: Facts = { comments: comments.length };
		for (const part of parts) {
			Object.assign(facts, part.facts);
		}
		return ctx.review({
			leads: [...untrackedTodos, ...parts.flatMap((p) => p.leads ?? [])],
			facts,
			limitations: parts.flatMap((p) => p.unrated ?? []),
			directions: ["Check each lead against the criterion before you cite it."],
		});
	},
});

/**
 * Added comments as items, and a closed vocabulary to rate them. A starting point for the practices
 * that judge comments; the kinds follow Pascarella & Bacchelli (MSR 2017) and the inline comment
 * smells of Jabrayilzade et al. (EMSE 2024), cut to what a practice can act on.
 */

interface AddedComment {
	at: Extract<EvidenceLocation, { change: string }>;
	language: string;
	/** The comment lines, trimmed and joined. */
	text: string;
	/** Added lines around the comment, as the change shows them. */
	code: string;
}

const AROUND = 6;

export function addedComments(change: ReadonlyMap<string, DiffFile>): AddedComment[] {
	const found: AddedComment[] = [];
	for (const [path, file] of change) {
		const language = languageOf(path);
		if (language === null) {
			continue;
		}
		const lines = [...file.addedLines];
		for (let i = 0; i < lines.length; i += 1) {
			const [line, content] = lines[i] ?? [0, ""];
			const previous = lines[i - 1];
			if (
				!isCommentLine(content, language) ||
				(previous !== undefined && previous[0] === line - 1 && isCommentLine(previous[1], language))
			) {
				continue;
			}
			let end = i;
			for (
				let next = lines[end + 1];
				next !== undefined &&
				next[0] === (lines[end]?.[0] ?? 0) + 1 &&
				isCommentLine(next[1], language);
				next = lines[end + 1]
			) {
				end += 1;
			}
			const last = lines[end]?.[0] ?? line;
			found.push({
				at: { change: path, line, ...(last > line ? { endLine: last } : {}), side: "NEW" },
				language,
				text: lines
					.slice(i, end + 1)
					.map(([, text]) => text.trim())
					.join("\n"),
				code: lines
					.filter(([n]) => n >= line - AROUND && n <= last + AROUND + 2)
					.map(([, text]) => text)
					.join("\n"),
			});
		}
	}
	return found;
}

export const commentKinds: QuestionSet<AddedComment> = {
	name: "comment kind",
	noun: "added code comment with the code around it",
	question: "What kind of comment is it, judged against the code around it?",
	values: {
		"explains-why":
			"Gives a reason, constraint, trade-off, caveat, invariant or reference that the code alone does not convey",
		"documents-contract":
			"Documents a function or type's parameters, return value, errors or usage",
		"tracked-todo":
			"Marks open work and references a tracking item such as an issue number, ticket key or URL",
		directive:
			"A tool directive or required marker: lint suppression, compiler pragma, code generation, license header",
		"restates-code": "Only says what the adjacent code already says plainly",
		"commented-out-code": "Source code disabled behind a comment marker",
		"untracked-todo": "Marks open work (TODO, FIXME, HACK, XXX) without any tracking reference",
		"narrates-change":
			"Narrates the editing session or change history instead of the code as it is",
		"temporary-stub": "Marks temporary, debug or placeholder residue meant to be removed",
		misleading: "States something that the adjacent code does not do",
	},
	render: (comment) =>
		`Comment: ${comment.text}\nCode around it (${comment.language}):\n${comment.code}`,
};

function isCommentLine(content: string, language: string): boolean {
	const trimmed = content.trimStart();
	if (language === "python" || language === "ruby") {
		return trimmed.startsWith("#");
	}
	return trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*");
}
