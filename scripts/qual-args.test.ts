import assert from "node:assert/strict";
import { test } from "node:test";

import { commonFrom } from "./lib/qual-args.ts";
import { isRow } from "./lib/qual-state.ts";

const usage = "usage";
const exec = { QUAL_STAGING_EXEC: "ssh staging-host" };

await test("staging is refused unless the staging hold started the process", () => {
	assert.throws(
		() => commonFrom({ target: "staging" }, usage, exec),
		/without a promotion freeze/u,
	);
	assert.equal(
		commonFrom({ target: "staging" }, usage, { ...exec, QUAL_STAGING_HOLD: "a".repeat(40) }).target,
		"staging",
	);
});

await test("staging needs the command that reaches its host", () => {
	assert.throws(
		() => commonFrom({ target: "staging" }, usage, { QUAL_STAGING_HOLD: "a".repeat(40) }),
		/needs QUAL_STAGING_EXEC/u,
	);
});

await test("k3d needs a kubeconfig file, so the current context is never used", () => {
	assert.throws(() => commonFrom({ target: "k3d" }, usage, {}), /needs --kubeconfig/u);
	assert.throws(
		() => commonFrom({ target: "k3d", kubeconfig: "/does/not/exist" }, usage, {}),
		/needs --kubeconfig/u,
	);
});

await test("a row is read only when every declared column has its type", () => {
	const shape = { id: "string", attempt: "number", ended_at: "string?" } as const;
	assert.equal(isRow({ id: "r", attempt: 1, ended_at: null }, shape), true);
	assert.equal(isRow({ id: "r", attempt: 1, ended_at: "2026-11-14T00:00:00Z" }, shape), true);
	assert.equal(isRow({ id: "r", attempt: null, ended_at: null }, shape), false);
	assert.equal(isRow({ id: "r", attempt: "1", ended_at: null }, shape), false);
	assert.equal(isRow({ id: "r", ended_at: null }, shape), false);
});
