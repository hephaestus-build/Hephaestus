import assert from "node:assert/strict";
import { mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";

import { probeVerifier } from "./previous-verifier-probe.ts";

/**
 * A verifier tree whose modules record each call in `calls.json` beside them. `chartValues` refuses a
 * lock without `nats`, as the verifier of 1.0.0-rc.4 does.
 */
async function verifierTree(
	t: { after: (callback: () => Promise<void>) => void },
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
export function chartValues(lock) {
	note("chart");
	if (!lock.images.some((image) => image.image === "nats")) {
		throw new Error("the release image inventory has no nats, which the chart runs");
	}
	return {};
}
`
			: "export const nothing = 1;\n",
	);
	const files = {
		lock: path.join(root, "lock.json"),
		manifest: path.join(root, "manifest.json"),
		inventory: path.join(root, "inventory.json"),
	};
	await writeFile(files.manifest, JSON.stringify({ release: "v1.1.0" }));
	await writeFile(files.inventory, JSON.stringify({ images: [] }));
	const lockWith = async (images: string[]) =>
		writeFile(files.lock, JSON.stringify({ images: images.map((image) => ({ image })) }));
	const calls = async () => {
		const recorded = await readFile(path.join(root, "calls"), "utf8");
		return recorded.trim().split("\n");
	};
	return { tree: path.join(root, "tree"), files, lockWith, calls };
}

await test("the probe runs the earlier verifier's own lock, evidence and chart checks in order", async (t) => {
	const { tree, files, lockWith, calls } = await verifierTree(t);
	await lockWith(["application-server", "nats"]);
	await probeVerifier(tree, "v1.1.0", files.lock, files.manifest, files.inventory);
	assert.deepEqual(await calls(), ["parse v1.1.0", "evidence v1.1.0", "inventory", "chart"]);
});

await test("a refusal by the earlier verifier fails the probe with that verifier's own error", async (t) => {
	const { tree, files, lockWith } = await verifierTree(t);
	await lockWith(["application-server"]);
	await assert.rejects(
		probeVerifier(tree, "v1.1.0", files.lock, files.manifest, files.inventory),
		/the release image inventory has no nats, which the chart runs/u,
	);
});

await test("a verifier tree without the expected functions is a failure, never a pass", async (t) => {
	const { tree, files, lockWith } = await verifierTree(t, false);
	await lockWith(["nats"]);
	await assert.rejects(
		probeVerifier(tree, "v1.1.0", files.lock, files.manifest, files.inventory),
		/has no chartValues/u,
	);
});
