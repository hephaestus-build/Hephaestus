import assert from "node:assert/strict";
import { mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";

import { probeVerifier } from "./previous-verifier-probe.ts";

const release = "v1.1.0";
const commit = "c".repeat(40);
const digest = (seed: number) => `sha256:${seed.toString(16).padStart(64, "0")}`;
const firstParty = ["agent-pi", "application-server", "postgres", "webapp"];
const upstream = [{ name: "vmalert", repository: "docker.io/victoriametrics/vmalert" }];

/** A schema 1 lock and an inventory that this tree accepts, for these first-party images. */
async function release1(directory: string, images: readonly string[]) {
	const inventory = {
		schemaVersion: 1,
		images,
		upstream: upstream.map((entry, index) => ({ ...entry, digest: digest(900 + index) })),
	};
	const lock = {
		schemaVersion: 1,
		release,
		commit,
		images: [
			...images.map((image, index) => ({
				image,
				repository: `ghcr.io/hephaestus-build/${image}`,
				provenance: "first-party",
				indexDigest: digest(100 + index),
				platforms: { "linux/amd64": digest(200 + index), "linux/arm64": digest(300 + index) },
			})),
			...inventory.upstream.map((entry, index) => ({
				image: entry.name,
				repository: entry.repository,
				provenance: "upstream",
				indexDigest: entry.digest,
				platforms: { "linux/amd64": digest(400 + index), "linux/arm64": digest(500 + index) },
			})),
		],
	};
	const files = {
		lock: path.join(directory, "lock.json"),
		manifest: path.join(directory, "manifest.json"),
		inventory: path.join(directory, "inventory.json"),
	};
	await writeFile(files.lock, JSON.stringify(lock));
	await writeFile(files.manifest, JSON.stringify({ release }));
	await writeFile(files.inventory, JSON.stringify(inventory));
	return files;
}

/**
 * An earlier verifier whose chart runs a fixed list of images, as the verifier of 1.0.0-rc.4 does.
 * Its modules record each call in `calls` beside the tree.
 */
async function fixedListVerifier(
	t: { after: (callback: () => Promise<void>) => void },
	runs: readonly string[],
	withChart = true,
) {
	const root = await mkdtemp(path.join(tmpdir(), "probe-test-"));
	t.after(async () => rm(root, { recursive: true, force: true }));
	const scripts = path.join(root, "tree", "scripts");
	await mkdir(scripts, { recursive: true });
	const record = `import { appendFileSync } from "node:fs";
const note = (call) => appendFileSync(${JSON.stringify(path.join(root, "calls"))}, call + "\\n");`;
	await writeFile(
		path.join(scripts, "release-image-lock.ts"),
		`${record}
export function parseReleaseImageLock(value, release) { note("parse " + release); return value; }
export function verifyLockAgainstEvidence(lock, evidence) { note("evidence " + evidence.release); }
`,
	);
	await writeFile(
		path.join(scripts, "commit-image-lock.ts"),
		`${record}
import { readFileSync } from "node:fs";
export async function readInventory(file) { note("inventory"); return JSON.parse(readFileSync(file, "utf8")); }
`,
	);
	await writeFile(
		path.join(scripts, "kubernetes-chart.ts"),
		withChart
			? `${record}
const RUNS = ${JSON.stringify(runs)};
export function chartValues(lock) {
	note("chart");
	const images = {};
	for (const name of RUNS) {
		const image = lock.images.find((candidate) => candidate.image === name);
		if (image === undefined) {
			throw new Error("the release image inventory has no " + name + ", which the chart runs");
		}
		images[name] = image.repository + "@" + image.indexDigest;
	}
	return { release: { version: lock.release, commit: lock.commit }, images };
}
`
			: "export const nothing = 1;\n",
	);
	const calls = async () => {
		const recorded = await readFile(path.join(root, "calls"), "utf8");
		return recorded.trim().split("\n");
	};
	return { root, tree: path.join(root, "tree"), calls };
}

const all = [...firstParty, ...upstream.map((entry) => entry.name)];

await test("the probe runs the earlier verifier's own checks, and its chart values match this tree's", async (t) => {
	const verifier = await fixedListVerifier(t, all);
	const files = await release1(verifier.root, firstParty);
	await probeVerifier(verifier.tree, release, files.lock, files.manifest, files.inventory);
	assert.deepEqual(await verifier.calls(), [
		"parse v1.1.0",
		"evidence v1.1.0",
		"inventory",
		"chart",
	]);
});

await test("a removed image fails with the earlier verifier's own error", async (t) => {
	const verifier = await fixedListVerifier(t, [...all, "nats"]);
	const files = await release1(verifier.root, firstParty);
	await assert.rejects(
		probeVerifier(verifier.tree, release, files.lock, files.manifest, files.inventory),
		/the release image inventory has no nats, which the chart runs/u,
	);
});

await test("an added image fails: the packaged chart pins an image the earlier verifier leaves out", async (t) => {
	const verifier = await fixedListVerifier(t, all);
	const files = await release1(verifier.root, [...firstParty, "extra"]);
	await assert.rejects(
		probeVerifier(verifier.tree, release, files.lock, files.manifest, files.inventory),
		/does not pin the images that the earlier verifier expects .*packaged: .*extra/u,
	);
});

await test("a verifier tree without the expected functions is a failure, never a pass", async (t) => {
	const verifier = await fixedListVerifier(t, all, false);
	const files = await release1(verifier.root, firstParty);
	await assert.rejects(
		probeVerifier(verifier.tree, release, files.lock, files.manifest, files.inventory),
		/has no chartValues/u,
	);
});
