import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdir, mkdtemp, readFile, readdir, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, test } from "node:test";

import { duplicatePorts } from "./check-ports.ts";
import { withDisposableDatabase } from "./db-utils.ts";
import { loadConfig } from "./e2e-setup.ts";
import { updateEnv } from "./jean-setup.ts";
import { isHostname, positivePort, readEnvFile } from "./lib/env.ts";

await describe("environment parsing", async () => {
	await test("parses data without evaluating shell syntax", async () => {
		const directory = await mkdtemp(join(tmpdir(), "hephaestus-env-"));
		try {
			const path = join(directory, ".env");
			await writeFile(path, "PORT=5432\nSECRET=$(echo leaked)\nexport BAD=ignored\n");
			assert.deepEqual(await readEnvFile(path), { PORT: "5432", SECRET: "$(echo leaked)" });
		} finally {
			await rm(directory, { recursive: true, force: true });
		}
	});

	await test("rejects invalid ports", () => {
		assert.throws(() => positivePort("0", "PORT"), /1 to 65535/);
		assert.throws(() => positivePort("65536", "PORT"), /1 to 65535/);
	});
});

await test("duplicate port reporting identifies the conflicting pair", () => {
	const first = { name: "database", port: 5432 };
	const second = { name: "server", port: 5432 };
	assert.deepEqual(duplicatePorts([first, second]), [[second, first]]);
});

await test("duplicate configured ports fail the preflight", async () => {
	const child = spawn(process.execPath, [join(import.meta.dirname, "check-ports.ts"), "--quiet"], {
		env: { ...process.env, POSTGRES_PORT: "65431", SERVER_PORT: "65431", WEBAPP_PORT: "65431" },
		stdio: "ignore",
	});
	const [exitCode] = await new Promise<[number | null]>((resolve) => {
		child.once("exit", (code) => resolve([code]));
	});
	assert.equal(exitCode, 1);
});

await test("public host validation rejects Traefik rule syntax", () => {
	assert.equal(isHostname("preview.example.com"), true);
	assert.equal(isHostname("example.com`) || Host(`attacker.example"), false);
});

await test("Jean setup updates only GitLab bootstrap settings and remains idempotent", () => {
	const initial =
		"# machine-local config\nGITLAB_PAT=secret\nGITLAB_GROUP_PATH=group\nGITLAB_ENABLED=false\n";
	const updated = updateEnv(initial);
	assert.equal(updated.startsWith("# machine-local config\n"), true);
	assert.ok(updated.includes("GITLAB_ENABLED=true"));
	assert.ok(updated.includes("GITLAB_SERVER_URL=https://gitlab.lrz.de"));
	assert.equal(updateEnv(updated), updated);
});

await test("database drafting restores planted state after external-command failure", async () => {
	const directory = await mkdtemp(join(tmpdir(), "hephaestus-db-"));
	const data = join(directory, "postgres-data");
	const backup = join(directory, "backup");
	await mkdir(data);
	await writeFile(join(data, "sentinel"), "original");
	let stops = 0;
	try {
		let failure: unknown;
		try {
			await withDisposableDatabase(
				data,
				backup,
				() => {
					stops += 1;
					return Promise.resolve();
				},
				async () => {
					await mkdir(data);
					await writeFile(join(data, "sentinel"), "disposable");
					throw new Error("planted migration failure");
				},
			);
		} catch (error) {
			failure = error;
		}
		assert.ok(failure instanceof Error);
		assert.equal(failure instanceof Error ? failure.message : "", "planted migration failure");
		assert.equal(await readFile(join(data, "sentinel"), "utf8"), "original");
		assert.equal(stops, 2);
	} finally {
		await rm(directory, { recursive: true, force: true });
	}
});

await test("database drafting does not move data after shutdown failure", async () => {
	const directory = await mkdtemp(join(tmpdir(), "hephaestus-db-stop-"));
	const data = join(directory, "postgres-data");
	const backup = join(directory, "backup");
	await mkdir(data);
	await writeFile(join(data, "sentinel"), "original");
	let stops = 0;
	try {
		let failure: unknown;
		try {
			await withDisposableDatabase(
				data,
				backup,
				() => {
					stops += 1;
					return stops === 1
						? Promise.resolve()
						: Promise.reject(new Error("postgres still running"));
				},
				async () => {
					await mkdir(data);
					await writeFile(join(data, "sentinel"), "disposable");
				},
			);
		} catch (error) {
			failure = error;
		}
		assert.equal(failure instanceof Error ? failure.message : "", "postgres still running");
		assert.equal(await readFile(join(backup, "sentinel"), "utf8"), "original");
		assert.equal(await readFile(join(data, "sentinel"), "utf8"), "disposable");
	} finally {
		await rm(directory, { recursive: true, force: true });
	}
});

await test("scripts contains no shell implementation", async () => {
	const entries = await readdir(import.meta.dirname, { recursive: true });
	assert.deepEqual(
		entries.filter((path) => path.endsWith(".sh")),
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
		assert.equal(config.llmKey, "llm-secret");
		assert.throws(() => loadConfig(valid, ["--llm-key", "leak"]), /invalid argument/);
	});

	await test("rejects non-loopback app and database endpoints", () => {
		assert.throws(
			() => loadConfig({ ...valid, E2E_APP_URL: "https://public.example" }, []),
			/loopback/,
		);
		assert.throws(
			() => loadConfig({ ...valid, E2E_DB_URL: "postgresql://secret@db.example/app" }, []),
			/loopback/,
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
			assert.ok(diagnostic);
			assert.ok(!diagnostic.includes("pat-secret"));
			assert.ok(!diagnostic.includes("llm-secret"));
		}
	});
});
