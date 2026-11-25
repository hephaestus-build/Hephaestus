import { readFileSync } from "node:fs";
import process from "node:process";

import { parse } from "yaml";

import {
	API_OPERATION_CALLERS,
	API_OPERATION_REGISTRY,
	apiOperationClassifications,
	apiOperations,
	operationClassificationProblems,
} from "./lib/api-operation-classification.ts";
import { parseJson } from "./lib/json.ts";

function annotate(file: string, problem: unknown): void {
	const text = problem instanceof Error ? problem.message : String(problem);
	const message = text.replaceAll("%", "%25").replaceAll("\r", "%0D").replaceAll("\n", "%0A");
	console.error(`::error file=${file}::${message}`);
	process.exitCode = 1;
}

function read<T>(file: string, parseFile: (source: string) => T): T | undefined {
	try {
		return parseFile(readFileSync(file, "utf8"));
	} catch (error) {
		annotate(file, error);
		return undefined;
	}
}

const operations = read("server/openapi.yaml", (source) => apiOperations(parse(source)));
const registry = read(API_OPERATION_REGISTRY, parseJson);
if (operations !== undefined && registry !== undefined) {
	try {
		const problems = operationClassificationProblems(
			operations,
			apiOperationClassifications(registry),
		);
		if (problems.length > 0) {
			throw new Error(problems.join("\n"));
		}
	} catch (error) {
		const problem = error instanceof Error ? error.message : String(error);
		const guidance = `Set access to public, internal, or reserved. For public operations, name at least one caller from ${[...API_OPERATION_CALLERS].join(", ")}.`;
		annotate(API_OPERATION_REGISTRY, `${problem}\n${guidance}`);
	}
}
