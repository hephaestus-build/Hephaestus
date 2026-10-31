import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { mkdir, mkdtemp, rm, symlink, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { test, type TestContext } from "node:test";

import { environmentForGitFixture } from "./lib/git-environment.ts";
import { scanOwnedAssets, scanOwnedSource } from "./owned-source-sbom.ts";

const folder = "webapp/public/brand/example";
const block = (files: string[]) => `Owned from: https://github.com/example/icons/tree/v1.2.3
Package: pkg:npm/%40example/icons@1.2.3
License: MIT
Changes: Keep unmodified marks.
Files: ${JSON.stringify(files)}
`;
const second = `Owned from: https://github.com/ls1intum/edutelligence/blob/${"a".repeat(40)}/logo.svg
Package: pkg:github/ls1intum/edutelligence@${"a".repeat(40)}
License: MIT
Changes: Keep one unmodified mark.
Files: ["b.bin"]
`;
const svg = Buffer.from('<svg viewBox="0 0 1 1">\r\n</svg>\n');
const binary = Buffer.from([0, 255, 13, 10]);

async function fixture(context: TestContext) {
	const directory = await mkdtemp(path.join(tmpdir(), "owned-assets-"));
	context.after(async () => rm(directory, { recursive: true, force: true }));
	const env = environmentForGitFixture();
	const git = (...args: string[]) =>
		execFileSync("git", args, { cwd: directory, env, encoding: "utf8" }).trim();
	git("init", "-q");
	await mkdir(path.join(directory, folder), { recursive: true });
	const write = async (name: string, content: string | Uint8Array) =>
		writeFile(path.join(directory, folder, name), content);
	await write("a.svg", svg);
	await write("b.bin", binary);
	await write("LICENSE", "upstream license");
	await write("NOTICE.md", `${block(["a.svg"])}\n${second}`);
	return { directory, git, write };
}

await test("asset notices preserve opaque bytes and upstreams without editing the assets", async (context) => {
	const { directory, git, write } = await fixture(context);
	const components = scanOwnedSource(undefined, directory);
	assert.equal(components.length, 2);
	assert.equal(components[0]?.name, `${folder}/a.svg`);
	assert.equal(components[0].hashes[0]?.content, createHash("sha256").update(svg).digest("hex"));
	assert.equal(
		components[1]?.hashes[0]?.content,
		createHash("sha256").update(binary).digest("hex"),
	);
	assert.equal(
		components[1].pedigree.ancestors[0]?.purl,
		`pkg:github/ls1intum/edutelligence@${"a".repeat(40)}`,
	);
	git("add", ".");
	git("-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-qm", "assets");
	const revision = git("rev-parse", "HEAD");
	await write("a.svg", "dirty working tree");
	assert.deepEqual(scanOwnedAssets(revision, directory), components);
	assert.notDeepEqual(scanOwnedAssets(undefined, directory), components);
});

await test("patch notices record the fork once in working and committed inventories", async (context) => {
	const { directory, git } = await fixture(context);
	const patches = "docker/agents/pi/patches";
	const name = "@earendil-works__pi-ai@1.0.0.patch";
	const upstream =
		"https://github.com/earendil-works/pi/tree/a13d35a742c6ef8462812a28fbe1d8c8b7431c32/packages/ai";
	const purl = "pkg:npm/%40earendil-works/pi-ai@1.0.0";
	const bytes = Buffer.from("diff --git a/dist/api.js b/dist/api.js\r\n+diagnostics();\n");
	await mkdir(path.join(directory, patches), { recursive: true });
	await writeFile(path.join(directory, patches, name), bytes);
	await writeFile(
		path.join(directory, patches, "NOTICE.md"),
		`Owned from: ${upstream}
Package: ${purl}
License: MIT
Changes: Keep the provider diagnostics patch.
Files: ["${name}"]
`,
	);
	const components = scanOwnedSource(undefined, directory);
	const patch = components.filter((component) => component.name === `${patches}/${name}`);
	assert.equal(components.length, 3);
	assert.equal(patch.length, 1);
	assert.deepEqual(patch[0]?.hashes, [
		{ alg: "SHA-256", content: createHash("sha256").update(bytes).digest("hex") },
	]);
	assert.equal(patch[0].pedigree.ancestors[0]?.purl, purl);
	assert.equal(patch[0].pedigree.ancestors[0].externalReferences[0]?.url, upstream);
	assert.deepEqual(patch[0].licenses, [{ expression: "MIT" }]);
	git("add", ".");
	git("-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-qm", "patch");
	const revision = git("rev-parse", "HEAD");
	await writeFile(path.join(directory, patches, name), "dirty patch");
	assert.deepEqual(scanOwnedSource(revision, directory), components);
	assert.notDeepEqual(scanOwnedSource(undefined, directory), components);
	await writeFile(path.join(directory, patches, "unlisted.patch"), "unlisted");
	assert.throws(
		() => scanOwnedSource(undefined, directory),
		/asset has no ownership entry: unlisted.patch/u,
	);
});

await test("asset notices reject gaps, overlap, missing files, paths and malformed blocks", async (context) => {
	const { directory, git, write } = await fixture(context);
	const invalid = [
		[block(["a.svg"]), /asset has no ownership entry: b.bin/u],
		[`${block(["a.svg", "b.bin"])}\n${second}`, /asset declared more than once: b.bin/u],
		[`${block(["a.svg", "a.svg"])}\n${second}`, /asset declared more than once: a.svg/u],
		[`${block(["../a.svg"])}\n${second}`, /asset does not exist: \.\.\/a.svg/u],
		[`${block(["*.svg"])}\n${second}`, /asset does not exist: \*.svg/u],
		[`${block(["missing.svg"])}\n${second}`, /asset does not exist: missing.svg/u],
		[`${block([])}\n${second}`, /Files must name at least one asset/u],
		[`${block(["a.svg"]).replace('Files: ["a.svg"]', "Files: not-json")}\n${second}`, /JSON/u],
		[
			`${block(["a.svg"]).replace("Owned from:", "Owned From:")}\n${second}`,
			/owned header needs each field once, in order/u,
		],
	] satisfies [string, RegExp][];
	for (const [notice, expected] of invalid) {
		await write("NOTICE.md", notice);
		assert.throws(() => scanOwnedAssets(undefined, directory), expected);
		git("add", ".");
		git(
			"-c",
			"user.name=Test",
			"-c",
			"user.email=test@example.com",
			"commit",
			"-qm",
			"invalid notice",
		);
		assert.throws(() => scanOwnedAssets(git("rev-parse", "HEAD"), directory), expected);
	}
	await write("NOTICE.md", `${block(["a.svg"])}\n${second}`);
	await write("new.svg", "unlisted");
	assert.throws(
		() => scanOwnedAssets(undefined, directory),
		/asset has no ownership entry: new.svg/u,
	);
});

await test(
	"asset directories reject symbolic links",
	{ skip: process.platform === "win32" },
	async (context) => {
		const { directory, git } = await fixture(context);
		await rm(path.join(directory, folder, "a.svg"));
		await symlink("b.bin", path.join(directory, folder, "a.svg"));
		assert.throws(() => scanOwnedAssets(undefined, directory), /not a regular asset file/u);
		git("add", ".");
		git("-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-qm", "symlink");
		assert.throws(
			() => scanOwnedAssets(git("rev-parse", "HEAD"), directory),
			/not a regular asset file/u,
		);
	},
);

await test("an opted directory rejects recursive asset paths", async (context) => {
	const { directory } = await fixture(context);
	await mkdir(path.join(directory, folder, "nested"));
	await writeFile(path.join(directory, folder, "nested/extra.svg"), "nested");
	assert.throws(() => scanOwnedAssets(undefined, directory), /must be flat/u);
});

await test("license-like asset filenames need entries in both scan modes", async (context) => {
	const { directory, git, write } = await fixture(context);
	await write("LICENSE-EDUTELLIGENCE", "second upstream license");
	await write("LICENSE-extra.svg", "<svg/>");
	assert.throws(
		() => scanOwnedAssets(undefined, directory),
		/asset has no ownership entry: LICENSE-extra.svg/u,
	);
	git("add", ".");
	git(
		"-c",
		"user.name=Test",
		"-c",
		"user.email=test@example.com",
		"commit",
		"-qm",
		"unlisted asset",
	);
	assert.throws(
		() => scanOwnedAssets(git("rev-parse", "HEAD"), directory),
		/asset has no ownership entry: LICENSE-extra.svg/u,
	);
});
