import assert from "node:assert/strict";
import { mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { test } from "node:test";

import {
	analyse,
	type ComposeFile,
	composeDefault,
	readComposeServices,
	readDisabledRoles,
	readProfileRoles,
} from "./check-env-roles.ts";

const REPO_ROOT = resolve(import.meta.dirname, "..");

/**
 * The failure at `index`, asserting there is one. `assert.match` reads a string, and a run that
 * produced fewer failures than the test names is the defect itself, so it fails here saying which.
 */
function failureAt(failures: readonly string[], index: number): string {
	const failure = failures[index];
	assert.ok(failure !== undefined, `no failure at [${index}] among:\n${failures.join("\n")}`);
	return failure;
}

/**
 * Carries every path ROLE_SCOPES and DEPLOYMENT_WIDE name, so a scope that goes stale fails loudly
 * rather than here.
 */
const APPLICATION = `
hephaestus:
    sandbox:
        docker:
            host: unix:///var/run/docker.sock
        gateway:
            port: 8081
            max-request-bytes: 4194304
            requests-per-minute: 120
    agent:
        image:
            reference: ghcr.io/hephaestus-build/agent-pi:1.2.3
    webhook:
        secret: \${WEBHOOK_SECRET:}
        stream:
            max-bytes: \${HEPHAESTUS_WEBHOOK_STREAM_MAX_BYTES:1073741824}
        publish:
            timeout: 9s
        shutdown:
            drain-timeout: 15s
        http:
            max-payload-bytes: 26214400
    integration:
        consumer:
            inactive-threshold: 30d
        github:
            token: \${GH_AUTH_TOKEN:}
`;

const compose = (services: string): ComposeFile[] => [["compose.yaml", `services:\n${services}`]];

/** A container that runs the webhook role, so the scoped variable reaches somewhere that reads it. */
const RECEIVER = `  webhook-server:
    environment:
      HEPHAESTUS_RUNTIME_WEBHOOK_ENABLED: "true"
      HEPHAESTUS_WEBHOOK_STREAM_MAX_BYTES: \${HEPHAESTUS_WEBHOOK_STREAM_MAX_BYTES:-1073741824}
`;

await test("a variable set on a container that disables the role reading it is a failure", () => {
	const { failures } = analyse(
		APPLICATION,
		compose(`  application-server:
    environment:
      HEPHAESTUS_RUNTIME_WEBHOOK_ENABLED: "false"
      HEPHAESTUS_WEBHOOK_STREAM_MAX_BYTES: \${HEPHAESTUS_WEBHOOK_STREAM_MAX_BYTES:-1073741824}
`),
	);

	assert.equal(failures.length, 2, failures.join("\n"));
	assert.match(failureAt(failures, 0), /disables the webhook role that reads it/);
	assert.match(failureAt(failures, 1), /no service running the webhook role receives it/);
});

await test("the role flag is read through its Compose default, not as raw text", () => {
	const { failures } = analyse(
		APPLICATION,
		compose(`  application-server:
    environment:
      HEPHAESTUS_RUNTIME_WEBHOOK_ENABLED: \${HEPHAESTUS_RUNTIME_WEBHOOK_ENABLED:-false}
      HEPHAESTUS_WEBHOOK_STREAM_MAX_BYTES: \${HEPHAESTUS_WEBHOOK_STREAM_MAX_BYTES:-1073741824}
`),
	);

	assert.equal(failures.length, 2, failures.join("\n"));
	assert.match(failureAt(failures, 0), /disables the webhook role that reads it/);
});

await test("removing it from the wrong container without adding it to the right one still fails", () => {
	const { failures } = analyse(
		APPLICATION,
		compose(`  application-server:
    environment:
      HEPHAESTUS_RUNTIME_WEBHOOK_ENABLED: "false"
  webhook-server:
    environment:
      HEPHAESTUS_RUNTIME_WEBHOOK_ENABLED: "true"
`),
	);

	assert.equal(failures.length, 1, failures.join("\n"));
	assert.match(failureAt(failures, 0), /HEPHAESTUS_WEBHOOK_STREAM_MAX_BYTES is offered by/);
	assert.match(failureAt(failures, 0), /no service in the deployment forwards it/);
});

await test("the variable reaching the container that runs its role passes", () => {
	const { failures, delivered } = analyse(
		APPLICATION,
		compose(`  application-server:
    environment:
      HEPHAESTUS_RUNTIME_WEBHOOK_ENABLED: "false"
${RECEIVER}      HEPHAESTUS_RUNTIME_SERVER_ENABLED: "false"
`),
	);

	assert.deepEqual(failures, []);
	assert.deepEqual([...delivered.keys()], ["HEPHAESTUS_WEBHOOK_STREAM_MAX_BYTES"]);
});

await test("a role is on unless the service turns it off, so an unflagged container counts as a reader", () => {
	const { failures } = analyse(
		APPLICATION,
		compose(`  appserver:
    environment:
      HEPHAESTUS_WEBHOOK_STREAM_MAX_BYTES: \${HEPHAESTUS_WEBHOOK_STREAM_MAX_BYTES:-1073741824}
`),
	);

	assert.deepEqual(failures, []);
});

await test("variables outside every declared scope are left alone", () => {
	const { failures, delivered } = analyse(
		APPLICATION,
		compose(`  application-server:
    environment:
      HEPHAESTUS_RUNTIME_WEBHOOK_ENABLED: "false"
      WEBHOOK_SECRET: \${WEBHOOK_SECRET}
      GH_AUTH_TOKEN: \${GH_AUTH_TOKEN}
${RECEIVER}`),
	);

	assert.deepEqual(failures, []);
	assert.equal(delivered.size, 1);
});

await test("a service that merges an anchor delivers every key in it", () => {
	const services = readComposeServices(`x-shared: &shared
  HEPHAESTUS_WEBHOOK_STREAM_MAX_BYTES: 1
x-runtime: &runtime
  HEPHAESTUS_RUNTIME_WEBHOOK_ENABLED: true
services:
  webhook-server:
    environment:
      <<: [*shared, *runtime]
`);

	assert.ok(services.get("webhook-server")?.env.has("HEPHAESTUS_WEBHOOK_STREAM_MAX_BYTES"));
	assert.equal(
		services.get("webhook-server")?.flags.get("HEPHAESTUS_RUNTIME_WEBHOOK_ENABLED"),
		"true",
	);
});

await test("a service writing its environment as a sequence delivers the same keys", () => {
	const services = readComposeServices(`services:
  webhook-server:
    environment:
      - HEPHAESTUS_RUNTIME_WEBHOOK_ENABLED=true
      - HEPHAESTUS_WEBHOOK_STREAM_MAX_BYTES=\${HEPHAESTUS_WEBHOOK_STREAM_MAX_BYTES:-1073741824}
`);

	assert.deepEqual([...(services.get("webhook-server")?.env ?? [])].toSorted(), [
		"HEPHAESTUS_RUNTIME_WEBHOOK_ENABLED",
		"HEPHAESTUS_WEBHOOK_STREAM_MAX_BYTES",
	]);
	assert.equal(
		services.get("webhook-server")?.flags.get("HEPHAESTUS_WEBHOOK_STREAM_MAX_BYTES"),
		"1073741824",
	);
});

await test("a compose file that yields no services is a failure, not a pass", () => {
	const { failures } = analyse(APPLICATION, [["compose.yaml", "name: hephaestus\n"]]);

	assert.ok(
		failures.some((f) => /parsed to zero services/.test(f)),
		failures.join("\n"),
	);
});

await test("a compose file that is not valid YAML fails by name, not by stack trace", () => {
	const { failures } = analyse(APPLICATION, [["compose.yaml", "services: [\n"]]);

	assert.ok(
		failures.some((f) => f.startsWith("compose.yaml is not valid YAML: ")),
		failures.join("\n"),
	);
});

await test("compose defaults resolve the way an operator who sets nothing gets them", () => {
	// `\${` throughout: these are Compose interpolation syntax under test, and a plain string
	// spelling them reads as a template literal someone forgot to make one.
	assert.equal(composeDefault(`\${HEPHAESTUS_RUNTIME_WEBHOOK_ENABLED:-false}`), "false");
	assert.equal(composeDefault(`\${HEPHAESTUS_RUNTIME_WEBHOOK_ENABLED-false}`), "false");
	assert.equal(composeDefault('"false"'), "false");
	assert.equal(composeDefault(`\${WEBHOOK_SECRET}`), "");
});

await test("a role a profile overlay switches off does not count as a reader", () => {
	const profileRoles = new Map([["worker", new Set(["server", "webhook"])]]);

	const { failures } = analyse(
		APPLICATION,
		compose(`  application-worker:
    environment:
      SPRING_PROFILES_ACTIVE: prod,worker
      HEPHAESTUS_WEBHOOK_STREAM_MAX_BYTES: \${HEPHAESTUS_WEBHOOK_STREAM_MAX_BYTES:-1073741824}
`),
		profileRoles,
	);

	assert.equal(failures.length, 2, failures.join("\n"));
	assert.match(failureAt(failures, 0), /disables the webhook role that reads it/);
});

await test("every role an overlay switches off is read, not just the first", () => {
	const disabled = readDisabledRoles(`
hephaestus:
    runtime:
        server:
            enabled: false
        webhook:
            enabled: false
        worker:
            enabled: true

    sync:
        nats:
            enabled: true
`);

	assert.deepEqual([...disabled].toSorted(), ["server", "webhook"]);
});

await test("reads disabled roles from every YAML document", () => {
	const disabled = readDisabledRoles(`
hephaestus:
    runtime:
        server:
            enabled: false
---
spring:
    config:
        activate:
            on-profile: worker
hephaestus:
    runtime:
        webhook:
            enabled: false
`);

	assert.deepEqual([...disabled].toSorted(), ["server", "webhook"]);
});

await test("readProfileRoles rejects malformed YAML", async () => {
	const root = await mkdtemp(join(tmpdir(), "check-env-roles-"));
	try {
		const resources = join(root, "server/application/src/main/resources");
		await mkdir(resources, { recursive: true });
		await writeFile(
			join(resources, "application-worker.yml"),
			"hephaestus: {}\n---\nhephaestus: [",
		);
		await assert.rejects(readProfileRoles(root));
	} finally {
		await rm(root, { recursive: true, force: true });
	}
});

/** The lock digest, spelled as the shipped topology spells it. */
const AGENT_DIGEST = `HEPHAESTUS_AGENT_IMAGE_REFERENCE: \${HEPHAESTUS_IMAGE_AGENT_PI:?verified release lock required}`;

/** Two containers of the one image every role boots from, each given its own environment lines. */
const applicationPair = (server: readonly string[], receiver: readonly string[]): ComposeFile[] => {
	const lines = (env: readonly string[]): string => env.map((line) => `      ${line}`).join("\n");
	return compose(`  application-server:
    image: "\${HEPHAESTUS_IMAGE_APPLICATION_SERVER:?verified release lock required}"
    environment:
      HEPHAESTUS_RUNTIME_WEBHOOK_ENABLED: "false"
${lines(server)}
  webhook-server:
    image: "\${HEPHAESTUS_IMAGE_APPLICATION_SERVER:?verified release lock required}"
    environment:
      HEPHAESTUS_RUNTIME_WEBHOOK_ENABLED: "true"
      HEPHAESTUS_WEBHOOK_STREAM_MAX_BYTES: \${HEPHAESTUS_WEBHOOK_STREAM_MAX_BYTES:-1073741824}
${lines(receiver)}
`);
};

await test("application containers that spell an ungated setting differently fail", () => {
	const { failures, applicationContainers: found } = analyse(
		APPLICATION,
		// Raw spellings must differ even when both default to an empty value.
		applicationPair([AGENT_DIGEST], ["HEPHAESTUS_AGENT_IMAGE_REFERENCE:"]),
	);

	assert.deepEqual(found, ["compose.yaml:application-server", "compose.yaml:webhook-server"]);
	assert.equal(failures.length, 1, failures.join("\n"));
	assert.match(failureAt(failures, 0), /disagree on HEPHAESTUS_AGENT_IMAGE_REFERENCE/);
	assert.match(failureAt(failures, 0), /<nothing>/);
});

await test("an application container that omits a deployment-wide setting fails", () => {
	const { failures } = analyse(APPLICATION, applicationPair([AGENT_DIGEST], []));

	assert.equal(failures.length, 1, failures.join("\n"));
	assert.match(
		failureAt(failures, 0),
		/HEPHAESTUS_AGENT_IMAGE_REFERENCE binds hephaestus\.agent\.image\.reference/,
	);
	assert.match(
		failureAt(failures, 0),
		/run the application image without it:\n {4}compose\.yaml:webhook-server/,
	);
});

await test("a deployment-wide setting no application container is given fails", () => {
	const { failures } = analyse(APPLICATION, applicationPair([], []));

	assert.equal(failures.length, 1, failures.join("\n"));
	assert.match(failureAt(failures, 0), /no container running the application image is given it/);
});

await test("application containers agreeing on an ungated setting pass", () => {
	const { failures } = analyse(APPLICATION, applicationPair([AGENT_DIGEST], [AGENT_DIGEST]));

	assert.deepEqual(failures, []);
});

await test("a setting named in PER_CONTAINER may differ", () => {
	// THC_PATH is in the list: the receiver reports NATS through readiness and the others do not.
	const { failures } = analyse(
		APPLICATION,
		applicationPair(
			[AGENT_DIGEST, "THC_PATH: /actuator/health/liveness"],
			[AGENT_DIGEST, "THC_PATH: /actuator/health/readiness"],
		),
	);

	assert.deepEqual(failures, []);
});

await test("a DEPLOYMENT_WIDE entry naming a path application.yml does not have is a failure", () => {
	const withoutTheSetting = APPLICATION.replace(
		"    agent:\n        image:\n            reference: ghcr.io/hephaestus-build/agent-pi:1.2.3\n",
		"",
	);

	const { failures } = analyse(withoutTheSetting, applicationPair([AGENT_DIGEST], [AGENT_DIGEST]));

	assert.ok(
		failures.some((f) => /DEPLOYMENT_WIDE declares "hephaestus\.agent\.image\.reference"/.test(f)),
		failures.join("\n"),
	);
});

await test("a service running some other image is not compared against the application containers", () => {
	const { failures, applicationContainers: found } = analyse(
		APPLICATION,
		compose(`  application-server:
    image: "\${HEPHAESTUS_IMAGE_APPLICATION_SERVER:?verified release lock required}"
    environment:
      HEPHAESTUS_AGENT_IMAGE_REFERENCE: \${HEPHAESTUS_IMAGE_AGENT_PI:?verified release lock required}
  webapp:
    image: "\${HEPHAESTUS_IMAGE_WEBAPP:?verified release lock required}"
    environment:
      HEPHAESTUS_AGENT_IMAGE_REFERENCE: something-else
${RECEIVER}`),
	);

	assert.deepEqual(failures, []);
	assert.deepEqual(found, ["compose.yaml:application-server"]);
});

await test("a scope naming a path application.yml does not have is a failure", () => {
	const { failures } = analyse("hephaestus:\n    webhook:\n        secret: x\n", compose(RECEIVER));

	assert.ok(failures.some((f) => /hephaestus\.webhook\.stream.*does not have/s.test(f)));
});

await test("the shipped topology delivers every role-scoped variable to a container that runs its role", async () => {
	const files = ["docker/compose.app.yaml", "docker/compose.core.yaml"];
	const shipped = await Promise.all(
		files.map(async (file): Promise<ComposeFile> => [
			file,
			await readFile(join(REPO_ROOT, file), "utf8"),
		]),
	);
	// With the profile overlays, exactly as the CLI runs it. Omitting them makes this pass on a
	// topology the real gate fails, which is the whole defect class the gate is here for.
	const { failures, applicationContainers } = analyse(
		await readFile(
			join(REPO_ROOT, "server/application/src/main/resources/application.yml"),
			"utf8",
		),
		shipped,
		await readProfileRoles(REPO_ROOT),
	);

	assert.deepEqual(failures, []);
	// Named rather than counted: the settings comparison only runs over containers the parse
	// recognised as running the application image, so a renamed lock variable would otherwise retire
	// that half of the gate by finding nothing to compare.
	assert.deepEqual(applicationContainers, [
		"docker/compose.app.yaml:application-server",
		"docker/compose.app.yaml:application-worker",
		"docker/compose.core.yaml:webhook-server",
	]);
});

await test("Docker settings are rejected on a container that disables the worker role", () => {
	const application = APPLICATION.replace(
		"host: unix:///var/run/docker.sock",
		`host: \${SANDBOX_DOCKER_HOST:unix:///var/run/docker.sock}`,
	);
	const { failures } = analyse(
		application,
		compose(`${RECEIVER}  non-worker:
    environment:
      HEPHAESTUS_RUNTIME_WORKER_ENABLED: "false"
      SANDBOX_DOCKER_HOST: unix:///var/run/docker.sock
`),
	);
	assert.equal(failures.length, 2, failures.join("\n"));
	assert.match(failureAt(failures, 0), /disables the worker role that reads it/);
	assert.match(failureAt(failures, 1), /no service running the worker role receives it/);
});

await test("Docker settings must be forwarded to a worker rather than only documented", () => {
	const application = APPLICATION.replace(
		"host: unix:///var/run/docker.sock",
		`host: \${SANDBOX_DOCKER_HOST:unix:///var/run/docker.sock}`,
	);
	const { failures } = analyse(application, compose(RECEIVER));
	assert.equal(failures.length, 1, failures.join("\n"));
	assert.match(failureAt(failures, 0), /SANDBOX_DOCKER_HOST is offered by/);
	assert.match(failureAt(failures, 0), /no service in the deployment forwards it/);
});
