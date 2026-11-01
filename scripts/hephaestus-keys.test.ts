import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

import { asRecord, isRecord, parseJson } from "./lib/json.ts";

const SCRIPT = fileURLToPath(new URL("../deploy/platform/hephaestus-keys.sh", import.meta.url));
const GENERATED = [
	"DATABASE_PASSWORD",
	"DATABASE_USERNAME",
	"HEPHAESTUS_AUTH_STATE_COOKIE_KEY",
	"HEPHAESTUS_SECURITY_CREDENTIAL_ENCRYPTION_KEY",
	"HEPHAESTUS_SECURITY_ENCRYPTION_KEY",
	"NATS_PASSWORD",
	"NATS_USERNAME",
	"WEBHOOK_ROUTING_SECRET",
	"WEBHOOK_SECRET",
];
const byName = (a: string, b: string) => a.localeCompare(b);
// The script needs jq, as the one-host installer does; CI's runners have it.
const skip = spawnSync("jq", ["--version"]).status === 0 ? false : "needs jq";

/**
 * A kubectl that keeps one Secret and the PostgreSQL volume in a JSON file, for the commands that
 * hephaestus-keys.sh runs. It logs each argument list, so a test can prove that no value reached one.
 * FAKE_FAIL makes every call fail, as an unreachable API server does.
 */
const FAKE_KUBECTL = `#!/usr/bin/env node
const fs = require("node:fs");
if (process.env.FAKE_FAIL) {
	console.error("error: the server is unreachable");
	process.exit(1);
}
const state = JSON.parse(fs.readFileSync(process.env.FAKE_STATE, "utf8"));
const args = process.argv.slice(2);
fs.appendFileSync(process.env.FAKE_LOG, JSON.stringify(args) + "\\n");
const save = () => fs.writeFileSync(process.env.FAKE_STATE, JSON.stringify(state));
const has = (word) => args.includes(word);
const b64 = (value) => Buffer.from(value).toString("base64");
if (has("pvc")) {
	if (state.volume) console.log("persistentvolumeclaim/data-hephaestus-postgresql-0");
} else if (has("get") && has("json")) {
	if (state.secret) console.log(JSON.stringify({ data: state.secret }));
} else if (has("create")) {
	// kubectl reads each line of an env file as KEY=VALUE, and keeps quotes and "#" in the value.
	const file = args.find((arg) => arg.startsWith("--from-env-file=")).slice("--from-env-file=".length);
	state.secret = {};
	for (const line of fs.readFileSync(file, "utf8").split("\\n").filter(Boolean)) {
		const at = line.indexOf("=");
		state.secret[line.slice(0, at)] = b64(line.slice(at + 1));
	}
	save();
} else if (has("patch")) {
	const patch = JSON.parse(fs.readFileSync(args[args.indexOf("--patch-file") + 1], "utf8"));
	for (const [key, value] of Object.entries(patch.stringData)) state.secret[key] = b64(value);
	save();
} else {
	process.exit(2);
}
`;

interface Cluster {
	secret?: Record<string, string>;
	volume?: boolean;
}

interface Run {
	cluster?: Cluster;
	args?: string[];
	env?: Record<string, string>;
	/** Commands that shadow the real ones, such as a failing openssl. */
	tools?: Record<string, string>;
}

function run({ cluster = {}, args = [], env = {}, tools = {} }: Run = {}) {
	const directory = mkdtempSync(path.join(tmpdir(), "heph-keys-"));
	try {
		const bin = path.join(directory, "bin");
		mkdirSync(bin);
		for (const [name, body] of Object.entries({ kubectl: FAKE_KUBECTL, ...tools })) {
			writeFileSync(path.join(bin, name), body, { mode: 0o755 });
		}
		const statePath = path.join(directory, "state.json");
		const logPath = path.join(directory, "kubectl.log");
		writeFileSync(statePath, JSON.stringify(cluster));
		writeFileSync(logPath, "");
		const result = spawnSync("sh", [SCRIPT, ...args], {
			encoding: "utf8",
			timeout: 30_000,
			env: {
				PATH: `${bin}${path.delimiter}${process.env.PATH ?? ""}`,
				FAKE_STATE: statePath,
				FAKE_LOG: logPath,
				...env,
			},
		});
		const after = asRecord(parseJson(readFileSync(statePath, "utf8")), "fake cluster state");
		const stored = isRecord(after.secret) ? after.secret : {};
		const secret = Object.fromEntries(
			Object.entries(stored).map(([key, value]) => [
				key,
				Buffer.from(String(value), "base64").toString(),
			]),
		);
		return {
			status: result.status,
			output: `${result.stdout}${result.stderr}`,
			stdout: result.stdout,
			secret,
			argv: readFileSync(logPath, "utf8"),
		};
	} finally {
		rmSync(directory, { recursive: true, force: true });
	}
}

const encoded = (secret: Record<string, string>) =>
	Object.fromEntries(
		Object.entries(secret).map(([key, value]) => [key, Buffer.from(value).toString("base64")]),
	);

await test(
	"a first run creates every generated key, each with its own value, and prints no value",
	{ skip },
	() => {
		const result = run({ env: { GITHUB_OAUTH_CLIENT_SECRET: "client-secret-value" } });
		assert.equal(result.status, 0, result.output);
		assert.deepEqual(
			Object.keys(result.secret).toSorted(byName),
			[...GENERATED, "GITHUB_OAUTH_CLIENT_SECRET"].toSorted(byName),
		);
		const values = GENERATED.filter((key) => key !== "DATABASE_USERNAME").map(
			(key) => result.secret[key],
		);
		assert.equal(new Set(values).size, values.length, "no two keys share a value");
		assert.match(result.secret.HEPHAESTUS_SECURITY_ENCRYPTION_KEY ?? "", /^[0-9a-f]{32}$/u);
		assert.match(result.secret.WEBHOOK_SECRET ?? "", /^[0-9a-f]{64}$/u);
		for (const stored of Object.values(result.secret).filter((value) => value !== "hephaestus")) {
			assert.ok(!result.output.includes(stored), "the output holds no value");
			assert.ok(!result.argv.includes(stored), "no kubectl argument holds a value");
		}
		assert.match(result.stdout, /^Added HEPHAESTUS_SECURITY_ENCRYPTION_KEY$/mu);
	},
);

await test("a second run keeps every key and changes nothing", { skip }, () => {
	const first = run();
	const second = run({ cluster: { secret: encoded(first.secret) } });
	assert.equal(second.status, 0, second.output);
	assert.deepEqual(second.secret, first.secret);
	assert.match(second.stdout, /has every key: nothing changed/u);
	assert.ok(!second.argv.includes("patch"));
});

await test("a missing key is added, and the existing keys keep their values", { skip }, () => {
	const first = run();
	const { WEBHOOK_ROUTING_SECRET: _routing, ...partial } = first.secret;
	const result = run({ cluster: { secret: encoded(partial), volume: true } });
	assert.equal(result.status, 0, result.output);
	assert.equal(result.stdout.trim(), "Added WEBHOOK_ROUTING_SECRET");
	for (const [key, kept] of Object.entries(partial)) {
		assert.equal(result.secret[key], kept);
	}
	assert.match(result.secret.WEBHOOK_ROUTING_SECRET ?? "", /^[0-9a-f]{64}$/u);
});

await test(
	"a database volume without its Secret or an encryption key stops the run and changes nothing",
	{ skip },
	() => {
		const first = run();
		const { HEPHAESTUS_SECURITY_ENCRYPTION_KEY: _lost, ...partial } = first.secret;
		for (const cluster of [{ secret: encoded(partial), volume: true }, { volume: true }]) {
			const result = run({ cluster });
			assert.notEqual(result.status, 0);
			assert.match(result.output, /Restore the Secret from your backup/u);
			assert.deepEqual(result.secret, cluster.secret ? partial : {});
		}
	},
);

await test(
	"a failed API call or a failed generator stops the run and writes no key",
	{ skip },
	() => {
		const unreachable = run({ env: { FAKE_FAIL: "1" } });
		assert.notEqual(unreachable.status, 0);
		assert.deepEqual(unreachable.secret, {});

		const brokenOpenssl = run({ tools: { openssl: "#!/bin/sh\nexit 1\n" } });
		assert.notEqual(brokenOpenssl.status, 0);
		assert.deepEqual(brokenOpenssl.secret, {}, "no key with an empty value");
	},
);

await test("a new client secret replaces the stored one and asks for a restart", { skip }, () => {
	const first = run({ env: { GITHUB_OAUTH_CLIENT_SECRET: "old-client-secret" } });
	const result = run({
		cluster: { secret: encoded(first.secret) },
		env: { GITHUB_OAUTH_CLIENT_SECRET: "new-client-secret" },
	});
	assert.equal(result.status, 0, result.output);
	assert.equal(result.stdout.trim(), "Changed GITHUB_OAUTH_CLIENT_SECRET");
	assert.equal(result.secret.GITHUB_OAUTH_CLIENT_SECRET, "new-client-secret");
	assert.ok(!result.output.includes("client-secret"));
});

await test(
	"a value with quotes, '=', '#', '$' or spaces is stored exactly, on create and on patch",
	{ skip },
	() => {
		const odd = `a"b'c=d #e $f \\g h`;
		const created = run({ env: { GITHUB_OAUTH_CLIENT_SECRET: odd } });
		assert.equal(created.status, 0, created.output);
		assert.equal(created.secret.GITHUB_OAUTH_CLIENT_SECRET, odd);
		const { GITHUB_OAUTH_CLIENT_SECRET: _old, ...rest } = created.secret;
		const patched = run({
			cluster: { secret: encoded(rest) },
			env: { GITHUB_OAUTH_CLIENT_SECRET: odd },
		});
		assert.equal(patched.status, 0, patched.output);
		assert.equal(patched.secret.GITHUB_OAUTH_CLIENT_SECRET, odd);
	},
);

await test("--backups adds the backup passphrase only", { skip }, () => {
	const first = run();
	const result = run({ cluster: { secret: encoded(first.secret) }, args: ["--backups"] });
	assert.equal(result.status, 0, result.output);
	assert.equal(result.stdout.trim(), "Added BACKUP_CIPHER_PASS");
	assert.match(result.secret.BACKUP_CIPHER_PASS ?? "", /^[A-Za-z0-9+/=]{64}$/u);
});

await test(
	"an external database gives its own login, and new encryption keys need --new-keys",
	{ skip },
	() => {
		const login = {
			DATABASE_USERNAME: "app",
			DATABASE_PASSWORD: "database-password",
			NATS_USERNAME: "broker",
			NATS_PASSWORD: "broker-password",
		};
		const withoutLogin = run({ args: ["--external-database", "--new-keys"] });
		assert.notEqual(withoutLogin.status, 0);
		assert.match(withoutLogin.output, /set DATABASE_USERNAME/u);
		assert.deepEqual(withoutLogin.secret, {});

		const withoutChoice = run({ args: ["--external-database", "--external-nats"], env: login });
		assert.notEqual(withoutChoice.status, 0);
		assert.match(withoutChoice.output, /restore both keys from your backup/u);
		assert.deepEqual(withoutChoice.secret, {});

		const result = run({
			args: ["--external-database", "--new-keys", "--external-nats"],
			env: login,
		});
		assert.equal(result.status, 0, result.output);
		assert.equal(result.secret.DATABASE_PASSWORD, "database-password");
		assert.equal(result.secret.NATS_PASSWORD, "broker-password");
		assert.match(result.secret.HEPHAESTUS_SECURITY_ENCRYPTION_KEY ?? "", /^[0-9a-f]{32}$/u);
		assert.ok(!result.output.includes("password"));
	},
);

await test("the installer keeps the Secret only through the script", () => {
	const installer = readFileSync(
		fileURLToPath(new URL("../deploy/platform/k3s-install.sh", import.meta.url)),
		"utf8",
	);
	assert.match(
		installer,
		/sh "\$HERE\/hephaestus-keys\.sh" --namespace "\$NAMESPACE" --secret "\$SECRET"/u,
	);
	assert.doesNotMatch(installer, /has \w+ \|\| add /u, "the installer generates no key itself");
	assert.match(
		installer,
		/for file in hephaestus-keys\.sh /u,
		"the installer requires the script beside it",
	);
});
