/**
 * Gradle only adds dependency-verification entries and cannot report an unused one. An entry for a
 * version that no build downloads any more stays, and OSV-Scanner reports it as a dependency. This
 * resolves the server's dependencies in an empty Gradle user home with strict verification, then
 * fails on each component that it did not download. `--write` deletes those components instead.
 * Regenerating the file with `--write-verification-metadata` would also work, but it rewrites the
 * `origin` of every entry and reports no component by name.
 */
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtemp, readdir, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";

const root = path.resolve(import.meta.dirname, "..");
const metadataPath = path.join(root, "server", "gradle", "verification-metadata.xml");

// Resolution alone downloads every component: compiling reads no more. Spotless resolves its
// formatters only when its tasks run. A component that only another task reads shows up as unused.
const tasks = [
	"resolveDependencies",
	"dependencies",
	"buildEnvironment",
	":spotlessCheck",
	":application:spotlessCheck",
];

// gradle/actions injects this plugin into the dependency-graph workflows, so no server build downloads
// it. Only its newest version stays: an older one is from a gradle/actions release no workflow uses.
const injected = new Set(["org.gradle:github-dependency-graph-gradle-plugin"]);

// Plain dotted numbers only, which is what the plugin releases use.
function compareVersions(left: string, right: string): number {
	const a = left.split(".").map(Number);
	const b = right.split(".").map(Number);
	for (let index = 0; index < Math.max(a.length, b.length); index += 1) {
		const difference = (a[index] ?? 0) - (b[index] ?? 0);
		if (difference !== 0) {
			return difference;
		}
	}
	return 0;
}

// Gradle writes one component per block at this indentation.
const componentPattern =
	/^ {6}<component group="(?<group>[^"]+)" name="(?<name>[^"]+)" version="(?<version>[^"]+)">\n[\s\S]*?^ {6}<\/component>\n/gmu;

function components(xml: string) {
	const matches = [...xml.matchAll(componentPattern)];
	if (matches.length !== xml.split("<component ").length - 1) {
		throw new Error("verification-metadata.xml is not in the layout Gradle writes");
	}
	return matches.map((match) => {
		const { group, name, version } = match.groups ?? {};
		assert.ok(group !== undefined && name !== undefined && version !== undefined);
		return {
			module: `${group}:${name}`,
			version,
			id: `${group}:${name}:${version}`,
			block: match[0],
		};
	});
}

export function staleComponents(xml: string, downloaded: ReadonlySet<string>): string[] {
	const all = components(xml);
	const newest = new Map<string, string>();
	for (const { module, version } of all) {
		const kept = newest.get(module);
		if (injected.has(module) && (kept === undefined || compareVersions(version, kept) > 0)) {
			newest.set(module, version);
		}
	}
	return all
		.filter(({ module, version, id }) => !downloaded.has(id) && newest.get(module) !== version)
		.map(({ id }) => id);
}

export function withoutComponents(xml: string, stale: ReadonlySet<string>): string {
	let pruned = xml;
	for (const { id, block } of components(xml)) {
		if (stale.has(id)) {
			pruned = pruned.replace(block, "");
		}
	}
	return pruned;
}

async function downloadedComponents(gradleHome: string): Promise<Set<string>> {
	const files = path.join(gradleHome, "caches", "modules-2", "files-2.1");
	const ids = new Set<string>();
	for (const group of await readdir(files)) {
		for (const name of await readdir(path.join(files, group))) {
			for (const version of await readdir(path.join(files, group, name))) {
				ids.add(`${group}:${name}:${version}`);
			}
		}
	}
	return ids;
}

async function main(): Promise<void> {
	const write = process.argv.includes("--write");
	const gradleHome = await mkdtemp(path.join(tmpdir(), "gradle-home-"));
	try {
		// A warm cache skips parent POMs and module descriptors, so only an empty home shows every read.
		const build = spawnSync(
			process.execPath,
			[
				path.resolve(import.meta.dirname, "run-gradlew.ts"),
				"--no-daemon",
				"--no-configuration-cache",
				"--dependency-verification=strict",
				"--console=plain",
				...tasks,
			],
			{ cwd: root, stdio: "inherit", env: { ...process.env, GRADLE_USER_HOME: gradleHome } },
		);
		if (build.error) {
			throw build.error;
		}
		if (build.status !== 0) {
			throw new Error(`Gradle failed with ${build.signal ?? `exit code ${String(build.status)}`}`);
		}
		const xml = await readFile(metadataPath, "utf8");
		const stale = staleComponents(xml, await downloadedComponents(gradleHome));
		if (stale.length === 0) {
			console.log(
				"check-gradle-verification-metadata: a clean resolution downloads every component.",
			);
		} else if (write) {
			await writeFile(metadataPath, withoutComponents(xml, new Set(stale)));
			console.log(
				`check-gradle-verification-metadata: removed ${String(stale.length)} components.`,
			);
		} else {
			console.error(
				`A clean resolution does not download these components:\n${stale.map((id) => `  ${id}`).join("\n")}`,
			);
			console.error(
				"Run `node scripts/check-gradle-verification-metadata.ts --write` and commit the result. " +
					"If a CI task needs one of them, add that task to this script instead.",
			);
			process.exitCode = 1;
		}
	} finally {
		// Gradle's single-use daemon can still write to the home while it exits.
		await rm(gradleHome, { recursive: true, force: true, maxRetries: 10, retryDelay: 500 });
	}
}

if (import.meta.main) {
	await main();
}
