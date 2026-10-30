import assert from "node:assert/strict";
import { mkdtemp, readFile, readdir, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";

import { readInventory } from "./commit-image-lock.ts";
import { chartImages, chartValues, releaseValuesLines } from "./kubernetes-chart.ts";
import { prepareReleaseChart } from "./prepare-release-chart.ts";
import { jointLockAsset, parseReleaseImageLock } from "./release-image-lock.ts";

const release = "v1.0.0";
const commit = "c".repeat(40);
const digest = `sha256:${"a".repeat(64)}`;
const inventoryFile = path.join(import.meta.dirname, "../security/release-images.json");
const inventory = await readInventory(inventoryFile);
const images = [
	...inventory.images.map((image) => ({
		image,
		repository: `ghcr.io/hephaestus-build/${image}`,
		provenance: "first-party",
		indexDigest: digest,
	})),
	...inventory.upstream.map((entry) => ({
		image: entry.name,
		repository: entry.repository,
		provenance: "upstream",
		indexDigest: entry.digest,
	})),
].map((image) => ({ ...image, platforms: { "linux/amd64": digest, "linux/arm64": digest } }));
const lock = parseReleaseImageLock({
	schemaVersion: 2,
	release,
	commit,
	images,
	chart: { repository: "ghcr.io/hephaestus-build/charts/hephaestus", version: "1.0.0", digest },
});
const evidence = {
	schemaVersion: 1,
	release,
	commit,
	subjects: images.flatMap((image) =>
		Object.entries(image.platforms).map(([platform, child]) => ({
			...image,
			platform,
			digest: child,
		})),
	),
};

for (const failure of [
	"none",
	"next inventory",
	"lock signature",
	"chart signature",
	"digest",
	"source",
	"evidence",
] as const) {
	await test(`installed chart preparation: ${failure}`, async (t) => {
		const directory = await mkdtemp(path.join(tmpdir(), "signed-release-chart-"));
		t.after(async () => rm(directory, { recursive: true, force: true }));
		const targetInventory =
			failure === "next inventory"
				? {
						images: inventory.images.filter((name) =>
							chartImages.some((chartImage) => chartImage === name),
						),
						upstream: inventory.upstream.filter((entry) =>
							chartImages.some((chartImage) => chartImage === entry.name),
						),
					}
				: inventory;
		const targetNames = new Set([
			...targetInventory.images,
			...targetInventory.upstream.map((entry) => entry.name),
		]);
		const targetLock = {
			...lock,
			images: lock.images.filter((image) => targetNames.has(image.image)),
		};
		const targetEvidence = {
			...evidence,
			subjects: evidence.subjects.filter((subject) => targetNames.has(subject.image)),
		};
		const targetInventoryFile = path.join(directory, "inventory.json");
		await writeFile(targetInventoryFile, JSON.stringify(targetInventory));
		const requested: string[] = [];
		const calls: string[] = [];
		const signatureCalls: (readonly string[])[] = [];
		const prepare = prepareReleaseChart(
			release,
			failure === "source" ? "d".repeat(40) : commit,
			directory,
			"helm",
			targetInventoryFile,
			{
				environment: {},
				download: async (_repository, _release, names, into) => {
					requested.push(...names);
					for (const name of names) {
						let content = "fixture signature";
						if (name === jointLockAsset(release)) {
							content = JSON.stringify(targetLock);
						}
						if (name === "manifest.json") {
							content = JSON.stringify({
								...targetEvidence,
								commit: failure === "evidence" ? "d".repeat(40) : commit,
							});
						}
						await writeFile(path.join(into, name), content);
					}
				},
			},
			async (command, args) => {
				calls.push(`${command} ${args[0] ?? ""}`);
				if (command === "cosign") {
					if (args[0] === "verify-blob") {
						signatureCalls.push(args);
					}
					if (
						(args[0] === "verify-blob" && failure === "lock signature") ||
						(args[0] === "verify" && failure === "chart signature")
					) {
						throw new Error("invalid signature");
					}
					return "";
				}
				if (args[0] === "pull") {
					await writeFile(
						path.join(args.at(-1) ?? "", `hephaestus@${digest.replace(":", "-")}.tgz`),
						"verified fixture package",
					);
					return `Digest: ${failure === "digest" ? `sha256:${"b".repeat(64)}` : digest}\n`;
				}
				return args[1] === "chart"
					? "apiVersion: v2\nname: hephaestus\nversion: 1.0.0\nappVersion: 1.0.0\n"
					: releaseValuesLines(chartValues(targetLock, targetInventory));
			},
		);
		if (failure === "none" || failure === "next inventory") {
			assert.equal(await prepare, path.join(directory, "hephaestus-1.0.0.tgz"));
			assert.match(
				await readFile(path.join(directory, `${release}.images.yaml`), "utf8"),
				/v1.0.0/u,
			);
		} else {
			await assert.rejects(prepare);
			assert.deepEqual(await readdir(directory), ["inventory.json"]);
			if (["lock signature", "source", "evidence"].includes(failure)) {
				assert.ok(!calls.includes("helm pull"));
			}
		}
		assert.equal(signatureCalls.length, 1);
		const signature = signatureCalls[0] ?? [];
		assert.equal(
			signature[signature.indexOf("--certificate-identity") + 1],
			"https://github.com/hephaestus-build/Hephaestus/.github/workflows/release.yml@refs/heads/main",
		);
		assert.equal(
			signature[signature.indexOf("--certificate-oidc-issuer") + 1],
			"https://token.actions.githubusercontent.com",
		);
		const blob = signature.at(-1) ?? "";
		assert.equal(path.basename(blob), jointLockAsset(release));
		assert.equal(signature[signature.indexOf("--bundle") + 1], `${blob}.sigstore.json`);
		assert.ok(
			!requested.includes(`release-${release}.json`),
			"no schema-1 projection is downloaded",
		);
	});
}
