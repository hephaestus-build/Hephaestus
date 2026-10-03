import assert from "node:assert/strict";
import { mkdtemp, rm } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { test } from "node:test";

import { environmentForGitFixture } from "./lib/git-environment.ts";
import { output } from "./lib/process.ts";
import { pullRequestBase, resolveAliasBase, type BaseChain } from "./resolve-alias-base.ts";

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

await test("image reuse follows the tested merge when main advances beyond the PR event", async (t) => {
	const directory = await mkdtemp(path.join(os.tmpdir(), "hephaestus-alias-base-"));
	t.after(async () => {
		await rm(directory, { recursive: true, force: true });
	});
	const environment = environmentForGitFixture({
		GIT_AUTHOR_NAME: "CI contract",
		GIT_AUTHOR_EMAIL: "ci@dev.invalid",
		GIT_COMMITTER_NAME: "CI contract",
		GIT_COMMITTER_EMAIL: "ci@dev.invalid",
	});
	const git = async (...args: string[]) =>
		output("git", ["-C", directory, ...args], { env: environment });
	await git("init", "--initial-branch=main");
	await git("commit", "--allow-empty", "-m", "Runtime v3");
	const eventRevision = await git("rev-parse", "HEAD");
	const eventBase = eventRevision.trim();
	await git("checkout", "-b", "feature");
	await git("commit", "--allow-empty", "-m", "Extension feature");
	await git("checkout", "main");
	await git("commit", "--allow-empty", "-m", "Runtime v4");
	const testedRevision = await git("rev-parse", "HEAD");
	const testedBase = testedRevision.trim();
	await git("merge", "--no-ff", "feature", "-m", "PR merge tree");
	const base = pullRequestBase(await git("rev-list", "--parents", "-n", "1", "HEAD"));
	assert.equal(base, testedBase);
	assert.notEqual(base, eventBase);
	assert.equal(await resolveAliasBase(base, "main", chain({}, [testedBase])), testedBase);
});

await test("a checkout that is not a valid PR merge cannot reuse image inputs", () => {
	for (const revision of [
		sha("a"),
		`${sha("a")} ${sha("b")}`,
		`${sha("a")} bad ${sha("b")}`,
		`${sha("a")} ${sha("b")} ${sha("c")} ${sha("d")}`,
	]) {
		assert.throws(() => pullRequestBase(revision), /two-parent merge commit/u);
	}
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
