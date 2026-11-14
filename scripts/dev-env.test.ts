import assert from "node:assert/strict";
import { mkdir, mkdtemp, readFile, rm, stat, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { describe, test } from "node:test";

import { ensureComposeProject, ensureEnvFile, ensureGeneratedValues } from "./dev-env.ts";
import { readEnvFile } from "./lib/env.ts";

async function checkout(
	run: (paths: { envFile: string; dataDirectory: string; checkout: string }) => Promise<void>,
): Promise<void> {
	const directory = await mkdtemp(path.join(tmpdir(), "hephaestus-dev-env-"));
	try {
		await run({
			envFile: path.join(directory, ".env"),
			dataDirectory: path.join(directory, "postgres-data"),
			checkout: directory,
		});
	} finally {
		await rm(directory, { recursive: true, force: true });
	}
}

const keyNames = [
	"HEPHAESTUS_SECURITY_ENCRYPTION_KEY",
	"HEPHAESTUS_SECURITY_CREDENTIAL_ENCRYPTION_KEY",
] as const;

await describe("the settings file of a checkout", async () => {
	await test("a checkout without one gets a copy of the template that only its owner reads", async () => {
		await checkout(async ({ envFile, checkout: directory }) => {
			const template = path.join(directory, ".env.example");
			await writeFile(template, "# comment\nGITHUB_OAUTH_CLIENT_ID=\n");
			assert.equal(await ensureEnvFile({ envFile, template }), true);
			assert.equal(await readFile(envFile, "utf8"), "# comment\nGITHUB_OAUTH_CLIENT_ID=\n");
			if (process.platform !== "win32") {
				const { mode } = await stat(envFile);
				assert.equal(mode.toString(8).slice(-2), "00");
			}
		});
	});

	await test("an existing file is never replaced by the template", async () => {
		await checkout(async ({ envFile, checkout: directory }) => {
			const template = path.join(directory, ".env.example");
			await writeFile(template, "GITHUB_OAUTH_CLIENT_ID=\n");
			await writeFile(envFile, "GITHUB_OAUTH_CLIENT_ID=mine\nPOSTGRES_PASSWORD=chosen\n");
			assert.equal(await ensureEnvFile({ envFile, template }), false);
			assert.equal(
				await readFile(envFile, "utf8"),
				"GITHUB_OAUTH_CLIENT_ID=mine\nPOSTGRES_PASSWORD=chosen\n",
			);
		});
	});

	await test("the template's blank lines receive the generated values, and a second run changes nothing", async () => {
		await checkout(async (paths) => {
			const template = path.join(paths.checkout, ".env.example");
			await writeFile(
				template,
				"# keys\nHEPHAESTUS_SECURITY_ENCRYPTION_KEY=\nHEPHAESTUS_SECURITY_CREDENTIAL_ENCRYPTION_KEY=\n",
			);
			await ensureEnvFile({ envFile: paths.envFile, template });
			await ensureGeneratedValues({ ...paths, environment: {} });
			const first = await readFile(paths.envFile, "utf8");
			assert.equal(first.match(/^HEPHAESTUS_SECURITY_ENCRYPTION_KEY=/gmu)?.length, 1);
			assert.deepEqual(await ensureGeneratedValues({ ...paths, environment: {} }), []);
			assert.equal(await readFile(paths.envFile, "utf8"), first);
		});
	});
});

await describe("the generated values of a checkout", async () => {
	await test("a new checkout gets a password, both keys and a webhook secret, and keeps its other settings", async () => {
		await checkout(async (paths) => {
			await writeFile(paths.envFile, "POSTGRES_PORT=15432");
			assert.deepEqual(await ensureGeneratedValues({ ...paths, environment: {} }), [
				"POSTGRES_PASSWORD",
				...keyNames,
				"WEBHOOK_SECRET",
			]);
			const values = await readEnvFile(paths.envFile);
			assert.match(values.POSTGRES_PASSWORD ?? "", /^[0-9a-f]{48}$/u);
			for (const key of keyNames) {
				assert.equal(Buffer.byteLength(values[key] ?? "", "utf8"), 32);
			}
			assert.notEqual(
				values.HEPHAESTUS_SECURITY_ENCRYPTION_KEY,
				values.HEPHAESTUS_SECURITY_CREDENTIAL_ENCRYPTION_KEY,
			);
			assert.match(values.WEBHOOK_SECRET ?? "", /^[0-9a-f]{64}$/u);
			assert.equal(values.POSTGRES_PORT, "15432");
		});
	});

	await test("a key repeated with a blank last line keeps the developer's line and gets set once", async () => {
		await checkout(async (paths) => {
			await writeFile(
				paths.envFile,
				"WEBHOOK_SECRET=mine\nPOSTGRES_PASSWORD=chosen\nWEBHOOK_SECRET=\n",
				{ mode: 0o644 },
			);
			await ensureGeneratedValues({ ...paths, environment: {} });
			const first = await readFile(paths.envFile, "utf8");
			assert.match(
				first,
				/^WEBHOOK_SECRET=mine\nPOSTGRES_PASSWORD=chosen\nWEBHOOK_SECRET=[0-9a-f]{64}\n/u,
			);
			assert.deepEqual(await ensureGeneratedValues({ ...paths, environment: {} }), []);
			assert.equal(await readFile(paths.envFile, "utf8"), first);
			if (process.platform !== "win32") {
				const { mode } = await stat(paths.envFile);
				assert.equal(mode.toString(8).slice(-2), "00");
			}
		});
	});

	await test("values that are set stay, and a second run changes nothing", async () => {
		await checkout(async (paths) => {
			await ensureGeneratedValues({ ...paths, environment: {} });
			const first = await readFile(paths.envFile, "utf8");
			assert.deepEqual(await ensureGeneratedValues({ ...paths, environment: {} }), []);
			assert.equal(await readFile(paths.envFile, "utf8"), first);
		});
	});

	await test("values in the environment are left alone", async () => {
		await checkout(async (paths) => {
			const environment = {
				POSTGRES_PASSWORD: "from-shell",
				HEPHAESTUS_SECURITY_ENCRYPTION_KEY: "from-shell",
				HEPHAESTUS_SECURITY_CREDENTIAL_ENCRYPTION_KEY: "from-shell",
				WEBHOOK_SECRET: "from-shell",
			};
			assert.deepEqual(await ensureGeneratedValues({ ...paths, environment }), []);
			await assert.rejects(readFile(paths.envFile, "utf8"));
		});
	});

	await test("existing data without a configured password refuses without changing the file", async () => {
		await checkout(async (paths) => {
			await mkdir(paths.dataDirectory);
			await writeFile(path.join(paths.dataDirectory, "PG_VERSION"), "18");
			await writeFile(paths.envFile, "POSTGRES_PORT=15432\n");
			await assert.rejects(
				ensureGeneratedValues({ ...paths, environment: {} }),
				/Set its password in server\/\.env, or run `vp run dev:reset`/u,
			);
			assert.equal(await readFile(paths.envFile, "utf8"), "POSTGRES_PORT=15432\n");
		});
	});

	await test("existing data gets no new keys, because its stored secrets need the old ones", async () => {
		await checkout(async (paths) => {
			await mkdir(paths.dataDirectory);
			await writeFile(path.join(paths.dataDirectory, "PG_VERSION"), "18");
			await writeFile(
				paths.envFile,
				"POSTGRES_PORT=15432\nPOSTGRES_PASSWORD=chosen\nSERVER_PORT=18080\n",
			);
			assert.deepEqual(await ensureGeneratedValues({ ...paths, environment: {} }), [
				"WEBHOOK_SECRET",
			]);
			const values = await readEnvFile(paths.envFile);
			assert.equal(values.POSTGRES_PASSWORD, "chosen");
			assert.equal(values.POSTGRES_PORT, "15432");
			assert.equal(values.SERVER_PORT, "18080");
			for (const key of keyNames) {
				assert.equal(values[key], undefined);
			}
		});
	});
});

await describe("the Compose project of a checkout", async () => {
	await test("each new checkout gets its own name, and a second run keeps it", async () => {
		const names: string[] = [];
		for (let index = 0; index < 2; index += 1) {
			await checkout(async (paths) => {
				await writeFile(paths.envFile, "POSTGRES_PORT=15432\n");
				const name = await ensureComposeProject({ ...paths, environment: {} });
				assert.match(name, /^hephaestus-hephaestus-dev-env-[a-z0-9_-]+-[0-9a-f]{8}$/u);
				assert.equal(await ensureComposeProject({ ...paths, environment: {} }), name);
				const values = await readEnvFile(paths.envFile);
				assert.equal(values.COMPOSE_PROJECT_NAME, name);
				assert.equal(values.POSTGRES_PORT, "15432");
				names.push(name);
			});
		}
		assert.notEqual(names[0], names[1]);
	});

	await test("existing data without a configured project refuses without creating a file", async () => {
		await checkout(async (paths) => {
			await mkdir(paths.dataDirectory);
			await writeFile(path.join(paths.dataDirectory, "PG_VERSION"), "18");
			await assert.rejects(
				ensureComposeProject({ ...paths, environment: {} }),
				/Set its project name in server\/\.env/u,
			);
			await assert.rejects(readFile(paths.envFile, "utf8"));
		});
	});

	await test("a name from the file or the shell wins and is not written", async () => {
		await checkout(async (paths) => {
			await mkdir(paths.dataDirectory);
			await writeFile(path.join(paths.dataDirectory, "PG_VERSION"), "18");
			await writeFile(paths.envFile, "COMPOSE_PROJECT_NAME=mine\n");
			assert.equal(await ensureComposeProject({ ...paths, environment: {} }), "mine");
			await writeFile(paths.envFile, "");
			const environment = { COMPOSE_PROJECT_NAME: "shell" };
			assert.equal(await ensureComposeProject({ ...paths, environment }), "shell");
			assert.equal(await readFile(paths.envFile, "utf8"), "");
		});
	});
});
