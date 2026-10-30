import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import path from "node:path";
import { test } from "node:test";

import { parseAllDocuments, stringify } from "yaml";

import { analyse, readProfileRoles } from "./check-env-roles.ts";
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
function changeEnvironment(containerName: string, variable: string, value?: string): string {
	const documents = parseAllDocuments(rendered).map((document) => document.toJS() as unknown);
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
