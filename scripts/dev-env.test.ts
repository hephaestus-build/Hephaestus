import assert from "node:assert/strict";
import { mkdir, mkdtemp, readFile, rm, stat, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { describe, test } from "node:test";

import { ensureDatabasePassword } from "./dev-env.ts";
import { readEnvFile } from "./lib/env.ts";

async function checkout(
	run: (paths: { envFile: string; dataDirectory: string }) => Promise<void>,
): Promise<void> {
	const directory = await mkdtemp(path.join(tmpdir(), "hephaestus-dev-env-"));
	try {
		await run({
			envFile: path.join(directory, ".env"),
			dataDirectory: path.join(directory, "postgres-data"),
		});
	} finally {
		await rm(directory, { recursive: true, force: true });
	}
}

async function passwordIn(envFile: string): Promise<string | undefined> {
	const values = await readEnvFile(envFile);
	return values.POSTGRES_PASSWORD;
}

await describe("the local database password", async () => {
	await test("a new checkout gets a random password and keeps its other settings", async () => {
		await checkout(async (paths) => {
			await writeFile(paths.envFile, "POSTGRES_PORT=15432");
			assert.equal(await ensureDatabasePassword({ ...paths, environment: {} }), "generated");
			const password = await passwordIn(paths.envFile);
			assert.match(password ?? "", /^[0-9a-f]{48}$/u);
			assert.notEqual(password, "root");
			const values = await readEnvFile(paths.envFile);
			assert.equal(values.POSTGRES_PORT, "15432");
		});
	});

	await test("a checkout without a .env file gets one", async () => {
		await checkout(async (paths) => {
			assert.equal(await ensureDatabasePassword({ ...paths, environment: {} }), "generated");
			assert.match((await passwordIn(paths.envFile)) ?? "", /^[0-9a-f]{48}$/u);
			if (process.platform !== "win32") {
				const { mode } = await stat(paths.envFile);
				assert.equal(mode.toString(8).slice(-2), "00");
			}
		});
	});

	await test("a password that is set stays, and a second run changes nothing", async () => {
		await checkout(async (paths) => {
			await ensureDatabasePassword({ ...paths, environment: {} });
			const first = await readFile(paths.envFile, "utf8");
			assert.equal(await ensureDatabasePassword({ ...paths, environment: {} }), "present");
			assert.equal(await readFile(paths.envFile, "utf8"), first);
		});
	});

	await test("a password in the environment is left alone", async () => {
		await checkout(async (paths) => {
			assert.equal(
				await ensureDatabasePassword({
					...paths,
					environment: { POSTGRES_PASSWORD: "from-shell" },
				}),
				"present",
			);
			await assert.rejects(readFile(paths.envFile, "utf8"));
		});
	});

	await test("a database made under the old fixed password keeps working", async () => {
		await checkout(async (paths) => {
			await mkdir(paths.dataDirectory);
			await writeFile(path.join(paths.dataDirectory, "PG_VERSION"), "18");
			await writeFile(paths.envFile, "POSTGRES_PORT=15432\n");
			assert.equal(await ensureDatabasePassword({ ...paths, environment: {} }), "legacy");
			assert.equal(await passwordIn(paths.envFile), "root");
			assert.equal(await ensureDatabasePassword({ ...paths, environment: {} }), "present");
		});
	});

	await test("the reset replaces the kept old password once the data is gone", async () => {
		await checkout(async (paths) => {
			await writeFile(
				paths.envFile,
				"POSTGRES_PORT=15432\nPOSTGRES_PASSWORD=root\nSERVER_PORT=18080\n",
			);
			assert.equal(await ensureDatabasePassword({ ...paths, environment: {} }), "generated");
			const values = await readEnvFile(paths.envFile);
			assert.match(values.POSTGRES_PASSWORD ?? "", /^[0-9a-f]{48}$/u);
			assert.equal(values.POSTGRES_PORT, "15432");
			assert.equal(values.SERVER_PORT, "18080");
		});
	});

	await test("a password that the developer chose survives a reset", async () => {
		await checkout(async (paths) => {
			await writeFile(paths.envFile, "POSTGRES_PASSWORD=chosen\n");
			assert.equal(await ensureDatabasePassword({ ...paths, environment: {} }), "present");
			assert.equal(await passwordIn(paths.envFile), "chosen");
		});
	});
});
