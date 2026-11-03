import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import process from "node:process";
import { test } from "node:test";
import { pathToFileURL } from "node:url";

import { isMap, isSeq, parseDocument } from "yaml";

import { asArray } from "./lib/json.ts";
import { currentReleaseIdentity, releaseIdentityFor } from "./lib/release-identities.ts";

await test("release and weekly runs use the supported seeded upgrade", () => {
	const workflow = parseDocument(readFileSync(".github/workflows/release-upgrade.yml", "utf8"));
	assert.equal(workflow.hasIn(["jobs", "upgrade", "strategy"]), false);
	assert.equal(workflow.hasIn(["on", "schedule"]), true);
	assert.equal(workflow.hasIn(["on", "workflow_call"]), true);
	const steps = workflow.getIn(["jobs", "upgrade", "steps"]);
	assert.ok(isSeq(steps));
	const resolver = steps.items.find((step) => isMap(step) && step.get("id") === "images");
	const driver = steps.items.find(
		(step) => isMap(step) && String(step.get("run")).includes("scripts/release-upgrade-test.ts"),
	);
	assert.ok(isMap(resolver));
	assert.ok(isMap(driver));
	const release = parseDocument(readFileSync(".github/workflows/release.yml", "utf8"));
	assert.equal(
		release.getIn(["jobs", "upgrade-test", "uses"]),
		"./.github/workflows/release-upgrade.yml",
	);
});

for (const version of ["0.95.0", "1.0.0-rc.1"] as const) {
	for (const source of ["release", "dispatch"] as const) {
		await test(`${source}: ${version} resolves its immutable image`, () => {
			const directory = mkdtempSync(path.join(tmpdir(), "upgrade-images-"));
			try {
				const preload = path.join(directory, "process.mjs");
				const output = path.join(directory, "output");
				const invocations = path.join(directory, "invocations");
				writeFileSync(
					preload,
					`import { appendFileSync } from "node:fs";
import { mock } from "node:test";
import * as childProcess from "node:child_process";
mock.module("node:child_process", { exports: {
  ...childProcess,
  spawnSync(executable, args) {
    if (executable !== "docker") throw new Error("Unexpected command: " + executable);
    appendFileSync(${JSON.stringify(invocations)}, args.join(" ") + "\\n");
    return { status: 0, stdout: JSON.stringify({ digest: "sha256:" + "a".repeat(64) }), stderr: "" };
  }
}});
`,
				);
				const { namespace } = currentReleaseIdentity();
				const result = spawnSync(
					process.execPath,
					[
						"--experimental-test-module-mocks",
						"--import",
						pathToFileURL(preload).href,
						"scripts/resolve-release-upgrade-images.ts",
					],
					{
						encoding: "utf8",
						env: {
							...process.env,
							INPUT_PREVIOUS_VERSION: source === "release" ? version : "",
							INPUT_CANDIDATE_APP:
								source === "release" ? `${namespace}/application-server:candidate` : "",
							INPUT_POSTGRES: source === "release" ? `${namespace}/postgres:candidate` : "",
							REQUESTED_PREVIOUS: `v${version}`,
							GITHUB_REPOSITORY: "hephaestus-build/Hephaestus",
							GITHUB_SHA: "b".repeat(40),
							GITHUB_OUTPUT: output,
						},
					},
				);
				assert.equal(result.status, 0, result.stdout + result.stderr);
				const historicalNamespace = releaseIdentityFor(version).namespace;
				assert.ok(
					readFileSync(invocations, "utf8").includes(
						`${historicalNamespace}/application-server:${version} `,
					),
				);
				assert.match(readFileSync(output, "utf8"), /^previous-app=.*@sha256:[a-f0-9]{64}$/mu);
				assert.match(
					readFileSync(output, "utf8"),
					new RegExp(`^previous-version=${version}$`, "mu"),
				);
				assert.match(
					readFileSync(output, "utf8"),
					/^previous-agent=.*\/agent-pi@sha256:[a-f0-9]{64}$/mu,
				);
				assert.match(readFileSync(output, "utf8"), /^candidate-app=.*@sha256:[a-f0-9]{64}$/mu);
			} finally {
				rmSync(directory, { recursive: true, force: true });
			}
		});
	}
}

// Protocol cases go through both receivers, with and without a stopped-upgrade waiver. These stubs prove orchestration;
// CI runs the PostgreSQL and NATS operations.
for (const [scenario, expectedError] of [
	["rolling-protocol-pass", ""],
	["stopped-protocol-pass", ""],
	["stopped-protocol-refused", "the body changed"],
] as const) {
	const stopped = scenario.startsWith("stopped-");
	await test(`upgrade qualification: ${scenario}`, () => {
		const directory = mkdtempSync(path.join(tmpdir(), "upgrade-seed-"));
		try {
			const commands = path.join(directory, "commands");
			const fixtures = path.join(directory, "fixtures");
			const preload = path.join(directory, "stubs.mjs");
			writeFileSync(
				preload,
				`import fs, { appendFileSync } from "node:fs";
import { syncBuiltinESMExports } from "node:module";
import { mock } from "node:test";
import * as childProcess from "node:child_process";
const commands = ${JSON.stringify(commands)};
const fixtures = ${JSON.stringify(fixtures)};
const stopped = ${JSON.stringify(stopped)};
const mismatch = ${JSON.stringify(scenario === "stopped-protocol-refused")};
const deliveries = new Map();
if (!stopped) {
  // The previous release's worker mounts the host's Docker socket, which a Windows runner lacks.
  const statSync = fs.statSync;
  fs.statSync = (file, ...rest) => (file === "/var/run/docker.sock" ? { gid: 0 } : statSync(file, ...rest));
  syncBuiltinESMExports();
}
{
  mock.module(${JSON.stringify(new URL("lib/migration-guide.ts", import.meta.url).href)}, {
    exports: { stopEveryRoleEntries: () => (stopped ? ["#### 🔴 Stop every role for the upgrade"] : []) }
  });
  mock.module(${JSON.stringify(import.meta.resolve("@nats-io/transport-node"))}, { exports: { connect: async () => ({ close: async () => {} }) } });
  mock.module(${JSON.stringify(import.meta.resolve("@nats-io/jetstream"))}, { exports: {
    jetstreamManager: async () => ({ streams: { getMessage: async (stream, { seq }) => {
      const delivery = deliveries.get(stream)[seq - 1];
      return {
        subject: stream + ".upgrade.issues",
        header: { keys: () => Object.keys(delivery.headers), get: key => delivery.headers[key] },
        string: () => mismatch && seq === 2 ? "changed" : delivery.body
      };
    } } })
  } });
}
globalThis.fetch = async (input, init) => {
  const url = String(input);
  if (url.endsWith("/auth/dev-login")) {
    return new Response(null, { status: 204, headers: { "set-cookie": "HEPHAESTUS_AT=fixture; Path=/" } });
  }
  if (url.endsWith("/user/consent")) return Response.json({ noticeVersion: "fixture", completed: true });
  if (url.endsWith("/identity-providers")) return Response.json([{}]);
  if (url.endsWith("/workspaces") && init?.method === "POST") return new Response(null, { status: 201 });
  if (url.endsWith("/workspaces")) return Response.json([{ workspaceSlug: "upgrade-fixture" }]);
  if (url.endsWith("/user")) return Response.json({ displayName: "Upgrade alice" });
  if (url.endsWith("/readyz")) return Response.json({ status: "UP" });
  if (url.includes("/webhooks/")) {
    const stream = url.split("/").at(-1);
    deliveries.set(stream, [...(deliveries.get(stream) ?? []), init]);
    appendFileSync(commands, JSON.stringify(["delivery", stream]) + "\\n");
    return Response.json({ status: "ok" }, { status: 202 });
  }
  if (url.endsWith("/admin/practice-catalog")) {
    return Response.json({ practices: [{ slug: "fixture", offered: true }] });
  }
  if (url.endsWith("/practices")) return Response.json([{ slug: "fixture" }]);
  // A rolling upgrade first runs the previous release on the candidate's schema and binds a review model there.
  if ((url.includes("/admin/llm/") || url.endsWith("/agents/PRACTICE_REVIEW"))) {
    return Response.json({ id: 1 });
  }
  throw new Error("Unexpected HTTP request: " + url);
};
mock.module("node:child_process", { exports: {
  ...childProcess,
  spawnSync(executable, args, options) {
    if (executable !== "docker") throw new Error("Unexpected command: " + executable);
    appendFileSync(commands, JSON.stringify(args) + "\\n");
    if (args.includes("--file=-")) appendFileSync(fixtures, options.input + "\\n");
    let stdout = "", stderr = "", status = 0;
    if (args[0] === "port") stdout = "127.0.0.1:18080";
    else if (args[0] === "inspect") stdout = "running";
    else if (args.includes("--command")) {
      const sql = args.at(-1);
      if (sql.includes("INSERT INTO")) stdout = "7";
      else if (sql.includes("SELECT kind ||")) {
        stdout = "account|1\\nidentity|1\\nuser|1\\nworkspace|1\\nmembership|1\\nconnection|1";
      }
      else if (sql.includes("LEFT JOIN practice_version")) stdout = "0";
      else if (sql.includes("FROM review_job WHERE id=")) stdout = "1|true|RUNNING|status=RUNNING";
      else if (sql.includes("count(*)")) stdout = "1";
    }
    return { status, stdout, stderr };
  }
}});
`,
			);
			const result = spawnSync(
				process.execPath,
				[
					"--experimental-test-module-mocks",
					"--import",
					pathToFileURL(preload).href,
					"scripts/release-upgrade-test.ts",
					"old-image",
					"target-image",
					"postgres-image",
				],
				{
					encoding: "utf8",
					timeout: 15_000,
					env: {
						...process.env,
						PREVIOUS_VERSION: "0.95.1",
						PREVIOUS_AGENT_IMAGE: "agent-image",
					},
				},
			);
			assert.equal(result.status, expectedError === "" ? 0 : 1, result.stdout + result.stderr);
			if (expectedError !== "") {
				assert.ok(result.stderr.includes(expectedError), result.stderr);
			}
			const invocations = existsSync(commands) ? readFileSync(commands, "utf8") : "";
			assert.ok(invocations.includes('"target-image"'), invocations);
			{
				assert.equal(
					/Previous application\/schema boot coverage is off/u.test(result.stdout),
					stopped,
				);
				const calls = invocations
					.trim()
					.split("\n")
					.map((line) => asArray(JSON.parse(line), "command"));
				const receivers = calls.filter(
					(call) => call[0] === "run" && call.includes("SPRING_PROFILES_ACTIVE=e2e,webhook"),
				);
				assert.equal(receivers.length, 2);
				const [previous = [], candidate = []] = receivers;
				assert.ok(previous.includes("old-image"));
				assert.ok(
					previous.includes(
						`SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/${stopped ? "hephaestus_partial" : "hephaestus"}`,
					),
				);
				assert.ok(candidate.includes("target-image"));
				assert.ok(
					candidate.includes("SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/hephaestus"),
				);
				assert.equal(calls.filter((call) => call[0] === "delivery").length, 2);
				assert.equal(
					calls.some((call) => call[0] === "pull" && call.includes("agent-image")),
					!stopped,
				);
				if (expectedError === "") {
					for (const provider of ["github"]) {
						assert.ok(
							result.stdout.includes(
								`Webhook NATS protocol passed (0.95.1 receiver -> candidate, ${provider})`,
							),
						);
					}
				} else {
					assert.doesNotMatch(result.stdout, /Webhook NATS protocol passed/u);
				}
			}
		} finally {
			rmSync(directory, { recursive: true, force: true });
		}
	});
}

await test("the upgrade refuses to run without the previous release version", () => {
	const result = spawnSync(
		process.execPath,
		["scripts/release-upgrade-test.ts", "old-image", "target-image", "postgres-image"],
		{ encoding: "utf8", timeout: 15_000, env: { ...process.env, PREVIOUS_VERSION: "" } },
	);
	assert.equal(result.status, 1, result.stdout + result.stderr);
	assert.match(result.stderr, /The upgrade needs PREVIOUS_VERSION/u);
});
