import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { chmodSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import process from "node:process";
import { test } from "node:test";

import { isSeq, parseDocument } from "yaml";

import { currentReleaseIdentity, releaseIdentityFor } from "./lib/release-identities.ts";

await test("release and weekly runs require latest, baseline, and refusal paths", () => {
	const workflow = parseDocument(readFileSync(".github/workflows/release-upgrade.yml", "utf8"));
	const paths = workflow.getIn(["jobs", "upgrade", "strategy", "matrix", "path"]);
	assert.ok(isSeq(paths));
	assert.deepEqual(paths.toJSON(), ["latest", "baseline", "refusal"]);
	assert.equal(workflow.getIn(["jobs", "upgrade", "strategy", "fail-fast"]), false);
	assert.equal(workflow.hasIn(["on", "schedule"]), true);
	assert.equal(workflow.hasIn(["on", "workflow_call"]), true);
	const source = readFileSync(".github/workflows/release-upgrade.yml", "utf8");
	assert.equal((source.match(/UPGRADE_PATH: \$\{\{ matrix.path \}\}/gu) ?? []).length, 2);
	assert.match(source, /release-upgrade-test.ts.*"\$UPGRADE_PATH"/u);
	const release = parseDocument(readFileSync(".github/workflows/release.yml", "utf8"));
	assert.equal(
		release.getIn(["jobs", "upgrade-test", "uses"]),
		"./.github/workflows/release-upgrade.yml",
	);
});

for (const [upgradePath, version] of [
	["latest", "0.80.0"],
	["baseline", "0.77.4"],
	["refusal", "0.76.0"],
] as const) {
	await test(`${upgradePath} resolves its own immutable historical image`, () => {
		const directory = mkdtempSync(path.join(tmpdir(), "upgrade-images-"));
		try {
			const executable = path.join(directory, "docker");
			const output = path.join(directory, "output");
			const invocations = path.join(directory, "invocations");
			writeFileSync(
				executable,
				`#!${process.execPath}
import { appendFileSync } from "node:fs";
appendFileSync(${JSON.stringify(invocations)}, process.argv.slice(2).join(" ") + "\\n");
console.log(JSON.stringify({ digest: "sha256:" + "a".repeat(64) }));
`,
			);
			chmodSync(executable, 0o755);
			const { namespace } = currentReleaseIdentity();
			const result = spawnSync(process.execPath, ["scripts/resolve-release-upgrade-images.ts"], {
				encoding: "utf8",
				env: {
					...process.env,
					PATH: `${directory}${path.delimiter}${process.env.PATH ?? ""}`,
					UPGRADE_PATH: upgradePath,
					INPUT_PREVIOUS_VERSION: "0.80.0",
					INPUT_CANDIDATE_APP: `${namespace}/application-server:candidate`,
					INPUT_POSTGRES: `${namespace}/postgres:candidate`,
					GITHUB_OUTPUT: output,
				},
			});
			assert.equal(result.status, 0, result.stdout + result.stderr);
			const historicalNamespace = releaseIdentityFor(version).namespace;
			assert.ok(
				readFileSync(invocations, "utf8").includes(
					`${historicalNamespace}/application-server:${version} `,
				),
			);
			assert.match(readFileSync(output, "utf8"), /^previous-app=.*@sha256:[a-f0-9]{64}$/mu);
			assert.match(readFileSync(output, "utf8"), /^candidate-app=.*@sha256:[a-f0-9]{64}$/mu);
		} finally {
			rmSync(directory, { recursive: true, force: true });
		}
	});
}

// Exercise the release driver as a process. The transport stubs model only the old release fixture;
// Liquibase behavior itself is covered against PostgreSQL by LiquibaseBaselineIntegrationTest.
for (const [scenario, expectedError] of [
	["refused", undefined],
	["sync-accepted", "Expected cut-point refusal from changeLogSyncToTag"],
	["sync-unrelated-error", "Expected cut-point refusal from changeLogSyncToTag"],
	["startup-accepted", "Expected cut-point refusal at startup"],
	["startup-unrelated-error", "Expected cut-point refusal at startup"],
	["history-changed", "Refused synchronization changed migration history or seeded data"],
	["lock-held", "Refused upgrade left the Liquibase lock held"],
] as const) {
	await test(`refusal driver rejects false evidence: ${scenario}`, () => {
		const directory = mkdtempSync(path.join(tmpdir(), "upgrade-refusal-"));
		try {
			const executable = path.join(directory, "docker");
			const commands = path.join(directory, "commands");
			const preload = path.join(directory, "fetch.mjs");
			writeFileSync(
				preload,
				`globalThis.fetch = async (input, init) => {
  const url = String(input);
  if (url.endsWith("/auth/dev-login")) {
    return new Response(null, { status: 204, headers: { "set-cookie": "HEPHAESTUS_AT=fixture; Path=/" } });
  }
  if (url.endsWith("/user/consent")) {
    return Response.json({ noticeVersion: "fixture", completed: true });
  }
  if (url.endsWith("/identity-providers")) return Response.json([{}]);
  if (url.endsWith("/workspaces") && init?.method === "POST") return new Response(null, { status: 201 });
  if (url.endsWith("/workspaces")) return Response.json([{ workspaceSlug: "upgrade-fixture" }]);
  if (url.endsWith("/user")) return Response.json({ displayName: "Upgrade alice" });
  if (url.endsWith("/actuator/health/readiness")) return Response.json({ status: "UP" });
  throw new Error("Unexpected HTTP request: " + url);
};
`,
			);
			writeFileSync(
				executable,
				`#!${process.execPath}
import { appendFileSync, readFileSync } from "node:fs";
const commands = ${JSON.stringify(commands)};
const scenario = ${JSON.stringify(scenario)};
const args = process.argv.slice(2);
appendFileSync(commands, JSON.stringify(args) + "\\n");
const message = "install v0.77.4 and start it once, then follow the baseline synchronization runbook";
if (args.includes("changeLogSyncToTag")) {
  console.error(scenario === "sync-unrelated-error" ? "connection failed" : message);
  process.exit(scenario === "sync-accepted" ? 0 : 1);
}
if (args[0] === "port") console.log("127.0.0.1:18080");
else if (args[0] === "inspect") {
  if (args.includes("{{.State.ExitCode}}")) console.log(scenario === "startup-accepted" ? "0" : "1");
  else console.log(args.at(-1).endsWith("-refused") ? "exited" : "running");
}
else if (args[0] === "logs") console.error(scenario === "startup-unrelated-error" ? "unrelated failure" : message);
else if (args.includes("--command")) {
  const sql = args.at(-1);
  if (sql.includes("SELECT kind ||")) console.log("account|1\\nidentity|1\\nuser|1\\nworkspace|1\\nmembership|1\\nconnection|1");
  else if (sql.includes("row_to_json")) {
    const synced = readFileSync(commands, "utf8").includes("changeLogSyncToTag");
    console.log(scenario === "history-changed" && synced ? "changed" : "history");
  }
  else if (sql.includes("databasechangeloglock")) console.log(scenario === "lock-held" ? "1" : "0");
  else if (sql.includes("1788679885460-1")) console.log("0");
  else if (sql.includes("count(*)")) console.log("1");
}
`,
			);
			chmodSync(executable, 0o755);
			const result = spawnSync(
				process.execPath,
				[
					"--import",
					preload,
					"scripts/release-upgrade-test.ts",
					"old-image",
					"target-image",
					"postgres-image",
					"refusal",
				],
				{
					encoding: "utf8",
					timeout: 15_000,
					env: { ...process.env, PATH: `${directory}${path.delimiter}${process.env.PATH ?? ""}` },
				},
			);
			if (expectedError === undefined) {
				assert.equal(result.status, 0, result.stdout + result.stderr);
				assert.match(result.stdout, /Pinned v0.76.0 update and synchronization refusal passed/u);
			} else {
				assert.equal(result.status, 1, result.stdout + result.stderr);
				assert.ok(result.stderr.includes(expectedError), result.stderr);
			}
			const invocations = readFileSync(commands, "utf8");
			assert.match(invocations, /"rm","--force".*-sync".*-postgres"/u);
			assert.match(invocations, /"network","rm"/u);
		} finally {
			rmSync(directory, { recursive: true, force: true });
		}
	});
}
