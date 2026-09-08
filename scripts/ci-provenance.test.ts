import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";

import { isMap, isSeq, parseDocument } from "yaml";

import { asArray, asRecord } from "./lib/json.ts";

void test("image provenance can persist storage records through every reusable-workflow caller", async () => {
	const callers = {
		"cicd.yml": ["Build", "Docker"],
		"ci-build.yml": ["application-server-image"],
		"ci-docker-build.yml": ["webapp-build", "agent-pi-build", "postgres-build"],
		"reusable-docker-build.yml": ["build", "merge"],
	};
	for (const [file, jobs] of Object.entries(callers)) {
		const workflow = parseDocument(await readFile(`.github/workflows/${file}`, "utf8"));
		const permissions = workflow.get("permissions");
		assert.ok(isMap(permissions));
		assert.deepEqual(permissions.toJSON(), {});
		for (const job of jobs) {
			for (const permission of ["id-token", "attestations", "artifact-metadata"]) {
				assert.equal(
					workflow.getIn(["jobs", job, "permissions", permission]),
					"write",
					`${file}: ${job}: ${permission}`,
				);
			}
		}
		if (file !== "reusable-docker-build.yml") continue;
		for (const job of jobs) {
			const steps = workflow.getIn(["jobs", job, "steps"]);
			assert.ok(isSeq(steps));
			const attestation = asArray(steps.toJSON(), "steps")
				.map((step) => asRecord(step, "step"))
				.find((step) => step.name === "Generate build provenance attestation");
			assert.ok(attestation);
			assert.match(String(attestation.uses), /^actions\/attest@[a-f0-9]{40}$/);
			const inputs = asRecord(attestation.with, "attestation inputs");
			assert.equal(inputs["push-to-registry"], true);
			assert.notEqual(inputs["create-storage-record"], false);
			assert.match(String(inputs["subject-digest"]), /outputs\.(manifest-digest|digest)/);
		}
	}
});
