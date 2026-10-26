import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { mkdir, mkdtemp, readdir, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

import { parseInventory } from "./commit-image-lock.ts";
import { readJsonFileSync } from "./lib/json.ts";
import { run } from "./lib/process.ts";
import { type PrepareDependencies, prepareReleaseLock } from "./prepare-release-lock.ts";

const release = "v0.93.0";
const commit = "c".repeat(40);
const digest = (character: string) => `sha256:${character.repeat(64)}`;
const sha256 = (bytes: string | Buffer) => createHash("sha256").update(bytes).digest("hex");
const inventory = parseInventory(
	readJsonFileSync(fileURLToPath(new URL("../security/release-images.json", import.meta.url))),
);
const platforms = { "linux/amd64": digest("1"), "linux/arm64": digest("2") };
const images = [
	...inventory.images.map((image, index) => ({
		image,
		repository: `ghcr.io/hephaestus-build/${image}`,
		provenance: "first-party",
		indexDigest: digest(String.fromCodePoint(97 + index)),
		platforms,
	})),
	...inventory.upstream.map((entry) => ({
		image: entry.name,
		repository: entry.repository,
		provenance: "upstream",
		indexDigest: entry.digest,
		platforms,
	})),
];
const manifest = {
	schemaVersion: 1,
	release,
	commit,
	subjects: images.flatMap(({ platforms: children, ...image }) =>
		Object.entries(children).map(([platform, child]) => ({ ...image, platform, digest: child })),
	),
};
const historical = { schemaVersion: 1, release, commit, images };

const legacyAsset = `release-${release}.json`;

/** Release assets as GitHub serves them, with synthetic signatures over their exact bytes. */
function assets(lock: unknown, overrides: Record<string, string> = {}) {
	const lockText = JSON.stringify(lock);
	return new Map(
		Object.entries({
			[legacyAsset]: lockText,
			[`${legacyAsset}.sigstore.json`]: sha256(lockText),
			"manifest.json": JSON.stringify(manifest),
			...overrides,
		}),
	);
}

/** A synthetic Cosign check: the bundle holds the SHA-256 of the exact signed bytes. */
async function verifyBlob(args: string[]): Promise<void> {
	const bundle = await readFile(args[args.indexOf("--bundle") + 1] ?? "", "utf8");
	if (bundle !== sha256(await readFile(args.at(-1) ?? ""))) {
		throw new Error("cosign: invalid signature");
	}
}

/** Fake remotes; the lock and evidence check is the real release-image-lock.ts in a child process. */
function dependencies(served: Map<string, string>) {
	const calls: string[][] = [];
	const prepared: PrepareDependencies = {
		environment: {},
		download: async (_repository, _release, names, into) => {
			calls.push(["download", ...names]);
			for (const name of names) {
				const contents = served.get(name);
				if (contents === undefined) {
					throw new Error(`GET ${name} returned 404`);
				}
				await writeFile(path.join(into, name), contents);
			}
		},
		run: async (command, args) => {
			calls.push([command === process.execPath ? "node" : command, ...args]);
			if (command === "cosign") {
				await verifyBlob(args);
				return;
			}
			await run(command, args, { stdout: "ignore", stderr: "ignore" });
		},
	};
	return { calls, prepared };
}

async function workspace(t: { after: (callback: () => Promise<void>) => void }) {
	const directory = await mkdtemp(path.join(tmpdir(), "prepare-release-lock-"));
	t.after(async () => rm(directory, { recursive: true, force: true }));
	return { directory, output: path.join(directory, "release-lock.env") };
}

await test("a historical schema 1 lock still prepares the Compose environment, signature first", async (t) => {
	const { directory, output } = await workspace(t);
	const { calls, prepared } = dependencies(assets(historical));
	await prepareReleaseLock({ release, output }, prepared);
	const environment = await readFile(output, "utf8");
	assert.match(environment, /^HEPHAESTUS_RELEASE=v0\.93\.0$/mu);
	assert.match(
		environment,
		/^HEPHAESTUS_IMAGE_WEBAPP=ghcr\.io\/hephaestus-build\/webapp@sha256:/mu,
	);
	assert.deepEqual(
		calls.map((call) => call[0]),
		["download", "cosign", "node"],
	);
	assert.deepEqual(calls[0]?.slice(1), [
		`release-${release}.json`,
		`release-${release}.json.sigstore.json`,
		"manifest.json",
	]);
	assert.deepEqual(await readdir(directory), ["release-lock.env"]);
});

await test("nothing is written when a lock or its evidence does not verify", async (t) => {
	for (const [label, served, message] of [
		[
			"an unsigned lock",
			assets(historical, { [`${legacyAsset}.sigstore.json`]: "forged" }),
			/invalid signature/u,
		],
		[
			"another release's lock",
			assets({ ...historical, release: "v0.93.1" }),
			/exited with code 1/u,
		],
		[
			"evidence of another commit",
			assets(historical, {
				"manifest.json": JSON.stringify({ ...manifest, commit: "d".repeat(40) }),
			}),
			/exited with code 1/u,
		],
	] as const) {
		const { directory, output } = await workspace(t);
		const { calls, prepared } = dependencies(served);
		await assert.rejects(prepareReleaseLock({ release, output }, prepared), message, label);
		assert.deepEqual(await readdir(directory), [], `${label} left output`);
		if (label === "an unsigned lock") {
			assert.ok(!calls.some((call) => call[0] === "node"), "nothing reads an unsigned lock");
		}
	}
});

const root = path.resolve(import.meta.dirname, "..");

/**
 * Every module a script loads, read from its import statements. Only `node:` and relative
 * specifiers are allowed: release jobs set up Node alone, and hosts copy scripts without
 * node_modules, so a package import would fail there with ERR_MODULE_NOT_FOUND.
 */
async function moduleClosure(entries: readonly string[]): Promise<Set<string>> {
	const files = new Set<string>();
	const pending = entries.map((entry) => path.join(root, entry));
	for (let file = pending.pop(); file !== undefined; file = pending.pop()) {
		if (files.has(file)) {
			continue;
		}
		files.add(file);
		const source = await readFile(file, "utf8");
		for (const match of source.matchAll(
			/^(?:import|export)\s[^;]*?\sfrom\s"(?<specifier>[^"]+)";/gmu,
		)) {
			const specifier = match.groups?.specifier ?? "";
			if (specifier.startsWith("node:")) {
				continue;
			}
			assert.ok(
				specifier.startsWith("./") || specifier.startsWith("../"),
				`${path.relative(root, file)} imports the package ${specifier}`,
			);
			pending.push(path.resolve(path.dirname(file), specifier));
		}
	}
	return files;
}

/** Owned fakes of GitHub and Cosign: no network, no ambient tool, no credential. */
const coldTools = String.raw`#!/usr/bin/env node
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const tool = path.basename(process.argv[1]);
const args = process.argv.slice(2);
const option = flag => args[args.indexOf(flag) + 1];
fs.appendFileSync(process.env.CALL_LOG, JSON.stringify([tool, ...args]) + '\n');
if (tool === 'gh' && args[0] === 'release' && args[1] === 'download') {
  for (let i = 0; i < args.length; i++) {
    if (args[i] !== '--pattern') continue;
    const name = args[++i];
    const source = path.join(process.env.ASSETS, name);
    if (!fs.existsSync(source)) { console.error('no assets match the file pattern ' + name); process.exit(1); }
    fs.copyFileSync(source, path.join(option('--dir'), name));
  }
} else if (tool === 'cosign' && args[0] === 'verify-blob') {
  const signed = fs.readFileSync(option('--bundle'), 'utf8');
  if (crypto.createHash('sha256').update(fs.readFileSync(args.at(-1))).digest('hex') !== signed) { console.error('invalid signature'); process.exit(1); }
} else { console.error('unexpected ' + tool + ' ' + args.join(' ')); process.exit(1); }
`;

/** A copy (never a link) of the scripts these commands load, in a directory with no node_modules. */
async function coldTree(t: { after: (callback: () => Promise<void>) => void }) {
	const directory = await mkdtemp(path.join(tmpdir(), "cold-release-tree-"));
	t.after(async () => rm(directory, { recursive: true, force: true }));
	const tree = path.join(directory, "tree");
	const files = await moduleClosure([
		"scripts/prepare-release-lock.ts",
		"scripts/release-chart.ts",
		"scripts/release-image-lock.ts",
		"scripts/keyless-sign.ts",
	]);
	for (const file of [
		...files,
		path.join(root, "security/release-images.json"),
		path.join(root, "security/release-identities.json"),
	]) {
		const target = path.join(tree, path.relative(root, file));
		await mkdir(path.dirname(target), { recursive: true });
		await writeFile(target, await readFile(file));
	}
	const bin = path.join(directory, "bin");
	const served = path.join(directory, "assets");
	const out = path.join(directory, "out");
	for (const folder of [bin, served, out]) {
		await mkdir(folder);
	}
	for (const tool of ["gh", "cosign"]) {
		await writeFile(path.join(bin, tool), coldTools, { mode: 0o755 });
	}
	const callLog = path.join(directory, "calls");
	const node = (args: string[]) => {
		const result = spawnSync(process.execPath, args, {
			cwd: directory,
			encoding: "utf8",
			timeout: 60_000,
			env: {
				PATH: `${bin}${path.delimiter}${path.dirname(process.execPath)}`,
				HOME: directory,
				GITHUB_ACTIONS: "true",
				GH_TOKEN: "synthetic-token",
				GITHUB_REPOSITORY: "hephaestus-build/Hephaestus",
				ASSETS: served,
				CALL_LOG: callLog,
			},
		});
		assert.equal(result.error, undefined);
		assert.doesNotMatch(result.stderr, /ERR_MODULE_NOT_FOUND/u);
		return result;
	};
	const serve = async (map: Map<string, string>) => {
		for (const [name, contents] of map) {
			await writeFile(path.join(served, name), contents);
		}
	};
	return { tree, out, node, serve };
}

// The cold tree models a Linux release host: its gh and cosign are POSIX executable stubs.
const posixStubs = {
	skip: process.platform === "win32" && "the release host tools are POSIX executable stubs",
};

await test(
	"a cold copied tree prepares the Compose lock, and loads the release chart commands, with Node alone",
	posixStubs,
	async (t) => {
		const cold = await coldTree(t);
		const prepare = path.join(cold.tree, "scripts/prepare-release-lock.ts");
		await cold.serve(assets(historical));
		const compose = cold.node([prepare, release, path.join(cold.out, "compose.env")]);
		assert.equal(compose.status, 0, compose.stderr);
		assert.match(
			await readFile(path.join(cold.out, "compose.env"), "utf8"),
			/^HEPHAESTUS_RELEASE=v0\.93\.0$/mu,
		);
		// The release jobs run these with Node alone; each loads on its own in the cold tree.
		for (const [script, usage] of [
			["release-chart.ts", /usage: release-chart/u],
			["release-image-lock.ts", /usage: release-image-lock/u],
			["keyless-sign.ts", /Expected a release keyless/u],
		] as const) {
			const result = cold.node([path.join(cold.tree, "scripts", script)]);
			assert.notEqual(result.status, 0);
			assert.match(result.stderr, usage, script);
		}
	},
);

await test(
	"a cold copied tree writes nothing for a forged lock signature",
	posixStubs,
	async (t) => {
		const cold = await coldTree(t);
		await cold.serve(assets(historical, { [`${legacyAsset}.sigstore.json`]: "forged" }));
		const result = cold.node([
			path.join(cold.tree, "scripts/prepare-release-lock.ts"),
			release,
			path.join(cold.out, "release-lock.env"),
		]);
		assert.notEqual(result.status, 0);
		assert.match(result.stderr, /invalid signature/u);
		assert.deepEqual(await readdir(cold.out), []);
	},
);
