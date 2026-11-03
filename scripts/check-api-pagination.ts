/**
 * Fails when `server/openapi.yaml` holds a paginated response outside the two page shapes.
 * `server/AGENTS.md` § API and security conventions says which shape a list uses.
 *
 * Removing a required response property needs a major release, so a third shape would stay for the
 * whole of a major version.
 */
import { readFile } from "node:fs/promises";
import path from "node:path";

import { parse } from "yaml";

import { isRecord } from "./lib/json.ts";

const ROOT = path.resolve(import.meta.dirname, "..");
const SPEC = "server/openapi.yaml";

const SHAPES = {
	offset: {
		prefix: "OffsetPageDTO",
		properties: ["content", "page", "size", "totalElements", "totalPages"],
		required: ["content", "page", "size", "totalElements", "totalPages"],
	},
	cursor: {
		prefix: "CursorPageDTO",
		properties: ["content", "nextCursor"],
		required: ["content"],
	},
} as const;

// `hasMore` is absent: a list truncated to a preview says it holds less, and offers no way to read on.
// An operation that does offer a way to read on is caught by its parameters.
const PAGING_PROPERTIES = new Set([
	"earlierBefore",
	"hasNext",
	"hasNextPage",
	"hasPrevious",
	"nextCursor",
	"nextPage",
	"numberOfElements",
	"pageable",
	"previousCursor",
	"previousPage",
	"totalElements",
	"totalPages",
]);

// A list reads on with `cursor` or `page`, and `size` bounds either. These names are another way to
// page. A time filter is named `from` and `to`.
const OTHER_PAGING_PARAMETERS = new Set([
	"after",
	"before",
	"limit",
	"offset",
	"pageSize",
	"pageable",
	"perPage",
]);

const SCHEMA_REF = "#/components/schemas/";

const refName = (schema: unknown): string | undefined => {
	if (!isRecord(schema) || typeof schema.$ref !== "string") {
		return undefined;
	}
	const ref = schema.$ref;
	return ref.startsWith(SCHEMA_REF) ? ref.slice(SCHEMA_REF.length) : undefined;
};

const sameMembers = (actual: readonly string[], expected: readonly string[]) =>
	actual.length === expected.length && expected.every((name) => actual.includes(name));

const recordOf = (value: unknown) => (isRecord(value) ? value : {});

const stringsOf = (value: unknown) =>
	Array.isArray(value) ? value.filter((entry) => typeof entry === "string") : [];

function schemaProblems(name: string, schema: unknown): string[] {
	const properties = Object.keys(recordOf(recordOf(schema).properties));
	const required = stringsOf(recordOf(schema).required);
	const shape = Object.values(SHAPES).find(({ prefix }) => name.startsWith(prefix));
	if (shape !== undefined) {
		return sameMembers(properties, shape.properties) && sameMembers(required, shape.required)
			? []
			: [
					`${name}: expected the properties ${shape.properties.join(", ")} with ${shape.required.join(", ")} required`,
				];
	}
	const paging = properties.filter((property) => PAGING_PROPERTIES.has(property));
	return paging.length === 0
		? []
		: [`${name}: ${paging.join(", ")} belong to a page. Use OffsetPageDTO or CursorPageDTO.`];
}

/** The success body, and the page shape it is or holds in one of its properties. */
function pagesOf(operation: Record<string, unknown>, schemas: Record<string, unknown>) {
	const pages: string[] = [];
	for (const [status, response] of Object.entries(recordOf(operation.responses))) {
		if (!status.startsWith("2")) {
			continue;
		}
		for (const media of Object.values(recordOf(recordOf(response).content))) {
			const name = refName(recordOf(media).schema);
			if (name === undefined) {
				continue;
			}
			pages.push(name);
			for (const property of Object.values(recordOf(recordOf(schemas[name]).properties))) {
				pages.push(refName(property) ?? "");
			}
		}
	}
	return pages;
}

function operationProblems(
	label: string,
	operation: Record<string, unknown>,
	schemas: Record<string, unknown>,
): string[] {
	const parameters = Array.isArray(operation.parameters) ? operation.parameters : [];
	const names = parameters
		.map((parameter) => recordOf(parameter))
		.filter((parameter) => parameter.in === "query")
		.map((parameter) => parameter.name)
		.filter((name) => typeof name === "string");
	const problems = names
		.filter((name) => OTHER_PAGING_PARAMETERS.has(name))
		.map((name) => `${label}: the parameter ${name} pages a list. Use cursor or page, with size.`);
	const pages = pagesOf(operation, schemas);
	const expects = (parameter: string, prefix: string) =>
		names.includes(parameter) && !pages.some((name) => name.startsWith(prefix))
			? [`${label}: a ${parameter} parameter needs a ${prefix} response.`]
			: [];
	return [
		...problems,
		...expects("page", SHAPES.offset.prefix),
		...expects("cursor", SHAPES.cursor.prefix),
	];
}

export function paginationProblems(spec: unknown): string[] {
	const schemas = recordOf(recordOf(recordOf(spec).components).schemas);
	const problems = Object.entries(schemas).flatMap(([name, schema]) =>
		schemaProblems(name, schema),
	);
	for (const [route, item] of Object.entries(recordOf(recordOf(spec).paths))) {
		for (const [method, operation] of Object.entries(recordOf(item))) {
			if (isRecord(operation)) {
				problems.push(...operationProblems(`${method.toUpperCase()} ${route}`, operation, schemas));
			}
		}
	}
	return problems;
}

if (import.meta.main) {
	const problems = paginationProblems(parse(await readFile(path.join(ROOT, SPEC), "utf8")));
	for (const problem of problems) {
		process.stderr.write(`${problem}\n`);
	}
	if (problems.length > 0) {
		process.stderr.write("See server/AGENTS.md § API and security conventions.\n");
		process.exitCode = 1;
	} else {
		process.stdout.write("check-api-pagination: every paginated response has a page shape.\n");
	}
}
