import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import {
	chmodSync,
	copyFileSync,
	existsSync,
	mkdirSync,
	mkdtempSync,
	readdirSync,
	readFileSync,
	rmSync,
	statSync,
	writeFileSync,
} from "node:fs";
import os from "node:os";
import path from "node:path";
import { describe, test } from "node:test";
import { runInNewContext } from "node:vm";

import { parseJson } from "./lib/json.ts";

/**
 * A copy of the runtime image's file layout: the build output and the environment source are
 * read-only, and only the runtime directory may be written, as under the production image's flags.
 */
interface Fixture {
	html: string;
	runtime: string;
	envTs: string;
	image: string;
}

const INDEX_TEMPLATE = readFileSync("webapp/index.html", "utf8");

/** The build's index with every env-config reference pointing at `envConfig`, and nothing else. */
const indexFor = (envConfig: string) =>
	INDEX_TEMPLATE.replaceAll(/\/env-config(?:\.[a-f0-9]{8})?\.js/gu, `/${envConfig}`);

function createFixture(): Fixture {
	const directory = mkdtempSync(path.join(os.tmpdir(), "workspace-address-entrypoint-"));
	const image = path.join(directory, "image");
	const html = path.join(image, "html");
	mkdirSync(path.join(html, "assets"), { recursive: true });
	writeFileSync(path.join(html, "index.html"), INDEX_TEMPLATE);
	writeFileSync(path.join(html, "assets/app.0123abcd.js"), "console.log('app');\n");
	const envTs = path.join(image, "environment.ts");
	copyFileSync("webapp/src/environment/index.ts", envTs);
	setWritable(image, false);
	// The services mount this directory empty, with mode 0700 for the image's user.
	const runtime = path.join(directory, "runtime");
	mkdirSync(runtime, { mode: 0o700 });
	return { html, runtime, envTs, image };
}

function setWritable(root: string, writable: boolean) {
	for (const entry of [".", ...readdirSync(root, { recursive: true, encoding: "utf8" })]) {
		const file = path.join(root, entry);
		chmodSync(file, fixtureMode(statSync(file).isDirectory(), writable));
	}
}

/** Directories stay traversable either way; only the write bits change. */
function fixtureMode(directory: boolean, writable: boolean): number {
	if (directory) {
		return writable ? 0o755 : 0o555;
	}
	return writable ? 0o644 : 0o444;
}

function removeFixture(fixture: Fixture) {
	setWritable(fixture.image, true);
	rmSync(path.dirname(fixture.image), { recursive: true, force: true });
}

/** Every file under the image layout and its bytes, to prove that a run changed none of them. */
function imageBytes(fixture: Fixture): Map<string, string> {
	const files = new Map<string, string>();
	for (const entry of readdirSync(fixture.image, { recursive: true, encoding: "utf8" })) {
		const file = path.join(fixture.image, entry);
		if (statSync(file).isFile()) {
			files.set(entry, readFileSync(file).toString("base64"));
		}
	}
	return files;
}

function runEntrypoint(fixture: Fixture, env: Record<string, string>) {
	const script = readFileSync("webapp/docker/entrypoint.sh", "utf8")
		.replaceAll("/usr/share/nginx/html", fixture.html)
		.replaceAll("/tmp/hephaestus-webapp", fixture.runtime)
		.replaceAll("/app/src/environment/index.ts", fixture.envTs);
	const before = imageBytes(fixture);
	const result = spawnSync("bash", [], {
		input: script,
		encoding: "utf8",
		timeout: 10_000,
		env: { PATH: process.env.PATH, ...env },
	});
	assert.deepEqual(imageBytes(fixture), before, "the entrypoint changed the image's files");
	const runtimeFiles = existsSync(fixture.runtime)
		? readdirSync(fixture.runtime, { recursive: true, encoding: "utf8" }).toSorted()
		: [];
	const envConfigs = runtimeFiles.filter((name) => /^env-config\.[a-f0-9]{8}\.js$/u.test(name));
	return { ...result, runtimeFiles, envConfigs };
}

/** What the served index, env config and discovery file tell a browser. */
function served(fixture: Fixture, envConfigs: string[]) {
	assert.equal(envConfigs.length, 1, `one env config, found ${envConfigs.join(", ")}`);
	const [envConfig = ""] = envConfigs;
	const index = readFileSync(path.join(fixture.runtime, "index.html"), "utf8");
	const context: { window: { __ENV__?: unknown } } = { window: {} };
	runInNewContext(readFileSync(path.join(fixture.runtime, envConfig), "utf8"), context);
	const environment = context.window.__ENV__;
	assert.ok(typeof environment === "object" && environment !== null);
	return {
		envConfig,
		index,
		environment: new Map(Object.entries(environment)),
		discovery: parseJson(
			readFileSync(path.join(fixture.runtime, ".well-known/hephaestus.json"), "utf8"),
		),
	};
}

void describe("webapp container entrypoint", () => {
	void test("workspace addresses require an apex URL before the container writes runtime files", () => {
		const fixture = createFixture();
		try {
			const result = runEntrypoint(fixture, {
				HEPHAESTUS_WORKSPACE_SUBDOMAINS_ENABLED: "true",
				APPLICATION_CLIENT_URL: "",
			});
			assert.equal(result.status, 1);
			assert.match(result.stderr, /APPLICATION_CLIENT_URL is required/u);
			assert.deepEqual(result.runtimeFiles, []);
		} finally {
			removeFixture(fixture);
		}
	});

	void test("an invalid workspace address switch stops the container before it writes files", () => {
		const fixture = createFixture();
		try {
			const result = runEntrypoint(fixture, {
				HEPHAESTUS_WORKSPACE_SUBDOMAINS_ENABLED: "yes",
				APPLICATION_CLIENT_URL: "https://example.invalid",
			});
			assert.equal(result.status, 1);
			assert.match(result.stderr, /Invalid workspace subdomains switch/u);
			assert.deepEqual(result.runtimeFiles, []);
		} finally {
			removeFixture(fixture);
		}
	});

	void test("the container accepts an empty client URL with workspace addresses off", () => {
		const fixture = createFixture();
		try {
			const result = runEntrypoint(fixture, {
				HEPHAESTUS_WORKSPACE_SUBDOMAINS_ENABLED: "false",
				APPLICATION_CLIENT_URL: "",
			});
			assert.equal(result.status, 0, result.stderr);
			const { environment, discovery } = served(fixture, result.envConfigs);
			assert.equal(environment.get("HEPHAESTUS_WORKSPACE_SUBDOMAINS_ENABLED"), "false");
			assert.equal(environment.get("APPLICATION_CLIENT_URL"), "");
			assert.deepEqual(discovery, {
				apexOrigin: "",
				baseDomain: "",
				workspaceSubdomainsEnabled: false,
			});
		} finally {
			removeFixture(fixture);
		}
	});

	void test("the runtime index loads the hashed config and the build output stays as built", () => {
		const fixture = createFixture();
		try {
			const result = runEntrypoint(fixture, {
				HEPHAESTUS_WORKSPACE_SUBDOMAINS_ENABLED: "true",
				HEPHAESTUS_WORKSPACE_SUBDOMAINS_BASE_DOMAIN: "example.invalid",
				APPLICATION_CLIENT_URL: "https://example.invalid",
				APPLICATION_SERVER_URL: "https://example.invalid/api",
			});
			assert.equal(result.status, 0, result.stderr);
			assert.deepEqual(result.runtimeFiles, [
				".well-known",
				path.join(".well-known", "hephaestus.json"),
				...result.envConfigs,
				"index.html",
			]);
			const { envConfig, index, environment, discovery } = served(fixture, result.envConfigs);
			assert.ok(index.includes(`<script src="/${envConfig}"></script>`));
			assert.ok(!index.includes('src="/env-config.js"'));
			assert.equal(index, indexFor(envConfig));
			assert.equal(environment.get("APPLICATION_SERVER_URL"), "https://example.invalid/api");
			assert.deepEqual(discovery, {
				apexOrigin: "https://example.invalid",
				baseDomain: "example.invalid",
				workspaceSubdomainsEnabled: true,
			});
		} finally {
			removeFixture(fixture);
		}
	});

	void test("values that need escaping reach the browser and the discovery file unchanged", () => {
		const fixture = createFixture();
		const tricky = 'https://example.invalid/"quoted"\\path\n\ttab\r';
		try {
			const result = runEntrypoint(fixture, {
				HEPHAESTUS_WORKSPACE_SUBDOMAINS_ENABLED: "true",
				HEPHAESTUS_WORKSPACE_SUBDOMAINS_BASE_DOMAIN: String.raw`a"b\c`,
				APPLICATION_CLIENT_URL: tricky,
			});
			assert.equal(result.status, 0, result.stderr);
			const { environment, discovery } = served(fixture, result.envConfigs);
			assert.equal(environment.get("APPLICATION_CLIENT_URL"), tricky);
			assert.deepEqual(discovery, {
				apexOrigin: tricky,
				baseDomain: String.raw`a"b\c`,
				workspaceSubdomainsEnabled: true,
			});
		} finally {
			removeFixture(fixture);
		}
	});

	void test("every variable of the environment source reaches the browser", () => {
		const fixture = createFixture();
		try {
			const result = runEntrypoint(fixture, { GIT_COMMIT: "", BUILD_GIT_COMMIT: "abc123" });
			assert.equal(result.status, 0, result.stderr);
			const { environment } = served(fixture, result.envConfigs);
			const declared = [
				...readFileSync("webapp/src/environment/index.ts", "utf8").matchAll(
					/^\t(?<name>[A-Z_]+)\?: string;$/gmu,
				),
			].map((match) => {
				const name = match.groups?.name;
				assert.ok(name !== undefined);
				return name;
			});
			assert.ok(declared.length > 0);
			assert.deepEqual([...environment.keys()].toSorted(), declared.toSorted());
			assert.equal(environment.get("GIT_COMMIT"), "abc123");
		} finally {
			removeFixture(fixture);
		}
	});

	void test("the config name changes with its content but not with a production start time", () => {
		const fixture = createFixture();
		const configName = (env: Record<string, string>) => {
			const result = runEntrypoint(fixture, env);
			assert.equal(result.status, 0, result.stderr);
			return served(fixture, result.envConfigs).envConfig;
		};
		try {
			const first = configName({
				APPLICATION_VERSION: "1.0.0",
				DEPLOYED_AT: "2026-01-01T00:00:00Z",
			});
			assert.equal(
				configName({ APPLICATION_VERSION: "1.0.0", DEPLOYED_AT: "2026-02-02T00:00:00Z" }),
				first,
			);
			assert.notEqual(configName({ APPLICATION_VERSION: "1.0.1" }), first);
			const preview = { APPLICATION_VERSION: "1.0.0", GIT_BRANCH: "feature" };
			assert.notEqual(
				configName({ ...preview, DEPLOYED_AT: "2026-01-01T00:00:00Z" }),
				configName({ ...preview, DEPLOYED_AT: "2026-02-02T00:00:00Z" }),
			);
		} finally {
			removeFixture(fixture);
		}
	});

	void test("a restart regenerates the index from the build output and removes the old config", () => {
		const fixture = createFixture();
		try {
			const first = runEntrypoint(fixture, { APPLICATION_VERSION: "1.0.0" });
			assert.equal(first.status, 0, first.stderr);
			const before = served(fixture, first.envConfigs).envConfig;

			const second = runEntrypoint(fixture, { APPLICATION_VERSION: "2.0.0" });
			assert.equal(second.status, 0, second.stderr);
			const { envConfig, index, environment } = served(fixture, second.envConfigs);
			assert.notEqual(envConfig, before);
			assert.ok(!existsSync(path.join(fixture.runtime, before)));
			assert.ok(!index.includes(before));
			assert.equal(index, indexFor(envConfig));
			assert.equal(environment.get("APPLICATION_VERSION"), "2.0.0");

			const restarted = runEntrypoint(fixture, { APPLICATION_VERSION: "2.0.0" });
			assert.equal(restarted.status, 0, restarted.stderr);
			assert.equal(served(fixture, restarted.envConfigs).envConfig, envConfig);
		} finally {
			removeFixture(fixture);
		}
	});
	void test("security.txt names only this deployment's contact and address", () => {
		const fixture = createFixture();
		try {
			const started = Date.now();
			const result = runEntrypoint(fixture, {
				APPLICATION_CLIENT_URL: "https://hephaestus.example.invalid/",
				SECURITY_CONTACT: "mailto:security@example.invalid",
			});
			const finished = Date.now();
			assert.equal(result.status, 0, result.stderr);
			const fields = readFileSync(path.join(fixture.runtime, ".well-known/security.txt"), "utf8")
				.split("\n")
				.filter((line) => line !== "" && !line.startsWith("#"))
				.map((line) => line.split(": ", 2));
			assert.deepEqual(
				fields.map(([field]) => field),
				["Contact", "Canonical", "Expires"],
			);
			const value = (name: string) => fields.find(([field]) => field === name)?.[1];
			assert.equal(value("Contact"), "mailto:security@example.invalid");
			assert.equal(
				value("Canonical"),
				"https://hephaestus.example.invalid/.well-known/security.txt",
			);
			// The file states whole seconds, read from the clock between these two readings.
			const expires = Date.parse(value("Expires") ?? "");
			const span = 364 * 86_400_000;
			assert.ok(expires >= Math.floor(started / 1000) * 1000 + span, value("Expires"));
			assert.ok(expires <= finished + span, value("Expires"));
		} finally {
			removeFixture(fixture);
		}
	});

	void test("security.txt names no canonical host where the file is served on many or without TLS", () => {
		const environments: Record<string, string>[] = [
			{ APPLICATION_CLIENT_URL: "http://localhost:8080" },
			{
				APPLICATION_CLIENT_URL:
					"https://hephaestus.example.invalid/\nContact: mailto:x@attacker.invalid",
			},
			{
				APPLICATION_CLIENT_URL: "https://hephaestus.example.invalid",
				HEPHAESTUS_WORKSPACE_SUBDOMAINS_ENABLED: "true",
				HEPHAESTUS_WORKSPACE_SUBDOMAINS_BASE_DOMAIN: "hephaestus.example.invalid",
			},
		];
		for (const env of environments) {
			const fixture = createFixture();
			try {
				const result = runEntrypoint(fixture, {
					...env,
					SECURITY_CONTACT: "mailto:security@example.invalid",
				});
				assert.equal(result.status, 0, result.stderr);
				const body = readFileSync(path.join(fixture.runtime, ".well-known/security.txt"), "utf8");
				assert.ok(!body.includes("Canonical:"), body);
				assert.ok(!body.includes("attacker"), body);
			} finally {
				removeFixture(fixture);
			}
		}
	});

	void test("without a security contact there is no security.txt, also after a restart", () => {
		const fixture = createFixture();
		try {
			const contact = { SECURITY_CONTACT: "mailto:security@example.invalid" };
			assert.equal(runEntrypoint(fixture, contact).status, 0);
			const result = runEntrypoint(fixture, {});
			assert.equal(result.status, 0, result.stderr);
			assert.ok(!result.runtimeFiles.includes(path.join(".well-known", "security.txt")));
		} finally {
			removeFixture(fixture);
		}
	});

	void test("a security contact that is not one URI stops the container before it writes files", () => {
		const fixture = createFixture();
		try {
			const result = runEntrypoint(fixture, {
				SECURITY_CONTACT: "mailto:a@example.invalid\nCanonical: https://attacker.invalid/",
			});
			assert.equal(result.status, 1);
			assert.match(result.stderr, /SECURITY_CONTACT must be one/u);
			assert.deepEqual(result.runtimeFiles, []);
		} finally {
			removeFixture(fixture);
		}
	});
});
