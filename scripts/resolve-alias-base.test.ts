import assert from "node:assert/strict";
import { test } from "node:test";

import { resolveAliasBase, type BaseChain } from "./resolve-alias-base.ts";

const sha = (letter: string): string => letter.repeat(40);
const main = sha("a");
const layers = { [sha("c")]: sha("b"), [sha("b")]: main };
const never = (what: string) => async (): Promise<never> => {
	throw new Error(`${what} must not be consulted`);
};

const chain = (
	bases: Readonly<Record<string, string>>,
	published: readonly string[],
): BaseChain => ({
	compare: async (base) => (published.includes(base) ? "ahead" : "diverged"),
	baseOf: async (commit) => bases[commit],
});

await test("a pull request based on the default branch aliases from its own base", async () => {
	assert.equal(
		await resolveAliasBase(main, "main", {
			compare: async () => "identical",
			baseOf: never("the base chain"),
		}),
		main,
	);
});

await test("a layer of a stack aliases from the nearest commit the default branch contains", async () => {
	assert.equal(await resolveAliasBase(sha("c"), "main", chain(layers, [main])), main);
	assert.equal(await resolveAliasBase(sha("b"), "main", chain(layers, [main])), main);
});

await test("a layer stops at the first published base, not at the default branch", async () => {
	assert.equal(await resolveAliasBase(sha("c"), "main", chain(layers, [sha("b"), main])), sha("b"));
});

await test("a chain that reaches nothing published leaves the run every image to build", async () => {
	assert.equal(await resolveAliasBase(sha("c"), "main", chain(layers, [])), undefined);
	assert.equal(
		await resolveAliasBase(sha("c"), "main", chain({ [sha("c")]: sha("c") }, [])),
		undefined,
	);
});
