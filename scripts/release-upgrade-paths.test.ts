import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import process from "node:process";
import { test } from "node:test";
import { pathToFileURL } from "node:url";

import { isMap, isSeq, parseDocument } from "yaml";

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

// A required seed must run on the previous release before the upgrade starts. The stubs model a previous release with
// or without Heph chat history and stop the run when the candidate starts, so each case ends at a known point.
// The seed and its assertions themselves run against PostgreSQL only in a real release upgrade.
for (const [scenario, args, previousKeepsChat, expectedError, seeded] of [
	["required-admitted", ["--require-seed=heph-chat"], true, "candidate start stubbed", true],
	[
		"required-missing",
		["--require-seed=heph-chat"],
		false,
		"The required heph-chat seed was not admitted",
		false,
	],
	["optional-admitted", [], true, "candidate start stubbed", true],
	["optional-missing", [], false, "candidate start stubbed", false],
	["unknown-seed", ["--require-seed=chat"], true, "Unknown option: --require-seed=chat", false],
] as const) {
	await test(`required seed: ${scenario}`, () => {
		const directory = mkdtempSync(path.join(tmpdir(), "upgrade-seed-"));
		try {
			const commands = path.join(directory, "commands");
			const fixtures = path.join(directory, "fixtures");
			const preload = path.join(directory, "stubs.mjs");
			writeFileSync(
				preload,
				`import { appendFileSync } from "node:fs";
import { mock } from "node:test";
import * as childProcess from "node:child_process";
const commands = ${JSON.stringify(commands)};
const fixtures = ${JSON.stringify(fixtures)};
const previousKeepsChat = ${JSON.stringify(previousKeepsChat)};
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
  throw new Error("Unexpected HTTP request: " + url);
};
mock.module("node:child_process", { exports: {
  ...childProcess,
  spawnSync(executable, args, options) {
    if (executable !== "docker") throw new Error("Unexpected command: " + executable);
    appendFileSync(commands, JSON.stringify(args) + "\\n");
    if (args.includes("--file=-")) appendFileSync(fixtures, options.input + "\\n");
    let stdout = "", stderr = "", status = 0;
    if (args[0] === "run" && args.includes("target-image")) { status = 1; stderr = "candidate start stubbed"; }
    else if (args[0] === "port") stdout = "127.0.0.1:18080";
    else if (args[0] === "inspect") stdout = "running";
    else if (args.includes("--command")) {
      const sql = args.at(-1);
      if (sql.includes("chat_message_id")) stdout = previousKeepsChat ? "1" : "0";
      else if (sql.includes("INSERT INTO")) stdout = "7";
      else if (sql.includes("SELECT kind ||")) {
        stdout = "account|1\\nidentity|1\\nuser|1\\nworkspace|1\\nmembership|1\\nconnection|1";
      }
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
					...args,
				],
				{
					encoding: "utf8",
					timeout: 15_000,
					env: {
						...process.env,
						PREVIOUS_VERSION: "0.94.7",
						PREVIOUS_AGENT_IMAGE: "agent-image",
					},
				},
			);
			assert.equal(result.status, 1, result.stdout + result.stderr);
			assert.ok(result.stderr.includes(expectedError), result.stderr);
			const executed = existsSync(fixtures) ? readFileSync(fixtures, "utf8") : "";
			assert.equal(executed.includes("INSERT INTO mentor_slack_thread"), seeded, executed);
			const invocations = existsSync(commands) ? readFileSync(commands, "utf8") : "";
			// A refused requirement stops the run before the candidate starts.
			assert.equal(
				invocations.includes('"target-image"'),
				expectedError === "candidate start stubbed",
				invocations,
			);
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
