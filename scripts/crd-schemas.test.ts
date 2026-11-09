import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";

import { crdSchemas } from "./crd-schemas.ts";
import { asRecord } from "./lib/json.ts";

void test("each served version of each definition gets a schema that refuses unknown fields", () => {
	const schemas = crdSchemas(
		[
			"apiVersion: apiextensions.k8s.io/v1",
			"kind: CustomResourceDefinition",
			"spec:",
			"  group: example.io",
			"  names: { kind: Widget }",
			"  versions:",
			"    - name: v1",
			"      served: true",
			"      schema:",
			"        openAPIV3Schema:",
			"          type: object",
			"          properties:",
			"            spec: { type: object, properties: { size: { type: integer } } }",
			"            status: { type: object, x-kubernetes-preserve-unknown-fields: true, properties: { a: {} } }",
			"            labels: { type: object, additionalProperties: { type: string }, properties: { b: {} } }",
			"    - name: v0",
			"      served: false",
			"      schema: { openAPIV3Schema: { type: object } }",
			"---",
			"kind: Namespace",
			"",
		].join("\n"),
	);
	assert.deepEqual([...schemas.keys()], ["example.io/widget_v1.json"]);
	const schema = asRecord(schemas.get("example.io/widget_v1.json"), "schema");
	const property = (name: string) =>
		asRecord(asRecord(schema.properties, "properties")[name], name);
	assert.equal(schema.additionalProperties, false);
	assert.equal(property("spec").additionalProperties, false);
	assert.equal(property("status").additionalProperties, undefined);
	assert.deepEqual(property("labels").additionalProperties, { type: "string" });
});

void test("the platform's agent-sandbox manifest gives a schema for every kind the chart renders", async () => {
	const schemas = crdSchemas(await readFile("deploy/platform/k3s-agent-sandbox.yaml", "utf8"));
	for (const kind of ["sandboxtemplate", "sandboxwarmpool", "sandboxclaim"]) {
		assert.ok(schemas.has(`extensions.agents.x-k8s.io/${kind}_v1beta1.json`), kind);
	}
});
