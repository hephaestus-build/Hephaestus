import assert from "node:assert/strict";
import { createHmac } from "node:crypto";
import { describe, test } from "node:test";

import {
	awaitImages,
	checkImages,
	deploymentLogUrl,
	IMAGE_PENDING,
	assertWebhookAccepted,
	buildWebhookPayload,
	formatOutputs,
	queuePreview,
	selectExactDeployment,
	validateDeploymentProvenance,
	waitForDeployment,
	type DeploymentConfig,
	type CommandRunner,
	type Dependencies,
	type PullRequestConfig,
	type QueueConfig,
} from "./coolify-preview.ts";

const SHA = "a".repeat(40);
const OTHER_SHA = "b".repeat(40);
const pullRequest: PullRequestConfig = {
	appUuid: "app",
	authorAssociation: "COLLABORATOR",
	baseRef: "main",
	coolifyUrl: new URL("https://coolify.example"),
	deliveryId: "preview-42-1",
	headRef: "feature/preview",
	headSha: SHA,
	prNumber: 7,
	prTitle: "Preview test",
	prUrl: new URL("https://github.com/owner/repo/pull/7"),
	repository: "owner/repo",
	webhookSecret: "webhook-secret",
};

const queueConfig: QueueConfig = { ...pullRequest, readToken: "read-token" };
const deploymentConfig: DeploymentConfig = {
	appUuid: "app",
	coolifyUrl: new URL("https://coolify.example"),
	deploymentUuid: "deployment-7",
	expectedSha: SHA,
	previewUrl: new URL("https://pr7.example"),
	prNumber: 7,
	readToken: "read-token",
};

const noSleep: Dependencies["sleep"] = async () => {
	/* nothing waits in a test */
};

function dependencies(fetchImplementation: Dependencies["fetch"]): Dependencies {
	return { fetch: fetchImplementation, now: Date.now, sleep: noSleep };
}

/** The URL a `fetch` was given, whichever of its three input shapes carried it. */
function requestUrl(input: Parameters<Dependencies["fetch"]>[0]): string {
	if (input instanceof Request) {
		return input.url;
	}
	if (input instanceof URL) {
		return input.href;
	}
	return input;
}

function isRecord(value: unknown): value is Record<string, unknown> {
	return typeof value === "object" && value !== null && !Array.isArray(value);
}

void describe("Coolify preview webhook", () => {
	void test("pins every commit field to the approved head so Coolify cannot resolve HEAD", () => {
		const payload = buildWebhookPayload(pullRequest, "opened");
		assert.deepEqual(
			[payload.before, payload.after, payload.pull_request.head.sha],
			[SHA, SHA, SHA],
		);
		// Coolify reads both full names to decide whether this is a fork; equal means same-repository.
		assert.equal(
			payload.pull_request.head.repo.full_name,
			payload.pull_request.base.repo.full_name,
		);
	});

	void test("accepts exactly one queued application and preserves rejection messages", () => {
		assert.doesNotThrow(() => assertWebhookAccepted([{ status: "queued" }]));
		assert.throws(
			() =>
				assertWebhookAccepted([{ message: "Unauthorized to deploy.\nDetails", status: "failed" }]),
			/Unauthorized to deploy\. Details/u,
		);
		assert.throws(
			() => assertWebhookAccepted([{ status: "queued" }, { status: "queued" }]),
			/No application/u,
		);
		assert.throws(
			() => assertWebhookAccepted([{ status: "queued" }, { message: "drift", status: "failed" }]),
			/drift/u,
		);
	});

	void test("signs the snapshot and resolves only the exact PR commit", async () => {
		let calls = 0;
		const fakeFetch: Dependencies["fetch"] = async (input, init) => {
			calls += 1;
			const url = requestUrl(input);
			if (url.includes("/webhooks/")) {
				if (typeof init?.body !== "string") {
					throw new TypeError("expected a string webhook body");
				}
				const { body } = init;
				const headers = new Headers(init.headers);
				const expected = createHmac("sha256", pullRequest.webhookSecret).update(body).digest("hex");
				assert.equal(headers.get("X-Hub-Signature-256"), `sha256=${expected}`);
				const parsed: unknown = JSON.parse(body);
				assert.ok(
					isRecord(parsed) && isRecord(parsed.pull_request) && isRecord(parsed.pull_request.head),
				);
				assert.equal(parsed.pull_request.head.sha, SHA);
				return Response.json([{ status: "queued" }]);
			}
			if (calls === 1) {
				return Response.json({ count: 0, deployments: [] });
			}
			return Response.json({
				count: 2,
				deployments: [
					{
						commit: OTHER_SHA,
						created_at: "2026-08-28T12:01:00Z",
						deployment_uuid: "wrong",
						pull_request_id: 7,
						status: "queued",
					},
					{
						commit: SHA,
						created_at: "2026-08-28T12:00:00Z",
						deployment_uuid: "exact",
						pull_request_id: 7,
						status: "queued",
					},
				],
			});
		};

		assert.equal(await queuePreview(queueConfig, dependencies(fakeFetch)), "exact");
		assert.equal(calls, 3);
	});

	void test("does not bind a same-SHA redeploy to an older finished record", async () => {
		let inventoryReads = 0;
		const oldRecord = {
			commit: SHA,
			created_at: "2026-08-28T12:00:00Z",
			deployment_uuid: "old-finished",
			pull_request_id: 7,
			status: "finished",
		};
		const fakeFetch: Dependencies["fetch"] = async (input) => {
			const url = requestUrl(input);
			if (url.includes("/webhooks/")) {
				return Response.json([{ status: "queued" }]);
			}
			inventoryReads += 1;
			return Response.json({
				count: inventoryReads === 1 ? 1 : 2,
				deployments:
					inventoryReads === 1
						? [oldRecord]
						: [
								oldRecord,
								{
									...oldRecord,
									created_at: "2026-08-28T12:01:00Z",
									deployment_uuid: "new-deployment",
									status: "queued",
								},
							],
			});
		};

		assert.equal(await queuePreview(queueConfig, dependencies(fakeFetch)), "new-deployment");
	});

	void test("adopts an exact active deployment after a lost webhook response", async () => {
		let calls = 0;
		const fakeFetch: Dependencies["fetch"] = async () => {
			calls += 1;
			return Response.json({
				count: 1,
				deployments: [
					{
						commit: SHA,
						created_at: "2026-08-28T12:00:00Z",
						deployment_uuid: "already-queued",
						pull_request_id: 7,
						status: "in_progress",
					},
				],
			});
		};

		assert.equal(await queuePreview(queueConfig, dependencies(fakeFetch)), "already-queued");
		assert.equal(calls, 1);
	});
});

void describe("Coolify deployment provenance", () => {
	void test("selects the newest exact deployment from a validated inventory", () => {
		const selected = selectExactDeployment(
			{
				count: 2,
				deployments: [
					{
						commit: SHA,
						created_at: "2026-08-28T12:00:00Z",
						deployment_uuid: "older",
						pull_request_id: 7,
						status: "finished",
					},
					{
						commit: SHA,
						created_at: "2026-08-28T12:01:00Z",
						deployment_uuid: "newer",
						pull_request_id: 7,
						status: "in_progress",
					},
				],
			},
			7,
			SHA,
		);
		assert.equal(selected?.deploymentUuid, "newer");
		assert.throws(
			() => selectExactDeployment({ count: 1, deployments: [{}] }, 7, SHA),
			/malformed/u,
		);
		assert.equal(
			selectExactDeployment(
				{
					count: 1,
					deployments: [
						{
							commit: SHA,
							created_at: "2026-08-28T12:02:00Z",
							deployment_uuid: "failed-fast",
							pull_request_id: 7,
							status: "failed",
						},
					],
				},
				7,
				SHA,
			)?.deploymentUuid,
			"failed-fast",
		);
	});

	void test("rejects a different SHA, PR, or malformed queue record", () => {
		assert.throws(
			() =>
				validateDeploymentProvenance(
					{
						commit: SHA,
						created_at: "now",
						deployment_uuid: "deployment-7",
						pull_request_id: 8,
						status: "finished",
					},
					7,
					SHA,
					"deployment-7",
				),
			/provenance/u,
		);
		assert.throws(
			() =>
				validateDeploymentProvenance(
					{
						commit: OTHER_SHA,
						created_at: "now",
						deployment_uuid: "deployment-7",
						pull_request_id: 7,
						status: "finished",
					},
					7,
					SHA,
					"deployment-7",
				),
			/provenance/u,
		);
		assert.throws(() => validateDeploymentProvenance({}, 7, SHA, "deployment-7"), /malformed/u);
		assert.throws(
			() =>
				validateDeploymentProvenance(
					{
						commit: SHA,
						created_at: "now",
						deployment_uuid: "deployment-7\nstate=success",
						pull_request_id: 7,
						status: "finished",
					},
					7,
					SHA,
					"deployment-7",
				),
			/malformed/u,
		);
		assert.throws(
			() =>
				validateDeploymentProvenance(
					{
						commit: SHA,
						created_at: "now",
						deployment_uuid: "other-deployment",
						pull_request_id: 7,
						status: "finished",
					},
					7,
					SHA,
					"deployment-7",
				),
			/provenance/u,
		);
	});

	void test("marks success only after exact provenance and an HTTP 2xx health response", async () => {
		let calls = 0;
		const fakeFetch: Dependencies["fetch"] = async () => {
			calls += 1;
			if (calls === 1) {
				return Response.json({
					commit: SHA,
					created_at: "now",
					deployment_url: "/project/app/deployment/deployment-7",
					deployment_uuid: "deployment-7",
					pull_request_id: 7,
					status: "finished",
				});
			}
			return new Response("ok", { status: 200 });
		};
		const result = await waitForDeployment(deploymentConfig, dependencies(fakeFetch));

		assert.equal(result.state, "success");
		assert.equal(result.logUrl, "https://coolify.example/project/app/deployment/deployment-7");
	});

	void test("never probes health after a provenance mismatch", async () => {
		let calls = 0;
		const fakeFetch: Dependencies["fetch"] = async () => {
			calls += 1;
			return Response.json({
				commit: OTHER_SHA,
				created_at: "now",
				deployment_uuid: "deployment-7",
				pull_request_id: 7,
				status: "finished",
			});
		};
		const result = await waitForDeployment(deploymentConfig, dependencies(fakeFetch));

		assert.equal(result.state, "failure");
		assert.match(result.description, /provenance/u);
		assert.equal(calls, 1);
	});

	void test("retries transient status and health failures without accepting redirects", async () => {
		let calls = 0;
		let now = 0;
		const fakeFetch: Dependencies["fetch"] = async (_input, init) => {
			calls += 1;
			if (calls === 1 || calls === 3) {
				throw new Error("temporary network failure");
			}
			if (calls === 2) {
				return Response.json({
					commit: SHA,
					created_at: "now",
					deployment_uuid: "deployment-7",
					pull_request_id: 7,
					status: "in_progress",
				});
			}
			if (calls === 4) {
				return Response.json({
					commit: SHA,
					created_at: "now",
					deployment_uuid: "deployment-7",
					pull_request_id: 7,
					status: "finished",
				});
			}
			if (calls >= 5) {
				assert.equal(init?.redirect, "manual");
			}
			if (calls === 5) {
				return new Response(null, { status: 302 });
			}
			return new Response("ok", { status: 200 });
		};
		const result = await waitForDeployment(deploymentConfig, {
			fetch: fakeFetch,
			now: () => now,
			sleep: async (milliseconds) => {
				now += milliseconds;
			},
		});

		assert.equal(result.state, "success");
		assert.equal(calls, 6);
	});
});

void test("step outputs cannot forge extra outputs from a server-supplied string", () => {
	const forged = formatOutputs({
		description: "Coolify failed\nstate=success",
		state: "failure",
	});

	assert.equal(forged, "description=Coolify failed state=success\nstate=failure\n");
});

const imageEnvironment = {
	GHCR_TOKEN: "registry-token",
	GITHUB_ACTOR: "actor",
	GITHUB_REPOSITORY: "owner/repo",
	HEAD_SHA: SHA,
};

void describe("published image provenance", () => {
	void test("accepts each commit-addressed tag that our build workflow attested", () => {
		const calls: string[][] = [];
		const runner: CommandRunner = {
			run: (command, arguments_, options) => {
				calls.push([command, ...arguments_]);
				if (arguments_[0] === "login") {
					assert.equal(options?.input, "registry-token");
					return { status: 0, stderr: "", stdout: "" };
				}
				if (command === "docker") {
					assert.ok(arguments_.some((argument) => argument.endsWith(`:${SHA}`)));
					return { status: 0, stderr: "", stdout: `sha256:${"c".repeat(64)}\n` };
				}
				assert.ok(arguments_.includes("owner/repo/.github/workflows/reusable-docker-build.yml"));
				return { status: 0, stderr: "", stdout: "" };
			},
		};

		checkImages(imageEnvironment, runner, () => undefined);

		// All three services run published artifacts, so all three must be attested.
		assert.equal(calls.filter(([command]) => command === "gh").length, 3);
	});

	void test("reports an unpublished tag as pending, so the caller can wait for CI", () => {
		const runner: CommandRunner = {
			run: (command, arguments_) => {
				if (arguments_[0] === "login") {
					return { status: 0, stderr: "", stdout: "" };
				}
				if (command === "docker") {
					return { status: 1, stderr: "manifest unknown", stdout: "" };
				}
				throw new Error("attestation must not run for a missing image");
			},
		};

		assert.throws(
			() => checkImages(imageEnvironment, runner, () => undefined),
			new RegExp(IMAGE_PENDING, "u"),
		);
	});

	void test("rejects a digest no trusted workflow attested", () => {
		const runner: CommandRunner = {
			run: (command, arguments_) => {
				if (arguments_[0] === "login") {
					return { status: 0, stderr: "", stdout: "" };
				}
				if (command === "docker") {
					return { status: 0, stderr: "", stdout: `sha256:${"c".repeat(64)}\n` };
				}
				return { status: 1, stderr: "no attestations found", stdout: "" };
			},
		};

		assert.throws(() => checkImages(imageEnvironment, runner, () => undefined), /provenance/u);
	});
});

const runnerFor = (results: readonly ("pending" | "unsigned" | "ok")[]): CommandRunner => {
	let call = 0;
	return {
		run: (command, arguments_) => {
			if (arguments_[0] === "login") {
				return { status: 0, stderr: "", stdout: "" };
			}
			if (command === "docker") {
				const outcome = results[Math.min(call, results.length - 1)];
				if (outcome === "pending") {
					return { status: 1, stderr: "manifest unknown", stdout: "" };
				}
				return { status: 0, stderr: "", stdout: `sha256:${"c".repeat(64)}\n` };
			}
			const outcome = results[Math.min(call, results.length - 1)];
			call += 1;
			return outcome === "unsigned"
				? { status: 1, stderr: "no attestations", stdout: "" }
				: { status: 0, stderr: "", stdout: "" };
		},
	};
};

void describe("waiting for CI to publish", () => {
	const noWait = dependencies(async () => {
		throw new Error("unused");
	});

	void test("returns once the images appear", async () => {
		await awaitImages(imageEnvironment, runnerFor(["ok"]), () => undefined, noWait);
	});

	void test("does not wait out the poll on an image that will never be trusted", async () => {
		// An unsigned digest is a verdict, not a delay: retrying it would burn the whole budget and
		// then report the wrong reason.
		let sleeps = 0;
		await assert.rejects(
			async () =>
				awaitImages(imageEnvironment, runnerFor(["unsigned"]), () => undefined, {
					...noWait,
					sleep: async () => {
						sleeps += 1;
					},
				}),
			/provenance/u,
		);
		assert.equal(sleeps, 0);
	});

	void test("gives up with the pending image as the cause", async () => {
		await assert.rejects(
			async () => awaitImages(imageEnvironment, runnerFor(["pending"]), () => undefined, noWait),
			(error: unknown) =>
				error instanceof Error &&
				error.message.includes("never published") &&
				error.cause instanceof Error &&
				error.cause.message.startsWith(IMAGE_PENDING),
		);
	});
});

void describe("deployment log links", () => {
	const config: Pick<DeploymentConfig, "coolifyUrl"> = {
		coolifyUrl: new URL("https://coolify.example/"),
	};

	void test("keeps only links that cannot navigate somewhere hostile", () => {
		assert.equal(
			deploymentLogUrl(config, "/project/1/logs"),
			"https://coolify.example/project/1/logs",
		);
		assert.equal(
			deploymentLogUrl(config, "https://coolify.example/x"),
			"https://coolify.example/x",
		);
		for (const hostile of [
			["javascript", "alert(1)"].join(":"),
			"http://coolify.example/x",
			"not a url",
			"  ",
			undefined,
		]) {
			assert.equal(deploymentLogUrl(config, hostile), "https://coolify.example/", String(hostile));
		}
	});
});
