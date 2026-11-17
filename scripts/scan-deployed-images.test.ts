import assert from "node:assert/strict";
import { test } from "node:test";

import { readJsonFileSync } from "./lib/json.ts";
import { channelTarget, deployedSubjects, promoteIdentity } from "./scan-deployed-images.ts";

const digest = `sha256:${"a".repeat(64)}`;
const inventory = readJsonFileSync("security/release-images.json");

void test("a release channel hands its release to the authenticated release rescan", () => {
	assert.deepEqual(
		channelTarget({ release: "v1.0.0-rc.10", allowRollback: false, freeze: false }, "production"),
		{ kind: "release", release: "v1.0.0-rc.10" },
	);
	assert.throws(() => channelTarget({ release: "main" }, "production"), /malformed release/u);
});

void test("a commit channel names the digests that the host runs", () => {
	const target = channelTarget(
		{
			commit: "9".repeat(40),
			images: { HEPHAESTUS_IMAGE_SERVER: `ghcr.io/hephaestus-build/server@${digest}` },
			freeze: true,
		},
		"staging",
	);
	assert.deepEqual(target, {
		kind: "commit",
		commit: "9".repeat(40),
		images: [`ghcr.io/hephaestus-build/server@${digest}`],
	});
	assert.throws(
		() => channelTarget({ commit: "9".repeat(40), images: {} }, "staging"),
		/no images/u,
	);
});

void test("each deployed image is scanned under the name that the policy keys it by", () => {
	const subjects = deployedSubjects(
		[`ghcr.io/hephaestus-build/server@${digest}`, `docker.io/victoriametrics/vmalert@${digest}`],
		inventory,
		"ghcr.io/hephaestus-build",
	);
	assert.deepEqual(subjects, [
		{
			image: "server",
			reference: `ghcr.io/hephaestus-build/server@${digest}`,
			repository: "ghcr.io/hephaestus-build/server",
		},
		{
			image: "vmalert",
			reference: `docker.io/victoriametrics/vmalert@${digest}`,
			repository: "docker.io/victoriametrics/vmalert",
		},
	]);
});

void test("an image without a digest or a policy name is refused, not scanned under a guess", () => {
	assert.throws(
		() =>
			deployedSubjects(
				["ghcr.io/hephaestus-build/server:main"],
				inventory,
				"ghcr.io/hephaestus-build",
			),
		/not pinned by digest/u,
	);
	assert.throws(
		() =>
			deployedSubjects(
				[`docker.io/library/redis@${digest}`],
				inventory,
				"ghcr.io/hephaestus-build",
			),
		/does not name the deployed image docker\.io\/library\/redis/u,
	);
});

void test("only the promotion workflow on main may sign a channel", () => {
	assert.equal(
		promoteIdentity("hephaestus-build/Hephaestus"),
		"https://github.com/hephaestus-build/Hephaestus/.github/workflows/promote.yml@refs/heads/main",
	);
});
