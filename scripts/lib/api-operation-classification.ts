import { isDeepStrictEqual } from "node:util";

import { asArray, asRecord, asString, at, isRecord } from "./json.ts";

export const API_OPERATION_REGISTRY = "server/public-api-operations.json";
export const HTTP_METHODS = new Set([
	"get",
	"put",
	"post",
	"delete",
	"options",
	"head",
	"patch",
	"trace",
]);

export const API_OPERATION_CALLERS = new Set([
	"webapp",
	"extension",
	"slack-adapter",
	"server-job",
	"operator",
	"automation",
]);

export type OperationClassification =
	| { access: "public"; callers: readonly string[] }
	| { access: "internal" | "reserved" };

export function apiOperationClassifications(
	value: unknown,
): ReadonlyMap<string, OperationClassification> {
	const classes = new Map<string, OperationClassification>();
	for (const entry of asArray(value, API_OPERATION_REGISTRY)) {
		const item = asRecord(entry, API_OPERATION_REGISTRY);
		const operation = asString(item.operation, "operation");
		if (classes.has(operation)) {
			throw new Error(`Duplicate operation classification: ${operation}`);
		}
		const { access } = item;
		switch (access) {
			case "public": {
				const callers = asArray(item.callers ?? [], `${operation} callers`).map((caller) =>
					asString(caller, `${operation} caller`),
				);
				if (
					callers.length === 0 ||
					new Set(callers).size !== callers.length ||
					callers.some((caller) => !API_OPERATION_CALLERS.has(caller))
				) {
					throw new Error(
						`${operation} needs a distinct, named 1.0 caller from ${[...API_OPERATION_CALLERS].join(", ")}`,
					);
				}
				classes.set(operation, { access, callers });
				break;
			}
			case "internal":
			case "reserved": {
				if (Object.hasOwn(item, "callers")) {
					throw new Error(`${operation} lists public callers with access ${access}`);
				}
				classes.set(operation, { access });
				break;
			}
			default: {
				throw new Error(`Unknown access classification for ${operation}: ${String(access)}`);
			}
		}
	}
	return classes;
}

interface ApiOperation {
	id: string;
	definition: Readonly<Record<string, unknown>>;
	pathParameters: unknown;
	components: ReadonlyMap<string, unknown>;
}

function referencedComponents(spec: unknown, ...nodes: unknown[]): ReadonlyMap<string, unknown> {
	const reached = new Map<string, unknown>();
	const walk = (node: unknown): void => {
		if (isRecord(node)) {
			const { $ref } = node;
			if (typeof $ref === "string" && $ref.startsWith("#/components/") && !reached.has($ref)) {
				const pointer = decodeURIComponent($ref.slice(2))
					.split("/")
					.map((token) => token.replaceAll("~1", "/").replaceAll("~0", "~"));
				const target = at(spec, pointer, $ref);
				reached.set($ref, target);
				walk(target);
			}
		}
		if (Array.isArray(node) || isRecord(node)) {
			for (const child of Object.values(node)) {
				walk(child);
			}
		}
	};
	for (const node of nodes) {
		walk(node);
	}
	return reached;
}

/** The packaged methods and paths, with their definitions, parameters and reachable components. */
export function apiOperations(spec: unknown): ReadonlyMap<string, ApiOperation> {
	const operations = new Map<string, ApiOperation>();
	const paths = asRecord(asRecord(spec, "OpenAPI").paths, "OpenAPI paths");
	for (const [resource, item] of Object.entries(paths)) {
		const pathItem = asRecord(item, resource);
		for (const [method, raw] of Object.entries(pathItem)) {
			if (HTTP_METHODS.has(method)) {
				const key = `${method.toUpperCase()} ${resource}`;
				const definition = asRecord(raw, key);
				operations.set(key, {
					id: asString(definition.operationId, `${key} operationId`),
					definition,
					pathParameters: pathItem.parameters,
					components: referencedComponents(spec, definition, pathItem.parameters),
				});
			}
		}
	}
	return operations;
}

export function operationClassificationProblems(
	base: ReadonlyMap<string, ApiOperation>,
	head: ReadonlyMap<string, ApiOperation>,
	classes: ReadonlyMap<string, OperationClassification>,
): string[] {
	return [
		...[...head]
			.filter(
				([operation, definition]) =>
					!classes.has(operation) && !isDeepStrictEqual(base.get(operation), definition),
			)
			.map(([operation]) => `Unclassified API operation in the generated diff: ${operation}`),
		...[...classes.keys()]
			.filter((operation) => !head.has(operation))
			.map((operation) => `Classified operation is absent from the packaged API: ${operation}`),
	];
}
