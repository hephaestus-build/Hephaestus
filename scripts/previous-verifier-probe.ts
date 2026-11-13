/**
 * An earlier release's verifier, run against a release that has no signed draft yet: the Version
 * PR's release evidence preflight. It calls the functions that the earlier release's
 * prepare-release-chart.ts calls, from that release's own tree, on this release's lock, evidence
 * manifest and image inventory:
 *
 *   node scripts/previous-verifier-probe.ts <verifier tree> <release> <lock.json> <manifest.json> <inventory.json>
 *
 * The lock is the one this release would sign, without the chart that only the release pushes. The
 * probe skips what needs release assets: the download, the signature checks, and the chart pull. The
 * image set, the lock's image identities and the evidence manifest are what it decides; those are
 * what a code change breaks between releases (docs/contributor/release-management.mdx § Joint lock
 * with the chart).
 */
import { readFile } from "node:fs/promises";
import path from "node:path";
import { pathToFileURL } from "node:url";

import { isRecord, parseJson } from "./lib/json.ts";

/**
 * The functions of an earlier verifier that its prepare-release-chart.ts imports, by module. Their
 * names and arguments are the same in every verifier from 1.0.0-rc.4 on.
 */
const SURFACE = {
	"release-image-lock.ts": ["parseReleaseImageLock", "verifyLockAgainstEvidence"],
	"commit-image-lock.ts": ["readInventory"],
	"kubernetes-chart.ts": ["chartValues"],
} as const;

type Name = (typeof SURFACE)[keyof typeof SURFACE][number];
type Verifier = Record<Name, (...args: unknown[]) => unknown>;

async function loadVerifier(tree: string): Promise<Verifier> {
	const functions = new Map<string, (...args: unknown[]) => unknown>();
	for (const [file, names] of Object.entries(SURFACE)) {
		const module: unknown = await import(pathToFileURL(path.join(tree, "scripts", file)).href);
		for (const name of names) {
			const value: unknown = isRecord(module) ? module[name] : undefined;
			if (typeof value !== "function") {
				throw new TypeError(`the verifier in ${tree} has no ${name}`);
			}
			functions.set(name, (...args) => {
				const result: unknown = Reflect.apply(value, undefined, args);
				return result;
			});
		}
	}
	const get = (name: Name) => {
		const found = functions.get(name);
		if (found === undefined) {
			throw new TypeError(`the verifier in ${tree} has no ${name}`);
		}
		return found;
	};
	return {
		parseReleaseImageLock: get("parseReleaseImageLock"),
		verifyLockAgainstEvidence: get("verifyLockAgainstEvidence"),
		readInventory: get("readInventory"),
		chartValues: get("chartValues"),
	};
}

export async function probeVerifier(
	tree: string,
	release: string,
	lockFile: string,
	manifestFile: string,
	inventoryFile: string,
): Promise<void> {
	const verifier = await loadVerifier(tree);
	const lock = verifier.parseReleaseImageLock(parseJson(await readFile(lockFile, "utf8")), release);
	verifier.verifyLockAgainstEvidence(lock, parseJson(await readFile(manifestFile, "utf8")));
	const inventory: unknown = await verifier.readInventory(inventoryFile);
	verifier.chartValues(lock, inventory);
}

if (import.meta.main) {
	const [tree = "", release = "", lock = "", manifest = "", inventory = ""] = process.argv.slice(2);
	if ([tree, release, lock, manifest, inventory].includes("")) {
		throw new Error(
			"usage: previous-verifier-probe <verifier tree> <release> <lock.json> <manifest.json> <inventory.json>",
		);
	}
	await probeVerifier(tree, release, lock, manifest, inventory);
}
