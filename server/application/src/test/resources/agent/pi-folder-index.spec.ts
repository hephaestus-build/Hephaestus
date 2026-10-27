import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import {
	folderCitationIndex,
	citableTextIndex,
} from "../../../main/resources/agent/pi-folder-index.ts";

await test("the full folder remains citable when a primary readiness source is absent", () => {
	const result = folderCitationIndex({
		sources: [
			{
				kind: "outline.documents",
				citation: { side: "FORBIDDEN", commit: "FORBIDDEN" },
				state: { availability: "UNAVAILABLE" },
			},
		],
		files: [
			{ kind: "outline.documents", file: { path: "context/docs/engineering/design.md" } },
			{ kind: "scm.repository.tree", file: { path: "repos/42/.git/HEAD" } },
		],
	});
	assert.equal(
		result.sourceFileSources.get("context/docs/engineering/design.md"),
		"outline.documents",
	);
	assert.equal(result.sourceFileSources.get("repos/42/.git/HEAD"), "scm.repository.tree");
	assert.equal(result.availableSourceKinds.has("outline.documents"), true);
});

const available = (kind: string, completeness: string) => ({
	kind,
	citation: { side: "OPTIONAL", commit: "OPTIONAL" },
	state: { availability: "AVAILABLE", completeness },
});

await test("only a source captured whole counts as complete", () => {
	const result = folderCitationIndex({
		sources: [
			available("scm.repository.tree", "PARTIAL"),
			available("scm.pull-request.diff", "COMPLETE"),
		],
		files: [],
	});
	assert.equal(result.availableSourceKinds.has("scm.repository.tree"), true);
	assert.deepEqual([...result.completeSourceKinds], ["scm.pull-request.diff"]);
});

await test("missing, duplicate and unsafe folder proofs fail closed", () => {
	assert.throws(() => folderCitationIndex({ sources: [] }), /files/u);
	const sourceFile = { kind: "outline.documents", file: { path: "context/docs/c/d.md" } };
	assert.throws(
		() => folderCitationIndex({ sources: [], files: [sourceFile, sourceFile] }),
		/duplicate/u,
	);
	for (const path of [
		"../foreign",
		"/foreign",
		"context/../foreign",
		String.raw`context\foreign`,
	]) {
		assert.throws(
			() => folderCitationIndex({ sources: [], files: [{ kind: "x", file: { path } }] }),
			/path/u,
		);
	}
});

await test("composed views are readable but only canonical records are citable", () => {
	const result = folderCitationIndex({
		sources: [],
		files: [
			{ kind: "review.problems", file: { path: "inputs/history/problems.json" } },
			{ kind: "review.problems", file: { path: "context/people/42/problems.jsonl" } },
		],
	});
	assert.equal(result.sourceFileSources.has("inputs/history/problems.json"), false);
	assert.equal(result.sourceFileSources.has("context/people/42/problems.jsonl"), true);
});

await test("logical captured texts preserve their digest, staged path and opaque version", () => {
	assert.deepEqual(citableTextIndex({}), []);
	for (const invalid of [null, {}, "text"]) {
		assert.throws(() => citableTextIndex({ citableTexts: invalid }), /citableTexts/u);
	}
	const text = {
		source: "custom.text",
		path: "thread/message",
		side: "NEW",
		commit: "opaque:v1",
		stagedPath: "context/message.txt",
		sha256: "a".repeat(64),
	};
	assert.deepEqual(citableTextIndex({ citableTexts: [text] }), [text]);
	for (const invalid of [
		{ ...text, stagedPath: "../foreign" },
		{ ...text, sha256: "bad" },
		{ ...text, side: "MIDDLE" },
	]) {
		assert.throws(() => citableTextIndex({ citableTexts: [invalid] }), /citableTexts/u);
	}
});

await test("the shared server manifest carries the source-catalog citation capabilities unchanged", () => {
	const { citationCapabilities } = folderCitationIndex(
		JSON.parse(readFileSync(new URL("folder-citation-index.json", import.meta.url), "utf8")),
	);
	assert.deepEqual(citationCapabilities.get("scm.pull-request.diff"), {
		side: "REQUIRED",
		commit: "OPTIONAL",
	});
	assert.deepEqual(citationCapabilities.get("scm.repository.tree"), {
		side: "FORBIDDEN",
		commit: "OPTIONAL",
	});
});
