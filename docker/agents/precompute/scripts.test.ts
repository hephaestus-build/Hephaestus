import assert from "node:assert/strict";
import { cp, mkdir, mkdtemp, rm, symlink, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { afterEach, before, describe, it } from "node:test";

import { isPracticeModule } from "./lib/practice-contract.ts";
import type { DiffFile, PracticeScript } from "./lib/types.ts";

const SCRIPTS_DIR = resolve(
	import.meta.dirname,
	"../../../server/application/src/main/resources/practices/precompute",
);
const LIB_DIR = resolve(import.meta.dirname, "lib");

const tempDirs: string[] = [];

async function createTempDir(prefix: string): Promise<string> {
	const dir = await mkdtemp(join(tmpdir(), prefix));
	tempDirs.push(dir);
	return dir;
}

/**
 * Stage a single script in a work dir laid out like the runner (`practices/<script>` + symlinked `lib/`)
 * so its `../lib/types` import resolves, then import the staged copy and return its default export.
 */
async function loadScript(name: string): Promise<PracticeScript> {
	const work = await createTempDir(`pc-script-${name}-`);
	await mkdir(join(work, "practices"), { recursive: true });
	await writeFile(join(work, "package.json"), '{"type":"module"}\n');
	await symlink(LIB_DIR, join(work, "lib"));
	const staged = join(work, "practices", `${name}.ts`);
	await cp(join(SCRIPTS_DIR, `${name}.ts`), staged);
	const mod: unknown = await import(staged);
	if (!isPracticeModule(mod)) {
		throw new Error(`${name} does not export a default function`);
	}
	return mod.default;
}

/** A path the diff touched. The scripts under test read the changed paths, not the hunk bodies. */
function changedFile(path: string): DiffFile {
	return { path, addedLines: new Map(), removedLines: new Map(), hunks: [] };
}

afterEach(async () => {
	await Promise.all(tempDirs.splice(0).map((dir) => rm(dir, { recursive: true, force: true })));
});

void describe("ships-tests-with-the-change", () => {
	let run: PracticeScript;
	before(async () => {
		run = await loadScript("ships-tests-with-the-change");
	});

	async function repoWith(files: Record<string, string>): Promise<string> {
		const repo = await createTempDir("pc-repo-");
		for (const [path, body] of Object.entries(files)) {
			const full = join(repo, path);
			await mkdir(join(full, ".."), { recursive: true });
			await writeFile(full, body);
		}
		return repo;
	}

	void it("classifies code vs test files in a single walk and flags an untested prod change", async () => {
		const repo = await repoWith({
			"src/App.swift": "struct App {}",
			"src/util.ts": "export const x = 1;",
			"Tests/AppTests.swift": "// test",
		});
		// Diff touches a production file, adds no test file.
		const diff = new Map([["src/App.swift", changedFile("src/App.swift")]]);

		const result = await run(repo, diff, {});

		assert.equal(result.metrics.repoCodeFileCount, 3);
		assert.equal(result.metrics.repoTestFileCount, 1);
		assert.equal(result.metrics.worktreeVisible, 1);
		assert.equal(result.metrics.diffProductionFiles, 1);
		assert.equal(result.metrics.diffTestFiles, 0);
		assert.ok(result.directions.join(" ").includes("0 test file(s)"));
	});

	void it("excludes node_modules / dotfile / .build dirs from the census", async () => {
		const repo = await repoWith({
			"src/main.ts": "export const a = 1;",
			"node_modules/dep/index.js": "module.exports = {};",
			".build/cache.swift": "struct Cached {}",
			".hidden/secret.go": "package x",
		});

		const result = await run(repo, new Map<string, DiffFile>(), {});

		// Only src/main.ts counts; the three excluded-dir files are skipped.
		assert.equal(result.metrics.repoCodeFileCount, 1);
	});

	void it("reports the worktree as not visible when zero source files are seen", async () => {
		const repo = await repoWith({ "README.md": "# docs only" });

		const result = await run(repo, new Map<string, DiffFile>(), {});

		assert.equal(result.metrics.worktreeVisible, 0);
		assert.ok(result.directions.join(" ").includes("WORKTREE NOT VISIBLE"));
	});
});

void describe("issue classification across practices", () => {
	const names = [
		"issue-has-checkable-outcome",
		"issue-states-an-actionable-problem",
		"issue-scoped-to-single-concern",
	];

	for (const { name, metadata, emptyOrTitleEcho, hasDeliverableType, looksUmbrella } of [
		{
			name: "empty deliverable",
			metadata: { title: "Export billing reports", labels: ["FEATURE"] },
			emptyOrTitleEcho: 1,
			hasDeliverableType: 1,
			looksUmbrella: 0,
		},
		{
			name: "title repeated with different punctuation",
			metadata: {
				title: "Export billing reports to CSV",
				body: "EXPORT billing reports: to CSV!",
				issue_type: "Task",
			},
			emptyOrTitleEcho: 1,
			hasDeliverableType: 1,
			looksUmbrella: 0,
		},
		{
			name: "substantive umbrella",
			metadata: {
				title: "Billing improvements",
				body: "Split the invoice work into independently deliverable child issues with their own acceptance criteria.",
				labels: ["REQUIREMENT"],
			},
			emptyOrTitleEcho: 0,
			hasDeliverableType: 1,
			looksUmbrella: 1,
		},
		{
			name: "substantive body with an omitted title",
			metadata: {
				body: "The PDF contains overlapping text when a customer's address spans more than three lines.",
				labels: ["BUG"],
			},
			emptyOrTitleEcho: 0,
			hasDeliverableType: 1,
			looksUmbrella: 0,
		},
		{
			name: "substantive body with an empty title",
			metadata: {
				title: "",
				body: "The PDF contains overlapping text when a customer's address spans more than three lines.",
				labels: ["BUG"],
			},
			emptyOrTitleEcho: 0,
			hasDeliverableType: 1,
			looksUmbrella: 0,
		},
		{
			name: "substantive body with a non-Latin title",
			metadata: {
				title: "报告",
				body: "The PDF contains overlapping text when a customer's address spans more than three lines.",
				labels: ["BUG"],
			},
			emptyOrTitleEcho: 0,
			hasDeliverableType: 1,
			looksUmbrella: 0,
		},
		{
			name: "substantive untyped issue",
			metadata: {
				title: "Unreadable invoice",
				body: "The PDF contains overlapping text when a customer's address spans more than three lines.",
			},
			emptyOrTitleEcho: 0,
			hasDeliverableType: 0,
			looksUmbrella: 0,
		},
	]) {
		void it(`preserves shared facts for a ${name}`, async () => {
			for (const scriptName of names) {
				const run = await loadScript(scriptName);
				const result = await run("", new Map(), metadata);
				assert.equal(result.metrics.emptyOrTitleEcho, emptyOrTitleEcho, scriptName);
				assert.deepEqual(result.hints, [], scriptName);
				if (scriptName !== "issue-scoped-to-single-concern") {
					assert.equal(result.metrics.hasDeliverableType, hasDeliverableType, scriptName);
					assert.equal(result.metrics.looksUmbrella, looksUmbrella, scriptName);
				}
				if (
					scriptName === "issue-states-an-actionable-problem" &&
					emptyOrTitleEcho &&
					hasDeliverableType
				) {
					assert.ok(
						result.directions.some((direction) =>
							direction.includes("investigate whether a maintainer can actually act on it."),
						),
					);
				}
			}
		});
	}
});

void it("linked-work analysis reads only explicitly supplied context, never a repository-derived fallback", async () => {
	const root = await createTempDir("linked-work-context-");
	const analyse = await loadScript("honours-linked-issue-acceptance-criteria");
	try {
		const legacy = join(root, "inputs", "context");
		const context = join(root, "areas/linked work");
		const repo = join(root, "inputs", "sources", "scm", "repo");
		await mkdir(legacy, { recursive: true });
		await mkdir(context, { recursive: true });
		await writeFile(
			join(legacy, "linked_work_items.json"),
			JSON.stringify({ workItems: [{ bodyExcerpt: "- [ ] WRONG CONTEXT" }] }),
		);
		await writeFile(
			join(context, "linked_work_items.json"),
			JSON.stringify({ workItems: [{ bodyExcerpt: "Acceptance criteria\n- [ ] one\n- [ ] two" }] }),
		);
		const metadata = {
			source_branch: "fix-example",
			title: "Fixes #12",
			pr_number: 1,
			pr_url: "https://example.invalid/pull/1",
			repository_full_name: "owner/project",
			target_branch: "main",
			commit_sha: "a".repeat(40),
		};
		const explicit = await analyse(repo, new Map(), metadata, context);
		assert.equal(explicit.metrics.acceptanceCriteriaCheckboxes, 2);
		const absent = await analyse(repo, new Map(), metadata);
		assert.equal(absent.metrics.linkedItemsFilePresent, 0);
		const missing = await analyse(repo, new Map(), metadata, join(root, "missing"));
		assert.equal(missing.metrics.linkedItemsFilePresent, 0);
	} finally {
		await rm(root, { recursive: true, force: true });
	}
});
