import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdir, mkdtemp, readFile, readlink, rm, symlink, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";

import { environmentForGitFixture, GIT_REPOSITORY_VARIABLES } from "./lib/git-environment.ts";
import { parseJson } from "./lib/json.ts";
import {
	adoptTooling,
	appliedCommit,
	carriesToolingLink,
	carryPostgresImage,
	commitImages,
	commitLockEnvironment,
	ensureReleaseTree,
	decide,
	isTarget,
	lockedReleaseCommit,
	parseChannel,
	parseStacks,
	readApplied,
	renderMetrics,
	serializeChannel,
	syncUnits,
	unlockedImages,
} from "./reconcile-deployment.ts";

// `ensureReleaseTree` runs `git worktree add` in the environment it inherits, which under a hook
// names the repository being pushed. The fixtures below are isolated only once this process has
// stopped carrying that repository; node runs each test file in its own process.
for (const name of GIT_REPOSITORY_VARIABLES) delete process.env[name];

const applied = {
	release: "v0.75.2",
	channelCommit: "b".repeat(40),
	appliedAt: "2026-09-03T20:00:00.000Z",
};

await test("a channel names an immutable release", () => {
	assert.deepEqual(parseChannel({ release: "v1.2.3" }), {
		release: "v1.2.3",
		allowRollback: false,
		freeze: false,
	});
	assert.throws(() => parseChannel({ release: "main" }), /immutable vX\.Y\.Z tag/);
	assert.throws(() => parseChannel({ release: "v01.2.3" }), /immutable vX\.Y\.Z tag/);
	assert.throws(() => parseChannel({ release: "v1.2.3-rc.1" }), /immutable vX\.Y\.Z tag/);
	assert.throws(() => parseChannel({}), /channel\.release/);
	assert.throws(() => parseChannel({ release: "v1.2.3", freeze: "yes" }), /must be a boolean/);
});

await test("an unchanged channel is a no-op, so most ticks do nothing", () => {
	assert.deepEqual(decide({ release: "v0.75.2" }, applied, applied.channelCommit, false), {
		action: "noop",
		reason: "already running v0.75.2",
	});
});

await test("re-promoting the release a host already runs re-applies it, so drift converges", () => {
	assert.deepEqual(decide({ release: applied.release }, applied, "c".repeat(40), true), {
		action: "apply",
		release: applied.release,
	});
});

await test("a descendant channel commit applies", () => {
	assert.deepEqual(decide({ release: "v0.75.3" }, applied, "c".repeat(40), true), {
		action: "apply",
		release: "v0.75.3",
	});
});

await test("a replay or divergent channel history is refused", () => {
	const decision = decide({ release: "v0.75.3" }, applied, "a".repeat(40), false);
	assert.equal(decision.action, "refuse");
});

await test("a downgrade requires an explicit rollback on a new channel commit", () => {
	assert.equal(decide({ release: "v0.75.1" }, applied, "c".repeat(40), true).action, "refuse");
	assert.deepEqual(
		decide({ release: "v0.75.1", allowRollback: true }, applied, "c".repeat(40), true),
		{ action: "apply", release: "v0.75.1" },
	);
	assert.equal(
		decide({ release: "v0.75.1", allowRollback: true }, applied, "a".repeat(40), false).action,
		"refuse",
	);
});

await test("a frozen channel holds the host where it is, even against a newer release", () => {
	assert.deepEqual(decide({ release: "v0.99.0", freeze: true }, applied, "c".repeat(40), true), {
		action: "noop",
		reason: "channel is frozen",
	});
});

await test("a host with no state converges on its first run", () => {
	assert.deepEqual(decide({ release: "v0.75.2" }, undefined, "a".repeat(40), false), {
		action: "apply",
		release: "v0.75.2",
	});
});

await test("only a missing applied-state file means first run", async () => {
	const directory = await mkdtemp(join(tmpdir(), "reconcile-state-"));
	try {
		assert.equal(await readApplied(join(directory, "missing.json")), undefined);
		const corrupt = join(directory, "applied.json");
		await writeFile(corrupt, "not json");
		await assert.rejects(readApplied(corrupt), /JSON/);
		await writeFile(
			corrupt,
			JSON.stringify({
				release: "v1.2.3",
				channelCommit: "d".repeat(40),
				appliedAt: "September 4, 2026",
			}),
		);
		await assert.rejects(readApplied(corrupt), /ISO timestamp/);

		// The commit is kept from the first record that carried it, and an older record has none.
		const withCommit = join(directory, "with-commit.json");
		await writeFile(withCommit, JSON.stringify({ ...applied, commit: "c".repeat(40) }));
		assert.deepEqual(await readApplied(withCommit), { ...applied, commit: "c".repeat(40) });
		const legacy = join(directory, "legacy.json");
		await writeFile(legacy, JSON.stringify(applied));
		assert.deepEqual(await readApplied(legacy), applied);
		await writeFile(corrupt, JSON.stringify({ ...applied, commit: "v1.2.3" }));
		await assert.rejects(readApplied(corrupt), /applied\.commit must be a Git commit/);
	} finally {
		await rm(directory, { recursive: true, force: true });
	}
});

await test("stacks come up in dependency order regardless of how they were configured", () => {
	assert.deepEqual(parseStacks("core app"), ["app", "core"]);
	assert.deepEqual(parseStacks("proxy, core, app"), ["app", "core", "proxy"]);
	assert.throws(() => parseStacks("app database"), /unknown stack/);
	assert.throws(() => parseStacks(""), /at least one stack/);
});

await test("the version rides in labels so a dashboard can group by it", () => {
	const metrics = renderMetrics({
		channel: "staging",
		release: "v0.75.2",
		commit: "abc",
		success: true,
		now: new Date("2026-09-03T21:00:00.000Z"),
		lastSuccessAt: new Date("2026-09-03T21:00:00.000Z"),
	});
	assert.match(
		metrics,
		/hephaestus_deploy_info\{channel="staging",release="v0\.75\.2",channel_commit="abc"\} 1/,
	);
	assert.match(metrics, /hephaestus_deploy_reconcile_success 1/);
	assert.match(metrics, /hephaestus_deploy_last_success_timestamp_seconds 1788469200/);
	// The heartbeat every run that inspects the channel writes, and what the last-success series means.
	assert.match(metrics, /hephaestus_deploy_reconcile_timestamp_seconds 1788469200\n/);
	assert.match(metrics, /hephaestus_deploy_tooling_pending 0\n/);
	assert.match(
		metrics,
		/# HELP hephaestus_deploy_last_success_timestamp_seconds When the release this host runs was applied\./,
	);
	assert.ok(metrics.endsWith("\n"));
});

await test("a failed run still publishes a series, or silence and failure look alike", () => {
	const metrics = renderMetrics({
		channel: "production",
		release: "none",
		commit: "none",
		success: false,
		now: new Date("2026-09-03T21:00:00.000Z"),
	});
	assert.match(metrics, /hephaestus_deploy_reconcile_success 0/);
	assert.doesNotMatch(metrics, /last_success/);
	assert.match(metrics, /hephaestus_deploy_reconcile_timestamp_seconds 1788469200\n/);
	assert.match(metrics, /hephaestus_deploy_tooling_pending 0\n/);
});

await test("an image the release lock does not cover is refused", () => {
	const lock = [
		"HEPHAESTUS_IMAGE_APPLICATION_SERVER=ghcr.io/o/application-server@sha256:aaa",
		"HEPHAESTUS_IMAGE_POSTGRES=ghcr.io/o/postgres@sha256:bbb",
		`HEPHAESTUS_RELEASE_COMMIT=${"d".repeat(40)}`,
		"IMAGE_TAG=v1.2.3",
	].join("\n");
	assert.equal(lockedReleaseCommit(lock), "d".repeat(40));
	assert.deepEqual(
		unlockedImages(
			["ghcr.io/o/application-server@sha256:aaa", "ghcr.io/o/postgres@sha256:bbb"],
			lock,
		),
		[],
	);
	assert.deepEqual(unlockedImages(["ghcr.io/o/webapp:latest"], lock), ["ghcr.io/o/webapp:latest"]);
	assert.throws(
		() => lockedReleaseCommit(lock.replace("d".repeat(40), "invalid")),
		/source commit/,
	);
});

const commit = "c".repeat(40);
const digest = `sha256:${"1".repeat(64)}`;
const images = { HEPHAESTUS_IMAGE_WEBAPP: `ghcr.io/o/webapp@${digest}` };

await test("a channel may follow the default branch by naming a commit and what to run", () => {
	assert.deepEqual(parseChannel({ commit, images }), {
		release: commit,
		images,
		allowRollback: false,
		freeze: false,
	});
});

await test("a commit channel pins every image by digest, never by tag", () => {
	// A tag lets the registry answer with something else later, which is the reason the release
	// path pins digests as well.
	assert.throws(
		() => parseChannel({ commit, images: { HEPHAESTUS_IMAGE_WEBAPP: "ghcr.io/o/webapp:main" } }),
		/pinned by digest/,
	);
	assert.throws(() => parseChannel({ commit, images: {} }), /names no image/);
	assert.throws(
		() => parseChannel({ commit, images: { WEBAPP: `ghcr.io/o/w@${digest}` } }),
		/unusable name/,
	);
});

await test("a commit channel names a whole commit, so it cannot be an abbreviation", () => {
	assert.throws(() => parseChannel({ commit: "c".repeat(7), images }), /full 40-character commit/);
	assert.throws(() => parseChannel({ commit: "main", images }), /full 40-character commit/);
});

await test("a channel names a release or a commit, never both", () => {
	assert.throws(() => parseChannel({ release: "v1.2.3", commit, images }), /must name one/);
});

await test("the channel the promotion writes is the channel the host reads back", () => {
	for (const channel of [
		parseChannel({ release: "v1.2.3", freeze: true }),
		parseChannel({ commit, images, allowRollback: true }),
	])
		assert.deepEqual(parseChannel(parseJson(serializeChannel(channel))), channel);
	assert.match(serializeChannel({ release: commit, images }), /"commit": "c{40}"/);
	assert.doesNotMatch(serializeChannel({ release: "v1.2.3" }), /images/);
});

await test("a commit is applied even though it cannot be ordered against a release", () => {
	// Releases compare by version; commits have no order at all. What stops either from moving
	// backwards is the channel ancestry check, which runs before this.
	assert.deepEqual(decide({ release: commit, images }, applied, "d".repeat(40), true), {
		action: "apply",
		release: commit,
	});
});

await test("a rewind is still refused when the channel follows a commit", () => {
	assert.equal(
		decide({ release: commit, images }, applied, "a".repeat(40), false).action,
		"refuse",
	);
});

await test("an environment following the branch reports the commit it runs", () => {
	const rendered = commitLockEnvironment(commit, {
		HEPHAESTUS_IMAGE_WEBAPP: `ghcr.io/o/webapp@${digest}`,
		HEPHAESTUS_IMAGE_ALPINE: `docker.io/library/alpine@${digest}`,
	});
	assert.match(rendered, new RegExp(`^IMAGE_TAG=${commit}$`, "m"));
	// Sorted, so the same channel always renders the same file.
	assert.ok(
		rendered.indexOf("HEPHAESTUS_IMAGE_ALPINE") < rendered.indexOf("HEPHAESTUS_IMAGE_WEBAPP"),
	);
	assert.ok(rendered.endsWith("\n"));
});

await test("a host following commits moves its database only when the image's inputs did", () => {
	// Every commit of the default branch rebuilds the PostgreSQL image, so two consecutive channels
	// name two digests for it; Compose recreates a container whose image changed, and the database
	// coming back means every connection pool and review in flight is lost.
	const running = `ghcr.io/o/postgres@sha256:${"a".repeat(64)}`;
	const rebuilt = `ghcr.io/o/postgres@sha256:${"b".repeat(64)}`;
	const channel = { ...images, HEPHAESTUS_IMAGE_POSTGRES: rebuilt };
	const appliedLock = commitLockEnvironment("d".repeat(40), {
		...images,
		HEPHAESTUS_IMAGE_POSTGRES: running,
	});
	const kept = carryPostgresImage(channel, appliedLock, false);
	assert.deepEqual(kept, { ...images, HEPHAESTUS_IMAGE_POSTGRES: running });
	// The lock the host then renders names what it keeps, so the app stack passes the lock guard
	// with the same pin — and the same Compose configuration — as before.
	assert.deepEqual(unlockedImages([running], commitLockEnvironment(commit, kept)), []);

	// A change under the image's inputs is applied, and so is the channel's pin on a host that has
	// no record of what it ran, or one whose record is not a digest.
	assert.deepEqual(carryPostgresImage(channel, appliedLock, true), channel);
	assert.deepEqual(carryPostgresImage(channel, undefined, false), channel);
	assert.deepEqual(
		carryPostgresImage(channel, "HEPHAESTUS_IMAGE_POSTGRES=ghcr.io/o/postgres:main\n", false),
		channel,
	);
	assert.deepEqual(carryPostgresImage(images, appliedLock, false), images);
	// A channel that moves the image to another repository is naming a different image.
	assert.deepEqual(
		carryPostgresImage(
			{
				...images,
				HEPHAESTUS_IMAGE_POSTGRES: `ghcr.io/elsewhere/postgres@sha256:${"b".repeat(64)}`,
			},
			appliedLock,
			false,
		),
		{ ...images, HEPHAESTUS_IMAGE_POSTGRES: `ghcr.io/elsewhere/postgres@sha256:${"b".repeat(64)}` },
	);
});

await test("the database image is kept while git says its tree stands still", async () => {
	const directory = await mkdtemp(join(tmpdir(), "reconcile-images-"));
	try {
		const checkout = join(directory, "checkout");
		await mkdir(join(checkout, "docker", "postgres"), { recursive: true });
		const git = (...args: string[]): string =>
			execFileSync("git", args, {
				cwd: checkout,
				encoding: "utf8",
				env: environmentForGitFixture(),
			}).trim();
		git("init", "--quiet", "--initial-branch=main");
		git("config", "user.email", "host@example.invalid");
		git("config", "user.name", "host");
		const commitAll = (message: string): string => {
			git("add", "--all");
			git("commit", "--quiet", "-m", message);
			return git("rev-parse", "HEAD");
		};
		const dockerfile = join(checkout, "docker", "postgres", "Dockerfile");
		await writeFile(dockerfile, "FROM postgres:18\n");
		await writeFile(join(checkout, "compose.yaml"), "services: {}\n");
		const first = commitAll("first");
		await writeFile(join(checkout, "compose.yaml"), "services: {app: {}}\n");
		const unrelated = commitAll("a commit that leaves the image alone");
		await writeFile(dockerfile, "FROM postgres:19\n");
		const rebuild = commitAll("a commit that rebuilds the image");

		const running = `ghcr.io/o/postgres@sha256:${"a".repeat(64)}`;
		const channel = {
			...images,
			HEPHAESTUS_IMAGE_POSTGRES: `ghcr.io/o/postgres@sha256:${"b".repeat(64)}`,
		};
		const lockDirectory = join(directory, "release-locks");
		await mkdir(lockDirectory);
		await writeFile(
			join(lockDirectory, `${first}.env`),
			commitLockEnvironment(first, { ...images, HEPHAESTUS_IMAGE_POSTGRES: running }),
		);
		const ran = { release: first, channelCommit: "e".repeat(40), appliedAt: applied.appliedAt };

		assert.deepEqual(await commitImages(checkout, lockDirectory, ran, unrelated, channel), {
			...images,
			HEPHAESTUS_IMAGE_POSTGRES: running,
		});
		// The image's tree moved, so the channel's pin is applied — and so it is when the host cannot
		// order the two commits at all, or has never applied anything.
		assert.deepEqual(await commitImages(checkout, lockDirectory, ran, rebuild, channel), channel);
		assert.deepEqual(
			await commitImages(checkout, lockDirectory, ran, "f".repeat(40), channel),
			channel,
		);
		assert.deepEqual(
			await commitImages(checkout, lockDirectory, undefined, unrelated, channel),
			channel,
		);
		// A host whose lock for the applied release is gone has no record of what it runs.
		await rm(join(lockDirectory, `${first}.env`));
		assert.deepEqual(await commitImages(checkout, lockDirectory, ran, unrelated, channel), channel);
	} finally {
		await rm(directory, { recursive: true, force: true });
	}
});

await test("a build that finishes late cannot put staging back on an older commit", () => {
	// Two builds of main can finish out of order. The later-finishing older build writes the newer
	// channel commit, so channel ancestry accepts it — what refuses it is comparing the commit being
	// asked for against the commit already running.
	const older = { ...applied, release: "b".repeat(40) };
	const decision = decide({ release: "a".repeat(40), images }, older, "e".repeat(40), true, true);
	assert.equal(decision.action, "refuse");
	assert.match(JSON.stringify(decision), /behind the running/);
});

await test("moving deliberately backwards is still possible", () => {
	assert.deepEqual(
		decide(
			{ release: "a".repeat(40), images, allowRollback: true },
			{ ...applied, release: "b".repeat(40) },
			"e".repeat(40),
			true,
			true,
		),
		{ action: "apply", release: "a".repeat(40) },
	);
});

await test("a host remembers a commit it applied, not only a release", () => {
	// The state file is read on the next tick; rejecting what was just written would strand the host.
	assert.ok(isTarget("v1.2.3"));
	assert.ok(isTarget("c".repeat(40)));
	assert.ok(!isTarget("main"));
	assert.ok(!isTarget("v1.2"));
});

await test("the tooling link moves to the applied tree in one step", async () => {
	const directory = await mkdtemp(join(tmpdir(), "reconcile-tooling-"));
	try {
		const link = join(directory, "tooling");
		await symlink(join(directory, "checkout"), link);
		assert.equal(await adoptTooling(link, join(directory, "releases/v1.0.0")), true);
		assert.equal(await readlink(link), join(directory, "releases/v1.0.0"));
		assert.equal(await adoptTooling(link, join(directory, "releases/v1.0.1")), true);
		assert.equal(await readlink(link), join(directory, "releases/v1.0.1"));
		assert.equal(await adoptTooling(link, join(directory, "releases/v1.0.1")), false);
		assert.equal(await readlink(link), join(directory, "releases/v1.0.1"));
	} finally {
		await rm(directory, { recursive: true, force: true });
	}
});

await test("installed units follow the applied tree, and a link left by an earlier install is replaced", async () => {
	const directory = await mkdtemp(join(tmpdir(), "reconcile-units-"));
	try {
		const tree = join(directory, "tree");
		const source = join(tree, "docker/self-host/systemd");
		const units = join(directory, "units");
		await mkdir(source, { recursive: true });
		await mkdir(units);
		await writeFile(join(source, "hephaestus-reconcile.service"), "[Service]\nExecStart=new\n");
		await writeFile(join(source, "hephaestus-reconcile.timer"), "[Timer]\nOnUnitActiveSec=1min\n");
		await writeFile(join(units, "hephaestus-reconcile.service"), "[Service]\nExecStart=old\n");
		await symlink(
			join(source, "hephaestus-reconcile.timer"),
			join(units, "hephaestus-reconcile.timer"),
		);

		assert.deepEqual(await syncUnits(tree, units), [
			"hephaestus-reconcile.service",
			"hephaestus-reconcile.timer",
		]);
		assert.equal(
			await readFile(join(units, "hephaestus-reconcile.service"), "utf8"),
			"[Service]\nExecStart=new\n",
		);
		await assert.rejects(readlink(join(units, "hephaestus-reconcile.timer")), { code: "EINVAL" });
		assert.deepEqual(await syncUnits(tree, units), []);

		await writeFile(join(source, "hephaestus-reconcile.timer"), "[Timer]\nOnUnitActiveSec=2min\n");
		assert.deepEqual(await syncUnits(tree, units), ["hephaestus-reconcile.timer"]);
		assert.equal(
			await readFile(join(units, "hephaestus-reconcile.timer"), "utf8"),
			"[Timer]\nOnUnitActiveSec=2min\n",
		);
	} finally {
		await rm(directory, { recursive: true, force: true });
	}
});

await test("a tree is adopted as tooling only when its own unit runs through the tooling link", async () => {
	const directory = await mkdtemp(join(tmpdir(), "reconcile-floor-"));
	try {
		const units = join(directory, "docker/self-host/systemd");
		await mkdir(units, { recursive: true });
		assert.equal(await carriesToolingLink(directory), false);
		await writeFile(
			join(units, "hephaestus-reconcile.service"),
			"# not /var/lib/hephaestus/tooling/\nExecStart=/usr/bin/env node /var/lib/hephaestus/checkout/scripts/reconcile-deployment.ts\n",
		);
		assert.equal(await carriesToolingLink(directory), false);
		await writeFile(
			join(units, "hephaestus-reconcile.service"),
			"ExecStart=/usr/bin/env node /var/lib/hephaestus/tooling/scripts/reconcile-deployment.ts\n",
		);
		assert.equal(await carriesToolingLink(directory), true);
	} finally {
		await rm(directory, { recursive: true, force: true });
	}
});

await test("a release's worktree is rebuilt at the accepted commit and checked before it is used", async () => {
	const directory = await mkdtemp(join(tmpdir(), "reconcile-tree-"));
	const git = (cwd: string, ...args: string[]): string =>
		execFileSync("git", args, { cwd, encoding: "utf8", env: environmentForGitFixture() }).trim();
	try {
		const checkout = join(directory, "checkout");
		await mkdir(checkout);
		git(checkout, "init", "--quiet", "--initial-branch=main");
		git(checkout, "config", "user.email", "host@example.invalid");
		git(checkout, "config", "user.name", "host");
		// Windows git would otherwise check the file out with CRLF and the content comparison fail.
		git(checkout, "config", "core.autocrlf", "false");
		await writeFile(join(checkout, "compose.yaml"), "services: {}\n");
		git(checkout, "add", "compose.yaml");
		git(checkout, "commit", "--quiet", "-m", "release");
		git(checkout, "tag", "v1.0.0");
		const accepted = git(checkout, "rev-parse", "HEAD");
		const releases = join(directory, "releases");

		const first = await ensureReleaseTree(checkout, releases, "v1.0.0", accepted);
		assert.deepEqual(first, { tree: join(releases, "v1.0.0"), commit: accepted });
		assert.equal(await readFile(join(first.tree, "compose.yaml"), "utf8"), "services: {}\n");

		// The tag moves after acceptance, and the tree is deleted by hand while git still has the
		// path registered: what comes back is the accepted commit, not where the tag points now.
		await writeFile(join(checkout, "compose.yaml"), "services: {later: {}}\n");
		git(checkout, "commit", "--quiet", "-am", "later");
		git(checkout, "tag", "--force", "v1.0.0");
		await rm(first.tree, { recursive: true, force: true });
		assert.deepEqual(await ensureReleaseTree(checkout, releases, "v1.0.0", accepted), first);
		assert.equal(await readFile(join(first.tree, "compose.yaml"), "utf8"), "services: {}\n");

		// A clean tree at a different commit is refused, as is a tampered one.
		await assert.rejects(
			ensureReleaseTree(checkout, releases, "v1.0.0", git(checkout, "rev-parse", "HEAD")),
			/is at .* not the .* accepted for v1\.0\.0/,
		);
		await writeFile(join(first.tree, "compose.yaml"), "services: {tampered: {}}\n");
		await assert.rejects(
			ensureReleaseTree(checkout, releases, "v1.0.0", accepted),
			/differs from v1\.0\.0/,
		);

		await assert.rejects(
			ensureReleaseTree(checkout, releases, "v9.9.9", "0".repeat(40)),
			/git exited with code/,
		);
	} finally {
		await rm(directory, { recursive: true, force: true });
	}
});

await test("only a record that kept its commit, or names one, says what tooling to run", () => {
	assert.equal(appliedCommit(applied), undefined);
	assert.equal(appliedCommit({ ...applied, commit: "c".repeat(40) }), "c".repeat(40));
	assert.equal(appliedCommit({ ...applied, release: "f".repeat(40) }), "f".repeat(40));
	assert.match(
		renderMetrics({
			channel: "staging",
			release: "v0.75.2",
			commit: "abc",
			success: true,
			now: new Date("2026-09-03T21:00:00.000Z"),
			lastSuccessAt: new Date("2026-09-03T21:00:00.000Z"),
			toolingPending: true,
		}),
		/hephaestus_deploy_tooling_pending 1\n/,
	);
	// A failed run reports the same fact, so it cannot read as the apply that ends it.
	assert.match(
		renderMetrics({
			channel: "staging",
			release: "v0.75.2",
			commit: "abc",
			success: false,
			now: new Date("2026-09-03T21:00:00.000Z"),
			toolingPending: true,
		}),
		/hephaestus_deploy_tooling_pending 1\n/,
	);
});
