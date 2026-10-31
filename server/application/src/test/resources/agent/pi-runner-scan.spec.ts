import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import {
	existsSync,
	mkdirSync,
	mkdtempSync,
	realpathSync,
	readFileSync,
	rmSync,
	writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";

import { isJsonObject } from "../../../../../../docker/agents/practice-scripts/lib/core/json.ts";
import { scan } from "../../../../../../docker/agents/practice-scripts/run-bundled.ts";
import { PRACTICES_DIR } from "./golden-review-workspace.ts";
import {
	capturedLines,
	field,
	runRunner,
	stagePractices,
	toldTheModel,
} from "./program-host-runner.ts";

const FILE = "Auth.ts";
const TEXT = "insecure();\n";
const CONTEXT_FILE = "Context.ts";
const CONTEXT_TEXT = "checked(token);\n";
const SOURCE_PATH = "context/item.json";
const REPOSITORY = "repos/reviewed";

function gitBlobId(text: string): string {
	return createHash("sha1")
		.update(`blob ${Buffer.byteLength(text)}\0`)
		.update(text)
		.digest("hex");
}

function stageScan(cwd: string, includeContext = false): string {
	stagePractices(cwd, [{ slug: "auth-review" }]);
	rmSync(path.join(cwd, "evidence"), { recursive: true });
	mkdirSync(path.join(cwd, "context"));
	mkdirSync(path.join(cwd, REPOSITORY), { recursive: true });
	const task: unknown = JSON.parse(readFileSync(path.join(cwd, "task.json"), "utf8"));
	assert.ok(isJsonObject(task));
	writeFileSync(
		path.join(cwd, "task.json"),
		JSON.stringify({
			...task,
			contextRoot: "context",
			repositoryRoot: REPOSITORY,
			manifest: "INDEX.json",
		}),
	);
	const commit = "a".repeat(40);

	const files = [
		{ path: FILE, text: TEXT },
		...(includeContext ? [{ path: CONTEXT_FILE, text: CONTEXT_TEXT }] : []),
	];
	for (const file of files) {
		writeFileSync(path.join(cwd, REPOSITORY, file.path), file.text);
	}
	const inputs = scan(FILE);
	const item = { ...inputs.item, commitId: commit, blobId: gitBlobId(TEXT) };
	writeFileSync(path.join(cwd, SOURCE_PATH), JSON.stringify(item));

	writeFileSync(
		path.join(cwd, PRACTICES_DIR, "auth-review.inputs.json"),
		JSON.stringify({
			...inputs,
			item,
			practice: { ...inputs.practice, slug: "auth-review" },
			scope: { ...inputs.scope, version: `scm.file:v1:${item.blobId}:${"b".repeat(64)}` },
		}),
	);
	writeFileSync(
		path.join(cwd, "catalog/practices/index.json"),
		JSON.stringify([
			{
				slug: "auth-review",
				name: "Authentication review",
				group: "security",
				versionId: "1",
				readsSources: ["scm.repository.tree"],
				exhaustiveSources: ["scm.repository.tree"],
				contextPaths: includeContext ? [CONTEXT_FILE] : [],
			},
		]),
	);
	writeFileSync(
		path.join(cwd, "INDEX.json"),
		JSON.stringify({
			itemKind: "scm.file",
			files: [SOURCE_PATH].map((witness) => ({
				kind: "scm.repository.tree",
				file: {
					path: witness,
					sha256: createHash("sha256")
						.update(readFileSync(path.join(cwd, witness)))
						.digest("hex"),
				},
			})),
			sources: [
				{
					kind: "scm.repository.tree",
					citation: { side: "FORBIDDEN", commit: "OPTIONAL" },
					state: { availability: "AVAILABLE" },
					files: [{ path: SOURCE_PATH }],
				},
			],
			citableTexts: files.flatMap((file) =>
				[commit, null].map((version) => ({
					source: "scm.repository.tree",
					path: file.path,
					side: "NEW",
					commit: version,
					stagedPath: `${REPOSITORY}/${file.path}`,
					sha256: createHash("sha256").update(file.text).digest("hex"),
				})),
			),
		}),
	);
	assert.equal(existsSync(path.join(cwd, REPOSITORY, ".git")), false);
	assert.equal(existsSync(path.join(cwd, "context/inventory.json")), false);
	return commit;
}

for (const [basis, explicitCommit, incremental = false] of [
	["state", true],
	["state", false],
	["introduced", true],
	["state", false, true],
	["introduced", true, true],
] as const) {
	void test(`SCAN ${basis === "state" ? "admits state with complete pinned-file receipts" : "rejects introduced basis"} through tool validation (explicit commit=${explicitCommit}, incremental=${incremental})`, async () => {
		const cwd = realpathSync(mkdtempSync(path.join(tmpdir(), "pi-runner-scan-")));
		try {
			const commit = stageScan(cwd);
			const candidate = {
				outcome: "NOT_MET",
				summary: "Authentication uses an insecure call",
				explanation: "The pinned source calls insecure().",
				citations: [],
				evidence: {},
				problems: [
					{
						...(incremental ? { key: "insecure-call" } : {}),
						severity: "MAJOR",
						summary: "Authentication uses an insecure call",
						explanation: "The source calls insecure() without checked().",
						citations: [
							{
								sourceKind: "scm.repository.tree",
								sourcePath: SOURCE_PATH,
								path: FILE,
								...(explicitCommit ? { commit } : {}),
								startLine: 1,
								endLine: 1,
								quote: "insecure();",
							},
						],
						basis: {
							type: basis,
							...(basis === "introduced" ? { head: 0 } : {}),
							explanation: "The pinned file contains the insecure call.",
						},
					},
				],
			};
			const exit = await runRunner(
				cwd,
				{},
				{
					PI_HOST_SCAN_PATH: FILE,
					PI_HOST_PROBLEM_DRAFTS: String(incremental),
					PI_HOST_SESSION_CANDIDATE: JSON.stringify(candidate),
					PI_PRACTICE_CONCURRENCY: "3",
				},
			);
			assert.deepEqual(capturedLines(cwd, "scan-read")[0], {
				content: [{ type: "text", text: TEXT }],
				details: { status: "FOUND", truncated: false },
			});
			if (basis === "introduced") {
				assert.equal(exit.status, 1, exit.stderr);
				// The submission's validation, or in an incremental scan the draft's, refuses the basis.
				assert.match(
					toldTheModel(cwd),
					/basis_not_allowed: problems\.0\.basis: a scan needs state basis/u,
				);
				assert.deepEqual(capturedLines(cwd, "admission"), []);
				return;
			}
			assert.equal(exit.status, 0, exit.stderr);
			assert.match(exit.stderr, /review\.concurrency configured=3 effective=1/u);
			const [request] = capturedLines(cwd, "admission");
			const body = field(request, "body");
			const results = field(body, "results");
			assert.ok(Array.isArray(results));
			assert.equal(results.length, 1);
			const submitted = field(results[0], "candidate");
			assert.ok(isJsonObject(submitted));
			const { coverage, ...measured } = submitted;
			assert.deepEqual(measured, {
				...candidate,
				problems: candidate.problems.map((problem) => ({
					...problem,
					citations: problem.citations.map((citation) => ({ ...citation, commit })),
				})),
			});
			assert.partialDeepStrictEqual(coverage, {
				source: "host",
				reads: 2,
				complete: true,
				failedReads: 0,
				truncatedReads: 0,
			});
			const receipts = field(coverage, "receipts");
			assert.ok(Array.isArray(receipts));
			assert.equal(receipts.length, 1);
			assert.deepEqual(field(results[0], "readReceipts"), receipts);
			for (const receipt of receipts) {
				assert.partialDeepStrictEqual(receipt, {
					sourceKind: "scm.repository.tree",
					sourcePath: SOURCE_PATH,
					path: FILE,
					version: commit,
					status: "COMPLETE",
					startLine: 1,
					endLine: 2,
					contentDigest: createHash("sha256").update(TEXT).digest("hex"),
				});
			}
		} finally {
			rmSync(cwd, { recursive: true, force: true });
		}
	});
}

for (const [readContext, collision] of [
	[false, false],
	[true, false],
	[false, true],
	[true, true],
] as const) {
	void test(`scan MET needs a complete read of cited context (read=${readContext}, same-named session file=${collision})`, async (t) => {
		const cwd = realpathSync(mkdtempSync(path.join(tmpdir(), "pi-scan-context-coverage-")));
		t.after(() => rmSync(cwd, { recursive: true, force: true }));
		const commit = stageScan(cwd, true);
		if (collision) {
			// A session file with the cited repository path must not shape the governed repair call.
			writeFileSync(path.join(cwd, CONTEXT_FILE), "unrelated\n".repeat(40));
		}
		const candidate = {
			outcome: "MET",
			summary: "The file uses checked authentication",
			explanation: "The context supplies the checked authentication helper.",
			citations: [
				{
					sourceKind: "scm.repository.tree",
					sourcePath: SOURCE_PATH,
					path: CONTEXT_FILE,
					commit,
					startLine: 1,
					endLine: 1,
					quote: "checked(token);",
				},
			],
			evidence: {},
		};
		const exit = await runRunner(
			cwd,
			{},
			{
				PI_HOST_SCAN_PATH: readContext ? CONTEXT_FILE : FILE,
				PI_HOST_SESSION_CANDIDATE: JSON.stringify(candidate),
			},
		);
		assert.equal(exit.status, readContext ? 0 : 1, exit.stderr);
		if (readContext) {
			const [request] = capturedLines(cwd, "admission");
			const results = field(field(request, "body"), "results");
			assert.ok(Array.isArray(results));
			assert.equal(field(field(field(results[0], "candidate"), "coverage"), "complete"), true);
		} else {
			assert.ok(
				toldTheModel(cwd).includes(`read(${JSON.stringify({ path: CONTEXT_FILE })})`),
				exit.stderr,
			);
			assert.doesNotMatch(
				toldTheModel(cwd),
				/"path":"Context\.ts","offset"|physical lines|Bash output/u,
			);
			assert.deepEqual(capturedLines(cwd, "admission"), []);
		}
	});
}

for (const [fieldName, value] of [
	["path", "Other.ts"],
	["blobId", "d".repeat(40)],
	["commitId", "d".repeat(40)],
	["commitId", "HEAD"],
	["providerId", "2"],
	["repositoryId", "2"],
] as const) {
	void test(`scan refuses a conflicting captured ${fieldName} (${value}) before a model session starts`, async (t) => {
		const cwd = realpathSync(mkdtempSync(path.join(tmpdir(), "pi-scan-conflict-")));
		t.after(() => rmSync(cwd, { recursive: true, force: true }));
		stageScan(cwd);
		const record: unknown = JSON.parse(readFileSync(path.join(cwd, SOURCE_PATH), "utf8"));
		assert.ok(isJsonObject(record));
		writeFileSync(path.join(cwd, SOURCE_PATH), JSON.stringify({ ...record, [fieldName]: value }));
		const exit = await runRunner(cwd, {}, { PI_HOST_SCAN_PATH: FILE });
		assert.equal(exit.status, 2, exit.stderr);
		assert.match(
			exit.stderr,
			value === "HEAD"
				? /review\.fatal error=ZodError/u
				: /review\.fatal error=UnratedError\.unavailable/u,
		);
		assert.deepEqual(capturedLines(cwd, "prompts"), []);
		assert.deepEqual(capturedLines(cwd, "admission"), []);
	});
}

void test("a scan script receives the pinned tree and returns a citation without Git metadata", async (t) => {
	const cwd = realpathSync(mkdtempSync(path.join(tmpdir(), "pi-scan-script-")));
	t.after(() => rmSync(cwd, { recursive: true, force: true }));
	const commit = stageScan(cwd);
	writeFileSync(
		path.join(cwd, PRACTICES_DIR, "auth-review.ts"),
		`
import assert from "node:assert/strict";
import { definePracticeScript } from "../lib/core/practice-script.ts";
import { scm } from "../kits/scm/index.ts";
export default definePracticeScript({
	meta: {},
	async run(input) {
		const ctx = scm(input);
		assert.ok(ctx.files);
		assert.equal(ctx.files.head.commit, ctx.item.commitId);
		assert.equal(ctx.files.change, null);
		const file = await ctx.files.head.read(ctx.scope.item.path);
		assert.deepEqual(file, { status: "FOUND", text: ${JSON.stringify(TEXT)}, truncated: false });
		return {
			outcome: "MET",
			summary: "The file has captured source",
			explanation: "The assigned source is readable at its pinned commit.",
			citations: [await ctx.files.head.cite(ctx.scope.item.path, 1)],
			evidence: {},
		};
	},
});
`,
	);
	const exit = await runRunner(cwd, {});
	assert.equal(exit.status, 0, exit.stderr);
	const [request] = capturedLines(cwd, "admission");
	const results = field(field(request, "body"), "results");
	assert.ok(Array.isArray(results));
	assert.partialDeepStrictEqual(field(results[0], "candidate"), {
		outcome: "MET",
		citations: [
			{
				sourceKind: "scm.repository.tree",
				sourcePath: SOURCE_PATH,
				path: FILE,
				commit,
				quote: "insecure();",
			},
		],
		coverage: { complete: true },
	});
});
