import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { chmod, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";

import { asRecord, asStringArray } from "./lib/json.ts";
import { loadTasks } from "./lib/task-graph.ts";

import { configuration, k6Image, renderBaseline } from "./load-test.ts";

const env = {
	BASE_URL: "https://load.example.test",
	WEBHOOK_SECRET: "do-not-record",
	AUTH_TOKEN: "also-secret",
	SANDBOX_API_MAX_REQUEST_BYTES: "1048576",
	SANDBOX_API_REQUESTS_PER_MINUTE: "120",
};
const mentorEnv = { ...env, WORKSPACE_SLUG: "capacity-test", ARTIFACT_IDS: "1,2" };
const template = await readFile(
	new URL("../load-tests/baseline-template.md", import.meta.url),
	"utf8",
);
const options = {
	scenarios: { webhook_burst: { executor: "constant-arrival-rate", rate: 100, duration: "2m0s" } },
	thresholds: { http_req_duration: ["p(95)<250"], http_req_failed: ["rate<0.01"] },
};
const metadata = {
	...configuration("webhook-burst", env),
	startedAt: "2026-09-05T00:00:00Z",
	exitCode: 0,
	options,
};
const mentorMetadata = { ...metadata, ...configuration("detection-mentor", mentorEnv) };
const summary = {
	metrics: {
		http_req_duration: {
			type: "trend",
			contains: "time",
			values: { med: 10, "p(95)": 20, "p(99)": 30 },
			thresholds: { "p(95)<250": { ok: true } },
		},
		http_req_failed: {
			type: "rate",
			contains: "default",
			values: { rate: 0, passes: 0, fails: 100 },
			thresholds: { "rate<0.01": { ok: true } },
		},
	},
};

await test("runner records the inputs a scenario ran under and never records secrets", () => {
	assert.ok(!JSON.stringify(mentorMetadata).includes("do-not-record"));
	assert.ok(!JSON.stringify(mentorMetadata).includes("also-secret"));
	assert.deepEqual(Object.keys(configuration("webhook-burst", env).inputs), ["BASE_URL"]);
	assert.deepEqual(Object.keys(configuration("detection-mentor", mentorEnv).inputs).toSorted(), [
		"ARTIFACT_IDS",
		"BASE_URL",
		"SANDBOX_API_MAX_REQUEST_BYTES",
		"SANDBOX_API_REQUESTS_PER_MINUTE",
		"WORKSPACE_SLUG",
	]);
	for (const key of ["BASE_URL", "WEBHOOK_SECRET"]) {
		assert.throws(
			() => configuration("webhook-burst", { ...env, [key]: "" }),
			new RegExp(key, "u"),
		);
	}
	for (const key of ["SANDBOX_API_MAX_REQUEST_BYTES", "SANDBOX_API_REQUESTS_PER_MINUTE"]) {
		assert.throws(
			() => configuration("detection-mentor", { ...mentorEnv, [key]: "" }),
			new RegExp(key, "u"),
		);
	}
	for (const BASE_URL of ["file:///tmp", "https://user:secret@host", "https://host?token=secret"]) {
		assert.throws(() => configuration("webhook-burst", { ...env, BASE_URL }));
	}
	assert.throws(() => configuration("unknown", env));
	assert.throws(() => configuration("detection-mentor", env), /WORKSPACE_SLUG/u);
	assert.throws(() =>
		configuration("detection-mentor", { ...mentorEnv, SANDBOX_API_REQUESTS_PER_MINUTE: "0" }),
	);
});

await test("renders k6 threshold verdicts without claiming host qualification", () => {
	const report = renderBaseline(summary, metadata, template);
	assert.match(report, /Automated result: PASS/u);
	assert.match(report, /qualification: PENDING/u);
	assert.match(report, /p\(99\) \| 30/u);
	assert.ok(!report.includes("{{"));
	assert.ok(!report.includes("SANDBOX_API"));
	assert.match(
		renderBaseline(summary, mentorMetadata, template),
		/SANDBOX_API_REQUESTS_PER_MINUTE \| 120/u,
	);
	assert.match(
		renderBaseline(summary, { ...metadata, exitCode: 99 }, template),
		/Automated result: FAIL/u,
	);
	assert.match(
		renderBaseline(
			{
				metrics: {
					...summary.metrics,
					http_req_failed: {
						...summary.metrics.http_req_failed,
						thresholds: { "rate<0.01": { ok: false } },
					},
				},
			},
			metadata,
			template,
		),
		/Automated result: FAIL/u,
	);
});

await test("renders a run recorded under another digest-pinned k6 image", () => {
	const image = `grafana/k6:1.0.0@sha256:${"a".repeat(64)}`;
	const report = renderBaseline(summary, { ...metadata, image }, template);
	assert.match(report, new RegExp(`k6 image: ${image.replaceAll(".", String.raw`\.`)}`, "u"));
	assert.match(report, /this checkout pins grafana\/k6:/u);
	assert.ok(!renderBaseline(summary, metadata, template).includes("this checkout pins"));
	for (const unpinned of [
		"grafana/k6:1.2.3",
		`ghcr.io/grafana/k6:1.2.3@sha256:${"a".repeat(64)}`,
	]) {
		assert.throws(
			() => renderBaseline(summary, { ...metadata, image: unpinned }, template),
			/digest-pinned/u,
		);
	}
});

await test("rejects incomplete threshold evidence, malformed values and invalid metadata", () => {
	const failed = summary.metrics.http_req_failed;
	assert.throws(
		() =>
			renderBaseline(
				{ metrics: { http_req_failed: summary.metrics.http_req_failed } },
				metadata,
				template,
			),
		/http_req_duration/u,
	);
	for (const [thresholds, message] of [
		[{}, /threshold evidence/u],
		[{ "rate<0.01": { ok: "true" } }, /threshold verdict/u],
		[{ "rate<0.01": false }, /must be a JSON object/u],
	] as const) {
		assert.throws(
			() =>
				renderBaseline(
					{ metrics: { ...summary.metrics, http_req_failed: { ...failed, thresholds } } },
					metadata,
					template,
				),
			message,
		);
	}
	assert.throws(
		() =>
			renderBaseline(
				{
					metrics: { ...summary.metrics, http_req_failed: { ...failed, values: { rate: "bad" } } },
				},
				metadata,
				template,
			),
		/Invalid metric/u,
	);
	for (const change of [
		{ scenario: "unknown" },
		{ image: "unpinned" },
		{ startedAt: "invalid" },
		{ exitCode: "0" },
		{ options: {} },
		{ inputs: { ...metadata.inputs, AUTH_TOKEN: "must-not-render" } },
		{ inputs: { ...metadata.inputs, SANDBOX_API_REQUESTS_PER_MINUTE: "120" } },
	]) {
		assert.throws(() => renderBaseline(summary, { ...metadata, ...change }, template));
	}
	for (const change of [
		{ inputs: { ...mentorMetadata.inputs, SANDBOX_API_REQUESTS_PER_MINUTE: "9007199254740992" } },
		{ inputs: { BASE_URL: env.BASE_URL } },
	]) {
		assert.throws(
			() => renderBaseline(summary, { ...mentorMetadata, ...change }, template),
			/gateway limit/u,
		);
	}
	assert.match(
		renderBaseline(summary, { ...metadata, exitCode: null }, template),
		/Automated result: FAIL/u,
	);
});

await test(
	"CLI preserves failure evidence and refuses stale output",
	{ skip: process.platform === "win32" },
	async () => {
		const root = await mkdtemp(path.resolve(tmpdir(), "load-test-"));
		try {
			const docker = path.resolve(root, "docker");
			await writeFile(
				docker,
				`#!/usr/bin/env node
import { writeFileSync } from 'node:fs';
writeFileSync(process.env.ARGS_FILE, JSON.stringify(process.argv.slice(2)));
if (process.argv.includes('inspect')) {
 process.stdout.write(${JSON.stringify(JSON.stringify(options))});
} else {
 process.exit(99);
}
`,
			);
			await chmod(docker, 0o755);
			const output = path.resolve(root, "results with spaces");
			const argsFile = path.resolve(root, "args.json");
			const invoke = (extra: NodeJS.ProcessEnv = {}) =>
				spawnSync(process.execPath, ["scripts/load-test.ts", "webhook-burst"], {
					encoding: "utf8",
					maxBuffer: 2 * 1024 * 1024,
					env: {
						...process.env,
						...env,
						PATH: `${root}${path.delimiter}${process.env.PATH}`,
						ARGS_FILE: argsFile,
						LOAD_RESULTS_DIR: output,
						LOAD_TEST_ACKNOWLEDGE: "isolated-host",
						...extra,
					},
				});
			assert.equal(invoke({ LOAD_TEST_ACKNOWLEDGE: "" }).status, 1);
			assert.equal(invoke().status, 99);
			const recorded = await readFile(path.resolve(output, "run.json"), "utf8");
			assert.match(recorded, /"exitCode": 99/u);
			assert.ok(!recorded.includes(env.WEBHOOK_SECRET));
			const args = await readFile(argsFile, "utf8");
			assert.ok(args.includes(k6Image));
			assert.ok(args.includes("--summary-trend-stats=avg,min,med,max,p(95),p(99)"));
			assert.ok(args.includes("/tests/webhook-burst.js"));
			assert.ok(!args.includes(env.WEBHOOK_SECRET));
			assert.ok(args.includes(`${output}/scripts:/tests:ro`));
			for (const file of ["webhook-burst.js", "lib/summary.js"]) {
				assert.equal(
					await readFile(path.resolve(output, "scripts", file), "utf8"),
					await readFile(`load-tests/${file}`, "utf8"),
				);
			}
			assert.equal(await readFile(path.resolve(output, "baseline-template.md"), "utf8"), template);
			assert.equal(invoke().status, 1);
			assert.equal(await readFile(path.resolve(output, "run.json"), "utf8"), recorded);
			await writeFile(path.resolve(output, "summary.json"), JSON.stringify(summary));
			const report = spawnSync(process.execPath, ["scripts/load-test.ts", "report", output], {
				encoding: "utf8",
				maxBuffer: 2 * 1024 * 1024,
			});
			assert.equal(report.status, 0, report.stderr);
			assert.match(
				await readFile(path.resolve(output, "baseline.md"), "utf8"),
				/Automated result: FAIL/u,
			);
		} finally {
			await rm(root, { recursive: true, force: true });
		}
	},
);

await test("load tasks stay uncached and outside automatic quality and verification graphs", async () => {
	const tasks = await loadTasks();
	for (const name of [
		"test:load:webhook-burst",
		"test:load:detection-mentor",
		"report:load:baseline",
	]) {
		assert.equal(asRecord(tasks[name], name).cache, false);
		for (const task of Object.values(tasks)) {
			const dependencies = asRecord(task, "task").dependsOn;
			if (dependencies !== undefined) {
				assert.ok(!asStringArray(dependencies, "dependsOn").includes(name));
			}
		}
	}
	const gate = asRecord(tasks["gate:load-syntax"], "gate:load-syntax");
	assert.deepEqual(asStringArray(gate.dependsOn, "dependsOn"), ["test:load:syntax"]);
});
