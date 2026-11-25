import { asArray, asRecord, asString } from "./json.ts";

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

/** Maps each packaged method and path to its operation ID. */
export function apiOperations(spec: unknown): ReadonlyMap<string, string> {
	const operations = new Map<string, string>();
	const paths = asRecord(asRecord(spec, "OpenAPI").paths, "OpenAPI paths");
	for (const [resource, item] of Object.entries(paths)) {
		const pathItem = asRecord(item, resource);
		for (const [method, raw] of Object.entries(pathItem)) {
			if (HTTP_METHODS.has(method)) {
				const key = `${method.toUpperCase()} ${resource}`;
				operations.set(key, asString(asRecord(raw, key).operationId, `${key} operationId`));
			}
		}
	}
	return operations;
}

export function operationClassificationProblems(
	operations: ReadonlyMap<string, string>,
	classes: ReadonlyMap<string, OperationClassification>,
): string[] {
	return [
		...[...operations.keys()]
			.filter((operation) => !classes.has(operation))
			.map((operation) => `Unclassified API operation: ${operation}`),
		...[...classes.keys()]
			.filter((operation) => !operations.has(operation))
			.map((operation) => `Classified operation is absent from the packaged API: ${operation}`),
	];
}
