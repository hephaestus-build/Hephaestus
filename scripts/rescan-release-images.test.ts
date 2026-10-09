import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdir, mkdtemp, readFile, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";

import { isRecord } from "./lib/json.ts";

const digest = `sha256:${"a".repeat(64)}`;
const release = "v0.86.1";

async function fixture(tag = release) {
	const directory = await mkdtemp(path.join(tmpdir(), "release-rescan-"));
	const evidence = path.join(directory, "evidence");
	const reports = path.join(directory, "reports");
	const bin = path.join(directory, "bin");
	await mkdir(evidence);
	await mkdir(bin);
	const images = ["server", "webapp"].map((image) => ({
		image,
		repository: `ghcr.io/hephaestus-build/${image}`,
		provenance: "first-party",
		indexDigest: digest,
		platforms: { "linux/amd64": digest, "linux/arm64": digest },
	}));
	const subjects = images.flatMap((image) =>
		["linux/amd64", "linux/arm64"].map((platform) => ({
			image: image.image,
			repository: image.repository,
			provenance: image.provenance,
			indexDigest: image.indexDigest,
			platform,
			digest,
		})),
	);
	const manifest = { schemaVersion: 1, release: tag, commit: "b".repeat(40), subjects };
	await writeFile(
		path.join(evidence, `release-${tag}.json`),
		JSON.stringify({ schemaVersion: 1, release: tag, commit: manifest.commit, images }),
	);
	await writeFile(path.join(evidence, "manifest.json"), JSON.stringify(manifest));
	await writeFile(
		path.join(evidence, "release-images.json"),
		JSON.stringify({ schemaVersion: 1, images: images.map((image) => image.image), upstream: [] }),
	);
	await writeFile(path.join(evidence, "vulnerability-policy.json"), "{}");
	for (const command of ["sha256sum", "cosign"]) {
		await writeFile(
			path.join(bin, command),
			`#!/bin/sh
printf '%s\n' '${command}' "$@" >> '${directory}/authentication'
if [ "$FAIL_AUTH" = '${command}' ]; then printf '%s\n' '${command} refused' >&2; exit 1; fi
`,
			{ mode: 0o755 },
		);
	}
	await writeFile(
		path.join(bin, "docker"),
		`#!/bin/sh
if [ "$5" = '--raw' ]; then printf '%s' '{"layers":[]}'; else printf '%s' '${digest}'; fi
`,
		{ mode: 0o755 },
	);
	await writeFile(
		path.join(bin, "trivy"),
		`#!/bin/sh
printf '%s\n' "$9" >> '${directory}/scans'
printf '%s' '{"Results":[]}' > "$8"
`,
		{ mode: 0o755 },
	);
	await writeFile(
		path.join(bin, "node"),
		`#!/bin/sh
case "$1" in *verify-release-evidence.ts)
 printf '%s\n' 'archive reader invoked' > '${directory}/archive-read'
 if [ -n "$ARCHIVE_FAILURE" ]; then printf '%s\n' "$ARCHIVE_FAILURE" >&2
 else printf '%s\n' 'archived evidence has no SHA256SUMS signature' >&2; fi
 exit 1;; esac
if [ "$2-$3" = 'server-linux/amd64' ] && [ "$PASS_SCAN" != 'true' ]; then
 if [ "$FAIL_SCAN" = 'infra' ]; then printf '%s\n' 'identity acquisition unavailable' >&2; exit 3; fi
 printf '%s' '{"status":"fail"}' > "$8"; exit 1
fi
printf '%s' '{"status":"pass"}' > "$8"
`,
		{ mode: 0o755 },
	);
	return { directory, evidence, reports, bin, manifest, release: tag };
}

function execute(f: Awaited<ReturnType<typeof fixture>>, env: Record<string, string> = {}) {
	return spawnSync(
		process.execPath,
		[
			new URL("rescan-release-images.ts", import.meta.url).pathname,
			f.evidence,
			f.reports,
			f.release,
		],
		{
			encoding: "utf8",
			env: { ...process.env, PATH: `${f.bin}:${process.env.PATH ?? ""}`, ...env },
		},
	);
}

async function result(f: Awaited<ReturnType<typeof fixture>>) {
	const value: unknown = JSON.parse(
		await readFile(path.join(f.reports, "release-rescan.json"), "utf8"),
	);
	assert.ok(isRecord(value));
	return value;
}

void test(
	"archived refusal and an early current policy failure still scan every authenticated subject",
	{ skip: process.platform === "win32" },
	async () => {
		const f = await fixture("v0.86.2");
		const child = execute(f);
		assert.notEqual(child.status, 0);
		const scans = await readFile(path.join(f.directory, "scans"), "utf8");
		assert.equal(scans.trim().split("\n").length, 4);
		const value = await result(f);
		assert.deepEqual(value.authentication, { status: "pass" });
		assert.match(
			await readFile(path.join(f.directory, "authentication"), "utf8"),
			/--certificate-identity[\s\S]*https:\/\/github.com\/hephaestus-build\/Hephaestus\/.github\/workflows\/release.yml@refs\/heads\/main/u,
		);
		assert.ok(isRecord(value.archiveAssurance));
		assert.equal(value.archiveAssurance.status, "fail");
		assert.match(String(value.archiveAssurance.error), /has no SHA256SUMS signature/u);
		assert.deepEqual(value.outcomes, [
			{ image: "server", passed: false, platform: "linux/amd64" },
			{ image: "webapp", passed: true, platform: "linux/amd64" },
			{ image: "server", passed: true, platform: "linux/arm64" },
			{ image: "webapp", passed: true, platform: "linux/arm64" },
		]);
		assert.equal(value.status, "fail");
	},
);

void test(
	"an unavailable early scan stays distinct from policy refusal and later subjects finish",
	{ skip: process.platform === "win32" },
	async () => {
		const f = await fixture();
		await mkdir(f.reports);
		await writeFile(path.join(f.reports, "server-linux-amd64.policy.json"), '{"status":"pass"}');
		const child = execute(f, { FAIL_SCAN: "infra" });
		assert.notEqual(child.status, 0);
		assert.match(child.stderr, /identity acquisition unavailable/u);
		const value = await result(f);
		assert.ok(Array.isArray(value.outcomes));
		assert.ok(isRecord(value.outcomes[0]));
		assert.match(String(value.outcomes[0].error), /produced no result/u);
		await assert.rejects(
			readFile(path.join(f.reports, "server-linux-amd64.policy.json")),
			/ENOENT/u,
		);
		assert.equal(value.outcomes.length, 4);
		const scans = await readFile(path.join(f.directory, "scans"), "utf8");
		assert.equal(scans.trim().split("\n").length, 4);
	},
);

for (const failure of ["cosign", "manifest"] as const) {
	void test(
		`${failure} authentication failure starts no registry scan`,
		{ skip: process.platform === "win32" },
		async () => {
			const f = await fixture();
			if (failure === "manifest") {
				await writeFile(
					path.join(f.evidence, "manifest.json"),
					JSON.stringify({ ...f.manifest, subjects: f.manifest.subjects.slice(1) }),
				);
			}
			const child = execute(f, { FAIL_AUTH: failure });
			assert.notEqual(child.status, 0);
			await assert.rejects(readFile(path.join(f.directory, "scans")), /ENOENT/u);
			const value = await result(f);
			assert.deepEqual(value.outcomes, []);
			assert.ok(isRecord(value.authentication));
			assert.equal(value.authentication.status, "fail");
		},
	);
}

void test(
	"historical unsigned assurance never reads archive claims and fresh security can pass",
	{ skip: process.platform === "win32" },
	async () => {
		const f = await fixture();
		await writeFile(path.join(f.evidence, "vulnerability-policy.json"), "untrusted forged policy");
		await writeFile(
			path.join(f.evidence, "server-linux-amd64.syft.json"),
			"untrusted forged identity",
		);
		const child = execute(f, { PASS_SCAN: "true" });
		assert.equal(child.status, 0);
		const value = await result(f);
		assert.ok(isRecord(value.archiveAssurance));
		assert.equal(value.archiveAssurance.status, "not_authenticated");
		assert.deepEqual(value.currentSecurity, { status: "pass" });
		assert.equal(value.status, "pass");
		await assert.rejects(readFile(path.join(f.directory, "archive-read")), /ENOENT/u);
		const scans = await readFile(path.join(f.directory, "scans"), "utf8");
		assert.equal(scans.trim().split("\n").length, 4);
	},
);

for (const failure of ["signature refused", "archived policy changed", "advisory SHA mismatch"]) {
	void test(
		`historical ${failure} stays a failure even when every fresh scan passes`,
		{ skip: process.platform === "win32" },
		async () => {
			const f = await fixture();
			await writeFile(
				path.join(f.evidence, "SHA256SUMS.sigstore.json"),
				"present synthetic bundle",
			);
			const child = execute(f, { PASS_SCAN: "true", ARCHIVE_FAILURE: failure });
			assert.notEqual(child.status, 0);
			const value = await result(f);
			assert.deepEqual(value.currentSecurity, { status: "pass" });
			assert.ok(isRecord(value.archiveAssurance));
			assert.equal(value.archiveAssurance.status, "fail");
			assert.ok(String(value.archiveAssurance.error).includes(failure));
			assert.equal(value.status, "fail");
			assert.ok(Array.isArray(value.outcomes));
			assert.equal(value.outcomes.length, 4);
		},
	);
}

void test(
	"new-contract missing signature fails independently of passing fresh security",
	{ skip: process.platform === "win32" },
	async () => {
		const f = await fixture("v0.86.2");
		const child = execute(f, { PASS_SCAN: "true" });
		assert.notEqual(child.status, 0);
		const value = await result(f);
		assert.deepEqual(value.currentSecurity, { status: "pass" });
		assert.ok(isRecord(value.archiveAssurance));
		assert.equal(value.archiveAssurance.status, "fail");
		assert.equal(value.status, "fail");
		assert.ok(Array.isArray(value.outcomes));
		assert.equal(value.outcomes.length, 4);
	},
);

void test(
	"an attacker-controlled historical manifest tag cannot downgrade the requested signed release",
	{ skip: process.platform === "win32" },
	async () => {
		const f = await fixture("v0.86.2");
		await writeFile(
			path.join(f.evidence, "manifest.json"),
			JSON.stringify({ ...f.manifest, release: "v0.86.1" }),
		);
		const child = execute(f, { PASS_SCAN: "true" });
		assert.notEqual(child.status, 0);
		const value = await result(f);
		assert.ok(isRecord(value.authentication));
		assert.equal(value.authentication.status, "fail");
		assert.deepEqual(value.outcomes, []);
		await assert.rejects(readFile(path.join(f.directory, "scans")), /ENOENT/u);
	},
);
