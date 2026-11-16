import assert from "node:assert/strict";
import { test } from "node:test";

import { changeIsLinkOnly } from "./release-note-rules.ts";

const before = [
	"### v1.0.0-rc.8",
	"",
	"- Stop every role. See [Stopped schema upgrades](https://docs.hephaestus.build/admin/kubernetes#stopped-schema-upgrades).",
	"- Set `server.replicas` to 2.",
].join("\n");

void test("a change that only rewrites docs URLs keeps released upgrade steps", () => {
	const after = before.replace(
		"https://docs.hephaestus.build/admin/kubernetes#stopped-schema-upgrades",
		"https://docs.hephaestus.build/admin/upgrade#stopped-schema-upgrades",
	);
	assert.equal(changeIsLinkOnly(before, after), true);
});

void test("a changed upgrade step fails, even with the same docs URLs", () => {
	assert.equal(changeIsLinkOnly(before, before.replace("to 2.", "to 3.")), false);
});

void test("an added line fails", () => {
	assert.equal(changeIsLinkOnly(before, `${before}\n- Restart the server.`), false);
});
