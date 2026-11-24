import { readFileSync } from "node:fs";
import process from "node:process";

import { parse } from "yaml";

import {
	API_OPERATION_REGISTRY,
	apiOperationClassifications,
	apiOperations,
	operationClassificationProblems,
} from "./lib/api-operation-classification.ts";
import { parseJson } from "./lib/json.ts";
import { output } from "./lib/process.ts";

const SPEC = "server/openapi.yaml";
const base = process.argv[2];
if (base === undefined || !/^[0-9a-f]{40}$/u.test(base)) {
	throw new Error("Usage: node scripts/check-api-operation-classification.ts <base-commit-sha>");
}
const baseSpec: unknown = parse(await output("git", ["show", `${base}:${SPEC}`]));
const packagedSpec: unknown = parse(readFileSync(SPEC, "utf8"));
const classes = apiOperationClassifications(
	parseJson(readFileSync(API_OPERATION_REGISTRY, "utf8")),
);
const problems = operationClassificationProblems(
	apiOperations(baseSpec),
	apiOperations(packagedSpec),
	classes,
);
if (problems.length > 0) {
	throw new Error(`Update ${API_OPERATION_REGISTRY}:\n${problems.join("\n")}`);
}
