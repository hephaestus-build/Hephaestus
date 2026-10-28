import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";
import { executionReportSchema } from "../../../../../../docker/agents/practice-scripts/lib/core/contract.ts";
import { z } from "../../../../../../docker/agents/practice-scripts/lib/core/practice-script.ts";
import { buildBrief } from "../../../main/resources/agent/pi-review-brief.ts";
import {
	capturedLines,
	readJsonFile,
	runRunner,
	SESSION_CANDIDATE,
	stagePractices,
} from "./program-host-runner.ts";

const fixture = z
	.object({
		contents: z.record(z.string(), z.string()),
		manifest: z.object({
			files: z.array(z.unknown()),
			sources: z.array(z.unknown()),
			citableTexts: z.array(z.unknown()),
		}),
	})
	.parse(
		JSON.parse(readFileSync(new URL("record-coverage-capture.json", import.meta.url), "utf8")),
	);
const manifestSchema = z.looseObject({
	files: z.array(z.looseObject({ kind: z.string(), file: z.object({ path: z.string() }) })),
	sources: z.array(z.looseObject({ kind: z.string() })),
});

for (const gap of [false, true]) {
	void test(`a Java capture manifest with extracted record copies and aliases ${gap ? "retains a real unread gap" : "permits MET from the complete parent record"}`, async (t) => {
		const cwd = mkdtempSync(path.join(tmpdir(), "pi-record-coverage-"));
		t.after(() => rmSync(cwd, { recursive: true, force: true }));
		stagePractices(cwd, [{ slug: "test-practice" }]);
		const manifestPath = path.join(cwd, "evidence/manifest.json");
		const manifest = manifestSchema.parse(JSON.parse(readFileSync(manifestPath, "utf8")));
		for (const [file, text] of Object.entries(fixture.contents)) {
			mkdirSync(path.dirname(path.join(cwd, file)), { recursive: true });
			writeFileSync(path.join(cwd, file), text);
		}
		const files = [
			...manifest.files.filter((file) => file.kind !== "scm.pull-request.core"),
			...fixture.manifest.files,
		];
		if (gap) {
			writeFileSync(path.join(cwd, "evidence/unseen.json"), '{"body":"Independent evidence"}');
			files.push({ kind: "scm.pull-request.core", file: { path: "evidence/unseen.json" } });
		}
		writeFileSync(
			manifestPath,
			JSON.stringify({
				...manifest,
				files,
				sources: [
					...manifest.sources.filter((source) => source.kind !== "scm.pull-request.core"),
					...fixture.manifest.sources,
				],
				citableTexts: fixture.manifest.citableTexts,
			}),
		);
		const candidate = {
			...SESSION_CANDIDATE,
			problems: undefined,
			outcome: "MET",
			citations: [
				{
					sourceKind: "scm.pull-request.core",
					sourcePath: "evidence/metadata.json",
					path: "evidence/metadata.json",
					startLine: 1,
					endLine: 1,
					quote: fixture.contents["evidence/metadata.json"],
				},
			],
		};
		const exit = await runRunner(cwd, {}, { PI_HOST_SESSION_CANDIDATE: JSON.stringify(candidate) });
		assert.equal(exit.status, gap ? 1 : 0, exit.stderr);
		if (gap) {
			assert.match(exit.stderr, /unseen\.json/u);
		} else {
			assert.equal(capturedLines(cwd, "submitted").length, 1);
		}
	});
}

for (const command of [
	"cat evidence/shell.txt",
	"head -n 1 evidence/shell.txt",
	"tail -n 1 evidence/shell.txt",
	"sed -n '2,2p' evidence/shell.txt",
	"cat evidence/shell.txt | head -n 1",
]) {
	void test(`the runner ignores incidental partial shell reads for ${command}`, async (t) => {
		const cwd = mkdtempSync(path.join(tmpdir(), "pi-shell-replay-"));
		t.after(() => rmSync(cwd, { recursive: true, force: true }));
		stagePractices(cwd, [{ slug: "test-practice" }]);
		writeFileSync(path.join(cwd, "evidence/shell.txt"), "first fact\nsecond fact\nthird fact");
		const candidate = {
			...SESSION_CANDIDATE,
			problems: undefined,
			outcome: "MET",
			citations: [{ path: "evidence/metadata.json", startLine: 1, quote: "Add login" }],
			evidence: {},
		};
		const exit = await runRunner(
			cwd,
			{},
			{ PI_HOST_SESSION_CANDIDATE: JSON.stringify(candidate), PI_HOST_BASH_COMMAND: command },
		);
		assert.equal(exit.status, 0, exit.stderr);
		assert.equal(capturedLines(cwd, "submitted").length, 1);
	});
}

for (const [command, complete, sameBytes] of [
	["head -n 1 repos/primary/src/Checked.java", false, true],
	["cat repos/primary/src/Checked.java", true, true],
	["cat repos/primary/src/Checked.java", false, false],
] as const) {
	void test(`a cited repository file needs complete coverage (sameBytes=${sameBytes}): ${command}`, async (t) => {
		const cwd = mkdtempSync(path.join(tmpdir(), "pi-cited-shell-replay-"));
		t.after(() => rmSync(cwd, { recursive: true, force: true }));
		stagePractices(cwd, [{ slug: "test-practice" }]);
		const manifestPath = path.join(cwd, "evidence/manifest.json");
		const manifest = manifestSchema.parse(JSON.parse(readFileSync(manifestPath, "utf8")));
		const text =
			readFileSync(path.join(cwd, "repos/primary/src/Checked.java"), "utf8") +
			(sameBytes ? "" : "extra captured context\n");
		const stagedPath = "evidence/checked.java";
		writeFileSync(path.join(cwd, stagedPath), text);
		writeFileSync(
			manifestPath,
			JSON.stringify({
				...manifest,
				citableTexts: [
					{
						source: "scm.repository.tree",
						path: "src/Checked.java",
						side: "NEW",
						commit: "a".repeat(40),
						stagedPath,
						sha256: createHash("sha256").update(text).digest("hex"),
					},
				],
				files: [
					...manifest.files,
					...["repos/primary/.git/HEAD", "repos/primary/.git/hephaestus-captured-refs"].map(
						(witness) => ({ kind: "scm.repository.tree", file: { path: witness } }),
					),
				],
				sources: [
					...manifest.sources,
					{
						kind: "scm.repository.tree",
						citation: { side: "OPTIONAL", commit: "OPTIONAL" },
						state: { availability: "AVAILABLE" },
					},
				],
			}),
		);
		const candidate = {
			...SESSION_CANDIDATE,
			problems: undefined,
			outcome: "MET",
			evidence: {},
			citations: [
				{
					sourceKind: "scm.repository.tree",
					sourcePath: "repos/primary/.git/HEAD",
					path: "src/Checked.java",
					startLine: 2,
					quote: "  checked(token);",
				},
			],
		};
		const exit = await runRunner(
			cwd,
			{},
			{
				PI_HOST_SESSION_CANDIDATE: JSON.stringify(candidate),
				PI_HOST_BASH_COMMAND: command,
			},
		);
		assert.equal(exit.status, complete ? 0 : 1, exit.stderr);
		if (complete) {
			assert.equal(capturedLines(cwd, "submitted").length, 1);
		} else {
			// Reading the checkout cannot cover a captured copy with other bytes, so the repair names the copy.
			assert.ok(
				exit.stderr.includes(
					`read(${JSON.stringify({ path: stagedPath, offset: 1, limit: text.split("\n").length })})`,
				),
				exit.stderr,
			);
			assert.doesNotMatch(exit.stderr, /coverage is unknown|bash: head/u);
		}
	});
}

for (const command of [
	"ls",
	"find . -name '*.json' | head -10",
	"git -C repos/primary log --oneline -5 2>/dev/null",
	"pwd",
	"cat .gitignore 2>/dev/null",
	"echo ---; ls; echo ---",
]) {
	void test(`the runner admits MET after incidental exploration: ${command}`, async (t) => {
		const cwd = mkdtempSync(path.join(tmpdir(), "pi-exploration-replay-"));
		t.after(() => rmSync(cwd, { recursive: true, force: true }));
		stagePractices(cwd, [{ slug: "test-practice" }]);
		const candidate = {
			...SESSION_CANDIDATE,
			problems: undefined,
			outcome: "MET",
			evidence: {},
			citations: [{ path: "evidence/metadata.json", startLine: 1, quote: "Add login" }],
		};
		const exit = await runRunner(
			cwd,
			{},
			{
				PI_HOST_SESSION_CANDIDATE: JSON.stringify(candidate),
				PI_HOST_BASH_COMMAND: command,
			},
		);
		assert.equal(exit.status, 0, exit.stderr);
		assert.equal(capturedLines(cwd, "submitted").length, 1);
	});
}

for (const mode of ["supplied", "omitted", "not-in-brief"] as const) {
	void test(`a cited canonical linked source outside the file inventory gets only actual brief credit: ${mode}`, async (t) => {
		const cwd = mkdtempSync(path.join(tmpdir(), "pi-linked-brief-coverage-"));
		t.after(() => rmSync(cwd, { recursive: true, force: true }));
		stagePractices(cwd, [
			{
				slug: "test-practice",
				readsSources: ["scm.pull-request.core", "scm.pull-request.diff", "scm.linked-work-items"],
			},
		]);
		const manifestPath = path.join(cwd, "evidence/manifest.json");
		const manifest = manifestSchema.parse(JSON.parse(readFileSync(manifestPath, "utf8")));
		const canonical = `evidence/${mode === "not-in-brief" ? "other" : "linked_work_items/3"}.md`;
		const quote = "The linked issue describes the intended authentication behavior.";
		const text =
			[quote, ...Array.from({ length: 13 }, (_, i) => `Captured detail ${i + 1}.`)].join("\n") +
			(mode === "omitted" ? `\n${"omitted detail ".repeat(2000)}` : "");
		mkdirSync(path.dirname(path.join(cwd, canonical)), { recursive: true });
		writeFileSync(path.join(cwd, canonical), text);
		// This unrelated omission is outside the practice's required sources and is never cited.
		writeFileSync(path.join(cwd, "evidence/general_comments.json"), " ".repeat(30_000));
		const linkedSource = "scm.linked-work-items";
		const witness = "evidence/linked_work_items.json";
		writeFileSync(path.join(cwd, witness), JSON.stringify({ workItems: [{ number: 3 }] }));
		writeFileSync(
			manifestPath,
			JSON.stringify({
				...manifest,
				files: [...manifest.files, { kind: linkedSource, file: { path: witness } }],
				sources: [
					...manifest.sources,
					{
						kind: linkedSource,
						citation: { side: "FORBIDDEN", commit: "FORBIDDEN" },
						state: { availability: "AVAILABLE" },
					},
				],
				citableTexts: [
					{
						source: linkedSource,
						path: canonical,
						side: "NEW",
						commit: null,
						stagedPath: canonical,
						sha256: createHash("sha256").update(text).digest("hex"),
					},
				],
			}),
		);
		const brief = buildBrief(
			cwd,
			{ contextRoot: "evidence", repositoryRoot: "repos/primary" },
			{
				declared: new Set([linkedSource]),
				fileKinds: new Map([[witness, linkedSource]]),
				available: new Set([linkedSource]),
			},
		);
		assert.equal(
			brief.sources.find((source) => source.path === canonical)?.supplied,
			mode === "not-in-brief" ? undefined : mode === "supplied",
		);
		// A source the practice does not declare is not in its brief at all.
		assert.equal(
			brief.sources.find((source) => source.path === "evidence/general_comments.json"),
			undefined,
		);
		const candidate = {
			...SESSION_CANDIDATE,
			problems: undefined,
			outcome: "MET",
			evidence: {},
			citations: [
				{
					sourceKind: linkedSource,
					sourcePath: witness,
					path: canonical,
					startLine: 1,
					endLine: 1,
					quote,
				},
			],
		};
		const exit = await runRunner(cwd, {}, { PI_HOST_SESSION_CANDIDATE: JSON.stringify(candidate) });
		const complete = mode === "supplied";
		assert.equal(exit.status, complete ? 0 : 1, exit.stderr);
		const [entry] = executionReportSchema.parse(
			readJsonFile(cwd, "out/practice-scripts.json"),
		).practices;
		assert.ok(entry);
		if (complete) {
			assert.equal(capturedLines(cwd, "submitted").length, 1);
			assert.deepEqual(entry.reviewSessions, {
				modelCalls: 1,
				evidenceRefusals: 0,
				formatRefusals: 0,
			});
			assert.doesNotMatch(exit.stderr, /Read the unread file lines/u);
		} else {
			assert.equal(entry.status, "failed");
			assert.ok((entry.reviewSessions?.evidenceRefusals ?? 0) > 0);
			assert.ok(exit.stderr.includes(canonical), exit.stderr);
		}
	});
}

void test("a session that cites a source its practice does not declare is refused with what it may cite", async (t) => {
	const cwd = mkdtempSync(path.join(tmpdir(), "pi-undeclared-citation-"));
	t.after(() => rmSync(cwd, { recursive: true, force: true }));
	stagePractices(cwd, [{ slug: "test-practice", readsSources: ["scm.pull-request.diff"] }]);
	// The candidate cites the pull request record, which a diff-only practice does not declare.
	const exit = await runRunner(
		cwd,
		{},
		{ PI_HOST_SESSION_CANDIDATE: JSON.stringify(SESSION_CANDIDATE) },
	);
	assert.equal(exit.status, 1, exit.stderr);
	assert.match(
		exit.stderr,
		/cites or consulted `scm\.pull-request\.core`, which test-practice does not declare\. Cite only: scm\.pull-request\.diff, scm\.repository\.tree\./u,
	);
	assert.equal(capturedLines(cwd, "submitted").length, 0);
});
