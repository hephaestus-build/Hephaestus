import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";

const installer = await readFile(
	new URL("../deploy/platform/k3s-install.sh", import.meta.url),
	"utf8",
);
// Execute the installer's actual artifact-verification/install stage, without host preparation.
const stage = installer.slice(
	installer.indexOf('say "Verifying the release chart"'),
	installer.indexOf('if [ "$oauth_changed" = true ]'),
);
const functions = installer.slice(
	installer.indexOf("matches()"),
	installer.indexOf("\n}", installer.indexOf("matches()")) + 2,
);
const transport = String.raw`#!/usr/bin/env node
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const args = process.argv.slice(2);
const option = flag => args[args.indexOf(flag) + 1];
const tool = path.basename(process.argv[1]);
if (tool === 'cosign') {
  if (args[0] === 'verify-blob') {
    const digest = crypto.createHash('sha256').update(fs.readFileSync(args.at(-1))).digest('hex');
    if (digest !== fs.readFileSync(option('--bundle'), 'utf8')) process.exit(1);
  }
} else if (tool === 'helm') {
  if (args[0] === 'pull') fs.mkdirSync(path.join(option('--untardir'), 'hephaestus', 'profiles'), {recursive:true});
  else if (args[0] === 'show') console.log('version: ' + process.env.CHART_VERSION);
  else if (args[0] === 'upgrade') fs.writeFileSync(process.env.INSTALL_ARGS, JSON.stringify(args));
  else process.exit(1);
} else process.exit(1);
`;

for (const scenario of [
	"candidate",
	"stable-promotion",
	"stale-candidate-chart",
	"tampered-lock",
	"foreign-chart",
	"wrong-release",
] as const) {
	void test(
		`installer release binding: ${scenario}`,
		{ skip: process.platform === "win32" },
		async (t) => {
			const directory = await mkdtemp(path.join(tmpdir(), "candidate-installer-"));
			t.after(async () => rm(directory, { recursive: true, force: true }));
			const bin = path.join(directory, "bin");
			await mkdir(bin);
			for (const tool of ["cosign", "helm"]) {
				await writeFile(path.join(bin, tool), transport, { mode: 0o755 });
			}
			const version = scenario === "candidate" ? "1.0.0-rc.2" : "1.0.0";
			const artifact =
				scenario === "foreign-chart"
					? "1.0.1-rc.2"
					: scenario === "stale-candidate-chart"
						? "1.0.0-rc.2"
						: version;
			const lock = path.join(directory, "release.chart.json");
			const body = JSON.stringify({
				schemaVersion: 2,
				release: scenario === "wrong-release" ? "v1.0.1" : `v${version}`,
				commit: "a".repeat(40),
				chart: { version: artifact, digest: `sha256:${"b".repeat(64)}` },
			});
			await writeFile(lock, body);
			await writeFile(
				`${lock}.sigstore.json`,
				scenario === "tampered-lock" ? "wrong" : createHash("sha256").update(body).digest("hex"),
			);
			const installArgs = path.join(directory, "install-args.json");
			const script = `set -eu\nsay() { :; }\ndie() { echo "$*" >&2; exit 1; }\n${functions}\n${stage}`;
			const result = spawnSync("sh", ["-c", script], {
				encoding: "utf8",
				env: {
					...process.env,
					PATH: `${bin}${path.delimiter}${process.env.PATH ?? ""}`,
					VERSION: version,
					CHART_VERSION: artifact,
					LOCK: lock,
					TEMP: directory,
					HERE: directory,
					SIGNER: "https://github.com/example/release.yml@refs/heads/main",
					ISSUER: "https://token.actions.githubusercontent.com",
					CHART_REPOSITORY: "ghcr.io/example/charts/hephaestus",
					NAMESPACE: "scratch",
					PROFILE_16GIB: "false",
					INSTALL_ARGS: installArgs,
				},
			});
			if (scenario === "candidate" || scenario === "stable-promotion") {
				assert.equal(result.status, 0, result.stderr);
				const args = await readFile(installArgs, "utf8");
				assert.ok(args.includes(`release.version=v${version}`));
				assert.ok(args.includes(`release.commit=${"a".repeat(40)}`));
			} else {
				assert.notEqual(result.status, 0);
				await assert.rejects(readFile(installArgs), { code: "ENOENT" });
			}
		},
	);
}
