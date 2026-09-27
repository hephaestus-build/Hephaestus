import assert from "node:assert/strict";
import { test } from "node:test";

import { ciVerdict, releaseProblems } from "./lib/mobile-release.ts";

const CERTIFICATE = "-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----\n";
const KEY = "-----BEGIN PRIVATE KEY-----\nMIIE\n-----END PRIVATE KEY-----\n";
const ACCOUNT = { EXPO_TOKEN: "token", EAS_PROJECT_ID: "project", EAS_OWNER: "owner" };
const STAGING = { PREVIEW_INSTANCE: "staging.example.org" };
const certificateAt = (path: string) =>
	path === "mobile/certs/certificate.pem" ? CERTIFICATE : undefined;

void test("a preview build needs the Expo account and project, and its staging Hephaestus", () => {
	assert.deepEqual(
		releaseProblems({
			env: { ...ACCOUNT, PREVIEW_INSTANCE: "staging.example.org" },
			profile: "preview",
			mode: "build",
			readText: () => undefined,
		}),
		[],
	);
	assert.match(
		releaseProblems({
			env: ACCOUNT,
			profile: "preview",
			mode: "build",
			readText: () => undefined,
		}).join("\n"),
		/PREVIEW_INSTANCE is not set/u,
	);
});

void test("names every missing account prerequisite at once", () => {
	const problems = releaseProblems({
		env: {},
		profile: "preview",
		mode: "build",
		readText: () => undefined,
	});

	assert.deepEqual(
		problems.map((problem) => problem.split(" ")[0]),
		["EXPO_TOKEN", "EAS_PROJECT_ID", "EAS_OWNER", "PREVIEW_INSTANCE"],
	);
});

void test("a production binary must carry the certificate it will verify updates with", () => {
	const without = releaseProblems({
		env: ACCOUNT,
		profile: "production",
		mode: "build",
		readText: certificateAt,
	});
	const missingFile = releaseProblems({
		env: { ...ACCOUNT, EXPO_UPDATES_CODE_SIGNING_CERTIFICATE: "certs/elsewhere.pem" },
		profile: "production",
		mode: "build",
		readText: certificateAt,
	});
	const present = releaseProblems({
		env: { ...ACCOUNT, EXPO_UPDATES_CODE_SIGNING_CERTIFICATE: "certs/certificate.pem" },
		profile: "production",
		mode: "build",
		readText: certificateAt,
	});

	assert.match(without.join("\n"), /EXPO_UPDATES_CODE_SIGNING_CERTIFICATE is not set/u);
	assert.match(missingFile.join("\n"), /is not a PEM certificate/u);
	assert.deepEqual(present, []);
});

void test("an update is signed, on every channel", () => {
	const env = {
		...ACCOUNT,
		...STAGING,
		EXPO_UPDATES_CODE_SIGNING_CERTIFICATE: "certs/certificate.pem",
	};

	assert.match(
		releaseProblems({ env, profile: "preview", mode: "update", readText: certificateAt }).join(
			"\n",
		),
		/EXPO_UPDATES_CODE_SIGNING_KEY is not set/u,
	);
	assert.match(
		releaseProblems({
			env: { ...env, EXPO_UPDATES_CODE_SIGNING_KEY: "not a key" },
			profile: "preview",
			mode: "update",
			readText: certificateAt,
		}).join("\n"),
		/not a PEM private key/u,
	);
	assert.deepEqual(
		releaseProblems({
			env: { ...env, EXPO_UPDATES_CODE_SIGNING_KEY: KEY },
			profile: "preview",
			mode: "update",
			readText: certificateAt,
		}),
		[],
	);
});

const SHA = "a".repeat(40);
const run = (
	conclusion: string | null,
	createdAt: string,
	status = "completed",
	headSha = SHA,
) => ({
	head_sha: headSha,
	status,
	conclusion,
	created_at: createdAt,
});

void test("releases a commit whose newest CI run passed", () => {
	const verdict = ciVerdict(
		{
			workflow_runs: [
				run("failure", "2026-09-26T10:00:00Z"),
				run("success", "2026-09-26T11:00:00Z"),
			],
		},
		SHA,
	);

	assert.deepEqual(verdict, { passed: true });
});

void test("refuses a commit whose newest CI run failed, even after an earlier pass", () => {
	const verdict = ciVerdict(
		{
			workflow_runs: [
				run("success", "2026-09-26T10:00:00Z"),
				run("failure", "2026-09-26T11:00:00Z"),
			],
		},
		SHA,
	);

	assert.equal(verdict.passed, false);
});

void test("refuses while CI is still running, and a commit CI never saw", () => {
	assert.equal(
		ciVerdict({ workflow_runs: [run(null, "2026-09-26T11:00:00Z", "in_progress")] }, SHA).passed,
		false,
	);
	assert.equal(
		ciVerdict(
			{ workflow_runs: [run("success", "2026-09-26T11:00:00Z", "completed", "b".repeat(40))] },
			SHA,
		).passed,
		false,
	);
});
