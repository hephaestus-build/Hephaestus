import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import {
	existsSync,
	mkdirSync,
	mkdtempSync,
	readFileSync,
	rmSync,
	statSync,
	writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

import { asRecord, isRecord, parseJson } from "./lib/json.ts";

const SCRIPT = fileURLToPath(new URL("../deploy/platform/hephaestus-keys.sh", import.meta.url));
const GENERATED = [
	"DATABASE_ADMIN_PASSWORD",
	"DATABASE_ADMIN_USERNAME",
	"DATABASE_APP_PASSWORD",
	"DATABASE_OWNER_PASSWORD",
	"HEPHAESTUS_AUTH_STATE_COOKIE_KEY",
	"HEPHAESTUS_CONVERSATION_LOOP_SECRET",
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
// A restart only needs to be logged: the tests read the log. A rollout status fails for a Deployment
// that the state names as unready. Each role has one Deployment unless the state names others.
if (has("rollout")) {
	const unready = (state.unready ?? []).some((name) => args.includes("deployment.apps/hephaestus-" + name));
	process.exit(has("status") && unready ? 1 : 0);
}
if (has("deployment")) {
	const role = /component=([\\w-]+)/.exec(args.join(" "))[1];
	const deployments = state.deployments ?? {
		server: ["server"],
		"conversation-loop": ["conversation-loop"],
	};
	for (const name of deployments[role] ?? []) {
		console.log("deployment.apps/hephaestus-" + name);
	}
	process.exit(0);
}
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
	/** The Deployment names per role; one per role when absent. */
	deployments?: Record<string, string[]>;
	/** The Deployments whose rollout does not finish. */
	unready?: string[];
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
		const values = GENERATED.filter((key) => key !== "DATABASE_ADMIN_USERNAME").map(
			(key) => result.secret[key],
		);
		assert.equal(new Set(values).size, values.length, "no two keys share a value");
		assert.match(result.secret.HEPHAESTUS_SECURITY_ENCRYPTION_KEY ?? "", /^[0-9a-f]{32}$/u);
		assert.match(result.secret.WEBHOOK_SECRET ?? "", /^[0-9a-f]{64}$/u);
		assert.equal(result.secret.DATABASE_ADMIN_USERNAME, "postgres");
		for (const stored of Object.values(result.secret).filter((value) => value !== "postgres")) {
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

		// A prefixed value must not become its bare prefix: "heph" would be a guessable NATS login.
		const { NATS_USERNAME: _user, NATS_PASSWORD: _password, ...withoutNats } = run().secret;
		const brokenNats = run({
			cluster: { secret: encoded(withoutNats) },
			tools: { openssl: "#!/bin/sh\nexit 1\n" },
		});
		assert.notEqual(brokenNats.status, 0);
		assert.deepEqual(brokenNats.secret, withoutNats, "no NATS login was stored");
	},
);

await test(
	"a changed OAuth seed points to login-provider rotation without exposing the secret",
	{ skip },
	() => {
		const first = run({ env: { GITHUB_OAUTH_CLIENT_SECRET: "old-client-secret" } });
		const result = run({
			cluster: { secret: encoded(first.secret) },
			env: { GITHUB_OAUTH_CLIENT_SECRET: "new-client-secret" },
		});
		assert.equal(result.status, 0, result.output);
		assert.equal(result.stdout.trim(), "Changed GITHUB_OAUTH_CLIENT_SECRET");
		assert.equal(result.secret.GITHUB_OAUTH_CLIENT_SECRET, "new-client-secret");
		assert.match(
			result.output,
			/A login provider that already exists keeps its database credential/u,
		);
		assert.match(result.output, /Instance administration \/ Login providers/u);
		assert.match(result.output, /secret-custody#login-provider-secrets/u);
		assert.ok(!result.output.includes("client-secret"));
	},
);

await test(
	"an existing Secret without an OAuth key receives the missing startup credential",
	{ skip },
	() => {
		const first = run();
		assert.equal(first.secret.GITHUB_OAUTH_CLIENT_SECRET, undefined);
		const result = run({
			cluster: { secret: encoded(first.secret) },
			env: { GITHUB_OAUTH_CLIENT_SECRET: "new-client-secret" },
		});
		assert.equal(result.status, 0, result.output);
		assert.equal(result.secret.GITHUB_OAUTH_CLIENT_SECRET, "new-client-secret");
		// The installer already uses this signal to restart the server and seed a missing provider.
		assert.equal(result.stdout.trim(), "Changed GITHUB_OAUTH_CLIENT_SECRET");
		assert.match(result.output, /for an existing provider/u);
		assert.ok(!result.output.includes("new-client-secret"));
	},
);

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
	"a Secret with the single database login before 1.0 is refused, and nothing is generated",
	{ skip },
	() => {
		const first = run();
		const {
			DATABASE_ADMIN_USERNAME: username = "",
			DATABASE_ADMIN_PASSWORD: password = "",
			DATABASE_OWNER_PASSWORD: _owner,
			DATABASE_APP_PASSWORD: _app,
			...rest
		} = first.secret;
		const before = { ...rest, DATABASE_USERNAME: username, DATABASE_PASSWORD: password };
		const result = run({ cluster: { secret: encoded(before), volume: true } });
		assert.notEqual(result.status, 0);
		assert.match(result.output, /Follow the 1\.0 upgrade guide/u);
		assert.deepEqual(result.secret, before);
	},
);

await test("--metrics adds the monitor password only", { skip }, () => {
	const first = run();
	const result = run({ cluster: { secret: encoded(first.secret) }, args: ["--metrics"] });
	assert.equal(result.status, 0, result.output);
	assert.equal(result.stdout.trim(), "Added DATABASE_MONITOR_PASSWORD");
	assert.match(result.secret.DATABASE_MONITOR_PASSWORD ?? "", /^[0-9a-f]{48}$/u);
});

await test(
	"an external database gives its own login, and new encryption keys need --new-keys",
	{ skip },
	() => {
		const login = {
			DATABASE_OWNER_PASSWORD: "owner-password",
			DATABASE_APP_PASSWORD: "database-password",
			NATS_USERNAME: "broker",
			NATS_PASSWORD: "broker-password",
		};
		const withoutLogin = run({ args: ["--external-database", "--new-keys"] });
		assert.notEqual(withoutLogin.status, 0);
		assert.match(withoutLogin.output, /set DATABASE_OWNER_PASSWORD/u);
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
		assert.equal(result.secret.DATABASE_OWNER_PASSWORD, "owner-password");
		assert.equal(result.secret.DATABASE_APP_PASSWORD, "database-password");
		assert.equal(
			result.secret.DATABASE_ADMIN_PASSWORD,
			undefined,
			"the chart never holds its administrator",
		);
		assert.equal(result.secret.NATS_PASSWORD, "broker-password");
		assert.match(result.secret.HEPHAESTUS_SECURITY_ENCRYPTION_KEY ?? "", /^[0-9a-f]{32}$/u);
		assert.ok(!result.output.includes("password"));

		// No login contains a CR. It sits mid-value, because Git Bash drops a trailing CRLF from $(...).
		const carriage = run({
			args: ["--external-database", "--new-keys", "--external-nats"],
			env: { ...login, NATS_PASSWORD: "broker\rpassword" },
		});
		assert.notEqual(carriage.status, 0);
		assert.match(carriage.output, /the value of NATS_PASSWORD holds a line break/u);
		assert.deepEqual(carriage.secret, {});
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

/** A fresh folder for a key file, removed after the test. */
function keyFolder(): { file: (name: string) => string; done: () => void } {
	const folder = mkdtempSync(path.join(tmpdir(), "heph-keys-file-"));
	return {
		file: (name) => path.join(folder, name),
		done: () => rmSync(folder, { recursive: true, force: true }),
	};
}

await test(
	"--export-keys writes exactly the keys a restore needs, readable only by the owner",
	{ skip },
	() => {
		const first = run({ args: ["--backups"] });
		const folder = keyFolder();
		try {
			const file = folder.file("keys.env");
			const result = run({
				cluster: { secret: encoded(first.secret) },
				args: ["--export-keys", file],
			});
			assert.equal(result.status, 0, result.output);
			// Windows has no POSIX mode: Node reports 0666 for any writable file there.
			if (process.platform !== "win32") {
				assert.equal((statSync(file).mode % 0o1000).toString(8), "600");
			}
			const written = Object.fromEntries(
				readFileSync(file, "utf8")
					.split("\n")
					.filter(Boolean)
					.map((line) => [line.slice(0, line.indexOf("=")), line.slice(line.indexOf("=") + 1)]),
			);
			assert.deepEqual(Object.keys(written).toSorted(byName), [
				"BACKUP_CIPHER_PASS",
				"DATABASE_ADMIN_USERNAME",
				"HEPHAESTUS_SECURITY_CREDENTIAL_ENCRYPTION_KEY",
				"HEPHAESTUS_SECURITY_ENCRYPTION_KEY",
			]);
			for (const [key, value] of Object.entries(written)) {
				assert.equal(value, first.secret[key]);
			}
			for (const stored of Object.values(written).filter((value) => value !== "postgres")) {
				assert.ok(!result.output.includes(stored), "the output holds no value");
			}
			assert.deepEqual(result.secret, first.secret, "an export changes nothing");

			const again = run({
				cluster: { secret: encoded(first.secret) },
				args: ["--export-keys", file],
			});
			assert.notEqual(again.status, 0, "an existing file is never overwritten");
			assert.match(again.output, /name a new file/u);
		} finally {
			folder.done();
		}
	},
);

await test(
	"--export-keys refuses a Secret without its encryption keys or with an empty key, and a mixed run",
	{ skip },
	() => {
		const folder = keyFolder();
		try {
			const first = run();
			const { HEPHAESTUS_SECURITY_CREDENTIAL_ENCRYPTION_KEY: _lost, ...partial } = first.secret;
			const lacking = run({
				cluster: { secret: encoded(partial) },
				args: ["--export-keys", folder.file("a.env")],
			});
			assert.notEqual(lacking.status, 0);
			assert.match(lacking.output, /lacks an encryption key/u);
			assert.ok(!existsSync(folder.file("a.env")));

			for (const key of ["HEPHAESTUS_SECURITY_ENCRYPTION_KEY", "DATABASE_ADMIN_USERNAME"]) {
				const empty = run({
					cluster: { secret: encoded({ ...first.secret, [key]: "" }) },
					args: ["--export-keys", folder.file("c.env")],
				});
				assert.notEqual(empty.status, 0);
				assert.match(empty.output, new RegExp(`holds an empty ${key}:`, "u"));
				assert.ok(!existsSync(folder.file("c.env")), "nothing is exported");
			}

			const mixed = run({ args: ["--backups", "--export-keys", folder.file("b.env")] });
			assert.notEqual(mixed.status, 0);
			assert.match(mixed.output, /run alone/u);
		} finally {
			folder.done();
		}
	},
);

await test(
	"--check-keys reports each key as matching or not, and fails on any difference",
	{ skip },
	() => {
		const first = run();
		const folder = keyFolder();
		try {
			const file = folder.file("keys.env");
			assert.equal(
				run({ cluster: { secret: encoded(first.secret) }, args: ["--export-keys", file] }).status,
				0,
			);

			const same = run({
				cluster: { secret: encoded(first.secret) },
				args: ["--check-keys", file],
			});
			assert.equal(same.status, 0, same.output);
			assert.match(same.stdout, /^HEPHAESTUS_SECURITY_ENCRYPTION_KEY: matches$/mu);

			const rotated = {
				...first.secret,
				HEPHAESTUS_SECURITY_CREDENTIAL_ENCRYPTION_KEY: "f".repeat(32),
			};
			const differs = run({ cluster: { secret: encoded(rotated) }, args: ["--check-keys", file] });
			assert.notEqual(differs.status, 0);
			assert.match(
				differs.stdout,
				/^HEPHAESTUS_SECURITY_CREDENTIAL_ENCRYPTION_KEY: differs from the Secret$/mu,
			);
			assert.ok(!differs.output.includes("f".repeat(32)), "the output holds no value");

			// A line break at the end of a Secret value is a difference, though $(...) would drop it.
			const trailing = {
				...first.secret,
				HEPHAESTUS_SECURITY_ENCRYPTION_KEY: `${first.secret.HEPHAESTUS_SECURITY_ENCRYPTION_KEY ?? ""}\n`,
			};
			const broken = run({ cluster: { secret: encoded(trailing) }, args: ["--check-keys", file] });
			assert.notEqual(broken.status, 0);
			assert.match(
				broken.stdout,
				/^HEPHAESTUS_SECURITY_ENCRYPTION_KEY: differs from the Secret$/mu,
			);

			const added = { ...first.secret, BACKUP_CIPHER_PASS: "passphrase" };
			const missing = run({ cluster: { secret: encoded(added) }, args: ["--check-keys", file] });
			assert.notEqual(missing.status, 0);
			assert.match(missing.stdout, /^BACKUP_CIPHER_PASS: not in the file$/mu);
		} finally {
			folder.done();
		}
	},
);

await test("an empty file name is refused, so a backup run never creates keys", { skip }, () => {
	for (const flag of ["--export-keys", "--check-keys"]) {
		const result = run({ args: [flag, ""] });
		assert.notEqual(result.status, 0);
		assert.match(result.output, /needs a file/u);
		assert.deepEqual(result.secret, {}, "no Secret was created");
	}
});

await test(
	"--export-keys refuses a key whose value holds a line break, and prints no part of it",
	{ skip },
	() => {
		const first = run();
		const broken = {
			...first.secret,
			HEPHAESTUS_SECURITY_ENCRYPTION_KEY: "first-half\nsecond-half",
		};
		const folder = keyFolder();
		try {
			const result = run({
				cluster: { secret: encoded(broken) },
				args: ["--export-keys", folder.file("keys.env")],
			});
			assert.notEqual(result.status, 0);
			assert.match(result.output, /line break/u);
			assert.ok(!result.output.includes("half"));
			assert.ok(!existsSync(folder.file("keys.env")));
		} finally {
			folder.done();
		}
	},
);

await test(
	"--check-keys reads a file with CRLF lines, and refuses a malformed or empty file",
	{ skip },
	() => {
		const first = run();
		const folder = keyFolder();
		try {
			const exported = folder.file("keys.env");
			assert.equal(
				run({ cluster: { secret: encoded(first.secret) }, args: ["--export-keys", exported] })
					.status,
				0,
			);
			const crlf = folder.file("crlf.env");
			writeFileSync(crlf, readFileSync(exported, "utf8").replaceAll("\n", "\r\n"));
			const windows = run({
				cluster: { secret: encoded(first.secret) },
				args: ["--check-keys", crlf],
			});
			assert.equal(windows.status, 0, windows.output);

			const malformed = folder.file("malformed.env");
			writeFileSync(malformed, "HEPHAESTUS_SECURITY_ENCRYPTION_KEY\n");
			const bad = run({
				cluster: { secret: encoded(first.secret) },
				args: ["--check-keys", malformed],
			});
			assert.notEqual(bad.status, 0);
			assert.match(bad.output, /not KEY=VALUE/u);

			const empty = folder.file("empty.env");
			writeFileSync(empty, "# no keys\n");
			const none = run({
				cluster: { secret: encoded(first.secret) },
				args: ["--check-keys", empty],
			});
			assert.notEqual(none.status, 0);
			assert.match(none.output, /holds no key/u);
		} finally {
			folder.done();
		}
	},
);

/** A stand-in for a tool that ends each output line with CRLF, as tools on Windows do. */
function windowsTool(tool: string): string {
	const real = spawnSync("sh", ["-c", `command -v ${tool}`], { encoding: "utf8" }).stdout.trim();
	return `#!/bin/sh\n"${real}" "$@" | awk '{ printf "%s\\r\\n", $0 }'\n`;
}

await test(
	"a jq and an openssl that end their lines with CRLF, as on Windows, change no result",
	{ skip },
	() => {
		const windowsJq = windowsTool("jq");
		const first = run({ args: ["--backups"], tools: { openssl: windowsTool("openssl") } });
		assert.equal(first.status, 0, first.output);
		for (const [key, value] of Object.entries(first.secret)) {
			assert.ok(!/[\r\n]/u.test(value), `${key} holds no line break`);
		}
		const second = run({ cluster: { secret: encoded(first.secret) }, tools: { jq: windowsJq } });
		assert.equal(second.status, 0, second.output);
		assert.deepEqual(second.secret, first.secret, "a repeat run keeps every key");
		const folder = keyFolder();
		try {
			const file = folder.file("keys.env");
			const exported = run({
				cluster: { secret: encoded(first.secret) },
				args: ["--export-keys", file],
				tools: { jq: windowsJq },
			});
			assert.equal(exported.status, 0, exported.output);
			assert.ok(!readFileSync(file, "utf8").includes("\r"));
			const checked = run({
				cluster: { secret: encoded(first.secret) },
				args: ["--check-keys", file],
				tools: { jq: windowsJq },
			});
			assert.equal(checked.status, 0, checked.output);
			assert.doesNotMatch(checked.output, /differs|not in/u);
		} finally {
			folder.done();
		}
	},
);

/** A Secret without its routing keys, to prove that a routing rotation changed nothing else. */
const withoutRouting = (secret: Record<string, string>) =>
	Object.fromEntries(Object.entries(secret).filter(([key]) => !key.startsWith("WEBHOOK_ROUTING")));

const restarts = (argv: string) =>
	argv
		.split("\n")
		.filter((line) => line.includes('"rollout"') && line.includes('"restart"'))
		.map((line) => /deployment\.apps\/hephaestus-(?<role>[\w-]+)/u.exec(line)?.groups?.role ?? "");

/** Each rollout command in order, as "restart server" or "status server". */
const rollouts = (argv: string) =>
	argv
		.split("\n")
		.map(
			(line) =>
				/"rollout","(?<verb>\w+)","deployment\.apps\/hephaestus-(?<role>[\w-]+)"/u.exec(line)
					?.groups,
		)
		.filter((groups) => groups !== undefined)
		.map((groups) => `${groups.verb ?? ""} ${groups.role ?? ""}`);

await test(
	"--rotate replaces the state-cookie key alone, and restarts the server",
	{ skip },
	() => {
		const first = run();
		const result = run({
			cluster: { secret: encoded(first.secret) },
			args: ["--rotate", "HEPHAESTUS_AUTH_STATE_COOKIE_KEY"],
			tools: { openssl: windowsTool("openssl") },
		});
		assert.equal(result.status, 0, result.output);
		assert.match(result.secret.HEPHAESTUS_AUTH_STATE_COOKIE_KEY ?? "", /^[A-Za-z0-9+/]{43}=$/u);
		assert.notEqual(
			result.secret.HEPHAESTUS_AUTH_STATE_COOKIE_KEY,
			first.secret.HEPHAESTUS_AUTH_STATE_COOKIE_KEY,
		);
		const { HEPHAESTUS_AUTH_STATE_COOKIE_KEY: _new, ...rest } = result.secret;
		const { HEPHAESTUS_AUTH_STATE_COOKIE_KEY: _old, ...before } = first.secret;
		assert.deepEqual(rest, before, "no other key changed");
		assert.deepEqual(restarts(result.argv), ["server"]);
		assert.ok(!result.output.includes(result.secret.HEPHAESTUS_AUTH_STATE_COOKIE_KEY ?? "-"));
	},
);

await test(
	"--rotate replaces the conversation-loop secret alone, and restarts the server and then the loop",
	{ skip },
	() => {
		const first = run();
		const result = run({
			cluster: { secret: encoded(first.secret) },
			args: ["--rotate", "HEPHAESTUS_CONVERSATION_LOOP_SECRET"],
			tools: { openssl: windowsTool("openssl") },
		});
		assert.equal(result.status, 0, result.output);
		assert.match(result.secret.HEPHAESTUS_CONVERSATION_LOOP_SECRET ?? "", /^[0-9a-f]{64}$/u);
		assert.notEqual(
			result.secret.HEPHAESTUS_CONVERSATION_LOOP_SECRET,
			first.secret.HEPHAESTUS_CONVERSATION_LOOP_SECRET,
		);
		const { HEPHAESTUS_CONVERSATION_LOOP_SECRET: _new, ...rest } = result.secret;
		const { HEPHAESTUS_CONVERSATION_LOOP_SECRET: _old, ...before } = first.secret;
		assert.deepEqual(rest, before, "no other key changed");
		// The server checks the credential that the loop presents, so both restart.
		assert.deepEqual(rollouts(result.argv), [
			"restart server",
			"status server",
			"restart conversation-loop",
			"status conversation-loop",
		]);
		assert.ok(!result.output.includes(result.secret.HEPHAESTUS_CONVERSATION_LOOP_SECRET ?? "-"));
	},
);

await test(
	"--rotate keeps the routing secret as the previous one, and restarts the server that reads it",
	{ skip },
	() => {
		const first = run();
		const result = run({
			cluster: { secret: encoded(first.secret) },
			args: ["--rotate", "WEBHOOK_ROUTING_SECRET"],
		});
		assert.equal(result.status, 0, result.output);
		assert.equal(
			result.secret.WEBHOOK_ROUTING_PREVIOUS_SECRET,
			first.secret.WEBHOOK_ROUTING_SECRET,
		);
		assert.match(result.secret.WEBHOOK_ROUTING_SECRET ?? "", /^[0-9a-f]{64}$/u);
		assert.notEqual(result.secret.WEBHOOK_ROUTING_SECRET, first.secret.WEBHOOK_ROUTING_SECRET);
		assert.deepEqual(
			rollouts(result.argv),
			["restart server", "status server"],
			"the server restarts and is ready again",
		);
		assert.deepEqual(
			withoutRouting(result.secret),
			withoutRouting(first.secret),
			"no other key changed",
		);
		for (const value of [
			result.secret.WEBHOOK_ROUTING_SECRET,
			first.secret.WEBHOOK_ROUTING_SECRET,
		]) {
			assert.ok(!result.output.includes(value ?? "-"), "the output holds no value");
			assert.ok(!result.argv.includes(value ?? "-"), "no kubectl argument holds a value");
		}

		// A second rotation would drop the previous value, so it is refused and changes nothing.
		const again = run({
			cluster: { secret: encoded(result.secret) },
			args: ["--rotate", "WEBHOOK_ROUTING_SECRET"],
		});
		assert.notEqual(again.status, 0);
		assert.match(again.output, /retire it first/u);
		assert.deepEqual(again.secret, result.secret);
		assert.deepEqual(restarts(again.argv), []);

		// A server that never becomes ready fails the run, and the run says so.
		const stuck = run({
			cluster: { secret: encoded(first.secret), unready: ["server"] },
			args: ["--rotate", "WEBHOOK_ROUTING_SECRET"],
		});
		assert.notEqual(stuck.status, 0);
		assert.deepEqual(restarts(stuck.argv), ["server"]);
		assert.match(stuck.output, /hephaestus-server restarted, but it is not ready/u);
	},
);

await test("--rotate refuses every other key, a missing Secret, and a mixed run", { skip }, () => {
	const first = run();
	for (const key of [
		"HEPHAESTUS_SECURITY_ENCRYPTION_KEY",
		"DATABASE_APP_PASSWORD",
		"WEBHOOK_SECRET",
	]) {
		const refused = run({ cluster: { secret: encoded(first.secret) }, args: ["--rotate", key] });
		assert.notEqual(refused.status, 0);
		assert.match(refused.output, /Rotate a value section of Secret custody/u);
		assert.deepEqual(refused.secret, first.secret);
	}
	const missing = run({ args: ["--rotate", "HEPHAESTUS_AUTH_STATE_COOKIE_KEY"] });
	assert.notEqual(missing.status, 0);
	assert.deepEqual(missing.secret, {});
	const mixed = run({ args: ["--rotate", "HEPHAESTUS_AUTH_STATE_COOKIE_KEY", "--backups"] });
	assert.notEqual(mixed.status, 0);
	assert.match(mixed.output, /each run alone/u);
});

await test("--rotate changes nothing unless each role has exactly one Deployment", { skip }, () => {
	const first = run();
	for (const deployments of [{ server: [] }, { server: ["one", "two"] }]) {
		const result = run({
			cluster: { secret: encoded(first.secret), deployments },
			args: ["--rotate", "HEPHAESTUS_AUTH_STATE_COOKIE_KEY"],
		});
		assert.notEqual(result.status, 0);
		assert.match(result.output, /exactly one server Deployment/u);
		assert.deepEqual(result.secret, first.secret);
		assert.deepEqual(restarts(result.argv), []);
	}
});
