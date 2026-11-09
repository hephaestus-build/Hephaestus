/**
 * Writes the JSON Schemas of the resource definitions in a manifest, in kubeconform's layout:
 * `<directory>/<group>/<kind>_<version>.json`, with the kind in lower case. The map keys use `/` on
 * every platform. CI validates the chart's
 * agent-sandbox objects against the definitions of the pinned platform manifest, which no public
 * schema catalog carries.
 *
 * As kubeconform's own converter does in its strict mode, an object schema that lists its properties
 * and allows nothing else by Kubernetes rules (no `additionalProperties`, no preserved unknown fields)
 * refuses other properties.
 *
 *   node scripts/crd-schemas.ts <manifest.yaml> <directory>
 */
import { mkdir, readFile, writeFile } from "node:fs/promises";
import path from "node:path";

import { parseAllDocuments } from "yaml";

import { asArray, asRecord, asString, isRecord } from "./lib/json.ts";

function strict(schema: unknown): unknown {
	if (Array.isArray(schema)) {
		return schema.map(strict);
	}
	if (!isRecord(schema)) {
		return schema;
	}
	const result: Record<string, unknown> = {};
	for (const [key, value] of Object.entries(schema)) {
		result[key] = strict(value);
	}
	if (
		isRecord(schema.properties) &&
		schema.additionalProperties === undefined &&
		schema["x-kubernetes-preserve-unknown-fields"] !== true
	) {
		result.additionalProperties = false;
	}
	return result;
}

/** Each served version of each CustomResourceDefinition, by its kubeconform path. */
export function crdSchemas(source: string): Map<string, unknown> {
	const schemas = new Map<string, unknown>();
	for (const document of parseAllDocuments(source)) {
		const resource: unknown = document.toJS();
		if (!isRecord(resource) || resource.kind !== "CustomResourceDefinition") {
			continue;
		}
		const spec = asRecord(resource.spec, "CRD spec");
		const group = asString(spec.group, "CRD group");
		const kind = asString(asRecord(spec.names, "CRD names").kind, "CRD kind").toLowerCase();
		for (const entry of asArray(spec.versions, "CRD versions")) {
			const version = asRecord(entry, "CRD version");
			if (version.served !== true) {
				continue;
			}
			const schema = asRecord(asRecord(version.schema, "version schema").openAPIV3Schema, "schema");
			const name = asString(version.name, "version name");
			schemas.set(`${group}/${kind}_${name}.json`, strict(schema));
		}
	}
	return schemas;
}

if (import.meta.main) {
	const [manifest, directory] = process.argv.slice(2);
	if (manifest === undefined || directory === undefined) {
		throw new Error("usage: crd-schemas.ts <manifest.yaml> <directory>");
	}
	const schemas = crdSchemas(await readFile(manifest, "utf8"));
	if (schemas.size === 0) {
		throw new Error(`${manifest} defines no resource`);
	}
	for (const [file, schema] of schemas) {
		const target = path.join(directory, ...file.split("/"));
		await mkdir(path.dirname(target), { recursive: true });
		await writeFile(target, `${JSON.stringify(schema)}\n`);
	}
}
