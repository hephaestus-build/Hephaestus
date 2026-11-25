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

try {
	const operations = apiOperations(parse(readFileSync("server/openapi.yaml", "utf8")));
	const classes = apiOperationClassifications(
		parseJson(readFileSync(API_OPERATION_REGISTRY, "utf8")),
	);
	const problems = operationClassificationProblems(operations, classes);
	if (problems.length > 0) {
		throw new Error(problems.join("\n"));
	}
} catch (error) {
	const problem = error instanceof Error ? error.message : String(error);
	const guidance = `Set access to public, internal, or reserved. For public operations, name at least one caller from ${[...API_OPERATION_CALLERS].join(", ")}.`;
	const message = `${problem}\n${guidance}`
		.replaceAll("%", "%25")
		.replaceAll("\r", "%0D")
		.replaceAll("\n", "%0A");
	console.error(`::error file=${API_OPERATION_REGISTRY}::${message}`);
	process.exitCode = 1;
}
