import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdir, mkdtemp, readFile, rename, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";

import { isRecord } from "./lib/json.ts";

const digest = `sha256:${"a".repeat(64)}`;
const release = "v0.86.1";

/**
 * An archived release by default: its server image predates the web app's move into it, and a webapp image carries
 * the web app (ADR 0100).
 */
async function fixture(tag = release, names = ["server", "webapp"]) {
	const directory = await mkdtemp(path.join(tmpdir(), "release-rescan-"));
	const evidence = path.join(directory, "evidence");
	const reports = path.join(directory, "reports");
	const bin = path.join(directory, "bin");
	await mkdir(evidence);
	await mkdir(bin);
	const images = names.map((image) => ({
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
		path.join(evidence, `release-${tag}.chart.json`),
		JSON.stringify({
			schemaVersion: 2,
			release: tag,
			commit: manifest.commit,
			images,
			chart: {
				repository: "ghcr.io/hephaestus-build/charts/hephaestus",
				version: tag.slice(1),
				digest,
			},
		}),
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
case "$1" in
 create) printf '%s\n' 'container'; exit 0;;
 cp) : > "$3"; exit 0;;
 rm) exit 0;;
esac
if [ "$5" = '--raw' ]; then printf '%s' '{"layers":[]}'; else printf '%s' '${digest}'; fi
`,
		{ mode: 0o755 },
	);
	await writeFile(
		path.join(bin, "trivy"),
		`#!/bin/sh
if [ "$1" = rootfs ]; then
 # trivy rootfs --skip-db-update --scanners vuln --list-all-pkgs --format json --output <report> <web app>
 printf '%s\n' "\${10}" >> '${directory}/scans'
 if [ "$WEB_APP_PACKAGES" = none ]; then printf '%s' '{"Results":[]}' > "$9"
 else printf '%s' '{"Results":[{"Type":"node-pkg","Packages":[{"Name":"react"},{"Name":"react-dom"}]}]}' > "$9"; fi
else
 printf '%s\n' "$9" >> '${directory}/scans'
 printf '%s' '{"Results":[]}' > "$8"
fi
`,
		{ mode: 0o755 },
	);
	// Every subject carries the server's JVM, so the runtime scan runs and matches nothing.
	await writeFile(
		path.join(bin, "syft"),
		`#!/bin/sh
for argument; do case "$argument" in syft-json=*) printf '%s' '${JSON.stringify({ artifacts: [{ name: "openjdk", version: "25.0.5.0.3+5-LTS", foundBy: "java-jvm-cataloger", locations: [{ path: "/jre/release" }], cpes: [{ cpe: "cpe:2.3:a:oracle:openjdk:25.0.5.0.3:*:*:*:*:*:*:*" }] }] })}' > "\${argument#syft-json=}";; esac; done
`,
		{ mode: 0o755 },
	);
	await writeFile(
		path.join(bin, "grype"),
		`#!/bin/sh\nif [ "$1" = db ]; then printf '[]'; else printf '{"matches":[]}'; fi\n`,
		{
			mode: 0o755,
		},
	);
	// The server's jar holds the web app only where WEB_APP is set.
	await writeFile(
		path.join(bin, "unzip"),
		`#!/bin/sh
# As unzip does, create only the last directory of the destination.
mkdir "$5" 2>/dev/null || [ -d "$5" ] || { echo "checkdir: cannot create extraction directory: $5" >&2; exit 3; }
if [ "$WEB_APP" = 'true' ]; then mkdir -p "$5/web-app"; : > "$5/web-app/index.html"; fi
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
case "$6" in *.web-app.json) if [ "$FAIL_WEB_APP" = 'true' ]; then
 printf '%s' '{"status":"fail"}' > "$8"; exit 1
fi;; esac
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

for (const failure of ["cosign", "manifest", "legacy-schema", "legacy-asset"] as const) {
	void test(
		`${failure} authentication failure starts no registry scan`,
		{ skip: process.platform === "win32" },
		async () => {
			const f = await fixture();
			const lockPath = path.join(f.evidence, `release-${f.release}.chart.json`);
			if (failure === "legacy-schema") {
				const lock: unknown = JSON.parse(await readFile(lockPath, "utf8"));
				assert.ok(isRecord(lock));
				const { chart: _chart, ...images } = lock;
				await writeFile(lockPath, JSON.stringify({ ...images, schemaVersion: 1 }));
			}
			if (failure === "legacy-asset") {
				await rename(lockPath, path.join(f.evidence, `release-${f.release}.json`));
			}
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
			if (failure === "legacy-schema") {
				assert.match(String(value.authentication.error), /expected a joint lock/u);
			}
			if (failure === "legacy-asset") {
				assert.match(String(value.authentication.error), /ENOENT.*\.chart\.json/u);
			}
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

void test(
	"a server image that carries the web app has the web app scanned under the same policy",
	{ skip: process.platform === "win32" },
	async () => {
		for (const failWebApp of [false, true]) {
			const f = await fixture(release, ["server", "agent"]);
			const child = execute(f, {
				PASS_SCAN: "true",
				WEB_APP: "true",
				FAIL_WEB_APP: String(failWebApp),
			});
			assert.equal(child.status === 0, !failWebApp, child.stderr);
			// The two images and the server's web app, once per platform.
			const scans = await readFile(path.join(f.directory, "scans"), "utf8");
			assert.equal(scans.trim().split("\n").length, 6);
			for (const platform of ["linux-amd64", "linux-arm64"]) {
				const policy: unknown = JSON.parse(
					await readFile(path.join(f.reports, `server-${platform}.web-app.policy.json`), "utf8"),
				);
				assert.deepEqual(policy, { status: failWebApp ? "fail" : "pass" });
			}
			const value = await result(f);
			assert.deepEqual(value.outcomes, [
				{ image: "server", passed: !failWebApp, platform: "linux/amd64" },
				{ image: "agent", passed: true, platform: "linux/amd64" },
				{ image: "server", passed: !failWebApp, platform: "linux/arm64" },
				{ image: "agent", passed: true, platform: "linux/arm64" },
			]);
			assert.deepEqual(value.currentSecurity, { status: failWebApp ? "fail" : "pass" });
		}
	},
);

void test(
	"a scan of the web app that lists no bundled react is unavailable, not a pass",
	{ skip: process.platform === "win32" },
	async () => {
		const f = await fixture(release, ["server", "agent"]);
		const child = execute(f, { PASS_SCAN: "true", WEB_APP: "true", WEB_APP_PACKAGES: "none" });
		assert.notEqual(child.status, 0);
		const value = await result(f);
		assert.deepEqual(value.outcomes, [
			{
				image: "server",
				platform: "linux/amd64",
				passed: false,
				error: "the scan of the web app lists no bundled react",
			},
			{ image: "agent", passed: true, platform: "linux/amd64" },
			{
				image: "server",
				platform: "linux/arm64",
				passed: false,
				error: "the scan of the web app lists no bundled react",
			},
			{ image: "agent", passed: true, platform: "linux/arm64" },
		]);
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
