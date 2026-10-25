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
	return evaluates(["jobs", job, "if"], outputs, results, cancelled) === "true";
}

/** Evaluates the expression at `at` in cicd.yml, such as a job condition or a job input. */
function evaluates(
	at: string[],
	outputs: Record<string, string>,
	results: Record<string, string> = {},
	cancelled = false,
) {
	const condition = main.getIn(at);
	assert.ok(typeof condition === "string");
	const functions = new Map([
		[
			"cancelled",
			{ name: "cancelled", minArgs: 0, maxArgs: 0, call: () => new data.BooleanData(cancelled) },
		],
	]);
	const expression = new Parser(
		new Lexer(condition.replaceAll(/^\$\{\{\s*|\s*\}\}$/gu, "")).lex().tokens,
		["needs", "github"],
		[...functions.values()],
	).parse();
	const context: unknown = JSON.parse(
		JSON.stringify({
			github: { event_name: "pull_request" },
			needs: {
				...Object.fromEntries(
					["detect-changes", "server-package", "vulnerability-database", "Docker"].map((name) => [
						name,
						{ result: results[name] ?? "success" },
					]),
				),
				"detect-changes": { result: results["detect-changes"] ?? "success", outputs },
			},
		}),
		data.reviver,
	);
	assert.ok(context instanceof data.Dictionary);
	return new Evaluator(expression, context, functions).evaluate().coerceString();
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
		assert.ok(
			runs("vulnerability-database", { publishable: "true", images: "true", [flag]: "true" }),
			flag,
		);
		assert.ok(
			!runs("vulnerability-database", { publishable: "false", images: "true", [flag]: "true" }),
			`unpublished ${flag}`,
		);
		assert.ok(
			!runs("vulnerability-database", { publishable: "true", images: "false", [flag]: "true" }),
			`${flag} without images`,
		);
	}
	for (const flag of ["release-preflight", "release-images"]) {
		assert.ok(runs("vulnerability-database", { publishable: "false", [flag]: "true" }), flag);
		assert.ok(
			!runs("vulnerability-database", { should_skip: "true", [flag]: "true" }),
			`duplicate ${flag}`,
		);
	}
	assert.ok(!runs("vulnerability-database", { publishable: "true", images: "true" }));
	const fork = {
		publishable: "false",
		images: "true",
		"all-images": "true",
		"any-code": "true",
		"release-images": "true",
	};
	for (const result of ["skipped", "failure", "cancelled"]) {
		// Security reads no advisory snapshot, so it does not wait for one.
		assert.ok(runs("Security", fork, { "vulnerability-database": result }), `Security: ${result}`);
		for (const job of ["Docker", "upstream-images"]) {
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
	assert.ok(
		runs(
			"Docker",
			{ images: "true", "head-images": "true" },
			{ "vulnerability-database": "skipped" },
		),
	);
	// A pull request that needs no head images builds none; the merge group builds them.
	assert.ok(!runs("Docker", { "any-code": "true", "all-images": "true", images: "false" }));
	// The application-server image needs this run's packaged JAR. The other images do not, so the
	// job still builds them when packaging failed or did not run.
	const serverImage = ["jobs", "Docker", "with", "application_server_image"];
	const dockerfileImages = ["jobs", "Docker", "with", "dockerfile_images"];
	assert.equal(evaluates(serverImage, fork), "true");
	for (const result of ["failure", "skipped"]) {
		assert.ok(runs("Docker", fork, { "server-package": result }), result);
		assert.equal(evaluates(serverImage, fork, { "server-package": result }), "false", result);
		assert.equal(evaluates(dockerfileImages, fork, { "server-package": result }), "true", result);
	}
	// The application-server image alone, for a change no Dockerfile image reads.
	const serverOnly = { images: "true", "application-server-image": "true" };
	assert.ok(runs("Docker", serverOnly));
	assert.equal(evaluates(serverImage, serverOnly), "true");
	assert.equal(evaluates(dockerfileImages, serverOnly), "false");
	assert.ok(!runs("Docker", serverOnly, { "server-package": "skipped" }));
	assert.ok(runs("Release-preflight", { "release-preflight": "true" }));
	assert.ok(!runs("Release-preflight", { "release-preflight": "false" }));
	assert.ok(
		!runs(
			"Release-preflight",
			{ "release-preflight": "true" },
			{ "vulnerability-database": "failure" },
		),
	);
	for (const prerequisite of ["Docker"]) {
		assert.ok(
			!runs("Release-preflight", { "release-preflight": "true" }, { [prerequisite]: "failure" }),
		);
		assert.ok(
			!runs("Release-preflight", { "release-preflight": "true" }, { [prerequisite]: "skipped" }),
		);
	}
});

void test("every CI image scan receives the producer's immutable artifact ID across reusable workflows", async () => {
	for (const job of ["Docker", "upstream-images"]) {
		if (job !== "upstream-images") {
			assert.equal(
				main.getIn(["jobs", job, "with", "database-artifact"]),
				`\${{ needs.vulnerability-database.outputs.artifact-id }}`,
			);
		}
		const needs = main.getIn(["jobs", job, "needs"]);
		assert.ok(isSeq(needs));
		assert.ok(
			needs.items.some((item) => isScalar(item) && item.value === "vulnerability-database"),
		);
	}
	for (const job of ["upstream-images", "Release-preflight"]) {
		const steps = main.getIn(["jobs", job, "steps"]);
		assert.ok(isSeq(steps));
		const restore = steps.items.find(
			(step) => isMap(step) && step.get("uses") === "./.github/actions/download-trivy-db",
		);
		assert.ok(isMap(restore));
		assert.equal(
			restore.getIn(["with", "database-artifact"]),
			`\${{ needs.vulnerability-database.outputs.artifact-id }}`,
		);
	}
	for (const name of ["ci-docker-build.yml", "reusable-docker-build.yml"]) {
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
			assert.equal(
				workflow.getIn(["jobs", "images", "with", "database-artifact"]),
				`\${{ inputs.database-artifact }}`,
			);
		} else {
			const steps = workflow.getIn(["jobs", "build", "steps"]);
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
	assert.equal(restore.getIn(["with", "max-age-hours"]), "48");
	assert.equal(restore.getIn(["with", "java-db"]), "true");
});

void test("snapshot identity comes from the producer, not the consumer attempt, and its verdict is required", () => {
	const producer = main.getIn(["jobs", "vulnerability-database", "steps"]);
	assert.ok(isSeq(producer));
	const upload = producer.items.find((step) => isMap(step) && step.get("id") === "snapshot");
	assert.ok(isMap(upload));
	assert.match(String(upload.get("uses")), /^actions\/upload-artifact@[a-f0-9]{40}$/u);
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
