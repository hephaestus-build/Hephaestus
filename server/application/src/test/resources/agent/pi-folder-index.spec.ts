import assert from "node:assert/strict";
import { test } from "node:test";
import { folderCitationIndex } from "../../../main/resources/agent/pi-folder-index.ts";

await test("the full folder remains citable when a primary readiness source is absent", () => {
	const result = folderCitationIndex({
		sources: [{ kind: "outline.documents", state: { availability: "UNAVAILABLE" } }],
		artifacts: [
			{ kind: "outline.documents", artifact: { path: "context/docs/engineering/design.md" } },
			{ kind: "scm.repository.tree", artifact: { path: "repos/42/.git/HEAD" } },
		],
	});
	assert.equal(
		result.artifactSources.get("context/docs/engineering/design.md"),
		"outline.documents",
	);
	assert.equal(result.artifactSources.get("repos/42/.git/HEAD"), "scm.repository.tree");
	assert.equal(result.availableSourceKinds.has("outline.documents"), true);
});

await test("missing, duplicate and unsafe folder proofs fail closed", () => {
	assert.throws(() => folderCitationIndex({ sources: [] }), /expected sources and artifacts/u);
	const artifact = { kind: "outline.documents", artifact: { path: "context/docs/c/d.md" } };
	assert.throws(
		() => folderCitationIndex({ sources: [], artifacts: [artifact, artifact] }),
		/duplicate/u,
	);
	for (const path of [
		"../foreign",
		"/foreign",
		"context/../foreign",
		String.raw`context\foreign`,
	]) {
		assert.throws(
			() => folderCitationIndex({ sources: [], artifacts: [{ kind: "x", artifact: { path } }] }),
			/invalid/u,
		);
	}
});

await test("composed history is readable but only canonical history is citable", () => {
	const result = folderCitationIndex({
		sources: [],
		artifacts: [
			{ kind: "review.observations", artifact: { path: "inputs/history/observations.json" } },
			{ kind: "review.observations", artifact: { path: "context/people/42/observations.jsonl" } },
		],
	});
	assert.equal(result.artifactSources.has("inputs/history/observations.json"), false);
	assert.equal(result.artifactSources.has("context/people/42/observations.jsonl"), true);
});
