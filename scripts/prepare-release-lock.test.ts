import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { mkdir, mkdtemp, readdir, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

import { parse } from "yaml";

import { parseInventory } from "./commit-image-lock.ts";
import { asRecord, readJsonFileSync } from "./lib/json.ts";
import { run } from "./lib/process.ts";
import { type PrepareDependencies, prepareReleaseLock } from "./prepare-release-lock.ts";
import type { Execute } from "./release-chart.ts";

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
const chart = {
	repository: "ghcr.io/hephaestus-build/charts/hephaestus",
	version: "0.93.0",
	digest: digest("f"),
};
const packageBytes = "hephaestus chart 0.93.0";
const historical = { schemaVersion: 1, release, commit, images };
const joint = { ...historical, schemaVersion: 2, chart };

const legacyAsset = `release-${release}.json`;
const jointAsset = `release-${release}.chart.json`;

/** Release assets as GitHub serves them, with synthetic signatures over their exact bytes. */
function assets(lock: unknown, overrides: Record<string, string> = {}, name = jointAsset) {
	const lockText = JSON.stringify(lock);
	return new Map(
		Object.entries({
			[name]: lockText,
			[`${name}.sigstore.json`]: sha256(lockText),
			"manifest.json": JSON.stringify(manifest),
			"hephaestus-0.93.0.tgz.sigstore.json": sha256(packageBytes),
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
function dependencies(
	served: Map<string, string>,
	{ registry = new Map([[`${chart.repository}@${chart.digest}`, packageBytes]]) } = {},
) {
	const calls: string[][] = [];
	const execute: Execute = async (command, args) => {
		calls.push([command, ...args]);
		if (command === "helm" && args[0] === "pull") {
			const bytes = registry.get((args[1] ?? "").replace(/^oci:\/\//u, ""));
			if (bytes === undefined) {
				throw new Error(`${args[1] ?? ""}: not found`);
			}
			await writeFile(
				path.join(args[args.indexOf("--destination") + 1] ?? "", "hephaestus-0.93.0.tgz"),
				bytes,
			);
			return "";
		}
		if (command === "helm" && args[0] === "show") {
			return "apiVersion: v2\nname: hephaestus\nversion: 0.93.0\nappVersion: 0.93.0\n";
		}
		if (command === "cosign") {
			await verifyBlob(args);
			return "";
		}
		throw new Error(`unexpected ${command}`);
	};
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
		execute,
	};
	return { calls, prepared };
}

async function workspace(t: { after: (callback: () => Promise<void>) => void }) {
	const directory = await mkdtemp(path.join(tmpdir(), "prepare-release-lock-"));
	t.after(async () => rm(directory, { recursive: true, force: true }));
	return {
		directory,
		output: path.join(directory, "release-lock.env"),
		chart: path.join(directory, "chart"),
	};
}

await test("a historical schema 1 lock still prepares the Compose environment, signature first", async (t) => {
	const { directory, output } = await workspace(t);
	const { calls, prepared } = dependencies(assets(historical, {}, legacyAsset));
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

await test("a joint lock yields the verified chart and its values only after every check", async (t) => {
	const { directory, output, chart: chartDirectory } = await workspace(t);
	const { calls, prepared } = dependencies(assets(joint));
	await prepareReleaseLock({ release, output, chart: chartDirectory }, prepared);
	assert.deepEqual(
		calls.map((call) => call.slice(0, 2).join(" ")),
		[
			"download release-v0.93.0.chart.json",
			"cosign verify-blob",
			`node ${path.join(import.meta.dirname, "release-image-lock.ts")}`,
			"helm pull",
			"cosign verify-blob",
			"helm show",
		],
	);
	assert.equal(calls[3]?.[2], `oci://${chart.repository}@${chart.digest}`);
	const chartFiles = await readdir(chartDirectory);
	assert.deepEqual(chartFiles.toSorted(), ["hephaestus-0.93.0.tgz", "release-values.yaml"]);
	assert.equal(
		await readFile(path.join(chartDirectory, "hephaestus-0.93.0.tgz"), "utf8"),
		packageBytes,
	);
	const values: unknown = parse(
		await readFile(path.join(chartDirectory, "release-values.yaml"), "utf8"),
	);
	assert.deepEqual(values, {
		release: { version: release, commit },
		images: Object.fromEntries(
			["agent-pi", "application-server", "nats", "postgres", "webapp"].map((name) => {
				const image = images.find((entry) => entry.image === name);
				return [name, `${image?.repository ?? ""}@${image?.indexDigest ?? ""}`];
			}),
		),
	});
	const workspaceFiles = await readdir(directory);
	assert.deepEqual(workspaceFiles.toSorted(), ["chart", "release-lock.env"]);
});

await test("nothing is written when a lock, its evidence or its chart does not verify", async (t) => {
	const otherRelease = { ...joint, release: "v0.93.1", chart: { ...chart, version: "0.93.1" } };
	// Every case asks for the chart, the path that reads the joint lock.
	for (const [label, served, options, message] of [
		[
			"an unsigned lock",
			assets(joint, { [`${jointAsset}.sigstore.json`]: "forged" }),
			{},
			/invalid signature/u,
		],
		["another release's lock", assets(otherRelease), {}, /exited with code 1/u],
		[
			"evidence of another commit",
			assets(joint, { "manifest.json": JSON.stringify({ ...manifest, commit: "d".repeat(40) }) }),
			{},
			/exited with code 1/u,
		],
		// No joint lock means no chart: the schema 1 lock is never a fallback for the chart path.
		[
			"a release without a joint lock",
			assets(historical, {}, legacyAsset),
			{},
			/release-v0\.93\.0\.chart\.json returned 404/u,
		],
		[
			"a schema 1 lock served as the joint lock",
			assets(historical),
			{},
			/the v0\.93\.0 lock has no chart/u,
		],
		[
			"a missing chart signature",
			new Map([...assets(joint)].filter(([name]) => !name.endsWith(".tgz.sigstore.json"))),
			{},
			/404/u,
		],
		[
			"a tampered chart package",
			assets(joint),
			{ registry: new Map([[`${chart.repository}@${chart.digest}`, "other bytes"]]) },
			/invalid signature/u,
		],
		[
			"a chart missing at its locked digest",
			assets(joint),
			{ registry: new Map<string, string>() },
			/not found/u,
		],
	] as const) {
		const { directory, output, chart: chartDirectory } = await workspace(t);
		const { calls, prepared } = dependencies(served, options);
		await assert.rejects(
			prepareReleaseLock({ release, output, chart: chartDirectory }, prepared),
			message,
			label,
		);
		assert.deepEqual(await readdir(directory), [], `${label} left output`);
		if (label === "an unsigned lock") {
			assert.ok(
				!calls.some((call) => call[0] === "node" || call[0] === "helm"),
				"nothing reads an unsigned lock",
			);
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

/** Owned fakes of GitHub, Cosign and Helm: no network, no ambient tool, no credential. */
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
} else if (tool === 'helm' && args[0] === 'pull') {
  const bytes = JSON.parse(fs.readFileSync(process.env.REGISTRY, 'utf8'))[args[1].replace(/^oci:\/\//, '')];
  if (bytes === undefined) { console.error(args[1] + ': not found'); process.exit(1); }
  fs.writeFileSync(path.join(option('--destination'), 'hephaestus-0.93.0.tgz'), bytes);
} else if (tool === 'helm' && args[0] === 'show' && args[1] === 'chart') {
  process.stdout.write('apiVersion: v2\nappVersion: 0.93.0\ndescription: Hephaestus\nname: hephaestus\ntype: application\nversion: 0.93.0\n');
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
		"scripts/kubernetes-chart.ts",
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
	for (const tool of ["gh", "cosign", "helm"]) {
		await writeFile(path.join(bin, tool), coldTools, { mode: 0o755 });
	}
	const registry = path.join(directory, "registry.json");
	await writeFile(
		registry,
		JSON.stringify({ [`${chart.repository}@${chart.digest}`]: packageBytes }),
	);
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
				REGISTRY: registry,
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
	// A run that called no tool leaves no log.
	const calls = async () => {
		try {
			return await readFile(callLog, "utf8");
		} catch {
			return "";
		}
	};
	return { tree, out, registry, node, serve, calls };
}

// The cold tree models a Linux release host: its gh, cosign and helm are POSIX executable stubs.
const posixStubs = {
	skip: process.platform === "win32" && "the release host tools are POSIX executable stubs",
};

await test(
	"a cold copied tree prepares the historical Compose lock and the joint chart with Node alone",
	posixStubs,
	async (t) => {
		const cold = await coldTree(t);
		const prepare = path.join(cold.tree, "scripts/prepare-release-lock.ts");
		await cold.serve(assets(historical, {}, legacyAsset));
		await cold.serve(assets(joint));
		const compose = cold.node([prepare, release, path.join(cold.out, "compose.env")]);
		assert.equal(compose.status, 0, compose.stderr);
		assert.match(
			await readFile(path.join(cold.out, "compose.env"), "utf8"),
			/^HEPHAESTUS_RELEASE=v0\.93\.0$/mu,
		);
		assert.doesNotMatch(
			await cold.calls(),
			/"helm"|chart\.json/u,
			"the Compose path never touches the chart",
		);
		const charted = cold.node([
			prepare,
			release,
			path.join(cold.out, "chart.env"),
			"--chart",
			path.join(cold.out, "chart"),
		]);
		assert.equal(charted.status, 0, charted.stderr);
		const chartFiles = await readdir(path.join(cold.out, "chart"));
		assert.deepEqual(chartFiles.toSorted(), ["hephaestus-0.93.0.tgz", "release-values.yaml"]);
		const values = await readFile(path.join(cold.out, "chart", "release-values.yaml"), "utf8");
		const [header = "", ...body] = values.split("\n");
		assert.match(header, /^# Generated by scripts\/kubernetes-chart\.ts/u);
		assert.deepEqual(asRecord(JSON.parse(body.join("\n")), "values").release, {
			version: release,
			commit,
		});
		// Each chart command also loads on its own in the cold tree.
		for (const script of ["release-chart.ts", "release-image-lock.ts"]) {
			const usage = cold.node([path.join(cold.tree, "scripts", script)]);
			assert.notEqual(usage.status, 0);
			assert.match(usage.stderr, /usage: release-/u, script);
		}
	},
);

await test(
	"a cold copied tree writes nothing for a forged joint lock or an unlocked chart",
	posixStubs,
	async (t) => {
		for (const [label, served, registry, message] of [
			[
				"a forged joint lock signature",
				assets(joint, { [`${jointAsset}.sigstore.json`]: "forged" }),
				undefined,
				/invalid signature/u,
			],
			["a chart missing at its locked digest", assets(joint), {}, /not found/u],
			[
				"a release without a joint lock",
				assets(historical, {}, legacyAsset),
				undefined,
				/no assets match the file pattern release-v0\.93\.0\.chart\.json/u,
			],
		] as const) {
			const cold = await coldTree(t);
			await cold.serve(served);
			if (registry !== undefined) {
				await writeFile(cold.registry, JSON.stringify(registry));
			}
			const result = cold.node([
				path.join(cold.tree, "scripts/prepare-release-lock.ts"),
				release,
				path.join(cold.out, "release-lock.env"),
				"--chart",
				path.join(cold.out, "chart"),
			]);
			assert.notEqual(result.status, 0, label);
			assert.match(result.stderr, message, label);
			assert.deepEqual(await readdir(cold.out), [], `${label} left output`);
			if (label === "a forged joint lock signature") {
				assert.doesNotMatch(await cold.calls(), /"helm"/u, "nothing pulls for an unsigned lock");
			}
		}
	},
);

await test("an existing chart directory is never replaced", async (t) => {
	const { directory, output, chart: chartDirectory } = await workspace(t);
	await mkdir(chartDirectory);
	await writeFile(path.join(chartDirectory, "operator-values.yaml"), "host: example\n");
	const { prepared } = dependencies(assets(joint));
	await assert.rejects(
		prepareReleaseLock({ release, output, chart: chartDirectory }, prepared),
		/EEXIST/u,
	);
	assert.deepEqual(await readdir(directory), ["chart"]);
	assert.deepEqual(await readdir(chartDirectory), ["operator-values.yaml"]);
});
