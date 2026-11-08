/** A frozen author consumer. Compile this file to check public members and accepted arguments. */
import {
	definePracticeScript,
	listWithin,
	UnratedError,
	type Citation,
	type PracticeScriptContext,
	type PracticeScriptDefinition,
	type ReviewOptions,
} from "@hephaestus/practice-scripts";
import { chat } from "@hephaestus/practice-scripts/chat";
import { docs } from "@hephaestus/practice-scripts/docs";
import {
	branchIssueReferences,
	checkableItems,
	dated,
	isTestPath,
	issueReferences,
	issueShape,
	languageOf,
	later,
	scm,
	subIssueRollup,
	type ChangeView,
	type DiffFile,
	type FileTree,
	type ScmWork,
	type SourceFile,
} from "@hephaestus/practice-scripts/scm";

const definition: PracticeScriptDefinition = definePracticeScript({
	meta: {},
	async run(ctx) {
		const summary: string = listWithin(["one", "two"], 160, "; ");
		const options: ReviewOptions = { directions: [summary] };
		const record: { status: "FOUND"; text: string } | { status: "ABSENT" } =
			await ctx.evidence.record("metadata.json");
		const citation: Citation = await ctx.evidence.citeRecord("metadata.json", 1, 2);
		const error = new UnratedError("unavailable", "The capture was unavailable");
		const reason: string = error.reason;
		void [
			record,
			citation,
			reason,
			ctx.kind,
			ctx.item,
			ctx.scope,
			ctx.about,
			ctx.practice,
			ctx.review.id,
			ctx.review.origin,
			ctx.review.startedAt,
			ctx.now,
			ctx.abortSignal,
		];
		ctx.log("Prepared evidence");
		return ctx.review(options);
	},
});
void definition;

export async function consumeKits(ctx: PracticeScriptContext) {
	const thread = chat(ctx);
	const channel: string = thread.item.channel;
	const message = thread.item.messages[0];
	if (message) {
		const identity: string | null = message.accountId;
		void [identity, message.author, message.text, message.ts, message.edited];
	}
	const document = docs(ctx);
	const text: { status: "FOUND"; text: string } | { status: "ABSENT" } = await document.body();
	const citation: Citation = await document.cite(1, 2);
	const work: ScmWork<"scm.pull_request"> = scm(ctx, "scm.pull_request");
	const files: ReadonlyMap<string, DiffFile> = work.changedFiles();
	const sources: readonly SourceFile[] = await work.sources(
		({ path, language }) => !isTestPath(path) && language === "TypeScript",
	);
	const sites = await work.sites([{ kind: "test", label: "Test", test: /test/u }], {
		comments: true,
		overlap: "keep",
		enclosing: { scope: /Class/u, only: true },
		tests: true,
		listed: 2,
	});
	const { leads } = sites;
	const counts: ReadonlyMap<string, number> = sites.counts;
	void [
		channel,
		document.item.title,
		text,
		citation,
		files,
		sources,
		leads,
		counts,
		await work.commits(),
		await work.linkedIssues(),
		await work.inlineComments(),
		await work.conversationComments(),
		await work.reviewRecord(),
		await work.checks(),
	];
	for (const source of sources) {
		const added: boolean = source.isAdded(1);
		const found = source.sites([{ kind: "test", label: "Test", test: /test/u }]);
		const blocks: readonly { start: number; end: number }[] = source.blocks(/function/u);
		void [added, found, blocks];
	}
	const language: string | null = languageOf("src/example.ts");
	const references: number[] = branchIssueReferences("issue-42");
	const newer: boolean = later("2026-01-02", "2026-01-01");
	const knownDate: boolean = dated(undefined);
	void [
		language,
		references,
		newer,
		knownDate,
		issueReferences("Closes #42"),
		checkableItems("- [x] Done"),
		issueShape({ title: "Task", body: null, labels: [] }),
		subIssueRollup({ sub_issues_total: 2, sub_issues_completed: 1, sub_issues_source: "provider" }),
	];
	if (work.head) {
		await consumeTree(work.head);
	}
	if (work.change) {
		await consumeChange(work.change);
	}
}

async function consumeTree(tree: FileTree) {
	const commit: string = tree.commit;
	const file = await tree.read("src/example.ts", { offsetLine: 2, maxLines: 20 });
	if (file.status === "FOUND") {
		const text: string = file.text;
		const truncated: boolean = file.truncated;
		void [text, truncated];
	}
	const { paths }: { paths: readonly string[] } = await tree.list("src/**");
	const matches = await tree.grep("pattern");
	const citation: Citation = await tree.cite("src/example.ts", 1, 2);
	void [commit, paths, matches, citation];
}

async function consumeChange(change: ChangeView) {
	const base: string = change.base;
	const head: string = change.head;
	const files: ReadonlyMap<string, DiffFile> = change.files;
	const citation: Citation = await change.cite("src/example.ts", "NEW", 1, 2);
	void [base, head, files, citation];
}
