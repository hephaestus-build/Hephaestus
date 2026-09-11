import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";

import { data, Evaluator, Lexer, Parser } from "@actions/expressions";
import { isMap, isScalar, isSeq, parseDocument } from "yaml";

const main = parseDocument(await readFile(".github/workflows/cicd.yml", "utf8"));

function runs(
	job: string,
	outputs: Record<string, string>,
	results: Record<string, string> = {},
	cancelled = false,
) {
	const condition = main.getIn(["jobs", job, "if"]);
	assert.ok(typeof condition === "string");
	const functions = new Map([
		[
			"cancelled",
			{ name: "cancelled", minArgs: 0, maxArgs: 0, call: () => new data.BooleanData(cancelled) },
		],
	]);
	const expression = new Parser(
		new Lexer(condition.replace(/^\$\{\{\s*|\s*}}$/g, "")).lex().tokens,
		["needs", "github"],
		[...functions.values()],
	).parse();
	const context: unknown = JSON.parse(
		JSON.stringify({
			github: { event_name: "pull_request" },
			needs: {
				...Object.fromEntries(
					[
						"detect-changes",
						"server-package",
						"vulnerability-database",
						"application-server-image",
						"Docker",
					].map((name) => [name, { result: results[name] ?? "success" }]),
				),
				"detect-changes": { result: results["detect-changes"] ?? "success", outputs },
			},
		}),
		data.reviver,
	);
	assert.ok(context instanceof data.Dictionary);
	return new Evaluator(expression, context, functions).evaluate().coerceString() === "true";
}

void test("one advisory snapshot is selected for every image-policy consumer, not unpublished fork builds", () => {
	for (const flag of [
		"all-images",
		"application-server-image",
		"supported-host-smoke",
		"webapp-image",
		"agent-images",
		"postgres-image",
		"docker-config",
	]) {
		assert.ok(runs("vulnerability-database", { publishable: "true", [flag]: "true" }), flag);
		assert.ok(
			!runs("vulnerability-database", { publishable: "false", [flag]: "true" }),
			`unpublished ${flag}`,
		);
	}
	for (const flag of ["release-preflight", "release-images"]) {
		assert.ok(runs("vulnerability-database", { publishable: "false", [flag]: "true" }), flag);
		assert.ok(
			!runs("vulnerability-database", { should_skip: "true", [flag]: "true" }),
			`duplicate ${flag}`,
		);
	}
	assert.ok(!runs("vulnerability-database", { publishable: "true", previews: "true" }));
	const fork = { publishable: "false", "all-images": "true", "any-code": "true" };
	for (const result of ["skipped", "failure", "cancelled"]) {
		for (const job of ["Docker", "application-server-image", "Security"]) {
			assert.equal(
				runs(job, fork, { "vulnerability-database": result }),
				result === "skipped",
				`${job}: ${result}`,
			);
			assert.ok(
				!runs(job, fork, { "vulnerability-database": result }, true),
				`${job}: cancellation`,
			);
			assert.ok(!runs(job, fork, { "detect-changes": "failure" }), `${job}: no selection`);
		}
	}
	assert.ok(runs("Docker", { previews: "true" }, { "vulnerability-database": "skipped" }));
	assert.ok(!runs("application-server-image", fork, { "server-package": "failure" }));
	assert.ok(!runs("application-server-image", fork, { "server-package": "skipped" }));
	assert.ok(runs("Release-preflight", { "release-preflight": "true" }));
	assert.ok(!runs("Release-preflight", { "release-preflight": "false" }));
	assert.ok(
		!runs(
			"Release-preflight",
			{ "release-preflight": "true" },
			{ "vulnerability-database": "failure" },
		),
	);
	for (const prerequisite of ["application-server-image", "Docker"]) {
		assert.ok(
			!runs("Release-preflight", { "release-preflight": "true" }, { [prerequisite]: "failure" }),
		);
		assert.ok(
			!runs("Release-preflight", { "release-preflight": "true" }, { [prerequisite]: "skipped" }),
		);
	}
});

void test("every CI image scan receives the producer's immutable artifact ID across reusable workflows", async () => {
	for (const job of ["application-server-image", "Docker", "Security"]) {
		assert.equal(
			main.getIn(["jobs", job, "with", "database-artifact"]),
			`\${{ needs.vulnerability-database.outputs.artifact-id }}`,
		);
		const needs = main.getIn(["jobs", job, "needs"]);
		assert.ok(isSeq(needs));
		assert.ok(
			needs.items.some((item) => isScalar(item) && item.value === "vulnerability-database"),
		);
	}
	for (const name of ["ci-docker-build.yml", "reusable-docker-build.yml", "ci-security-scan.yml"]) {
		const workflow = parseDocument(await readFile(`.github/workflows/${name}`, "utf8"));
		assert.equal(
			workflow.getIn(["on", "workflow_call", "inputs", "database-artifact", "required"]),
			true,
		);
		assert.equal(
			workflow.getIn(["on", "workflow_call", "inputs", "database-artifact", "default"]),
			undefined,
		);
		if (name === "ci-docker-build.yml") {
			for (const job of ["webapp-build", "agent-pi-build", "postgres-build"])
				assert.equal(
					workflow.getIn(["jobs", job, "with", "database-artifact"]),
					`\${{ inputs.database-artifact }}`,
				);
		} else {
			const job = name === "reusable-docker-build.yml" ? "scan" : "upstream-images";
			const steps = workflow.getIn(["jobs", job, "steps"]);
			assert.ok(isSeq(steps));
			const restore = steps.items.find(
				(step) => isMap(step) && step.get("uses") === "./.github/actions/download-trivy-db",
			);
			assert.ok(isMap(restore));
			assert.equal(
				restore.getIn(["with", "database-artifact"]),
				`\${{ inputs.database-artifact }}`,
			);
		}
	}
	const preflight = main.getIn(["jobs", "Release-preflight", "steps"]);
	assert.ok(isSeq(preflight));
	const restore = preflight.items.find(
		(step) => isMap(step) && step.get("uses") === "./.github/actions/download-trivy-db",
	);
	assert.ok(isMap(restore));
	assert.equal(
		restore.getIn(["with", "database-artifact"]),
		`\${{ needs.vulnerability-database.outputs.artifact-id }}`,
	);
	assert.equal(restore.getIn(["with", "max-age-hours"]), "24");
	assert.equal(restore.getIn(["with", "java-db"]), "true");
});

void test("snapshot identity comes from the producer, not the consumer attempt, and its verdict is required", () => {
	const producer = main.getIn(["jobs", "vulnerability-database", "steps"]);
	assert.ok(isSeq(producer));
	const upload = producer.items.find((step) => isMap(step) && step.get("id") === "snapshot");
	assert.ok(isMap(upload));
	assert.match(String(upload.get("uses")), /^actions\/upload-artifact@[a-f0-9]{40}$/);
	assert.equal(
		upload.getIn(["with", "name"]),
		`trivy-db-\${{ github.run_id }}-\${{ github.run_attempt }}`,
	);
	assert.equal(upload.getIn(["with", "overwrite"]), undefined);
	assert.equal(upload.getIn(["with", "path"]), `\${{ steps.database.outputs.database-directory }}`);
	assert.equal(upload.getIn(["with", "if-no-files-found"]), "error");
	assert.equal(upload.getIn(["with", "retention-days"]), 1);
	assert.equal(
		main.getIn(["jobs", "vulnerability-database", "outputs", "artifact-id"]),
		`\${{ steps.snapshot.outputs.artifact-id }}`,
	);
	const needs = main.getIn(["jobs", "all-ci-passed", "needs"]);
	assert.ok(isSeq(needs));
	assert.ok(needs.items.some((item) => isScalar(item) && item.value === "vulnerability-database"));
});
