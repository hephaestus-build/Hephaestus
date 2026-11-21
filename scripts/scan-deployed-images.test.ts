import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";

import { environmentForGitFixture } from "./lib/git-environment.ts";
import { asArray, asRecord, readJsonFileSync } from "./lib/json.ts";
import {
	channelTarget,
	commitInventory,
	deployedSubjects,
	promoteIdentity,
} from "./scan-deployed-images.ts";

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
	// A look-alike registry is not first-party, and a nested repository under the registry is no image name.
	assert.throws(
		() =>
			deployedSubjects(
				[`ghcr.io/hephaestus-build-evil/server@${digest}`],
				inventory,
				"ghcr.io/hephaestus-build",
			),
		/does not name the deployed image ghcr\.io\/hephaestus-build-evil\/server/u,
	);
	assert.throws(
		() =>
			deployedSubjects(
				[`ghcr.io/hephaestus-build/charts/server@${digest}`],
				inventory,
				"ghcr.io/hephaestus-build",
			),
		/does not name the deployed image ghcr\.io\/hephaestus-build\/charts\/server/u,
	);
});

const gitEnvironment = environmentForGitFixture();
const git = (cwd: string, ...args: string[]) =>
	execFileSync("git", ["-c", "user.name=t", "-c", "user.email=t@example.com", ...args], {
		cwd,
		encoding: "utf8",
		env: gitEnvironment,
	}).trim();

void test("a commit channel's images are named by the inventory of the commit it deploys, not main's", async () => {
	const directory = await mkdtemp(path.join(tmpdir(), "deployed-inventory-"));
	try {
		const origin = path.join(directory, "origin");
		await mkdir(path.join(origin, "security"), { recursive: true });
		git(origin, "init", "--quiet", "--initial-branch=main");
		// The workflow fetches the deployed commit by its hash, which GitHub allows.
		git(origin, "config", "uploadpack.allowReachableSHA1InWant", "true");
		const current = asRecord(inventory, "inventory");
		const images = asArray(current.images, "images");
		const write = async (names: readonly unknown[]) =>
			writeFile(
				path.join(origin, "security/release-images.json"),
				JSON.stringify({ ...current, images: names }),
			);
		// An older build still ran an image that main no longer builds.
		await write([...images, "webapp"]);
		git(origin, "add", ".");
		git(origin, "commit", "--quiet", "-m", "older build");
		const older = git(origin, "rev-parse", "HEAD");
		await write(images);
		git(origin, "commit", "--quiet", "-am", "main");
		// Shallow, as the workflow's checkout is: the older commit is not in it.
		const checkout = path.join(directory, "checkout");
		git(directory, "clone", "--quiet", "--depth=1", `file://${origin}`, checkout);

		const webApp = `ghcr.io/hephaestus-build/webapp@${digest}`;
		assert.deepEqual(
			deployedSubjects(
				[webApp],
				await commitInventory(older, checkout, gitEnvironment),
				"ghcr.io/hephaestus-build",
			),
			[{ image: "webapp", reference: webApp, repository: "ghcr.io/hephaestus-build/webapp" }],
		);
		assert.throws(
			() => deployedSubjects([webApp], inventory, "ghcr.io/hephaestus-build"),
			/does not name the deployed image ghcr\.io\/hephaestus-build\/webapp/u,
		);
	} finally {
		await rm(directory, { recursive: true, force: true });
	}
});

void test("only the promotion workflow on main may sign a channel", () => {
	assert.equal(
		promoteIdentity("hephaestus-build/Hephaestus"),
		"https://github.com/hephaestus-build/Hephaestus/.github/workflows/promote.yml@refs/heads/main",
	);
});
