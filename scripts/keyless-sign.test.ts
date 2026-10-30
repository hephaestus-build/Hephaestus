import assert from "node:assert/strict";
import { mkdtemp, readFile, rm, symlink, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";

import { output } from "./lib/process.ts";

import { keylessSign } from "./keyless-sign.ts";

const unavailable =
	'Post "https://rekor.sigstore.dev/api/v1/log/entries": giving up after 2 attempt(s): status 502: <html>';
const conflict =
	'[POST /api/v1/log/entries][409] createLogEntryConflict {"message":"an equivalent entry already exists in the transparency log with UUID abc"}';
// Run 40355151916: GitHub's token service failed while `cosign` requested a keyless identity.
const token =
	'getting keypair and token: retrieving ID token: reading ID token: fetching ambient OIDC credentials: identity token request to provider github-actions failed with status 503 Service Unavailable: "upstream connect error"';
const failure = (stderr: string) => Object.assign(new Error("cosign exited nonzero"), { stderr });

async function fixture(t: test.TestContext) {
	const directory = await mkdtemp(path.join(tmpdir(), "keyless-sign-"));
	t.after(async () => rm(directory, { recursive: true, force: true }));
	const input = path.join(directory, "predicate.json");
	const bundle = path.join(directory, "bundle.json");
	await writeFile(input, '{"subject":"unchanged"}');
	return { input, bundle, args: ["sign-blob", "--yes", "--bundle", bundle, input] };
}

void test("first success has no backoff and leaves the bundle for native verification", async (t) => {
	const { args, bundle } = await fixture(t);
	let calls = 0;
	await keylessSign(args, {
		execute: async (actual, signal) => {
			calls += 1;
			assert.deepEqual(actual, args);
			assert.equal(signal.aborted, false);
			await writeFile(bundle, '{"verificationMaterial":{}}');
		},
		sleep: async () => assert.fail("no retry on success"),
	});
	assert.equal(calls, 1);
	assert.equal(await readFile(bundle, "utf8"), '{"verificationMaterial":{}}');
});

const transients = { "Rekor 502": unavailable, "equivalent 409": conflict, "token 503": token };
for (const [name, transient] of Object.entries(transients)) {
	void test(`fresh invocation after ${name} preserves inputs and discards partial output`, async (t) => {
		const { args, input, bundle } = await fixture(t);
		let calls = 0;
		const delays: number[] = [];
		await keylessSign(args, {
			execute: async (actual) => {
				assert.deepEqual(actual, args);
				assert.equal(await readFile(input, "utf8"), '{"subject":"unchanged"}');
				calls += 1;
				if (calls === 1) {
					await writeFile(bundle, "partial");
					throw failure(transient);
				}
				await assert.rejects(readFile(bundle), { code: "ENOENT" });
				await writeFile(bundle, "{}");
			},
			sleep: async (delay) => {
				delays.push(delay);
			},
			random: () => 0.5,
		});
		assert.equal(calls, 2);
		assert.deepEqual(delays, [32_500]);
	});
}

void test("409 uncertainty alone never succeeds and stops at three attempts", async (t) => {
	const { args, bundle } = await fixture(t);
	let calls = 0;
	let waits = 0;
	await assert.rejects(
		keylessSign(args, {
			execute: async () => {
				calls += 1;
				await writeFile(bundle, "partial");
				throw failure(conflict);
			},
			sleep: async () => {
				waits += 1;
			},
		}),
		/cosign exited nonzero/u,
	);
	assert.equal(calls, 3);
	assert.equal(waits, 2);
	await assert.rejects(readFile(bundle), { code: "ENOENT" });
});

for (const permanent of [
	"status 403: forbidden",
	"status 401: authentication required",
	"invalid predicate",
	"policy refused",
	"registry status 502",
	"[POST /api/v1/log/entries][409] unrelated conflict",
]) {
	void test(`does not retry ${permanent}`, async (t) => {
		const { args } = await fixture(t);
		let calls = 0;
		await assert.rejects(
			keylessSign(args, {
				execute: async () => {
					calls += 1;
					throw failure(permanent);
				},
				sleep: async () => assert.fail("permanent failure must not wait"),
			}),
		);
		assert.equal(calls, 1);
	});
}

void test("shared time budget refuses another invocation", async (t) => {
	const { args } = await fixture(t);
	let clock = 0;
	let calls = 0;
	await assert.rejects(
		keylessSign(args, {
			now: () => clock,
			execute: async () => {
				calls += 1;
				clock = 350_000;
				throw failure(unavailable);
			},
			sleep: async () => assert.fail("no wait beyond deadline"),
		}),
		/time budget exhausted/u,
	);
	assert.equal(calls, 1);
});

void test("refuses changed input instead of signing new bytes on retry", async (t) => {
	const { args, input } = await fixture(t);
	let calls = 0;
	await assert.rejects(
		keylessSign(args, {
			execute: async () => {
				calls += 1;
				await writeFile(input, "changed");
				throw failure(unavailable);
			},
			sleep: async (milliseconds) => {
				assert.ok(milliseconds >= 30_000);
			},
		}),
		/Signing input changed/u,
	);
	assert.equal(calls, 1);
});

void test("refuses preexisting bundles and invalid successful output", async (t) => {
	const { args, bundle } = await fixture(t);
	await writeFile(bundle, "original");
	await assert.rejects(keylessSign(args), /already exists/u);
	assert.equal(await readFile(bundle, "utf8"), "original");
	await rm(bundle);
	await assert.rejects(
		keylessSign(args, {
			execute: async () => {
				await writeFile(bundle, "null");
			},
		}),
		/JSON bundle/u,
	);
	await assert.rejects(readFile(bundle), { code: "ENOENT" });
});

void test("supports only the release keyless immutable attestation command", async (t) => {
	const { input } = await fixture(t);
	const args = [
		"attest",
		"--yes",
		"--type",
		"spdxjson",
		"--predicate",
		input,
		`ghcr.io/owned/image@sha256:${"a".repeat(64)}`,
	];
	await keylessSign(args, {
		execute: async (actual) => {
			assert.deepEqual(actual, args);
		},
	});
	await assert.rejects(keylessSign([...args, "--key", "other"]), /Expected a release keyless/u);
});

void test("signs an OCI subject only by digest, with no bundle and no key", async () => {
	const reference = `ghcr.io/owned/charts/hephaestus@sha256:${"a".repeat(64)}`;
	const calls: string[][] = [];
	await keylessSign(["sign", "--yes", reference], {
		execute: async (actual) => {
			calls.push(actual);
		},
	});
	assert.deepEqual(calls, [["sign", "--yes", reference]]);
	for (const args of [
		["sign", "--yes", "ghcr.io/owned/charts/hephaestus:1.2.3"],
		["sign", "--yes", `${reference}@sha256:${"b".repeat(64)}`],
		["sign", reference],
		["sign", "--yes", "--key", "other", reference],
		["sign", "--yes", "--recursive", reference],
		// A subject that starts with `-` would reach cosign as a flag.
		["sign", "--yes", `--x@sha256:${"a".repeat(64)}`],
	]) {
		await assert.rejects(
			keylessSign(args, { execute: async () => assert.fail("must not sign") }),
			/Expected a release keyless/u,
			args.join(" "),
		);
	}
});

void test("retry launches distinct processes with identical signing arguments", async (t) => {
	const { args, bundle, input } = await fixture(t);
	const log = path.join(path.dirname(input), "calls.jsonl");
	const cli = path.join(path.dirname(input), "fake-cosign.mjs");
	await writeFile(
		cli,
		`import { appendFileSync, existsSync, writeFileSync } from "node:fs";
 const [log, ...args] = process.argv.slice(2);
 const retry = existsSync(log);
 appendFileSync(log, JSON.stringify({ pid: process.pid, args }) + "\\n");
 writeFileSync(args[3], retry ? "{}" : "partial");
 if (!retry) { console.error(${JSON.stringify(unavailable)}); process.exitCode = 1; }
 `,
	);
	await keylessSign(args, {
		execute: async (actual, signal) => {
			await output(process.execPath, [cli, log, ...actual], { signal });
		},
		sleep: async (milliseconds) => {
			assert.ok(milliseconds >= 30_000);
		},
	});
	const contents = await readFile(log, "utf8");
	const calls = contents
		.trim()
		.split("\n")
		.map((line) => {
			const value: unknown = JSON.parse(line);
			assert.ok(value !== null && typeof value === "object" && "pid" in value && "args" in value);
			return value;
		});
	assert.equal(calls.length, 2);
	assert.notEqual(calls[0]?.pid, calls[1]?.pid);
	for (const call of calls) {
		assert.deepEqual(call.args, args);
	}
	assert.equal(await readFile(bundle, "utf8"), "{}");
});

void test("zero exit without a bundle is not accepted", async (t) => {
	const { args } = await fixture(t);
	await assert.rejects(
		keylessSign(args, {
			execute: async () => {
				assert.equal(args[0], "sign-blob");
			},
			sleep: async () => assert.fail("missing output is permanent"),
		}),
		{ code: "ENOENT" },
	);
});

void test("an explicit signing config stays identical across attestation retries", async (t) => {
	const { input } = await fixture(t);
	const config = path.join(path.dirname(input), "signing-config.json");
	await writeFile(config, '{"rekorTlogUrls":[{"majorApiVersion":2}]}');
	const args = [
		"attest",
		"--signing-config",
		config,
		"--yes",
		"--type",
		"spdxjson",
		"--predicate",
		input,
		`ghcr.io/owned/image@sha256:${"a".repeat(64)}`,
	];
	let calls = 0;
	await keylessSign(args, {
		execute: async (actual) => {
			assert.deepEqual(actual, args);
			calls += 1;
			if (calls === 1) {
				throw failure(unavailable);
			}
		},
		sleep: async (milliseconds) => {
			assert.ok(milliseconds >= 30_000);
		},
	});
	assert.equal(calls, 2);
	await assert.rejects(
		keylessSign(args, {
			execute: async () => {
				await writeFile(config, "changed");
			},
		}),
		/Signing input changed/u,
	);
	await assert.rejects(
		keylessSign(["attest", "--signing-config", config, "--yes", "--key", "other"]),
		/Expected a release keyless/u,
	);
});

void test("missing and symbolic signing configs fail before Cosign starts", async (t) => {
	const { args, input } = await fixture(t);
	const config = path.join(path.dirname(input), "missing-config.json");
	const configured = [args[0] ?? "", "--signing-config", config, ...args.slice(1)];
	const dependencies = { execute: async () => assert.fail("invalid configuration must not sign") };
	await assert.rejects(keylessSign(configured, dependencies), { code: "ENOENT" });
	await symlink(input, config);
	await assert.rejects(keylessSign(configured, dependencies), /regular file/u);
});
