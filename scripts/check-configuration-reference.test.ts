import assert from "node:assert/strict";
import { test } from "node:test";

import { judge } from "./check-configuration-reference.ts";
import {
	descriptionProblems,
	displayDefault,
	join,
	Metadata,
	placeholders,
	readCompose,
	readEnvExample,
	readPage,
	readSpringBindings,
	renderPage,
	type Sources,
} from "./lib/configuration-reference.ts";

await test("a Spring placeholder and the one nested in its fallback both bind", () => {
	assert.deepEqual(placeholders(`\${ISSUER:http://localhost:\${SERVER_PORT:8080}}`, "spring"), [
		{ name: "ISSUER", fallback: `http://localhost:\${SERVER_PORT:8080}`, required: false },
		{ name: "SERVER_PORT", fallback: "8080", required: false },
	]);
	assert.equal(
		displayDefault(`http://localhost:\${SERVER_PORT:8080}`, "spring"),
		`http://localhost:\${SERVER_PORT}`,
	);
});

await test(`Compose fallbacks and required markers read, and an escaped \`$\${\` is not a placeholder`, () => {
	assert.deepEqual(placeholders(`\${A:-x} \${B?set B} $\${HOSTNAME}.net`, "compose"), [
		{ name: "A", fallback: "x", required: false },
		{ name: "B", required: true },
	]);
});

await test("a comment block describes the keys below it, and the next block starts fresh", () => {
	const keys = readEnvExample(
		".env.example",
		[
			"# --- General ---",
			"# The public hostname.",
			"APP_HOSTNAME=",
			"#APP_HOST_MATCH=",
			"# Challenge type.",
			"ACME_CHALLENGE=http",
			"",
			"ACME_EMAIL=",
		].join("\n"),
	);
	assert.deepEqual(
		keys.map((key) => [key.name, key.description]),
		[
			["APP_HOSTNAME", "The public hostname."],
			["APP_HOST_MATCH", "The public hostname."],
			["ACME_CHALLENGE", "Challenge type."],
			["ACME_EMAIL", ""],
		],
	);
});

const APPLICATION = `
hephaestus:
    agent:
        enabled: \${AGENT_ENABLED:false}
        poll: \${AGENT_POLL:5s}
    host-url: \${APPLICATION_HOST_URL}
    dev:
        seed: \${UNFORWARDED_SEED:false}
---
spring:
    config:
        activate:
            on-profile: local
hephaestus:
    local-only: \${LOCAL_ONLY:x}
`;

const COMPOSE = `
services:
  application-server:
    image: "\${HEPHAESTUS_IMAGE_APPLICATION_SERVER:?lock}"
    mem_limit: \${SERVER_MEM:-5g}
    environment:
      AGENT_ENABLED: \${AGENT_ENABLED:-false}
      HEPHAESTUS_AUTH_BOOTSTRAP_ADMINS: \${HEPHAESTUS_AUTH_BOOTSTRAP_ADMINS:-}
      APPLICATION_HOST_URL: https://\${APP_HOSTNAME:?Set APP_HOSTNAME}
      OLD_NAME: \${OLD_NAME:-1h}
      JAVA_TOOL_OPTIONS: "-Xss1m"
      AGENT_POLL:
  postgres:
    ports: !override []
    environment:
      POSTGRES_PASSWORD: \${POSTGRES_PASSWORD:?required}
`;

const ENV_EXAMPLE = `
# Memory limit of the server container.
#SERVER_MEM=5g
# The public hostname.
APP_HOSTNAME=
# Database password.
POSTGRES_PASSWORD=
# Nothing reads this.
DEAD_KEY=
`;

function fixture(): Sources {
	return {
		bindings: readSpringBindings([["application.yml", APPLICATION]]),
		compose: readCompose([["compose.app.yaml", COMPOSE]]),
		envExamples: readEnvExample(".env.example", ENV_EXAMPLE),
	};
}

const METADATA = new Metadata([
	{
		name: "hephaestus.agent.enabled",
		type: "java.lang.Boolean",
		description: "Turns practice review on",
	},
	{
		name: "hephaestus.auth.bootstrap-admins",
		type: "java.util.List<java.lang.String>",
		description: "Accounts promoted to instance admin on sign-in.",
	},
	{
		name: "hephaestus.host-url",
		type: "java.lang.String",
		description: "The public URL of the instance.",
	},
	{ name: "hephaestus.dev.seed", type: "java.lang.Boolean" },
	{
		name: "hephaestus.agent.poll",
		type: "java.time.Duration",
		description: "How often the worker polls.",
	},
]);

await test("each variable joins its setting, default, required flag and description from its own source", () => {
	const { rows } = join(fixture(), METADATA);
	const row = (variable: string) => rows.find((candidate) => candidate.variable === variable);

	assert.deepEqual(row("AGENT_ENABLED"), {
		kind: "application",
		variable: "AGENT_ENABLED",
		settings: ["hephaestus.agent.enabled"],
		defaultValue: "false",
		required: false,
		services: ["application-server"],
		description: "Turns practice review on.",
	});
	assert.deepEqual(row("HEPHAESTUS_AUTH_BOOTSTRAP_ADMINS")?.settings, [
		"hephaestus.auth.bootstrap-admins",
	]);
	assert.equal(row("SERVER_MEM")?.kind, "compose");
	assert.equal(row("SERVER_MEM")?.description, "Memory limit of the server container.");
	assert.deepEqual(row("SERVER_MEM")?.settings, ["mem_limit"]);
	// A value that Compose builds from a variable describes the variable, not the setting it feeds.
	assert.equal(row("APP_HOSTNAME")?.kind, "compose");
	assert.equal(row("APP_HOSTNAME")?.required, true);
	assert.equal(row("APPLICATION_HOST_URL")?.kind, "unforwarded");
	assert.equal(row("APPLICATION_HOST_URL")?.defaultValue, `Compose: https://\${APP_HOSTNAME}`);
	assert.equal(row("APPLICATION_HOST_URL")?.required, false);
	assert.equal(row("UNFORWARDED_SEED")?.description, "");
	assert.equal(row("LOCAL_ONLY"), undefined);
	// A key without a value passes the `.env` value through; unset, the application's default applies.
	assert.equal(row("AGENT_POLL")?.kind, "application");
	assert.equal(row("AGENT_POLL")?.defaultValue, "5s");
});

await test("a forwarded variable that binds no setting and a key nothing reads are both reported", () => {
	const { unbound, unread } = join(fixture(), METADATA);
	assert.deepEqual(unbound, ["application-server: OLD_NAME"]);
	assert.deepEqual(unread, ["DEAD_KEY"]);
});

await test("the gate reads back the page the generator writes", () => {
	const sources = fixture();
	const { rows } = join(sources, METADATA);
	const page = renderPage(rows);
	assert.deepEqual(
		readPage(page).find((row) => row.variable === "AGENT_ENABLED"),
		{ variable: "AGENT_ENABLED", description: "Turns practice review on." },
	);

	const verdict = judge(new Set([...rows.map((row) => row.variable), "NEW_VARIABLE"]), page);
	assert.deepEqual(verdict.missing, ["NEW_VARIABLE"]);
	assert.deepEqual(verdict.stale, []);
	assert.deepEqual(verdict.undescribed, [
		"HEPHAESTUS_IMAGE_APPLICATION_SERVER",
		"OLD_NAME",
		"UNFORWARDED_SEED",
	]);

	assert.deepEqual(judge(new Set(["AGENT_ENABLED"]), page).stale.includes("SERVER_MEM"), true);
});

await test("a pipe or a brace in a description cannot break the table or the MDX", () => {
	const page = renderPage([
		{
			kind: "compose",
			variable: "APP_HOST_MATCH",
			settings: ["labels"],
			defaultValue: `Host(\`\${APP_HOSTNAME}\`)`,
			required: false,
			services: ["webapp"],
			description: "A matcher such as Host(`a`) || Host(`b`) with {braces}.",
		},
	]);
	assert.match(page, /\\\|\\\| Host/u);
	assert.match(page, /\\\{braces\\\}/u);
	assert.equal(readPage(page)[0]?.variable, "APP_HOST_MATCH");
});

await test("a description that states a default or names Java code is rejected", () => {
	assert.deepEqual(descriptionProblems("Timeout for GraphQL operations."), []);
	assert.deepEqual(descriptionProblems("Runs on gVisor and pgBackRest."), []);
	assert.deepEqual(descriptionProblems("Timeout (default: 30 seconds)."), ["states a default"]);
	assert.deepEqual(descriptionProblems("Paired with `clientId`."), [
		"names a Java identifier: clientId",
	]);
	assert.deepEqual(descriptionProblems("Checked by AgentImagePinGuard."), [
		"names a Java type: AgentImagePinGuard",
	]);
	assert.deepEqual(descriptionProblems("See core.auth.clientsession.InstalledClientRegistry."), [
		"names a Java type: core.auth.clientsession.InstalledClientRegistry",
	]);
});

await test("a field that a later file replaces with `!override` drops what earlier files fed into it", () => {
	const compose = readCompose([
		[
			"compose.core.yaml",
			`services:\n  nats:\n    ports:\n      - "\${NATS_BIND_HOST:-127.0.0.1}:4222:4222"\n`,
		],
		["compose.single-host.yaml", "services:\n  nats:\n    ports: !override []\n"],
	]);
	assert.deepEqual(compose.uses, []);
});
