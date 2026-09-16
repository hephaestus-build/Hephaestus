import assert from "node:assert/strict";
import { execFileSync, spawn } from "node:child_process";
import {
	chmod,
	copyFile,
	lstat,
	mkdir,
	mkdtemp,
	readFile,
	rm,
	symlink,
	writeFile,
} from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { afterEach, test } from "node:test";

const sourceDirectory = path.join(import.meta.dirname, "..", "docker", "self-host");
const posixOnly = { skip: process.platform === "win32" && "setup.sh is a POSIX shell script" };
const temporaryDirectories: string[] = [];

afterEach(async () => {
	await Promise.all(
		temporaryDirectories
			.splice(0)
			.map(async (directory) => rm(directory, { recursive: true, force: true })),
	);
});

async function fixture(): Promise<string> {
	const directory = await mkdtemp(path.join(tmpdir(), "hephaestus-self-host-setup-"));
	temporaryDirectories.push(directory);
	await copyFile(path.join(sourceDirectory, "setup.sh"), path.join(directory, "setup.sh"));
	await copyFile(path.join(sourceDirectory, ".env.example"), path.join(directory, ".env.example"));
	return directory;
}

/**
 * Every run gets a `docker` of its own, so no test depends on the volumes of the machine it runs
 * on. The stub answers from `docker-answer` (`absent` by default) and records what it was asked.
 */
async function installDockerStub(
	directory: string,
	answer: "present" | "absent" | "error",
): Promise<string> {
	const bin = path.join(directory, "bin");
	await mkdir(bin, { recursive: true });
	await writeFile(path.join(directory, "docker-answer"), answer);
	await writeFile(
		path.join(bin, "docker"),
		`#!/bin/sh
printf '%s\\n' "$*" >> "${path.join(directory, "docker-args")}"
case $(cat "${path.join(directory, "docker-answer")}") in
	present) printf '%s\\n' 'another-product_postgresql-data' 'hephaestus_postgresql-data' ;;
	absent) printf '%s\\n' 'another-product_postgresql-data' ;;
	*) printf '%s\\n' 'Cannot connect to the Docker daemon at unix:///var/run/docker.sock. Is the docker daemon running?' >&2; exit 1 ;;
esac
`,
		{ mode: 0o755 },
	);
	return bin;
}

async function setup(
	directory: string,
	options: { path?: string; database?: "present" | "absent" | "error" } = {},
): Promise<{ exitCode: number; output: string }> {
	const bin = await installDockerStub(directory, options.database ?? "absent");
	const searchPath = options.path ?? `${bin}:${process.env.PATH ?? ""}`;
	const child = spawn(path.join(directory, "setup.sh"), {
		env: { ...process.env, PATH: searchPath },
		stdio: "pipe",
	});
	let stdout = "";
	let stderr = "";
	child.stdout.setEncoding("utf8").on("data", (chunk: string) => {
		stdout += chunk;
	});
	child.stderr.setEncoding("utf8").on("data", (chunk: string) => {
		stderr += chunk;
	});
	const exited = Promise.withResolvers<number>();
	child.once("error", () => {
		exited.resolve(1);
	});
	child.once("close", (code) => {
		exited.resolve(code ?? 1);
	});
	return { exitCode: await exited.promise, output: `${stdout}${stderr}` };
}

function managedValues(environment: string): string[] {
	const keys = new Set([
		"POSTGRES_PASSWORD",
		"HEPHAESTUS_SECURITY_ENCRYPTION_KEY",
		"HEPHAESTUS_SECURITY_CREDENTIAL_ENCRYPTION_KEY",
		"HEPHAESTUS_AUTH_STATE_COOKIE_KEY",
		"WEBHOOK_SECRET",
		"NATS_USERNAME",
		"NATS_PASSWORD",
	]);
	return environment
		.split("\n")
		.map((line) => line.split("=", 2))
		.filter(([key, value]) => keys.has(key ?? "") && value !== undefined)
		.map(([, value]) => value ?? "");
}

await test("generates protected secrets without printing them", posixOnly, async () => {
	const directory = await fixture();
	const result = await setup(directory);
	const environmentPath = path.join(directory, ".env");
	const environment = await readFile(environmentPath, "utf8");

	assert.equal(result.exitCode, 0);
	assert.match(environment, /^POSTGRES_PASSWORD=[0-9a-f]{32}$/mu);
	assert.match(environment, /^HEPHAESTUS_SECURITY_ENCRYPTION_KEY=[0-9a-f]{32}$/mu);
	const encryptionKey = /^HEPHAESTUS_SECURITY_ENCRYPTION_KEY=(?<key>.+)$/mu.exec(environment)
		?.groups?.key;
	const credentialKey = /^HEPHAESTUS_SECURITY_CREDENTIAL_ENCRYPTION_KEY=(?<key>.+)$/mu.exec(
		environment,
	)?.groups?.key;
	assert.equal(credentialKey, encryptionKey);
	assert.match(environment, /^HEPHAESTUS_AUTH_STATE_COOKIE_KEY=[A-Za-z0-9+/]{43}=$/mu);
	assert.match(environment, /^WEBHOOK_SECRET=[0-9a-f]{64}$/mu);
	// The broker's config file rejects an all-digit credential, so both carry a letter prefix.
	assert.match(environment, /^NATS_USERNAME=heph[0-9a-f]{32}$/mu);
	assert.match(environment, /^NATS_PASSWORD=heph[0-9a-f]{32}$/mu);
	assert.notEqual(
		/^NATS_USERNAME=(?<value>.+)$/mu.exec(environment)?.groups?.value,
		/^NATS_PASSWORD=(?<value>.+)$/mu.exec(environment)?.groups?.value,
	);
	const environmentFile = await lstat(environmentPath);
	assert.equal(environmentFile.mode.toString(8).slice(-3), "600");
	for (const secret of managedValues(environment)) {
		assert.ok(!result.output.includes(secret));
	}

	const second = await setup(directory);
	assert.equal(second.exitCode, 0);
	assert.equal(await readFile(environmentPath, "utf8"), environment);
});

await test("preserves configured values and fills missing assignments", posixOnly, async () => {
	const directory = await fixture();
	const example = await readFile(path.join(directory, ".env.example"), "utf8");
	await writeFile(
		path.join(directory, ".env"),
		example
			.replace("POSTGRES_PASSWORD=", "POSTGRES_PASSWORD=preserved")
			.replace(/^WEBHOOK_SECRET=.*\n/mu, ""),
	);

	const result = await setup(directory);
	assert.equal(result.exitCode, 0);
	const environment = await readFile(path.join(directory, ".env"), "utf8");
	assert.ok(environment.includes("POSTGRES_PASSWORD=preserved\n"));
	assert.match(environment, /^WEBHOOK_SECRET=[0-9a-f]{64}$/mu);
});

await test("rejects duplicate managed assignments", posixOnly, async () => {
	const directory = await fixture();
	const examplePath = path.join(directory, ".env.example");
	await writeFile(
		examplePath,
		`${await readFile(examplePath, "utf8")}POSTGRES_PASSWORD=duplicate\n`,
	);

	const result = await setup(directory);
	assert.notEqual(result.exitCode, 0);
	await assert.rejects(lstat(path.join(directory, ".env")));
});

await test(
	"leaves an existing environment unchanged when generation fails",
	posixOnly,
	async () => {
		const directory = await fixture();
		const environmentPath = path.join(directory, ".env");
		const original = await readFile(path.join(directory, ".env.example"), "utf8");
		await writeFile(environmentPath, original);
		const binaryDirectory = path.join(directory, "bin");
		await mkdir(binaryDirectory);
		const openssl = path.join(binaryDirectory, "openssl");
		await writeFile(openssl, "#!/bin/sh\nexit 1\n");
		await chmod(openssl, 0o700);

		const result = await setup(directory, {
			path: `${binaryDirectory}:${process.env.PATH ?? ""}`,
		});
		assert.notEqual(result.exitCode, 0);
		assert.equal(await readFile(environmentPath, "utf8"), original);
	},
);

await test("refuses to write through an environment symlink", posixOnly, async () => {
	const directory = await fixture();
	const target = path.join(directory, "target");
	await writeFile(target, "unchanged");
	await symlink(target, path.join(directory, ".env"));

	const result = await setup(directory);
	assert.notEqual(result.exitCode, 0);
	assert.equal(await readFile(target, "utf8"), "unchanged");
});

await test(
	"asks docker about the exact volume the supported topology uses",
	posixOnly,
	async () => {
		const directory = await fixture();
		const result = await setup(directory);
		assert.equal(result.exitCode, 0);
		assert.equal(
			await readFile(path.join(directory, "docker-args"), "utf8"),
			"volume ls --quiet\n",
		);
		assert.match(result.output, /Generated HEPHAESTUS_SECURITY_ENCRYPTION_KEY/u);
	},
);

await test(
	"refuses to generate the encryption key over an existing database",
	posixOnly,
	async () => {
		const directory = await fixture();
		const refused = await setup(directory, { database: "present" });
		assert.equal(refused.exitCode, 1);
		assert.match(
			refused.output,
			/already exists on this host .*Set HEPHAESTUS_SECURITY_ENCRYPTION_KEY/u,
		);
		assert.doesNotMatch(refused.output, /Generated/u);
		await assert.rejects(readFile(path.join(directory, ".env"), "utf8"), { code: "ENOENT" });
	},
);

await test(
	"an existing database with its master key still derives the credential key",
	posixOnly,
	async () => {
		// The v0.74 to v0.75 path: older installations carried only the master key, and the
		// credential key was that key. Refusing here would strand every one of them.
		const directory = await fixture();
		await writeFile(
			path.join(directory, ".env"),
			"HEPHAESTUS_SECURITY_ENCRYPTION_KEY=0123456789abcdef0123456789abcdef\n",
		);
		const result = await setup(directory, { database: "present" });
		assert.equal(result.exitCode, 0);
		const environment = await readFile(path.join(directory, ".env"), "utf8");
		assert.match(
			environment,
			/^HEPHAESTUS_SECURITY_CREDENTIAL_ENCRYPTION_KEY=0123456789abcdef0123456789abcdef$/mu,
		);
		assert.match(environment, /^POSTGRES_PASSWORD=.+$/mu);
	},
);

await test("a docker that cannot answer is not taken for an empty host", posixOnly, async () => {
	const directory = await fixture();
	const result = await setup(directory, { database: "error" });
	assert.equal(result.exitCode, 1);
	assert.match(
		result.output,
		/Could not tell whether a Hephaestus database already exists on this host \(docker volume ls failed: Cannot connect to the Docker daemon/u,
	);
	await assert.rejects(readFile(path.join(directory, ".env"), "utf8"), { code: "ENOENT" });
});

await test("docker is not asked when the key is already set", posixOnly, async () => {
	const directory = await fixture();
	await writeFile(
		path.join(directory, ".env"),
		"HEPHAESTUS_SECURITY_ENCRYPTION_KEY=0123456789abcdef0123456789abcdef\n",
	);
	const result = await setup(directory, { database: "error" });
	assert.equal(result.exitCode, 0);
	await assert.rejects(readFile(path.join(directory, "docker-args"), "utf8"), { code: "ENOENT" });
});

await test(
	"an existing database refuses a credential key that was set separately and is now missing",
	posixOnly,
	async () => {
		// A post-v0.75 configuration assigns the credential key explicitly; blank, or with rotation
		// settings beside it, it must not be quietly replaced by the master key.
		for (const environment of [
			"HEPHAESTUS_SECURITY_ENCRYPTION_KEY=0123456789abcdef0123456789abcdef\nHEPHAESTUS_SECURITY_CREDENTIAL_ENCRYPTION_KEY=\n",
			"HEPHAESTUS_SECURITY_ENCRYPTION_KEY=0123456789abcdef0123456789abcdef\nHEPHAESTUS_SECURITY_CREDENTIAL_ENCRYPTION_KEY_VERSION=2\n",
		]) {
			const directory = await fixture();
			await writeFile(path.join(directory, ".env"), environment);
			const result = await setup(directory, { database: "present" });
			assert.equal(result.exitCode, 1);
			assert.match(
				result.output,
				/Set HEPHAESTUS_SECURITY_CREDENTIAL_ENCRYPTION_KEY to the value/u,
			);
			assert.equal(await readFile(path.join(directory, ".env"), "utf8"), environment);
		}
	},
);

await test(
	"a host without docker on PATH is not taken for an empty host either",
	posixOnly,
	async () => {
		const directory = await fixture();
		const tools = path.join(directory, "tools-without-docker");
		await mkdir(tools);
		for (const tool of [
			"openssl",
			"grep",
			"sed",
			"mktemp",
			"cp",
			"chmod",
			"mv",
			"rm",
			"dirname",
			"head",
			"cat",
			"sh",
		]) {
			const resolved = execFileSync("sh", ["-c", `command -v ${tool}`], {
				encoding: "utf8",
			}).trim();
			await symlink(resolved, path.join(tools, tool));
		}
		const result = await setup(directory, { path: tools });
		assert.equal(result.exitCode, 1);
		assert.match(result.output, /docker is not installed on this host, or not on PATH/u);
	},
);

await test("an empty env file over an existing database names both keys", posixOnly, async () => {
	// Nothing in an empty or damaged file says whether the credential key was the master key
	// or set separately, so the instruction must not lead the operator to restore one and have
	// the other quietly derived on the next run.
	const directory = await fixture();
	const result = await setup(directory, { database: "present" });
	assert.equal(result.exitCode, 1);
	assert.match(
		result.output,
		/Set HEPHAESTUS_SECURITY_ENCRYPTION_KEY and HEPHAESTUS_SECURITY_CREDENTIAL_ENCRYPTION_KEY \(the same value as the master key on an installation from before v0\.75\)/u,
	);
});

await test(
	"a blank rotation setting counts as a separately set credential key",
	posixOnly,
	async () => {
		const directory = await fixture();
		await writeFile(
			path.join(directory, ".env"),
			"HEPHAESTUS_SECURITY_ENCRYPTION_KEY=0123456789abcdef0123456789abcdef\nHEPHAESTUS_SECURITY_PRIOR_CREDENTIAL_ENCRYPTION_KEY=\n",
		);
		const result = await setup(directory, { database: "present" });
		assert.equal(result.exitCode, 1);
		assert.match(result.output, /Set HEPHAESTUS_SECURITY_CREDENTIAL_ENCRYPTION_KEY to the value/u);
	},
);
