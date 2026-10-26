import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdir, mkdtemp, readFile, readdir, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { describe, test } from "node:test";

import { duplicatePorts } from "./check-ports.ts";
import { withDisposableDatabase } from "./db-utils.ts";
import { loadConfig } from "./e2e-setup.ts";
import {
	type Api,
	type E2eLlmRoute,
	provisionLlmConnection,
	provisionLlmModel,
	readLlmRoute,
} from "./lib/e2e-llm.ts";
import { isSet, positivePort, readEnvFile } from "./lib/env.ts";

await describe("environment parsing", async () => {
	await test("parses data without evaluating shell syntax", async () => {
		const directory = await mkdtemp(path.join(tmpdir(), "hephaestus-env-"));
		try {
			const envFile = path.join(directory, ".env");
			await writeFile(envFile, "PORT=5432\nSECRET=$(echo leaked)\nexport BAD=ignored\n");
			assert.deepEqual(await readEnvFile(envFile), { PORT: "5432", SECRET: "$(echo leaked)" });
		} finally {
			await rm(directory, { recursive: true, force: true });
		}
	});

	await test("rejects invalid ports", () => {
		assert.throws(() => positivePort("0", "PORT"), /1 to 65535/u);
		assert.throws(() => positivePort("65536", "PORT"), /1 to 65535/u);
	});
});

await test("duplicate port reporting identifies the conflicting pair", () => {
	const first = { name: "database", port: 5432 };
	const second = { name: "server", port: 5432 };
	assert.deepEqual(duplicatePorts([first, second]), [[second, first]]);
});

await test("duplicate configured ports fail the preflight", async () => {
	const result = spawnSync(
		process.execPath,
		[path.join(import.meta.dirname, "check-ports.ts"), "--quiet"],
		{
			env: { ...process.env, POSTGRES_PORT: "65431", SERVER_PORT: "65431", WEBAPP_PORT: "65431" },
			stdio: "ignore",
		},
	);
	assert.equal(result.status, 1);
});

await test("database drafting restores planted state after external-command failure", async () => {
	const directory = await mkdtemp(path.join(tmpdir(), "hephaestus-db-"));
	const data = path.join(directory, "postgres-data");
	const backup = path.join(directory, "backup");
	await mkdir(data);
	await writeFile(path.join(data, "sentinel"), "original");
	let stops = 0;
	try {
		let failure: unknown;
		try {
			await withDisposableDatabase(
				data,
				backup,
				async () => {
					stops += 1;
				},
				async () => {
					await mkdir(data);
					await writeFile(path.join(data, "sentinel"), "disposable");
					throw new Error("planted migration failure");
				},
			);
		} catch (error) {
			failure = error;
		}
		assert.ok(failure instanceof Error);
		assert.equal(failure instanceof Error ? failure.message : "", "planted migration failure");
		assert.equal(await readFile(path.join(data, "sentinel"), "utf8"), "original");
		assert.equal(stops, 2);
	} finally {
		await rm(directory, { recursive: true, force: true });
	}
});

await test("database drafting does not move data after shutdown failure", async () => {
	const directory = await mkdtemp(path.join(tmpdir(), "hephaestus-db-stop-"));
	const data = path.join(directory, "postgres-data");
	const backup = path.join(directory, "backup");
	await mkdir(data);
	await writeFile(path.join(data, "sentinel"), "original");
	let stops = 0;
	try {
		let failure: unknown;
		try {
			await withDisposableDatabase(
				data,
				backup,
				async () => {
					stops += 1;
					if (stops !== 1) {
						throw new Error("postgres still running");
					}
				},
				async () => {
					await mkdir(data);
					await writeFile(path.join(data, "sentinel"), "disposable");
				},
			);
		} catch (error) {
			failure = error;
		}
		assert.equal(failure instanceof Error ? failure.message : "", "postgres still running");
		assert.equal(await readFile(path.join(backup, "sentinel"), "utf8"), "original");
		assert.equal(await readFile(path.join(data, "sentinel"), "utf8"), "disposable");
	} finally {
		await rm(directory, { recursive: true, force: true });
	}
});

await test("scripts contains no shell implementation", async () => {
	const entries = await readdir(import.meta.dirname, { recursive: true });
	assert.deepEqual(
		entries.filter((entry) => entry.endsWith(".sh")),
		[],
	);
});

await describe("E2E setup trust boundaries", async () => {
	const valid = {
		E2E_GITLAB_PAT: "pat-secret",
		E2E_LLM_KEY: "llm-secret",
		E2E_LLM_BASE_URL: "https://models.example/v1",
		E2E_MODEL: "model",
		E2E_LLM_PRICING_MODE: "NO_CHARGE",
		E2E_LLM_PRICE_NOTE: "internal test credits",
	};

	await test("accepts credentials only from the environment", () => {
		const config = loadConfig(valid, []);
		assert.equal(config.pat, "pat-secret");
		assert.equal(config.llm.key, "llm-secret");
		assert.throws(() => loadConfig(valid, ["--llm-key", "leak"]), /invalid argument/u);
	});

	await test("rejects non-loopback app and database endpoints", () => {
		assert.throws(
			() => loadConfig({ ...valid, E2E_APP_URL: "https://public.example" }, []),
			/loopback/u,
		);
		assert.throws(
			() => loadConfig({ ...valid, E2E_DB_URL: "postgresql://secret@db.example/app" }, []),
			/loopback/u,
		);
	});

	await test("diagnostics never contain credential values", () => {
		for (const env of [
			{ ...valid, E2E_LLM_PRICING_MODE: "invalid" },
			{ ...valid, E2E_REPO: "bad;repo" },
		]) {
			let diagnostic: string | undefined;
			try {
				loadConfig(env, []);
			} catch (error) {
				diagnostic = error instanceof Error ? error.message : String(error);
			}
			assert.ok(isSet(diagnostic));
			assert.ok(!diagnostic.includes("pat-secret"));
			assert.ok(!diagnostic.includes("llm-secret"));
		}
	});
});

/** Only the required model variables; everything else takes its default. */
function minimalSetting(_flag: string, name: string, fallback = ""): string {
	return (
		(
			{ E2E_LLM_BASE_URL: "https://models.example/v1/", E2E_MODEL: "model" } as Record<
				string,
				string
			>
		)[name] ?? fallback
	);
}

await describe("E2E model connection", async () => {
	const route: E2eLlmRoute = {
		baseUrl: "https://models.example/v1",
		key: "rotated-key",
		model: "model",
		protocol: "openai-responses",
		authMode: "BEARER",
	};
	const stored = {
		id: 7,
		slug: "e2e-live-llm",
		baseUrl: route.baseUrl,
		apiProtocol: route.protocol,
		authMode: route.authMode,
	};

	/** An admin API holding `connections` and `models`, which records every write. */
	function fakeApi(connections: unknown[], models: unknown[] = []): { api: Api; writes: string[] } {
		const writes: string[] = [];
		const api: Api = async (method, requestPath, body) => {
			if (method === "GET") {
				return requestPath.endsWith("/models") ? models : connections;
			}
			writes.push(`${method} ${requestPath} ${JSON.stringify(body ?? null)}`);
			if (requestPath.endsWith("/probe")) {
				return { reachable: true };
			}
			return method === "POST" ? { ...stored, id: 9 } : stored;
		};
		return { api, writes };
	}

	await test("reads the same defaults for every caller", () => {
		assert.deepEqual(readLlmRoute(minimalSetting, { E2E_LLM_KEY: "key" }), {
			baseUrl: "https://models.example/v1",
			key: "key",
			model: "model",
			protocol: "openai-responses",
			authMode: "BEARER",
		});
	});

	await test("sets the key of a connection from an earlier run, so a rotated key takes effect", async () => {
		const { api, writes } = fakeApi([stored]);
		assert.equal(
			await provisionLlmConnection(
				api,
				"/admin/llm",
				{ slug: "e2e-live-llm", displayName: "E2E" },
				route,
			),
			7,
		);
		assert.deepEqual(writes, [
			'PATCH /admin/llm/connections/7 {"apiKey":"rotated-key"}',
			"POST /admin/llm/connections/7/probe null",
			'PATCH /admin/llm/connections/7 {"enabled":true}',
		]);
	});

	await test("refuses a connection whose route changed instead of calling the old provider", async () => {
		for (const changed of [
			{ baseUrl: "https://other.example/v1" },
			{ apiProtocol: "openai-completions" },
			{ authMode: "API_KEY" },
		]) {
			const { api, writes } = fakeApi([{ ...stored, ...changed }]);
			await assert.rejects(
				provisionLlmConnection(
					api,
					"/admin/llm",
					{ slug: "e2e-live-llm", displayName: "E2E" },
					route,
				),
				/different base URL, protocol or auth mode/u,
			);
			assert.deepEqual(writes, []);
		}
	});

	await test("creates the connection on a first run", async () => {
		const { api, writes } = fakeApi([]);
		assert.equal(
			await provisionLlmConnection(
				api,
				"/admin/llm",
				{ slug: "e2e-live-llm", displayName: "E2E" },
				route,
			),
			9,
		);
		assert.match(
			writes[0] ?? "",
			/^POST \/admin\/llm\/connections \{.*"apiProtocol":"openai-responses"/u,
		);
		assert.equal(writes[1], 'PATCH /admin/llm/connections/9 {"apiKey":"rotated-key"}');
	});
	await test("repairs a model that a partial earlier run left disabled and unpriced", async () => {
		const leftOver = { id: 3, connectionId: 7, upstreamModelId: "model", enabled: false };
		const setup = {
			connection: { slug: "e2e-live-llm", displayName: "E2E" },
			modelDisplayName: "model",
			pricing: { pricingMode: "NO_CHARGE", note: "E2E test model" },
		} as const;

		const instance = fakeApi([stored], [leftOver]);
		assert.equal(await provisionLlmModel(instance.api, "/admin/llm", setup, route), 3);
		assert.deepEqual(instance.writes.slice(3), [
			'PUT /admin/llm/models/3/price {"pricingMode":"NO_CHARGE","note":"E2E test model"}',
			'PATCH /admin/llm/models/3 {"enabled":true}',
			'PUT /admin/llm/models/3/sharing {"visibility":"PUBLIC"}',
		]);

		const workspace = fakeApi([stored], [leftOver]);
		assert.equal(await provisionLlmModel(workspace.api, "/workspaces/e2e/llm", setup, route), 3);
		assert.deepEqual(workspace.writes.slice(3), [
			'PATCH /workspaces/e2e/llm/models/3 {"enabled":true,"pricingMode":"NO_CHARGE","priceNote":"E2E test model"}',
		]);
	});
});
