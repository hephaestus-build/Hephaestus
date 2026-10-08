import assert from "node:assert/strict";
import { test } from "node:test";

import { deploymentOutcome, deploymentRequest } from "./record-deployment.ts";

const commit = "a".repeat(40);

await test("a release is recorded against its tag, as the production environment", () => {
	const request = deploymentRequest(
		{ release: "v1.2.3", allowRollback: false, freeze: false },
		"Production",
		"octocat",
	);
	assert.ok(request);
	assert.equal(request.ref, "v1.2.3");
	assert.equal(request.auto_merge, false);
	assert.deepEqual(request.required_contexts, []);
	assert.equal(request.production_environment, true);
	assert.deepEqual(request.payload, {
		release: "v1.2.3",
		allow_rollback: false,
		refresh_database_image: false,
		requested_by: "octocat",
	});
});

await test("a commit is recorded against itself, with what its channel allows", () => {
	const request = deploymentRequest(
		{ release: commit, images: {}, allowRollback: true, freeze: false, refreshDatabaseImage: true },
		"Staging",
		"octocat",
	);
	assert.ok(request);
	assert.equal(request.ref, commit);
	assert.equal(request.production_environment, false);
	assert.equal(
		request.description,
		`commit ${commit.slice(0, 12)} · rollback allowed · database image refresh requested`,
	);
	assert.deepEqual(request.payload, {
		commit,
		allow_rollback: true,
		refresh_database_image: true,
		requested_by: "octocat",
	});
});

await test("a hold records no deployment", () => {
	assert.equal(
		deploymentRequest(
			{ release: "v1.2.3", allowRollback: false, freeze: true },
			"Production",
			"octocat",
		),
		undefined,
	);
});

await test("success claims only the public webapp version", () => {
	const success = deploymentOutcome("success", "success", "1.2.3");
	assert.equal(success.state, "success");
	assert.match(success.description, /public webapp reports 1\.2\.3/u);
	assert.equal(deploymentOutcome("success", "failure", "1.2.3").state, "failure");
	// Without a published channel no host can move.
	assert.equal(deploymentOutcome("failure", "skipped", "1.2.3").state, "error");
	// GitHub rejects a status description longer than 140 characters.
	for (const [published, observed] of [
		["success", "success"],
		["success", "cancelled"],
		["cancelled", "skipped"],
	] as const) {
		assert.ok(deploymentOutcome(published, observed, commit).description.length <= 140);
	}
});
