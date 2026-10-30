import assert from "node:assert/strict";
import { readFile, mkdtemp, mkdir, writeFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";

import { parseAllDocuments, stringify } from "yaml";

import {
	analyse,
	readProfileRoles,
	readDisabledRoles,
	readApplicationConfig,
} from "./check-env-roles.ts";
import {
	applicationImage,
	readDeployContainers,
	renderDeployTopology,
	REPO_ROOT,
} from "./lib/deploy-topology.ts";
import { isRecord } from "./lib/json.ts";

const rendered = renderDeployTopology();
const application = await readFile(
	path.join(REPO_ROOT, "server/application/src/main/resources/application.yml"),
	"utf8",
);
const profiles = await readProfileRoles();
const check = (text: string) => analyse(application, [["Helm", text]], profiles);
function changeEnvironment(
	containerName: string,
	variable: string,
	value?: string,
	source = rendered,
): string {
	const documents = parseAllDocuments(source).map((document) => document.toJS() as unknown);
	const visit = (node: unknown): void => {
		if (!isRecord(node)) {
			return;
		}
		if (node.name === containerName && Array.isArray(node.env)) {
			const environment = node.env.filter(
				(entry: unknown) => !isRecord(entry) || entry.name !== variable,
			);
			if (value !== undefined) {
				environment.push({ name: variable, value });
			}
			node.env = environment;
		}
		for (const child of Object.values(node)) {
			if (Array.isArray(child)) {
				for (const entry of child) {
					visit(entry);
				}
			} else {
				visit(child);
			}
		}
	};
	for (const document of documents) {
		visit(document);
	}
	return documents.map((document) => stringify(document)).join("---\n");
}
await test("the Helm topology supplies four application containers and every scoped setting to its role", () => {
	const result = check(rendered);
	assert.deepEqual(result.failures, []);
	assert.equal(result.applicationContainers.length, 4);
	for (const role of ["server", "webhook", "worker", "migration"]) {
		assert.ok(result.applicationContainers.some((name) => name.endsWith(`:${role}`)));
	}
});
await test("a worker cannot receive the server-only mail setting", () => {
	assert.ok(
		check(changeEnvironment("worker", "SPRING_MAIL_HOST", "relay.example.com")).failures.some(
			(failure) => failure.includes("disables the server role"),
		),
	);
});
await test("an omitted GitLab switch is rejected on the worker", () => {
	assert.ok(
		check(changeEnvironment("worker", "GITLAB_ENABLED")).failures.some(
			(failure) => failure.includes("GITLAB_ENABLED") && failure.includes(":worker"),
		),
	);
});
await test("the webhook and server must share the same routing credential source", () => {
	assert.ok(
		check(changeEnvironment("webhook", "WEBHOOK_ROUTING_SECRET", "different-source")).failures.some(
			(failure) => failure.includes("disagree on WEBHOOK_ROUTING_SECRET"),
		),
	);
});
await test("an empty or invalid render fails closed", () => {
	for (const text of ["", "services: {}", "spec: ["]) {
		assert.ok(check(text).failures.length > 0);
	}
});
await test("rendered Secret references stay references and init containers are inventoried", () => {
	const containers = readDeployContainers(rendered);
	assert.ok(containers.some((container) => container.name.endsWith(":wait-for-postgresql")));
	const server = containers.find((container) => container.name.endsWith(":server"));
	assert.equal(server?.image, applicationImage);
	assert.match(server.environment.get("DATABASE_PASSWORD") ?? "", /secretKeyRef/u);
});
await test("hidden or duplicate environment sources are rejected", () => {
	for (const env of [
		"envFrom: [{secretRef: {name: hidden}}]",
		"env: [{name: DUP, value: a}, {name: DUP, value: b}]",
	]) {
		assert.throws(() =>
			readDeployContainers(
				`kind: Pod\nmetadata: {name: test}\nspec:\n  containers:\n    - name: server\n      image: example\n      ${env}\n`,
			),
		);
	}
});

await test("containers may set different role flags and health paths", () => {
	assert.deepEqual(
		check(changeEnvironment("webhook", "THC_PATH", "/different-health")).failures,
		[],
	);
});
await test("different Secret keys or optional flags cannot describe one routing credential", () => {
	for (const changed of [
		rendered.replace("key: WEBHOOK_ROUTING_SECRET", "key: OTHER_KEY"),
		rendered.replace(
			"key: WEBHOOK_ROUTING_SECRET",
			"key: WEBHOOK_ROUTING_SECRET\n                optional: true",
		),
	]) {
		assert.ok(
			check(changed).failures.some((failure) =>
				failure.includes("disagree on WEBHOOK_ROUTING_SECRET"),
			),
		);
	}
});
await test("a setting scoped to a deleted application path fails loudly", () => {
	const result = analyse(
		application.replace("check-enabled:", "removed-check-enabled:"),
		[["Helm", rendered]],
		profiles,
	);
	assert.ok(
		result.failures.some(
			(failure) =>
				failure.includes("ROLE_SCOPES declares") && failure.includes("release.check-enabled"),
		),
	);
});
await test("required settings fail even when every reader omits them", () => {
	const missing = changeEnvironment(
		"webhook",
		"WEBHOOK_ROUTING_SECRET",
		undefined,
		changeEnvironment("server", "WEBHOOK_ROUTING_SECRET"),
	);
	assert.ok(check(missing).failures.some((failure) => failure.includes("WEBHOOK_ROUTING_SECRET")));
	const stale = analyse(
		application.replaceAll("reference:", "removed-reference:"),
		[["Helm", rendered]],
		profiles,
	);
	assert.ok(stale.failures.some((failure) => failure.includes("REQUIRED_SETTINGS declares")));
});
await test("profiles disable only their named roles and malformed overlays fail closed", async () => {
	assert.deepEqual(
		[
			...readDisabledRoles(
				"hephaestus:\n  runtime:\n    worker: {enabled: false}\n    server: {enabled: true}\n",
			),
		],
		["worker"],
	);
	const root = await mkdtemp(path.join(tmpdir(), "helm-role-profile-"));
	try {
		const resources = path.join(root, "server/application/src/main/resources");
		await mkdir(resources, { recursive: true });
		await writeFile(path.join(resources, "application-worker.yml"), "hephaestus: [broken");
		await assert.rejects(readProfileRoles(root));
	} finally {
		await rm(root, { recursive: true, force: true });
	}
});
await test("a non-worker application container cannot mount the Docker socket", () => {
	const workload = `kind: Pod
metadata: {name: unsafe}
spec:
  volumes: [{name: docker, hostPath: {path: /var/run/docker.sock}}]
  containers:
    - name: server
      image: ${applicationImage}
      env:
        - {name: HEPHAESTUS_RUNTIME_WORKER_ENABLED, value: "false"}
`;
	assert.ok(
		check(workload).failures.some((failure) => failure.includes("mounts /var/run/docker.sock")),
	);
});
await test("another image is excluded from the application settings comparison", () => {
	const other = `---
kind: Pod
metadata: {name: other}
spec:
  containers:
    - name: unrelated
      image: example.invalid/unrelated@sha256:${"b".repeat(64)}
      env: [{name: APPLICATION_HOST_URL, value: different}]
`;
	assert.deepEqual(check(rendered + other).failures, []);
});
await test("nested defaults assign ownership to both variables", () => {
	assert.deepEqual(
		[
			...readApplicationConfig(
				`hephaestus:\n  webhook:\n    stream:\n      max-bytes: \${OUTER:\${INNER:1GB}}\n`,
			).placeholders.keys(),
		],
		["OUTER", "INNER"],
	);
});
await test("the disabled-worker render still checks every remaining role", () => {
	const result = check(renderDeployTopology(REPO_ROOT, false));
	assert.deepEqual(result.failures, []);
	assert.ok(!result.applicationContainers.some((name) => name.endsWith(":worker")));
});
await test("unnamed workloads and non-array environment lists are refused", () => {
	for (const text of [
		"kind: Pod\nspec: {containers: []}\n",
		"kind: Pod\nmetadata: {name: test}\nspec: {containers: [{name: server, image: example, env: {A: b}}]}\n",
	]) {
		assert.throws(() => readDeployContainers(text));
	}
});
