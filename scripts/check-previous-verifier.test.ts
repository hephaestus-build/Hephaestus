import assert from "node:assert/strict";
import { mkdtemp, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";

import {
	checkPreviousVerifiers,
	checkPreviousVerifiersBeforeRelease,
	parseAcceptedBreaks,
	preflightJointLock,
	verifiersOf,
} from "./check-previous-verifier.ts";
import { readJsonFile } from "./lib/json.ts";

const commit = "c".repeat(40);
const published = (tag: string, isPrerelease = tag.includes("-rc.")) => ({
	tag,
	isDraft: false,
	isPrerelease,
	isImmutable: true,
});
const releases = [
	published("v0.95.1"),
	published("v1.0.0-rc.3"),
	published("v1.0.0-rc.4"),
	published("v1.0.0"),
	published("v1.1.0-rc.1"),
];

const listed = async () => releases;
const refusing = async () => {
	throw new Error("Command failed: node prepare\nError: x.tgz does not pin the images\n  at y");
};
const noBreaks = async () => [];

/** A fake git and Node: tags are missing unless `tagPresent`, and `verifier` is the older tooling. */
function tools(verifier: (tree: string) => Promise<string>, tagPresent = false) {
	const calls: string[][] = [];
	const run = async (command: string, args: string[]) => {
		calls.push([command, ...args]);
		if (args[0] === "rev-parse" && !tagPresent) {
			throw new Error("no such tag");
		}
		return command === "git" ? "" : verifier(args[0] ?? "");
	};
	return { calls, run };
}

async function scratch(t: { after: (callback: () => Promise<void>) => void }) {
	const directory = await mkdtemp(path.join(tmpdir(), "previous-verifier-test-"));
	t.after(async () => rm(directory, { recursive: true, force: true }));
	return directory;
}

await test("the verifiers of a release are the nearest one and the latest stable one", () => {
	// Tooling from before rc.4 is the manual step's, so it never blocks a later release.
	assert.deepEqual(verifiersOf(releases, "v1.0.0-rc.5"), ["v1.0.0-rc.4"]);
	// Until a stable release exists, the hosts that the manual step moved stay on rc.4.
	const later = [...releases.slice(0, 3), published("v1.0.0-rc.5"), published("v1.0.0-rc.6")];
	assert.deepEqual(verifiersOf(later, "v1.0.0-rc.7"), ["v1.0.0-rc.6", "v1.0.0-rc.4"]);
	assert.deepEqual(verifiersOf(later, "v1.0.0"), ["v1.0.0-rc.6", "v1.0.0-rc.4"]);
	assert.deepEqual(verifiersOf(releases, "v1.0.0-rc.4"), ["v1.0.0-rc.3", "v0.95.1"]);
	// A host on v1.0.0 never ran the candidate.
	assert.deepEqual(verifiersOf(releases, "v1.1.0"), ["v1.1.0-rc.1", "v1.0.0"]);
	assert.deepEqual(verifiersOf(releases, "v1.0.1"), ["v1.0.0"]);
	// A backport never compares itself with a newer line.
	assert.deepEqual(verifiersOf(releases, "v0.95.2"), ["v0.95.1"]);
	assert.deepEqual(verifiersOf(releases, "v0.95.1"), []);
	assert.throws(() => verifiersOf(releases, "latest"), /not a release tag/u);
});

await test("a pushed tag or a draft runs no code in the release job", () => {
	const hostile = [
		{ ...published("v1.0.0-rc.99"), isImmutable: false },
		{ tag: "v1.0.0-rc.98", isDraft: false, isPrerelease: true },
		{ ...published("v1.0.0-rc.97"), isDraft: true },
		...releases,
	];
	assert.deepEqual(verifiersOf(hostile, "v1.0.1"), ["v1.0.0"]);
	assert.deepEqual(verifiersOf(hostile, "v1.0.0"), ["v1.0.0-rc.4"]);
});

await test("each verifier reads this release from a bare tree of its own tag", async (t) => {
	const destination = await scratch(t);
	const { calls, run } = tools(async () => "");
	const { verifiers } = await checkPreviousVerifiers(
		"v1.1.0",
		commit,
		destination,
		"security/release-images.json",
		async () => releases,
		run,
		noBreaks,
	);
	assert.deepEqual(verifiers, ["v1.1.0-rc.1", "v1.0.0"]);
	const worktrees = calls.filter((call) => call[2] === "add");
	assert.deepEqual(
		worktrees.map((call) => call[6]),
		verifiers,
	);
	const tree = worktrees[0]?.[5];
	assert.ok(tree !== undefined);
	assert.deepEqual(
		calls.find((call) => call[0] === process.execPath),
		[
			process.execPath,
			path.join(tree, "scripts/prepare-release-chart.ts"),
			"v1.1.0",
			commit,
			path.join(destination, "v1.1.0-rc.1"),
			"helm",
			"security/release-images.json",
		],
	);
	assert.deepEqual(calls.at(-1)?.slice(0, 4), ["git", "worktree", "remove", "--force"]);
});

await test("a refusal by any verifier fails the release and names that verifier", async (t) => {
	const { calls, run } = tools(async (script) => {
		if (script.includes("previous-verifier-")) {
			throw new Error("hephaestus-1.0.0-rc.5.tgz does not pin the v1.0.0-rc.5 images");
		}
		return "";
	});
	await assert.rejects(
		checkPreviousVerifiers(
			"v1.0.0-rc.5",
			commit,
			await scratch(t),
			"inventory.json",
			async () => releases,
			run,
			noBreaks,
		),
		(error: Error) => {
			assert.match(error.message, /verifier of v1\.0\.0-rc\.4 did not accept v1\.0\.0-rc\.5/u);
			assert.match(String(error.cause), /does not pin/u);
			return true;
		},
	);
	assert.equal(calls.at(-1)?.[2], "remove", "the tree is removed after a refusal too");
});

await test("no earlier release is a failure, never a pass", async (t) => {
	await assert.rejects(
		checkPreviousVerifiers(
			"v0.1.0",
			commit,
			await scratch(t),
			"inventory.json",
			async () => releases,
			tools(async () => "").run,
			noBreaks,
		),
		/nothing proves a host accepts it/u,
	);
});

await test("a full clone keeps its depth: only a missing tag is fetched", async (t) => {
	const missing = tools(async () => "");
	await checkPreviousVerifiers(
		"v1.0.1",
		commit,
		await scratch(t),
		"i.json",
		listed,
		missing.run,
		noBreaks,
	);
	assert.ok(missing.calls.some((call) => call[1] === "fetch"));
	const present = tools(async () => "", true);
	await checkPreviousVerifiers(
		"v1.0.1",
		commit,
		await scratch(t),
		"i.json",
		listed,
		present.run,
		noBreaks,
	);
	assert.ok(!present.calls.some((call) => call[1] === "fetch"));
});

await test("an accepted break lets only its own release and verifier pass", async (t) => {
	const entry = {
		release: "v1.0.1",
		verifier: "v1.0.0",
		reason: "Adds an image. See the upgrade guide.",
	};
	const check = async (release: string, breaks: readonly (typeof entry)[]) =>
		checkPreviousVerifiers(
			release,
			commit,
			await scratch(t),
			"i.json",
			listed,
			tools(refusing).run,
			async () => breaks,
		);
	const passed = await check("v1.0.1", [entry]);
	assert.deepEqual(passed.verifiers, ["v1.0.0"]);
	assert.deepEqual(
		passed.breaks.map(({ reason, failure }) => [reason, failure]),
		[[entry.reason, "Error: x.tgz does not pin the images"]],
		"the log names the real failure",
	);
	await assert.rejects(check("v1.0.2", [entry]), /did not accept v1\.0\.2/u, "a later release");
	await assert.rejects(
		check("v1.0.1", [{ ...entry, verifier: "v1.0.0-rc.4" }]),
		/did not accept v1\.0\.1/u,
		"another verifier",
	);
	await assert.rejects(check("v1.0.1", []), /did not accept v1\.0\.1/u, "no entry");
});

await test("an accepted break needs its reason, and the repository's file parses", async () => {
	assert.throws(
		() => parseAcceptedBreaks([{ release: "v1.0.1", verifier: "v1.0.0", reason: " " }]),
		/must state why/u,
	);
	assert.throws(() => parseAcceptedBreaks([{ release: "v1.0.1" }]));
	assert.throws(() => parseAcceptedBreaks({}));
	parseAcceptedBreaks(
		await readJsonFile(
			path.join(import.meta.dirname, "..", "security", "accepted-verifier-breaks.json"),
		),
	);
});

await test("the release runs the older verifiers before it publishes", async () => {
	const workflow = await readFile(
		new URL("../.github/workflows/release.yml", import.meta.url),
		"utf8",
	);
	const check = workflow.indexOf("node scripts/check-previous-verifier.ts");
	assert.ok(check !== -1, "release.yml runs the check");
	assert.ok(check < workflow.indexOf("name: Publish immutable release"));
});

await test("before a release, each verifier probes this ref's lock, manifest and inventory", async () => {
	const { calls, run } = tools(async () => "");
	const { verifiers } = await checkPreviousVerifiersBeforeRelease(
		"v1.1.0",
		"lock.json",
		"evidence/manifest.json",
		"security/release-images.json",
		listed,
		run,
		noBreaks,
	);
	assert.deepEqual(verifiers, ["v1.1.0-rc.1", "v1.0.0"]);
	const tree = calls.find((call) => call[2] === "add")?.[5];
	assert.ok(tree !== undefined);
	assert.deepEqual(
		calls.find((call) => call[0] === process.execPath),
		[
			process.execPath,
			path.join(import.meta.dirname, "previous-verifier-probe.ts"),
			tree,
			"v1.1.0",
			path.resolve("lock.json"),
			path.resolve("evidence/manifest.json"),
			path.resolve("security/release-images.json"),
		],
	);
});

await test("before a release, the same accepted breaks decide", async () => {
	const entry = {
		release: "v1.0.1",
		verifier: "v1.0.0",
		reason: "Removes an image. See the upgrade guide.",
	};
	const check = async (breaks: readonly (typeof entry)[]) =>
		checkPreviousVerifiersBeforeRelease(
			"v1.0.1",
			"lock.json",
			"manifest.json",
			"inventory.json",
			listed,
			tools(refusing).run,
			async () => breaks,
		);
	const accepted = await check([entry]);
	assert.deepEqual(
		accepted.breaks.map(({ verifier }) => verifier),
		["v1.0.0"],
	);
	await assert.rejects(check([]), /verifier of v1\.0\.0 did not accept v1\.0\.1/u);
});

const digest = (seed: number) => `sha256:${seed.toString(16).padStart(64, "0")}`;

await test("before a release, the verifiers read a joint lock with the chart that the release pushes", () => {
	const evidence = {
		schemaVersion: 1,
		release: "v1.1.0",
		commit,
		subjects: ["linux/amd64", "linux/arm64"].map((platform, index) => ({
			image: "webapp",
			repository: "ghcr.io/hephaestus-build/webapp",
			provenance: "first-party",
			indexDigest: digest(1),
			platform,
			digest: digest(2 + index),
		})),
	};
	const lock = preflightJointLock(evidence, "v1.1.0");
	assert.equal(lock.schemaVersion, 2);
	assert.equal(lock.chart.version, "1.1.0");
	assert.match(lock.chart.repository, /\/charts\/hephaestus$/u);
	assert.equal(lock.chart.digest, `sha256:${"0".repeat(64)}`);
	assert.deepEqual(
		lock.images.map(({ image }) => image),
		["webapp"],
	);
});

await test("the Version PR's preflight runs the older verifiers too", async () => {
	const workflow = await readFile(
		new URL("../.github/workflows/cicd.yml", import.meta.url),
		"utf8",
	);
	const preflight = workflow.slice(workflow.indexOf("Release-preflight:"));
	assert.match(preflight, /node scripts\/check-previous-verifier\.ts --preflight "\$RELEASE"/u);
});
