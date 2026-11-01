import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";

const read = (file: string) => readFileSync(new URL(`../${file}`, import.meta.url), "utf8");

await test("tenant host and redirect policies reserve every server label", () => {
	const server = read(
		"server/application/src/main/java/de/tum/cit/aet/hephaestus/workspace/validation/WorkspaceSlugValidator.java",
	);
	const labels = [
		...server
			.slice(
				server.indexOf("private static final Set<String> RESERVED"),
				server.indexOf("static Set<String> reservedLabels"),
			)
			.matchAll(/"(?<label>[a-z0-9-]+)"/gu),
	].map((match) => match.groups?.label);
	assert.ok(labels.length > 0);
	const expected = new Set([...labels, "pr[0-9]+"]);
	assert.deepEqual(
		new Set(read("deploy/chart/hephaestus/files/workspace-reserved-labels.txt").trim().split("|")),
		expected,
	);
});
