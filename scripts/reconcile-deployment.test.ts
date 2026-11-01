import assert from "node:assert/strict";
import { execFileSync, spawnSync } from "node:child_process";
import {
	copyFile,
	mkdir,
	mkdtemp,
	readdir,
	readFile,
	readlink,
	rm,
	symlink,
	writeFile,
} from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";

import { environmentForGitFixture, GIT_REPOSITORY_VARIABLES } from "./lib/git-environment.ts";
import { asRecord, at, parseJson } from "./lib/json.ts";
import {
	adoptTooling,
	appliedCommit,
	carriesToolingLink,
	carryPostgresImage,
	chartSettings,
	commitImages,
	commitLockEnvironment,
	helmArguments,
	isMajorCutover,
	profileFiles,
	ensureReleaseTree,
	decide,
	isTarget,
	lockedReleaseCommit,
	parseChannel,
	readApplied,
	renderMetrics,
	requestIdentity,
	serializeChannel,
	syncUnits,
} from "./reconcile-deployment.ts";

// `ensureReleaseTree` runs `git worktree add` in the environment it inherits, which under a hook
// names the repository being pushed. The fixtures below are isolated only once this process has
// stopped carrying that repository; node runs each test file in its own process. Only a delete
// unsets a variable: `process.env` stores an assigned `undefined` as the string "undefined".
for (const name of GIT_REPOSITORY_VARIABLES) {
	Reflect.deleteProperty(process.env, name);
}

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
	assert.throws(
		() => parseChannel({ release: "main" }),
		/immutable vX\.Y\.Z or vX\.Y\.Z-rc\.N tag/u,
	);
	assert.throws(
		() => parseChannel({ release: "v01.2.3" }),
		/immutable vX\.Y\.Z or vX\.Y\.Z-rc\.N tag/u,
	);
	assert.throws(
		() => parseChannel({ release: "v1.2.3-rc.01" }),
		/immutable vX\.Y\.Z or vX\.Y\.Z-rc\.N tag/u,
	);
	assert.throws(() => parseChannel({}), /channel\.release/u);
	assert.throws(() => parseChannel({ release: "v1.2.3", freeze: "yes" }), /must be a boolean/u);
});

/** The identity of the signed request `current` applied, and of a request signed after it. */
const appliedRequest = "1".repeat(64);
const newRequest = "2".repeat(64);
const current = { ...applied, channelIdentity: appliedRequest };

await test("an unchanged own request is a no-op, even when another channel moved deploy-state", () => {
	const unchanged = { action: "noop", reason: "already running v0.75.2" };
	assert.deepEqual(
		decide({ release: "v0.75.2" }, current, applied.channelCommit, appliedRequest, false),
		unchanged,
	);
	assert.deepEqual(
		decide({ release: "v0.75.2" }, current, "c".repeat(40), appliedRequest, true),
		unchanged,
	);
	// The same request in a history that does not descend is still a rewind.
	assert.equal(
		decide({ release: "v0.75.2" }, current, "a".repeat(40), appliedRequest, false).action,
		"refuse",
	);
});

await test("re-promoting the release a host already runs re-applies it, so drift converges", () => {
	assert.deepEqual(
		decide({ release: applied.release }, current, "c".repeat(40), newRequest, true),
		{ action: "apply", release: applied.release },
	);
});

await test("a record from before requests were kept vouches only for its accepted commit", () => {
	assert.equal(
		decide({ release: applied.release }, applied, applied.channelCommit, newRequest, true).action,
		"noop",
	);
	assert.equal(
		decide({ release: applied.release }, applied, "c".repeat(40), appliedRequest, true).action,
		"apply",
	);
});

await test("a descendant channel commit applies", () => {
	assert.deepEqual(decide({ release: "v0.75.3" }, current, "c".repeat(40), newRequest, true), {
		action: "apply",
		release: "v0.75.3",
	});
});

await test("a replay or divergent channel history is refused", () => {
	const decision = decide({ release: "v0.75.3" }, current, "a".repeat(40), newRequest, false);
	assert.equal(decision.action, "refuse");
});

await test("a downgrade requires an explicit rollback on a new channel commit", () => {
	assert.equal(
		decide({ release: "v0.75.1" }, current, "c".repeat(40), newRequest, true).action,
		"refuse",
	);
	assert.deepEqual(
		decide({ release: "v0.75.1", allowRollback: true }, current, "c".repeat(40), newRequest, true),
		{ action: "apply", release: "v0.75.1" },
	);
	assert.equal(
		decide({ release: "v0.75.1", allowRollback: true }, current, "a".repeat(40), newRequest, false)
			.action,
		"refuse",
	);
});

await test("a frozen channel moves the host to what it names, and freezing what runs restarts nothing", () => {
	assert.deepEqual(
		decide({ release: "v0.99.0", freeze: true }, current, "c".repeat(40), newRequest, true),
		{ action: "apply", release: "v0.99.0" },
	);
	assert.deepEqual(
		decide({ release: "v0.75.2", freeze: true }, current, "c".repeat(40), newRequest, true),
		{ action: "noop", reason: "already running v0.75.2" },
	);
	// A freeze that also asks for the commit's database image is a request the host must apply.
	assert.deepEqual(
		decide(
			{ release: "v0.75.2", freeze: true, refreshDatabaseImage: true },
			current,
			"c".repeat(40),
			newRequest,
			true,
		),
		{ action: "apply", release: "v0.75.2" },
	);
	// A freeze bypasses neither the channel's ancestry nor a commit that runs behind what is applied.
	assert.equal(
		decide({ release: "v0.75.2", freeze: true }, current, "c".repeat(40), newRequest, false).action,
		"refuse",
	);
	assert.equal(
		decide(
			{ release: "a".repeat(40), freeze: true },
			current,
			"c".repeat(40),
			newRequest,
			true,
			true,
		).action,
		"refuse",
	);
	// A freeze is no rollback permission.
	assert.equal(
		decide({ release: "v0.75.1", freeze: true }, current, "c".repeat(40), newRequest, true).action,
		"refuse",
	);
});

await test("a request is its channel and bundle together, however the bytes are split", () => {
	assert.notEqual(requestIdentity('{"a":1}', "{}\n"), requestIdentity('{"a":1}{', "}\n"));
	assert.notEqual(requestIdentity('{"a":1}', "{}\n"), requestIdentity('{"a":1}', "{ }\n"));
	assert.match(requestIdentity("", ""), /^[0-9a-f]{64}$/u);
});

await test("a host with no state converges on its first run", () => {
	assert.deepEqual(decide({ release: "v0.75.2" }, undefined, "a".repeat(40), newRequest, false), {
		action: "apply",
		release: "v0.75.2",
	});
});

await test("only a missing applied-state file means first run", async () => {
	const directory = await mkdtemp(path.join(tmpdir(), "reconcile-state-"));
	try {
		assert.equal(await readApplied(path.join(directory, "missing.json")), undefined);
		const corrupt = path.join(directory, "applied.json");
		await writeFile(corrupt, "not json");
		await assert.rejects(readApplied(corrupt), /JSON/u);
		await writeFile(
			corrupt,
			JSON.stringify({
				release: "v1.2.3",
				channelCommit: "d".repeat(40),
				appliedAt: "September 4, 2026",
			}),
		);
		await assert.rejects(readApplied(corrupt), /ISO timestamp/u);

		// The commit is kept from the first record that carried it, and an older record has none.
		const withCommit = path.join(directory, "with-commit.json");
		await writeFile(withCommit, JSON.stringify({ ...applied, commit: "c".repeat(40) }));
		assert.deepEqual(await readApplied(withCommit), { ...applied, commit: "c".repeat(40) });
		const legacy = path.join(directory, "legacy.json");
		await writeFile(legacy, JSON.stringify(applied));
		assert.deepEqual(await readApplied(legacy), applied);
		await writeFile(corrupt, JSON.stringify({ ...applied, commit: "v1.2.3" }));
		await assert.rejects(readApplied(corrupt), /applied\.commit must be a Git commit/u);

		// The rollback target names a tree to keep, so it is a release or a commit like any other.
		await writeFile(withCommit, JSON.stringify({ ...applied, previous: "v0.75.1" }));
		assert.deepEqual(await readApplied(withCommit), { ...applied, previous: "v0.75.1" });
		await writeFile(corrupt, JSON.stringify({ ...applied, previous: "../checkout" }));
		await assert.rejects(readApplied(corrupt), /applied\.previous must be/u);

		// The applied request is a SHA-256 or absent, never anything a comparison could match by accident.
		await writeFile(withCommit, JSON.stringify(current));
		assert.deepEqual(await readApplied(withCommit), current);
		for (const channelIdentity of [42, null, "", "A".repeat(64), "1".repeat(63)]) {
			await writeFile(corrupt, JSON.stringify({ ...applied, channelIdentity }));
			await assert.rejects(readApplied(corrupt), /applied\.channelIdentity must be/u);
		}
	} finally {
		await rm(directory, { recursive: true, force: true });
	}
});

/** The images a commit channel names for a chart host, as the promotion writes them. */
const chartChannelImages = (omit?: string): Record<string, string> =>
	Object.fromEntries(
		(
			[
				["HEPHAESTUS_IMAGE_AGENT_PI", `ghcr.io/hephaestus-build/agent-pi@sha256:${"1".repeat(64)}`],
				[
					"HEPHAESTUS_IMAGE_APPLICATION_SERVER",
					`ghcr.io/hephaestus-build/application-server@sha256:${"2".repeat(64)}`,
				],
				["HEPHAESTUS_IMAGE_NATS", `docker.io/synadia/nats-server@sha256:${"3".repeat(64)}`],
				["HEPHAESTUS_IMAGE_POSTGRES", `ghcr.io/hephaestus-build/postgres@sha256:${"4".repeat(64)}`],
				["HEPHAESTUS_IMAGE_WEBAPP", `ghcr.io/hephaestus-build/webapp@sha256:${"5".repeat(64)}`],
			] satisfies [string, string][]
		).filter(([key]) => key !== omit),
	);

await test("a chart host installs the release tree's chart with its profiles, the site values and last the lock's images", async () => {
	assert.deepEqual(chartSettings({}), {
		release: "hephaestus",
		namespace: "hephaestus",
		profiles: ["small"],
		helm: "helm",
	});
	const settings = chartSettings({ HEPHAESTUS_CHART_PROFILE: "small small-16gib" });
	assert.deepEqual(settings.profiles, ["small", "small-16gib"]);
	assert.deepEqual(
		chartSettings({ HEPHAESTUS_CHART_PROFILE: "small,small-16gib" }).profiles,
		settings.profiles,
	);
	assert.throws(() => chartSettings({ HEPHAESTUS_CHART_PROFILE: "small ../dev" }), /PROFILE/u);
	assert.throws(() => chartSettings({ HEPHAESTUS_CHART_PROFILE: " , " }), /PROFILE/u);

	const tree = await mkdtemp(path.join(tmpdir(), "reconcile-profiles-"));
	try {
		const profiles = path.join(tree, "deploy/chart/hephaestus/profiles");
		await mkdir(profiles, { recursive: true });
		await writeFile(path.join(profiles, "small.yaml"), "{}\n");
		await assert.rejects(profileFiles(settings, tree), /no chart profile small-16gib/u);
		await writeFile(path.join(profiles, "small-16gib.yaml"), "{}\n");
		const files = await profileFiles(settings, tree);
		assert.deepEqual(files, [
			path.join(profiles, "small.yaml"),
			path.join(profiles, "small-16gib.yaml"),
		]);
		const argv = helmArguments(
			settings,
			tree,
			files,
			"/var/lib/hephaestus/chart/abc.images.yaml",
			"/etc/hephaestus/chart.yaml",
			1200,
		);
		const values = argv.flatMap((argument, index) =>
			argv[index - 1] === "--values" ? [argument] : [],
		);
		// Helm merges later files over earlier ones: the profiles in the host's order, the site
		// values, and last the lock's images, so that no site value runs an image the lock omits.
		assert.deepEqual(values, [
			path.join(profiles, "small.yaml"),
			path.join(profiles, "small-16gib.yaml"),
			"/etc/hephaestus/chart.yaml",
			"/var/lib/hephaestus/chart/abc.images.yaml",
		]);
		for (const flag of ["--rollback-on-failure", "--wait=watcher", "--wait-for-jobs"]) {
			assert.ok(argv.includes(flag), flag);
		}
		assert.equal(argv[argv.indexOf("--timeout") + 1], "1200s");
	} finally {
		await rm(tree, { recursive: true, force: true });
	}
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
		/hephaestus_deploy_info\{channel="staging",release="v0\.75\.2",channel_commit="abc"\} 1/u,
	);
	assert.match(metrics, /hephaestus_deploy_reconcile_success 1/u);
	assert.match(metrics, /hephaestus_deploy_last_success_timestamp_seconds 1788469200/u);
	// The heartbeat every run that inspects the channel writes, and what the last-success series means.
	assert.match(metrics, /hephaestus_deploy_reconcile_timestamp_seconds 1788469200\n/u);
	assert.match(metrics, /hephaestus_deploy_tooling_pending 0\n/u);
	assert.match(
		metrics,
		/# HELP hephaestus_deploy_last_success_timestamp_seconds When the release this host runs was applied\./u,
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
	assert.match(metrics, /hephaestus_deploy_reconcile_success 0/u);
	assert.doesNotMatch(metrics, /last_success/u);
	assert.match(metrics, /hephaestus_deploy_reconcile_timestamp_seconds 1788469200\n/u);
	assert.match(metrics, /hephaestus_deploy_tooling_pending 0\n/u);
});

await test("a commit lock contains one valid source commit", () => {
	const commit = "d".repeat(40);
	const lock = `HEPHAESTUS_RELEASE_COMMIT=${commit}\n`;
	assert.equal(lockedReleaseCommit(lock), commit);
	assert.throws(() => lockedReleaseCommit(lock.replace(commit, "invalid")), /source commit/u);
	assert.throws(() => lockedReleaseCommit(lock + lock), /source commit/u);
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
		refreshDatabaseImage: false,
	});
});

await test("a commit channel pins every image by digest, never by tag", () => {
	// A tag lets the registry answer with something else later, which is the reason the release
	// path pins digests as well.
	assert.throws(
		() => parseChannel({ commit, images: { HEPHAESTUS_IMAGE_WEBAPP: "ghcr.io/o/webapp:main" } }),
		/pinned by digest/u,
	);
	assert.throws(() => parseChannel({ commit, images: {} }), /names no image/u);
	assert.throws(
		() => parseChannel({ commit, images: { WEBAPP: `ghcr.io/o/w@${digest}` } }),
		/unusable name/u,
	);
});

await test("a commit channel names a whole commit, so it cannot be an abbreviation", () => {
	assert.throws(() => parseChannel({ commit: "c".repeat(7), images }), /full 40-character commit/u);
	assert.throws(() => parseChannel({ commit: "main", images }), /full 40-character commit/u);
});

await test("a channel names a release or a commit, never both", () => {
	assert.throws(() => parseChannel({ release: "v1.2.3", commit, images }), /must name one/u);
});

await test("the channel the promotion writes is the channel the host reads back", () => {
	for (const channel of [
		parseChannel({ release: "v1.2.3", freeze: true }),
		parseChannel({ commit, images, allowRollback: true }),
	]) {
		assert.deepEqual(parseChannel(parseJson(serializeChannel(channel))), channel);
	}
	assert.match(serializeChannel({ release: commit, images }), /"commit": "c{40}"/u);
	assert.doesNotMatch(serializeChannel({ release: "v1.2.3" }), /images/u);
});

await test("a commit is applied even though it cannot be ordered against a release", () => {
	// Releases compare by version; commits have no order at all. What stops either from moving
	// backwards is the channel ancestry check, which runs before this.
	assert.deepEqual(decide({ release: commit, images }, current, "d".repeat(40), newRequest, true), {
		action: "apply",
		release: commit,
	});
});

await test("a rewind is still refused when the channel follows a commit", () => {
	assert.equal(
		decide({ release: commit, images }, current, "a".repeat(40), newRequest, false).action,
		"refuse",
	);
});

await test("an environment following the branch reports the commit it runs", () => {
	const rendered = commitLockEnvironment(commit, {
		HEPHAESTUS_IMAGE_WEBAPP: `ghcr.io/o/webapp@${digest}`,
		HEPHAESTUS_IMAGE_POSTGRES: `ghcr.io/o/postgres@${digest}`,
	});
	assert.match(rendered, new RegExp(`^IMAGE_TAG=${commit}$`, "mu"));
	// Sorted, so the same channel always renders the same file.
	assert.ok(
		rendered.indexOf("HEPHAESTUS_IMAGE_POSTGRES") < rendered.indexOf("HEPHAESTUS_IMAGE_WEBAPP"),
	);
	assert.ok(rendered.endsWith("\n"));
});

const running = `ghcr.io/o/postgres@sha256:${"a".repeat(64)}`;
const rebuilt = `ghcr.io/o/postgres@sha256:${"b".repeat(64)}`;

await test("a host following commits keeps the database pin it applied until the image is rebuilt", () => {
	const channel = { ...images, HEPHAESTUS_IMAGE_POSTGRES: rebuilt };
	const appliedLock = commitLockEnvironment("d".repeat(40), {
		...images,
		HEPHAESTUS_IMAGE_POSTGRES: running,
	});
	const kept = carryPostgresImage(channel, appliedLock, false);
	assert.deepEqual(kept, { ...images, HEPHAESTUS_IMAGE_POSTGRES: running });
	// The retained lock records the same PostgreSQL pin on the next apply.
	assert.match(
		commitLockEnvironment(commit, kept),
		new RegExp(`HEPHAESTUS_IMAGE_POSTGRES=${running}`, "u"),
	);

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
	// A lock a host wrote with CRLF line endings still names the pin it wrote.
	assert.deepEqual(carryPostgresImage(channel, `HEPHAESTUS_IMAGE_POSTGRES=${running}\r\n`, false), {
		...images,
		HEPHAESTUS_IMAGE_POSTGRES: running,
	});
});

await test("the database image is kept while git says nothing rebuilt it that day", async () => {
	const directory = await mkdtemp(path.join(tmpdir(), "reconcile-images-"));
	try {
		const checkout = path.join(directory, "checkout");
		await mkdir(path.join(checkout, "docker", "postgres"), { recursive: true });
		await mkdir(path.join(checkout, ".github", "workflows"), { recursive: true });
		const git = (date: string, ...args: string[]): string =>
			execFileSync("git", args, {
				cwd: checkout,
				encoding: "utf8",
				env: environmentForGitFixture({ GIT_AUTHOR_DATE: date, GIT_COMMITTER_DATE: date }),
			}).trim();
		const day = "2026-09-03T12:00:00+0000";
		git(day, "init", "--quiet", "--initial-branch=main");
		git(day, "config", "user.email", "host@example.invalid");
		git(day, "config", "user.name", "host");
		const commitAll = (message: string, date = day): string => {
			git(date, "add", "--all");
			git(date, "commit", "--quiet", "-m", message);
			return git(date, "rev-parse", "HEAD");
		};
		const dockerfile = path.join(checkout, "docker", "postgres", "Dockerfile");
		const buildWorkflow = path.join(checkout, ".github", "workflows", "reusable-docker-build.yml");
		await writeFile(dockerfile, "FROM postgres:18\n");
		await writeFile(buildWorkflow, "on: workflow_call\n");
		await writeFile(path.join(checkout, "compose.yaml"), "services: {}\n");
		const first = commitAll("first");
		await writeFile(path.join(checkout, "compose.yaml"), "services: {app: {}}\n");
		const unrelated = commitAll("a commit that leaves the image alone");
		await writeFile(path.join(checkout, "compose.yaml"), "services: {app: {ports: []}}\n");
		const nextDay = commitAll("the first commit of the next day", "2026-09-04T09:00:00+0000");
		await writeFile(dockerfile, "FROM postgres:19\n");
		const rebuild = commitAll("a commit that rebuilds the image");
		await writeFile(buildWorkflow, "on: workflow_call\n# and a build argument\n");
		const rebuiltByWorkflow = commitAll("a commit that changes how the image is built");

		const channel = { ...images, HEPHAESTUS_IMAGE_POSTGRES: rebuilt };
		const carried = { ...images, HEPHAESTUS_IMAGE_POSTGRES: running };
		const lockDirectory = path.join(directory, "release-locks");
		await mkdir(lockDirectory);
		await writeFile(
			path.join(lockDirectory, `${first}.env`),
			commitLockEnvironment(first, carried),
		);
		const ran = { release: first, channelCommit: "e".repeat(40), appliedAt: applied.appliedAt };

		assert.deepEqual(await commitImages(checkout, lockDirectory, ran, unrelated, channel), carried);
		// Promoting the commit the host already runs is still the same day as itself.
		assert.deepEqual(await commitImages(checkout, lockDirectory, ran, first, channel), carried);
		assert.deepEqual(
			await commitImages(checkout, lockDirectory, ran, first, channel, true),
			channel,
		);
		// CI rebuilds the image for either tree, so a host that watched only one would run neither
		// rebuild; the day after, it takes the rebuild every push earns whatever the diff says.
		assert.deepEqual(await commitImages(checkout, lockDirectory, ran, rebuild, channel), channel);
		assert.deepEqual(
			await commitImages(checkout, lockDirectory, ran, rebuiltByWorkflow, channel),
			channel,
		);
		assert.deepEqual(await commitImages(checkout, lockDirectory, ran, nextDay, channel), channel);
		// The operator asked for the channel's pin, so the day and the diff decide nothing.
		assert.deepEqual(
			await commitImages(checkout, lockDirectory, ran, unrelated, channel, true),
			channel,
		);
		// The channel's pin is applied when the host cannot order the two commits at all, or has
		// never applied anything.
		assert.deepEqual(
			await commitImages(checkout, lockDirectory, ran, "f".repeat(40), channel),
			channel,
		);
		// Naming an unknown commit twice does not make git vouch for it.
		const unknown = "f".repeat(40);
		await writeFile(
			path.join(lockDirectory, `${unknown}.env`),
			commitLockEnvironment(unknown, carried),
		);
		assert.deepEqual(
			await commitImages(checkout, lockDirectory, { ...ran, release: unknown }, unknown, channel),
			channel,
		);
		assert.deepEqual(
			await commitImages(checkout, lockDirectory, undefined, unrelated, channel),
			channel,
		);
		// A host whose lock for the applied release is gone has no record of what it runs.
		await rm(path.join(lockDirectory, `${first}.env`));
		assert.deepEqual(await commitImages(checkout, lockDirectory, ran, unrelated, channel), channel);
	} finally {
		await rm(directory, { recursive: true, force: true });
	}
});

await test("a build that finishes late cannot put staging back on an older commit", () => {
	// Two builds of main can finish out of order. The later-finishing older build writes the newer
	// channel commit, so channel ancestry accepts it — what refuses it is comparing the commit being
	// asked for against the commit already running.
	const older = { ...current, release: "b".repeat(40) };
	const decision = decide(
		{ release: "a".repeat(40), images },
		older,
		"e".repeat(40),
		newRequest,
		true,
		true,
	);
	assert.equal(decision.action, "refuse");
	assert.match(JSON.stringify(decision), /behind the running/u);
});

await test("moving deliberately backwards is still possible", () => {
	assert.deepEqual(
		decide(
			{ release: "a".repeat(40), images, allowRollback: true },
			{ ...current, release: "b".repeat(40) },
			"e".repeat(40),
			newRequest,
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

await test("release cutovers distinguish major changes, first installs and an unversioned commit chart", () => {
	assert.equal(isMajorCutover("v1.0.1", "v1.0.0", "1.0.0"), false);
	assert.equal(isMajorCutover("v1.0.0", "v0.95.1", "0.95.1"), true);
	assert.equal(isMajorCutover("v1.0.0", undefined, "0.95.1"), true);
	assert.equal(isMajorCutover("v1.0.0"), false);
	assert.throws(
		() => isMajorCutover("v0.95.1", "v1.0.0", "1.0.0"),
		/major downgrade requires a database restore/u,
	);
	assert.throws(
		() => isMajorCutover("v1.0.0", "c".repeat(40), ""),
		/Pause reconciliation.*Database maintenance/u,
	);
	assert.equal(isMajorCutover("v1.0.0", "c".repeat(40), "1.0.0"), false);
});

await test("major cutovers order 9 and 10 numerically", () => {
	assert.equal(isMajorCutover("v10.0.0", "v9.2.1", "9.2.1"), true);
	assert.equal(isMajorCutover("v10.1.0", "v10.0.0", "10.0.0"), false);
	assert.throws(
		() => isMajorCutover("v9.2.1", "v10.0.0", "10.0.0"),
		/major downgrade requires a database restore/u,
	);
});

await test("the tooling link moves to the applied tree in one step", async () => {
	const directory = await mkdtemp(path.join(tmpdir(), "reconcile-tooling-"));
	try {
		const link = path.join(directory, "tooling");
		await symlink(path.join(directory, "checkout"), link);
		assert.equal(await adoptTooling(link, path.join(directory, "releases/v1.0.0")), true);
		assert.equal(await readlink(link), path.join(directory, "releases/v1.0.0"));
		assert.equal(await adoptTooling(link, path.join(directory, "releases/v1.0.1")), true);
		assert.equal(await readlink(link), path.join(directory, "releases/v1.0.1"));
		assert.equal(await adoptTooling(link, path.join(directory, "releases/v1.0.1")), false);
		assert.equal(await readlink(link), path.join(directory, "releases/v1.0.1"));
	} finally {
		await rm(directory, { recursive: true, force: true });
	}
});

await test("installed units follow the applied tree, and a link left by an earlier install is replaced", async () => {
	const directory = await mkdtemp(path.join(tmpdir(), "reconcile-units-"));
	try {
		const tree = path.join(directory, "tree");
		const source = path.join(tree, "deploy/reconcile");
		const units = path.join(directory, "units");
		await mkdir(source, { recursive: true });
		await mkdir(units);
		await writeFile(
			path.join(source, "hephaestus-reconcile.service"),
			"[Service]\nExecStart=new\n",
		);
		await writeFile(
			path.join(source, "hephaestus-reconcile.timer"),
			"[Timer]\nOnUnitActiveSec=1min\n",
		);
		await writeFile(path.join(units, "hephaestus-reconcile.service"), "[Service]\nExecStart=old\n");
		await symlink(
			path.join(source, "hephaestus-reconcile.timer"),
			path.join(units, "hephaestus-reconcile.timer"),
		);

		assert.deepEqual(await syncUnits(tree, units), [
			"hephaestus-reconcile.service",
			"hephaestus-reconcile.timer",
		]);
		assert.equal(
			await readFile(path.join(units, "hephaestus-reconcile.service"), "utf8"),
			"[Service]\nExecStart=new\n",
		);
		await assert.rejects(readlink(path.join(units, "hephaestus-reconcile.timer")), {
			code: "EINVAL",
		});
		assert.deepEqual(await syncUnits(tree, units), []);

		await writeFile(
			path.join(source, "hephaestus-reconcile.timer"),
			"[Timer]\nOnUnitActiveSec=2min\n",
		);
		assert.deepEqual(await syncUnits(tree, units), ["hephaestus-reconcile.timer"]);
		assert.equal(
			await readFile(path.join(units, "hephaestus-reconcile.timer"), "utf8"),
			"[Timer]\nOnUnitActiveSec=2min\n",
		);
	} finally {
		await rm(directory, { recursive: true, force: true });
	}
});

await test("a tree is adopted as tooling only when its own unit runs through the tooling link", async () => {
	const directory = await mkdtemp(path.join(tmpdir(), "reconcile-floor-"));
	try {
		const units = path.join(directory, "deploy/reconcile");
		await mkdir(units, { recursive: true });
		assert.equal(await carriesToolingLink(directory), false);
		await writeFile(
			path.join(units, "hephaestus-reconcile.service"),
			"# not /var/lib/hephaestus/tooling/\nExecStart=/usr/bin/env node /var/lib/hephaestus/checkout/scripts/reconcile-deployment.ts\n",
		);
		assert.equal(await carriesToolingLink(directory), false);
		await writeFile(
			path.join(units, "hephaestus-reconcile.service"),
			"ExecStart=/usr/bin/env node /var/lib/hephaestus/tooling/scripts/reconcile-deployment.ts\n",
		);
		assert.equal(await carriesToolingLink(directory), true);
	} finally {
		await rm(directory, { recursive: true, force: true });
	}
});

function gitIn(cwd: string, ...args: string[]): string {
	return execFileSync("git", args, {
		cwd,
		encoding: "utf8",
		env: environmentForGitFixture(),
	}).trim();
}

await test("a release's worktree is rebuilt at the accepted commit and checked before it is used", async () => {
	const directory = await mkdtemp(path.join(tmpdir(), "reconcile-tree-"));
	try {
		const checkout = path.join(directory, "checkout");
		await mkdir(checkout);
		gitIn(checkout, "init", "--quiet", "--initial-branch=main");
		gitIn(checkout, "config", "user.email", "host@example.invalid");
		gitIn(checkout, "config", "user.name", "host");
		// Windows git would otherwise check the file out with CRLF and the content comparison fail.
		gitIn(checkout, "config", "core.autocrlf", "false");
		await writeFile(path.join(checkout, "compose.yaml"), "services: {}\n");
		gitIn(checkout, "add", "compose.yaml");
		gitIn(checkout, "commit", "--quiet", "-m", "release");
		gitIn(checkout, "tag", "v1.0.0");
		const accepted = gitIn(checkout, "rev-parse", "HEAD");
		const releases = path.join(directory, "releases");

		const first = await ensureReleaseTree(checkout, releases, "v1.0.0", accepted);
		assert.deepEqual(first, { tree: path.join(releases, "v1.0.0"), commit: accepted });
		assert.equal(await readFile(path.join(first.tree, "compose.yaml"), "utf8"), "services: {}\n");

		// The tag moves after acceptance, and the tree is deleted by hand while git still has the
		// path registered: what comes back is the accepted commit, not where the tag points now.
		await writeFile(path.join(checkout, "compose.yaml"), "services: {later: {}}\n");
		gitIn(checkout, "commit", "--quiet", "-am", "later");
		gitIn(checkout, "tag", "--force", "v1.0.0");
		await rm(first.tree, { recursive: true, force: true });
		assert.deepEqual(await ensureReleaseTree(checkout, releases, "v1.0.0", accepted), first);
		assert.equal(await readFile(path.join(first.tree, "compose.yaml"), "utf8"), "services: {}\n");

		// A clean tree at a different commit is refused, as is a tampered one.
		await assert.rejects(
			ensureReleaseTree(checkout, releases, "v1.0.0", gitIn(checkout, "rev-parse", "HEAD")),
			/is at .* not the .* accepted for v1\.0\.0/u,
		);
		await writeFile(path.join(first.tree, "compose.yaml"), "services: {tampered: {}}\n");
		await assert.rejects(
			ensureReleaseTree(checkout, releases, "v1.0.0", accepted),
			/differs from v1\.0\.0/u,
		);

		await assert.rejects(
			ensureReleaseTree(checkout, releases, "v9.9.9", "0".repeat(40)),
			/git exited with code/u,
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
		/hephaestus_deploy_tooling_pending 1\n/u,
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
		/hephaestus_deploy_tooling_pending 1\n/u,
	);
});

const reconcilerSubprocess = {
	skip: process.platform === "win32" && "the systemd host uses POSIX executable stubs",
};

async function reconcilerFixture(directory: string, targetRelease = "v1.0.0") {
	const checkout = path.join(directory, "checkout");
	const bootstrap = path.join(directory, "bootstrap");
	const units = path.join(directory, "units");
	const bin = path.join(directory, "bin");
	const callsFile = path.join(directory, "calls");
	const metricsFile = path.join(directory, "deploy.prom");
	await Promise.all(
		[checkout, units, bin].map(async (folder) => mkdir(folder, { recursive: true })),
	);
	for (const tree of [checkout, bootstrap]) {
		await mkdir(path.join(tree, "scripts/lib"), { recursive: true });
		await writeFile(path.join(tree, "package.json"), '{"type":"module"}\n');
		for (const file of [
			"reconcile-deployment.ts",
			"kubernetes-chart.ts",
			"release-chart.ts",
			"release-image-lock.ts",
			"lib/release-identities.ts",
			"lib/release-signer.ts",
			"commit-image-lock.ts",
			"lib/env.ts",
			"lib/json.ts",
			"lib/process.ts",
			"lib/release-version.ts",
		]) {
			await copyFile(path.join(import.meta.dirname, file), path.join(tree, "scripts", file));
		}
	}
	const sourceUnits = path.join(checkout, "deploy/reconcile");
	await mkdir(sourceUnits, { recursive: true });
	await writeFile(
		path.join(sourceUnits, "hephaestus-reconcile.service"),
		"[Service]\nExecStart=/usr/bin/env node /var/lib/hephaestus/tooling/scripts/reconcile-deployment.ts\n",
	);
	await writeFile(
		path.join(sourceUnits, "hephaestus-reconcile.timer"),
		"[Timer]\nOnUnitActiveSec=1min\n",
	);
	// A worktree can carry ignored local operator files.
	await writeFile(path.join(checkout, ".gitignore"), ".local/\n");
	// The profile that a chart host installs by default.
	await mkdir(path.join(checkout, "deploy/chart/hephaestus/profiles"), { recursive: true });
	await writeFile(path.join(checkout, "deploy/chart/hephaestus/profiles/small.yaml"), "{}\n");
	// The release image inventory, whose upstream pins the chart values check.
	await mkdir(path.join(checkout, "security"), { recursive: true });
	await writeFile(
		path.join(checkout, "security/release-images.json"),
		JSON.stringify({
			images: ["webapp", "application-server", "agent-pi", "postgres"],
			upstream: [
				{
					name: "nats",
					repository: "docker.io/synadia/nats-server",
					digest: `sha256:${"3".repeat(64)}`,
				},
			],
		}),
	);
	gitIn(checkout, "init", "--quiet", "--initial-branch=main");
	gitIn(checkout, "config", "user.email", "host@example.invalid");
	gitIn(checkout, "config", "user.name", "host");
	gitIn(checkout, "config", "core.autocrlf", "false");
	const commits = new Map<string, string>();
	for (const release of ["v0.8.0", "v0.9.0", targetRelease]) {
		await writeFile(path.join(checkout, "README.md"), `${release}\n`);
		gitIn(checkout, "add", ".");
		gitIn(checkout, "commit", "--quiet", "-m", release);
		gitIn(checkout, "tag", release);
		commits.set(release, gitIn(checkout, "rev-parse", "HEAD"));
	}
	const releaseCommit = gitIn(checkout, "rev-parse", "HEAD");
	gitIn(checkout, "checkout", "--quiet", "-b", "deploy-state");
	await mkdir(path.join(checkout, "channels"));
	await writeFile(
		path.join(checkout, "channels/test.json"),
		JSON.stringify({ release: targetRelease }),
	);
	await writeFile(path.join(checkout, "channels/test.json.sigstore.json"), "{}\n");
	// A chart host follows a commit channel: the candidate commit with the chart's images.
	await writeFile(
		path.join(checkout, "channels/chart.json"),
		JSON.stringify({ commit: releaseCommit, images: chartChannelImages() }),
	);
	await writeFile(path.join(checkout, "channels/chart.json.sigstore.json"), "{}\n");
	// The same commit missing a chart image, which the chart values refuse.
	await writeFile(
		path.join(checkout, "channels/chart-incomplete.json"),
		JSON.stringify({
			commit: releaseCommit,
			images: chartChannelImages("HEPHAESTUS_IMAGE_WEBAPP"),
		}),
	);
	await writeFile(path.join(checkout, "channels/chart-incomplete.json.sigstore.json"), "{}\n");
	gitIn(checkout, "add", "channels");
	gitIn(checkout, "commit", "--quiet", "-m", "promotion");
	const channelCommit = gitIn(checkout, "rev-parse", "HEAD");
	const origin = path.join(directory, "origin.git");
	gitIn(checkout, "clone", "--quiet", "--bare", checkout, origin);
	gitIn(checkout, "remote", "add", "origin", origin);
	gitIn(checkout, "checkout", "--quiet", "main");
	await symlink(bootstrap, path.join(directory, "tooling"));
	await writeFile(metricsFile, "previous metrics\n");
	await writeFile(callsFile, "");
	const realGit = execFileSync("which", ["git"], { encoding: "utf8" }).trim();
	const recordCall = `import { appendFileSync } from "node:fs";
const args = process.argv.slice(2);
appendFileSync(${JSON.stringify(callsFile)}, [NAME, ...args].join(" ") + "\\n");\n`;
	// Keep subprocess flags separate from Node startup options.
	for (const [name, body] of Object.entries({
		git: `import { spawnSync } from "node:child_process";
if (process.env.FAIL_WORKTREE_REMOVE === "1" && args[0] === "worktree" && args[1] === "remove") process.exit(1);
const result = spawnSync(${JSON.stringify(realGit)}, args, { stdio: "inherit" });
process.exit(result.status ?? 1);`,
		cosign: 'process.exit(process.env.FAIL_VERIFICATION === "1" ? 1 : 0);',
		systemctl:
			'if (args[0] === "show") console.log("yes"); if (args[0] === "daemon-reload" && process.env.FAIL_RELOAD === "1") process.exit(1);',
		kubectl: "console.log(process.env.CHART_WORKLOADS ?? JSON.stringify({items: []}));",
	})) {
		await writeFile(
			path.join(bin, name),
			`#!/usr/bin/env -S node --\n${recordCall.replace("NAME", JSON.stringify(name))}${body}\n`,
			{
				mode: 0o755,
			},
		);
	}
	await writeFile(
		path.join(directory, "run.mjs"),
		`import { main } from "./tooling/scripts/reconcile-deployment.ts";
await main(${JSON.stringify(units)});\n`,
	);
	await writeFile(
		path.join(bootstrap, "scripts/prepare-release-chart.ts"),
		`import { writeFileSync, copyFileSync, readFileSync, statSync } from "node:fs";
import path from "node:path";
if (process.env.FAIL_CHART_VERIFY === "1") throw new Error("chart verification failed");
const [release, commit, destination, helm, inventory] = process.argv.slice(2);
if (!statSync(helm).isFile() || !Array.isArray(JSON.parse(readFileSync(inventory, "utf8")).images)) throw new Error("incorrect chart CLI arguments");
const packaged = path.join(destination, "hephaestus-" + release.slice(1) + ".tgz");
if (process.env.TRANSITION_PACKAGE) copyFileSync(process.env.TRANSITION_PACKAGE, packaged);
else writeFileSync(packaged, "verified chart fixture");
writeFileSync(path.join(destination, release + ".images.yaml"), JSON.stringify({ release: { version: release, commit }, images: {} }));
`,
	);
	const { helm: defaultHelm } = await fakeHelm(directory, callsFile);
	const record = { release: targetRelease, channelCommit, appliedAt: applied.appliedAt };
	return {
		bootstrap,
		checkout,
		origin,
		units,
		releaseCommit,
		record,
		metricsFile,
		calls: async () => readFile(callsFile, "utf8"),
		/** The source commit a fixture release was tagged at. */
		commitOf: (release: string): string => {
			const sourceCommit = commits.get(release);
			assert.ok(sourceCommit !== undefined, `the fixture has no ${release}`);
			return sourceCommit;
		},
		run: ({
			cli = false,
			failVerification = false,
			failWorktreeRemove = false,
			channel = "test",
			environment = {},
		}: {
			cli?: boolean;
			failVerification?: boolean;
			failWorktreeRemove?: boolean;
			channel?: string;
			/** More of the host environment for a failure scenario. */
			environment?: Record<string, string>;
		} = {}) =>
			spawnSync(
				process.execPath,
				[path.join(directory, cli ? "tooling/scripts/reconcile-deployment.ts" : "run.mjs")],
				{
					encoding: "utf8",
					timeout: 30_000,
					env: environmentForGitFixture({
						PATH: `${bin}${path.delimiter}${process.env.PATH ?? ""}`,
						STATE_DIRECTORY: directory,
						HEPHAESTUS_CHANNEL: channel,
						HEPHAESTUS_HELM: defaultHelm,
						HEPHAESTUS_SECRETS: directory,
						HEPHAESTUS_PROMOTE_IDENTITY: "https://example.invalid/promote",
						HEPHAESTUS_METRICS_FILE: metricsFile,
						FAIL_VERIFICATION: failVerification ? "1" : "0",
						FAIL_WORKTREE_REMOVE: failWorktreeRemove ? "1" : "0",
						...environment,
					}),
				},
			),
	};
}

await test(
	"a host waiting for its first promotion says so, without a stack trace",
	reconcilerSubprocess,
	async () => {
		const directory = await mkdtemp(path.join(tmpdir(), "reconcile-unpromoted-"));
		try {
			const fixture = await reconcilerFixture(directory);
			// `cli: true` runs the real entry point, which is where the two failure classes part.
			const result = fixture.run({ cli: true, channel: "production" });

			assert.notEqual(result.status, 0, "an unpromoted host must not report success");
			assert.match(result.stderr, /channels\/production\.json/u);
			assert.match(result.stderr, /has not been promoted yet/u);
			assert.match(result.stderr, /Run the Release \/ Promote workflow/u);
			// The whole point: the journal gets the sentence, not a Node stack.
			assert.doesNotMatch(result.stderr, /^\s+at /mu);
			assert.doesNotMatch(result.stderr, /OperatorActionRequiredError:/u);
		} finally {
			await rm(directory, { recursive: true, force: true });
		}
	},
);

await test(
	"startup adopts the recorded release before attempting to read the channel",
	reconcilerSubprocess,
	async () => {
		const directory = await mkdtemp(path.join(tmpdir(), "reconcile-startup-"));
		try {
			const fixture = await reconcilerFixture(directory);
			const record = { ...fixture.record, commit: fixture.releaseCommit };
			await writeFile(path.join(directory, "applied.json"), JSON.stringify(record));
			const result = fixture.run();
			assert.equal(result.status, 0, result.stderr);
			const tree = path.join(directory, "releases", record.release);
			assert.equal(await readlink(path.join(directory, "tooling")), tree);
			assert.deepEqual(await readApplied(path.join(directory, "applied.json")), record);
			assert.equal(await readFile(fixture.metricsFile, "utf8"), "previous metrics\n");
			assert.equal(
				await readFile(path.join(fixture.units, "hephaestus-reconcile.service"), "utf8"),
				await readFile(path.join(tree, "deploy/reconcile/hephaestus-reconcile.service"), "utf8"),
			);
			const calls = await fixture.calls();
			assert.match(calls, /git worktree add/u);
			assert.match(calls, /git status[\s\S]*systemctl show[\s\S]*systemctl daemon-reload/u);
			assert.doesNotMatch(calls, /git fetch|cosign|docker/u);
		} finally {
			await rm(directory, { recursive: true, force: true });
		}
	},
);

await test(
	"the symlinked CLI preserves pending tooling after a no-op and a failure",
	reconcilerSubprocess,
	async () => {
		const directory = await mkdtemp(path.join(tmpdir(), "reconcile-cli-"));
		try {
			const fixture = await reconcilerFixture(directory);
			await writeFile(path.join(directory, "applied.json"), JSON.stringify(fixture.record));
			const noop = fixture.run({ cli: true });
			assert.equal(noop.status, 0, noop.stderr);
			assert.match(noop.stdout, /No change: already running v1\.0\.0/u);
			const accepted = await readApplied(path.join(directory, "applied.json"));
			assert.match(accepted?.channelIdentity ?? "", /^[0-9a-f]{64}$/u);
			assert.deepEqual(accepted, { ...fixture.record, channelIdentity: accepted?.channelIdentity });
			assert.match(
				await readFile(fixture.metricsFile, "utf8"),
				/^hephaestus_deploy_reconcile_success 1$/mu,
			);
			assert.match(
				await readFile(fixture.metricsFile, "utf8"),
				/^hephaestus_deploy_tooling_pending 1$/mu,
			);
			const failed = fixture.run({ cli: true, failVerification: true });
			assert.notEqual(failed.status, 0);
			assert.match(failed.stderr, /cosign exited with code 1/u);
			assert.match(
				await readFile(fixture.metricsFile, "utf8"),
				/^hephaestus_deploy_reconcile_success 0$/mu,
			);
			assert.match(
				await readFile(fixture.metricsFile, "utf8"),
				/^hephaestus_deploy_tooling_pending 1$/mu,
			);
			assert.equal(await readlink(path.join(directory, "tooling")), fixture.bootstrap);
			assert.deepEqual(await readApplied(path.join(directory, "applied.json")), accepted);
			// A failed channel check starts no workload and changes no installed unit.
			assert.doesNotMatch(await fixture.calls(), /docker|systemctl/u);
		} finally {
			await rm(directory, { recursive: true, force: true });
		}
	},
);

/**
 * A fake Helm that logs each call; `status` answers HELM_STATUS, or fails as for no release, and
 * `history` answers HELM_HISTORY.
 */
async function fakeHelm(
	directory: string,
	combinedLog?: string,
): Promise<{ helm: string; log: () => Promise<string[]> }> {
	const calls = path.join(directory, "helm-calls");
	const helm = path.join(directory, "helm");
	await writeFile(calls, "");
	await writeFile(
		helm,
		`#!/usr/bin/env -S node --
import { appendFileSync } from "node:fs";
const args = process.argv.slice(2);
appendFileSync(${JSON.stringify(calls)}, args.join(" ") + "\\n");
${combinedLog === undefined ? "" : `appendFileSync(${JSON.stringify(combinedLog)}, "helm " + args.join(" ") + "\\n");`}
if (args[0] === "status") {
if (process.env.HELM_STATUS === undefined) process.exit(1);
console.log(JSON.stringify({ info: { status: process.env.HELM_STATUS } }));
}
if (args[0] === "history") console.log(process.env.HELM_HISTORY ?? "[]");
if (args[0] === "list") console.log(process.env.HELM_RELEASES ?? "[]");
if (args[0] === "get") console.log(process.env.HELM_VALUES ?? '{"server":{"replicas":0},"worker":{"replicas":0,"sandbox":{"namespace":"sandboxes"}}}');
process.exit(args[0] === "upgrade" && process.env.FAIL_HELM === "1" ? 1 : 0);
`,
		{ mode: 0o755 },
	);
	return {
		helm,
		log: async () => {
			const text = await readFile(calls, "utf8");
			return text.split("\n").filter(Boolean);
		},
	};
}

await test(
	"a commit channel applies its verified image pins with one Helm upgrade",
	reconcilerSubprocess,
	async () => {
		const directory = await mkdtemp(path.join(tmpdir(), "reconcile-chart-"));
		try {
			const fixture = await reconcilerFixture(directory);
			const { helm, log } = await fakeHelm(directory);
			const environment = {
				HEPHAESTUS_HELM: helm,
				HEPHAESTUS_SECRETS: directory,
			};

			const failed = fixture.run({
				channel: "chart",
				environment: { ...environment, FAIL_HELM: "1" },
			});
			assert.notEqual(failed.status, 0, "a failed upgrade fails the tick");
			assert.match(failed.stderr, /exited with code 1/u);
			assert.equal(await readApplied(path.join(directory, "applied.json")), undefined);
			assert.equal(await readlink(path.join(directory, "tooling")), fixture.bootstrap);

			const result = fixture.run({ channel: "chart", environment });
			assert.equal(result.status, 0, result.stderr);
			const release = fixture.releaseCommit;
			const tree = path.join(directory, "releases", release);
			const imagesFile = path.join(directory, `chart/${release}.images.yaml`);
			const upgraded = await log();
			assert.equal(
				upgraded.at(-1),
				[
					"upgrade --install hephaestus",
					path.join(tree, "deploy/chart/hephaestus"),
					"--namespace hephaestus --values",
					path.join(tree, "deploy/chart/hephaestus/profiles/small.yaml"),
					"--values",
					path.join(directory, "chart.yaml"),
					"--values",
					imagesFile,
					// A chart host waits longer than a new Pod's 10 minutes for the schema.
					"--rollback-on-failure --wait=watcher --wait-for-jobs --timeout 1200s",
				].join(" "),
			);
			// A comment line, then JSON, which Helm reads as YAML.
			const written = await readFile(imagesFile, "utf8");
			const values = asRecord(parseJson(written.replace(/^#.*\n/u, "")), "images values");
			assert.deepEqual(at(values, ["release"], "images values"), {
				version: release,
				commit: release,
			});
			assert.equal(
				at(values, ["images", "application-server"], "images values"),
				`ghcr.io/hephaestus-build/application-server@sha256:${"2".repeat(64)}`,
			);
			const record = await readApplied(path.join(directory, "applied.json"));
			assert.equal(record?.release, release);
			assert.equal(await readlink(path.join(directory, "tooling")), tree);
			// The kubelet owns the cluster's images: no Compose stack, pull or image removal runs.
			assert.doesNotMatch(await fixture.calls(), /^docker /mu);
			// The free-space series reads Docker's root, which a chart host does not use.
			assert.doesNotMatch(await readFile(fixture.metricsFile, "utf8"), /free_bytes/u);

			// The next tick finds the request applied and calls Helm no more.
			const { length: calls } = await log();
			const again = fixture.run({ channel: "chart", environment });
			assert.equal(again.status, 0, again.stderr);
			const after = await log();
			assert.equal(after.length, calls);
		} finally {
			await rm(directory, { recursive: true, force: true });
		}
	},
);

await test(
	"a chart host repairs a release that an interrupted upgrade left pending before it upgrades",
	reconcilerSubprocess,
	async () => {
		const directory = await mkdtemp(path.join(tmpdir(), "reconcile-chart-pending-"));
		try {
			const fixture = await reconcilerFixture(directory);
			const { helm, log } = await fakeHelm(directory);
			const result = fixture.run({
				channel: "chart",
				environment: {
					HEPHAESTUS_HELM: helm,
					HEPHAESTUS_SECRETS: directory,
					HELM_STATUS: "pending-upgrade",
					// Helm's own rollback default, the revision before the stuck one, failed.
					HELM_HISTORY: JSON.stringify([
						{ revision: 2, status: "superseded" },
						{ revision: 3, status: "deployed" },
						{ revision: 4, status: "failed" },
						{ revision: 5, status: "pending-upgrade" },
					]),
				},
			});
			assert.equal(result.status, 0, result.stderr);
			const repaired = await log();
			assert.deepEqual(
				repaired.map((call) => call.split(" ")[0]),
				["status", "history", "rollback", "upgrade"],
			);
			assert.match(repaired[2] ?? "", /^rollback hephaestus 3 --namespace hephaestus --wait/u);
		} finally {
			await rm(directory, { recursive: true, force: true });
		}
	},
);

await test(
	"a chart host leaves a cut-off first install to the operator and names the command that removes it",
	reconcilerSubprocess,
	async () => {
		const directory = await mkdtemp(path.join(tmpdir(), "reconcile-chart-pending-install-"));
		try {
			const fixture = await reconcilerFixture(directory);
			const { helm, log } = await fakeHelm(directory);
			const result = fixture.run({
				channel: "chart",
				environment: {
					HEPHAESTUS_HELM: helm,
					HEPHAESTUS_SECRETS: directory,
					HELM_STATUS: "pending-install",
					HELM_HISTORY: JSON.stringify([{ revision: 1, status: "pending-install" }]),
				},
			});
			assert.notEqual(result.status, 0, "a release with nothing to return to fails the tick");
			assert.match(result.stderr, /`helm uninstall hephaestus --namespace hephaestus`/u);
			const calls = await log();
			assert.deepEqual(
				calls.map((call) => call.split(" ")[0]),
				["status", "history"],
			);
			assert.equal(await readApplied(path.join(directory, "applied.json")), undefined);
		} finally {
			await rm(directory, { recursive: true, force: true });
		}
	},
);

await test(
	"an unsigned channel or incomplete commit lock never reaches Helm",
	reconcilerSubprocess,
	async () => {
		const directory = await mkdtemp(path.join(tmpdir(), "reconcile-chart-refused-"));
		try {
			const fixture = await reconcilerFixture(directory);
			const { helm, log } = await fakeHelm(directory);
			const environment = {
				HEPHAESTUS_HELM: helm,
				HEPHAESTUS_SECRETS: directory,
			};
			// A channel whose signature cosign refuses.
			const unsigned = fixture.run({ channel: "chart", failVerification: true, environment });
			assert.notEqual(unsigned.status, 0);
			// A commit lock without an image the chart runs.
			const incomplete = fixture.run({ channel: "chart-incomplete", environment });
			assert.notEqual(incomplete.status, 0);
			assert.match(incomplete.stderr, /no webapp digest/u);
			assert.deepEqual(await log(), []);
			assert.equal(await readApplied(path.join(directory, "applied.json")), undefined);
		} finally {
			await rm(directory, { recursive: true, force: true });
		}
	},
);

await test(
	"this tree's installed reconciler applies the rc.1 chart on Kubernetes",
	{
		skip:
			process.env.RECONCILER_TEST_CONTEXT === undefined &&
			"set RECONCILER_TEST_CONTEXT to a disposable cluster context",
	},
	async () => {
		const directory = await mkdtemp(path.join(tmpdir(), "reconciler-transition-"));
		const namespace = `reconciler-${Date.now()}`;
		const context = process.env.RECONCILER_TEST_CONTEXT ?? "";
		const target = "v1.0.0-rc.1";
		const realHelm = execFileSync("which", ["helm"], { encoding: "utf8" }).trim();
		const realKubectl = execFileSync("which", ["kubectl"], { encoding: "utf8" }).trim();
		const kubectl = (...args: string[]) =>
			execFileSync(realKubectl, ["--context", context, ...args], { encoding: "utf8" });
		const helm = (...args: string[]) =>
			execFileSync(realHelm, ["--kube-context", context, ...args], { encoding: "utf8" });
		try {
			const fixture = await reconcilerFixture(directory, target);
			const source = path.join(directory, "source");
			await mkdir(path.join(source, "templates"), { recursive: true });
			await writeFile(
				path.join(source, "values.yaml"),
				JSON.stringify({
					server: { replicas: 0 },
					worker: { replicas: 0, sandbox: { namespace: `${namespace}-sandboxes` } },
				}),
			);
			await writeFile(
				path.join(source, "templates/version.yaml"),
				"apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: transition-proof\ndata:\n  version: {{ .Chart.AppVersion | quote }}\n",
			);
			await writeFile(
				path.join(source, "Chart.yaml"),
				"apiVersion: v2\nname: hephaestus\nversion: 0.95.1\nappVersion: 0.95.1\n",
			);
			kubectl("create", "namespace", namespace);
			kubectl("create", "namespace", `${namespace}-sandboxes`);
			helm("install", "hephaestus", source, "--namespace", namespace, "--wait", "--timeout", "30s");
			await writeFile(
				path.join(source, "Chart.yaml"),
				`apiVersion: v2\nname: hephaestus\nversion: ${target.slice(1)}\nappVersion: ${target.slice(1)}\n`,
			);
			helm("package", source, "--destination", directory);
			// Transport and signature admission have their own failure tests. This leg uses real Helm
			// and the installed reconciler, with synthetic next-release bytes (1.0 is not published).
			await writeFile(path.join(directory, "chart.yaml"), "{}\n");
			await writeFile(
				path.join(directory, "applied.json"),
				JSON.stringify({ ...fixture.record, release: "v0.95.1" }),
			);
			await writeFile(
				path.join(directory, "bin/kubectl"),
				`#!/bin/sh\nexec ${realKubectl} --context ${context} "$@"\n`,
				{ mode: 0o755 },
			);
			const wrappedHelm = path.join(directory, "helm");
			await writeFile(wrappedHelm, `#!/bin/sh\nexec ${realHelm} --kube-context ${context} "$@"\n`, {
				mode: 0o755,
			});
			const result = fixture.run({
				environment: {
					HEPHAESTUS_HELM: wrappedHelm,
					HEPHAESTUS_CHART_NAMESPACE: namespace,
					HEPHAESTUS_SECRETS: directory,
					HEPHAESTUS_WAIT_TIMEOUT: "30",
					TRANSITION_PACKAGE: path.join(directory, `hephaestus-${target.slice(1)}.tgz`),
				},
			});
			assert.equal(result.status, 0, result.stderr);
			const proof = asRecord(
				parseJson(kubectl("get", "configmap", "transition-proof", "-n", namespace, "-o", "json")),
				"transition proof",
			);
			assert.equal(asRecord(proof.data, "proof data").version, target.slice(1));
			const record = await readApplied(path.join(directory, "applied.json"));
			assert.equal(record?.release, target);
			assert.equal(
				await readlink(path.join(directory, "tooling")),
				path.join(directory, `releases/${target}`),
			);
			assert.equal(await carriesToolingLink(path.join(directory, `releases/${target}`)), true);
		} finally {
			kubectl(
				"delete",
				"namespace",
				namespace,
				`${namespace}-sandboxes`,
				"--ignore-not-found",
				"--wait=false",
			);
			await rm(directory, { recursive: true, force: true });
		}
	},
);

for (const mode of [
	"first install",
	"same major",
	"pending same major",
	"pending first install",
] as const) {
	await test(`signed package recovery: ${mode}`, reconcilerSubprocess, async () => {
		const directory = await mkdtemp(path.join(tmpdir(), "reconcile-release-recovery-"));
		try {
			const fixture = await reconcilerFixture(directory);
			const { helm, log } = await fakeHelm(directory);
			if (mode.includes("same major")) {
				await writeFile(
					path.join(directory, "applied.json"),
					JSON.stringify({ ...fixture.record, channelIdentity: "f".repeat(64) }),
				);
			}
			const result = fixture.run({
				environment: {
					HEPHAESTUS_HELM: helm,
					HEPHAESTUS_SECRETS: directory,
					...(mode.startsWith("pending")
						? {
								HELM_STATUS: "pending-upgrade",
								HELM_HISTORY: '[{"revision":3,"status":"deployed"}]',
							}
						: {}),
				},
			});
			const calls = await log();
			if (mode === "pending first install") {
				assert.notEqual(result.status, 0);
				assert.equal(await readApplied(path.join(directory, "applied.json")), undefined);
				assert.ok(
					!calls.some((call) => call.startsWith("upgrade ") || call.startsWith("rollback ")),
				);
			} else {
				assert.equal(result.status, 0, result.stderr);
				assert.match(calls.at(-1) ?? "", /hephaestus-1\.0\.0\.tgz.*--rollback-on-failure/u);
				assert.equal(
					calls.filter((call) => call.startsWith("rollback ")).length,
					Number(mode === "pending same major"),
				);
				const record = await readApplied(path.join(directory, "applied.json"));
				assert.equal(record?.release, "v1.0.0");
			}
		} finally {
			await rm(directory, { recursive: true, force: true });
		}
	});
}

for (const failure of [
	"none",
	"completed before record",
	"completed with workers",
	"failed migration",
	"installed major downgrade",
	"workers disabled",
	"verification",
	"apply",
	"pending",
	"reload",
	"unitcopy",
	"downgrade",
	"writers",
	"sandbox",
] as const) {
	await test(`signed release channel: ${failure}`, reconcilerSubprocess, async () => {
		const directory = await mkdtemp(path.join(tmpdir(), "reconcile-signed-chart-"));
		try {
			const fixture = await reconcilerFixture(directory);
			const { helm, log } = await fakeHelm(directory);
			const failures: Record<string, Record<string, string>> = {
				"completed before record": {
					HELM_RELEASES: JSON.stringify([
						{ name: "hephaestus", app_version: "1.0.0", status: "deployed" },
					]),
					HELM_VALUES: JSON.stringify({
						release: { version: "v1.0.0", commit: fixture.releaseCommit },
						images: {},
						server: { replicas: 1 },
						worker: { replicas: 0 },
					}),
				},
				"completed with workers": {
					HELM_RELEASES: JSON.stringify([
						{ name: "hephaestus", app_version: "1.0.0", status: "deployed" },
					]),
					HELM_VALUES: JSON.stringify({
						release: { version: "v1.0.0", commit: fixture.releaseCommit },
						images: {},
						server: { replicas: 1 },
						worker: { replicas: 2 },
					}),
				},
				"failed migration": {
					HELM_RELEASES: JSON.stringify([
						{ name: "hephaestus", app_version: "1.0.0", status: "failed" },
					]),
				},
				"installed major downgrade": {
					HELM_RELEASES: JSON.stringify([
						{ name: "hephaestus", app_version: "2.0.0", status: "deployed" },
					]),
				},
				"workers disabled": {
					HELM_VALUES:
						'{"server":{"replicas":0},"worker":{"enabled":false,"replicas":0,"sandbox":{"namespace":""}}}',
				},
				verification: { FAIL_CHART_VERIFY: "1" },
				apply: { FAIL_HELM: "1" },
				pending: { HELM_STATUS: "pending-upgrade" },
				reload: { FAIL_RELOAD: "1" },
				writers: { HELM_VALUES: '{"server":{"replicas":1},"worker":{"replicas":1}}' },
				sandbox: {
					CHART_WORKLOADS:
						'{"items":[{"metadata":{"labels":{"hephaestus.build/sandbox-kind":"review"}}}]}',
				},
			};
			const environment = {
				HEPHAESTUS_HELM: helm,
				HEPHAESTUS_SECRETS: directory,
				...failures[failure],
			};
			if (failure === "unitcopy") {
				await mkdir(path.join(fixture.units, "hephaestus-reconcile.timer"));
			}
			const previous = {
				...fixture.record,
				release: failure === "downgrade" ? "v2.0.0" : "v0.95.1",
			};
			await writeFile(path.join(directory, "applied.json"), JSON.stringify(previous));
			const result = fixture.run({ environment });
			const record = await readApplied(path.join(directory, "applied.json"));
			if (
				failure === "none" ||
				failure === "workers disabled" ||
				failure === "completed before record" ||
				failure === "completed with workers" ||
				failure === "reload" ||
				failure === "unitcopy"
			) {
				assert.equal(record?.release, "v1.0.0");
				const calls = await log();
				const upgrade = calls.find((call) => call.startsWith("upgrade ")) ?? "";
				if (failure === "completed before record" || failure === "completed with workers") {
					assert.equal(upgrade, "", "a completed cutover does not run migration hooks again");
				} else {
					assert.match(upgrade, /chart\/hephaestus-1\.0\.0\.tgz/u);
					assert.match(upgrade, /--set server.replicas=1 --set worker.replicas=0/u);
					assert.doesNotMatch(upgrade, /rollback-on-failure/u);
				}
				if (failure === "reload" || failure === "unitcopy") {
					if (failure === "unitcopy") {
						await rm(path.join(fixture.units, "hephaestus-reconcile.timer"), { recursive: true });
					}
					assert.notEqual(result.status, 0);
					assert.equal(await readlink(path.join(directory, "tooling")), fixture.bootstrap);
					const repaired = fixture.run({ environment: { ...environment, FAIL_RELOAD: "0" } });
					assert.equal(repaired.status, 0, repaired.stderr);
					assert.equal(
						await readlink(path.join(directory, "tooling")),
						path.join(directory, "releases/v1.0.0"),
					);
					const recoveredCalls = await log();
					assert.equal(recoveredCalls.filter((call) => call.startsWith("upgrade ")).length, 1);
				} else {
					assert.equal(result.status, 0, result.stderr);
				}
			} else {
				assert.notEqual(result.status, 0);
				assert.equal(record?.release, previous.release);
				assert.equal(await readlink(path.join(directory, "tooling")), fixture.bootstrap);
				const failedCalls = await log();
				assert.ok(!failedCalls.some((call) => call.startsWith("rollback ")));
				if (failure !== "apply") {
					assert.ok(!failedCalls.some((call) => call.startsWith("upgrade ")));
				}
			}
			assert.doesNotMatch(await fixture.calls(), /^docker /mu);
		} finally {
			await rm(directory, { recursive: true, force: true });
		}
	});
}

for (const [release, direction, allowRollback] of [
	["v1.0.0", "behind", false],
	["v1.0.0", "divergent", false],
	["v1.0.0", "behind", true],
	["v1.0.0", "divergent", true],
	["v0.95.1", "behind", true],
] as const) {
	await test(
		`a commit channel ${direction} ${release} with rollback ${allowRollback} is ${release === "v0.95.1" ? "applied" : "refused"}`,
		reconcilerSubprocess,
		async () => {
			const directory = await mkdtemp(path.join(tmpdir(), "release-commit-order-"));
			try {
				const fixture = await reconcilerFixture(directory, release);
				const { helm, log } = await fakeHelm(directory);
				let target = fixture.commitOf("v0.9.0");
				if (direction === "divergent") {
					gitIn(fixture.checkout, "checkout", "--quiet", "-b", "divergent", target);
					await writeFile(path.join(fixture.checkout, "branch-marker"), "another source history");
					gitIn(fixture.checkout, "add", "branch-marker");
					gitIn(fixture.checkout, "commit", "--quiet", "-m", "divergent source");
					target = gitIn(fixture.checkout, "rev-parse", "HEAD");
					gitIn(fixture.checkout, "push", "--quiet", "origin", "divergent:refs/heads/divergent");
				}
				gitIn(fixture.checkout, "checkout", "--quiet", "deploy-state");
				await writeFile(
					path.join(fixture.checkout, "channels/chart.json"),
					JSON.stringify({ commit: target, images: chartChannelImages(), allowRollback }),
				);
				gitIn(fixture.checkout, "add", "channels/chart.json");
				gitIn(fixture.checkout, "commit", "--quiet", "-m", "commit promotion");
				gitIn(
					fixture.checkout,
					"push",
					"--quiet",
					"origin",
					"deploy-state:refs/heads/deploy-state",
				);
				gitIn(fixture.checkout, "checkout", "--quiet", "main");
				const record = { ...fixture.record, commit: fixture.releaseCommit };
				await writeFile(path.join(directory, "applied.json"), JSON.stringify(record));
				// The first tick finishes adoption of the recorded source before reading a channel.
				assert.equal(
					fixture.run({
						environment: {
							HEPHAESTUS_HELM: helm,
							HEPHAESTUS_SECRETS: directory,
						},
					}).status,
					0,
				);
				const result = fixture.run({
					channel: "chart",
					environment: {
						HEPHAESTUS_HELM: helm,
						HEPHAESTUS_SECRETS: directory,
					},
				});
				if (release === "v0.95.1") {
					assert.equal(result.status, 0, result.stderr);
					const rollbackRecord = await readApplied(path.join(directory, "applied.json"));
					const calls = await log();
					assert.equal(rollbackRecord?.commit, target);
					assert.ok(calls.some((call) => call.startsWith("upgrade ")));
					return;
				}
				assert.notEqual(result.status, 0, result.stderr);
				assert.match(
					result.stderr,
					allowRollback ? /major downgrade requires a database restore/u : /behind the running/u,
				);
				if (allowRollback) {
					assert.match(result.stderr, /pull-based-deployment#recover-a-stopped-cutover/u);
				}
				assert.deepEqual(await readApplied(path.join(directory, "applied.json")), record);
				assert.deepEqual(await log(), []);
			} finally {
				await rm(directory, { recursive: true, force: true });
			}
		},
	);
}

await test(
	"the CLI explains an unpromoted channel without starting a workload",
	reconcilerSubprocess,
	async () => {
		const directory = await mkdtemp(path.join(tmpdir(), "reconcile-missing-channel-"));
		try {
			const fixture = await reconcilerFixture(directory);
			const result = fixture.run({ cli: true, channel: "unpromoted" });
			assert.notEqual(result.status, 0);
			assert.match(result.stderr, /no channels\/unpromoted\.json on deploy-state/u);
			assert.match(result.stderr, /Run the Release \/ Promote workflow/u);
			assert.equal(await readApplied(path.join(directory, "applied.json")), undefined);
			assert.equal(await readlink(path.join(directory, "tooling")), fixture.bootstrap);
			assert.match(
				await readFile(fixture.metricsFile, "utf8"),
				/^hephaestus_deploy_reconcile_success 0$/mu,
			);
			// A failed channel check starts no workload and changes no installed unit.
			assert.doesNotMatch(await fixture.calls(), /cosign|docker|systemctl/u);
		} finally {
			await rm(directory, { recursive: true, force: true });
		}
	},
);

/**
 * A host following the default branch, the one whose disk fills when nothing prunes: `publish`
 * commits to `main` and promotes that commit, and `promote` names a commit again, as a re-promotion
 * or a rollback does.
 */
async function followingHost(directory: string) {
	const fixture = await reconcilerFixture(directory);
	const publisher = path.join(directory, "publisher");
	gitIn(directory, "clone", "--quiet", fixture.origin, publisher);
	gitIn(publisher, "config", "user.email", "ci@example.invalid");
	gitIn(publisher, "config", "user.name", "ci");
	let signatures = 0;
	const promote = async (
		target: string,
		{ allowRollback = false, freeze = false } = {},
	): Promise<void> => {
		gitIn(publisher, "checkout", "--quiet", "deploy-state");
		await writeFile(
			path.join(publisher, "channels/test.json"),
			JSON.stringify({
				commit: target,
				images: chartChannelImages(),
				allowRollback,
				freeze,
			}),
		);
		// As `cosign sign-blob` does, every promotion signs anew, even one naming the same target.
		signatures += 1;
		await writeFile(
			path.join(publisher, "channels/test.json.sigstore.json"),
			`${JSON.stringify({ signature: signatures })}\n`,
		);
		gitIn(publisher, "commit", "--quiet", "-am", `promote ${target}`);
		gitIn(publisher, "push", "--quiet", "origin", "deploy-state");
	};
	let builds = 0;
	const releases = path.join(directory, "releases");
	return {
		...fixture,
		releases,
		promote,
		publish: async (): Promise<string> => {
			gitIn(publisher, "checkout", "--quiet", "main");
			builds += 1;
			await writeFile(path.join(publisher, "build.txt"), `${builds}\n`);
			gitIn(publisher, "add", "build.txt");
			gitIn(publisher, "commit", "--quiet", "-m", `build ${builds}`);
			gitIn(publisher, "push", "--quiet", "origin", "main");
			const built = gitIn(publisher, "rev-parse", "HEAD");
			await promote(built);
			return built;
		},
		/** Promotes another environment, which moves deploy-state, and returns the commit it made. */
		promoteElsewhere: async (): Promise<string> => {
			gitIn(publisher, "checkout", "--quiet", "deploy-state");
			signatures += 1;
			await writeFile(
				path.join(publisher, "channels/production.json"),
				JSON.stringify({ release: "v1.0.0" }),
			);
			await writeFile(
				path.join(publisher, "channels/production.json.sigstore.json"),
				`${JSON.stringify({ signature: signatures })}\n`,
			);
			gitIn(publisher, "add", "channels");
			gitIn(publisher, "commit", "--quiet", "-m", "promote production");
			gitIn(publisher, "push", "--quiet", "origin", "deploy-state");
			return gitIn(publisher, "rev-parse", "HEAD");
		},
		/** Applies what the channel names and returns the output, failing the test if the run failed. */
		apply: (options: Parameters<typeof fixture.run>[0] = {}) => {
			const result = fixture.run(options);
			assert.equal(result.status, 0, result.stderr);
			return result.stdout;
		},
		/** The release trees on disk, sorted. */
		trees: async () => {
			const trees = await readdir(releases);
			return trees.toSorted();
		},
		/** The releases with a lock on disk, sorted. */
		locks: async () => {
			const files = await readdir(path.join(directory, "release-locks"));
			return files.map((file) => file.slice(0, -".env".length)).toSorted();
		},
		record: async () => readApplied(path.join(directory, "applied.json")),
	};
}

const sorted = (...releases: string[]): string[] => releases.toSorted();

await test(
	"a host following the branch keeps the release it runs, the one before it and any tree in use",
	reconcilerSubprocess,
	async () => {
		const directory = await mkdtemp(path.join(tmpdir(), "reconcile-prune-"));
		try {
			const host = await followingHost(directory);
			const first = await host.publish();
			host.apply();
			const second = await host.publish();
			host.apply();
			assert.deepEqual(await host.trees(), sorted(first, second));

			const third = await host.publish();
			const pruned = host.apply();
			assert.deepEqual(await host.trees(), sorted(second, third));
			assert.deepEqual(await host.locks(), sorted(second, third));
			assert.match(pruned, new RegExp(`Removed .*releases/${first}\\n`, "u"));
			const afterThird = await host.record();
			assert.equal(afterThird?.previous, second);

			// A tree someone locked is in use, whatever the host would otherwise decide about it.
			gitIn(
				host.checkout,
				"worktree",
				"lock",
				"--reason",
				"copying acme.json",
				path.join(host.releases, second),
			);
			const fourth = await host.publish();
			assert.match(
				host.apply(),
				new RegExp(`Keeping .*${second}: locked copying acme\\.json`, "u"),
			);
			assert.deepEqual(await host.trees(), sorted(second, third, fourth));

			// An older release's certificate store is ignored by git, and a plain `git worktree remove`
			// deletes ignored files.
			const store = path.join(host.releases, third, ".local/acme.json");
			await mkdir(path.dirname(store), { recursive: true });
			await writeFile(store, '{"letsencrypt":{}}\n');
			const fifth = await host.publish();
			assert.match(host.apply(), new RegExp(`Keeping .*${third}: !! .local/`, "u"));
			assert.equal(await readFile(store, "utf8"), '{"letsencrypt":{}}\n');
			assert.deepEqual(await host.trees(), sorted(second, third, fourth, fifth));
			assert.deepEqual(await host.locks(), sorted(second, third, fourth, fifth));

			// Re-promoting the running commit converges it without losing the rollback target, and
			// the unlocked tree goes with its lock.
			gitIn(host.checkout, "worktree", "unlock", path.join(host.releases, second));
			await host.promote(fifth);
			host.apply();
			const reapplied = await host.record();
			assert.equal(reapplied?.release, fifth);
			assert.equal(reapplied.previous, fourth);
			assert.deepEqual(await host.trees(), sorted(third, fourth, fifth));
			assert.deepEqual(await host.locks(), sorted(third, fourth, fifth));
		} finally {
			await rm(directory, { recursive: true, force: true });
		}
	},
);

await test(
	"a host acts on its own signed request, not on every deploy-state commit",
	reconcilerSubprocess,
	async () => {
		const directory = await mkdtemp(path.join(tmpdir(), "reconcile-request-"));
		try {
			const host = await followingHost(directory);
			const appliedFile = path.join(directory, "applied.json");
			const built = await host.publish();
			host.apply();
			const first = await host.record();
			assert.ok(first?.channelIdentity !== undefined);
			/** What the reconciler ran during `tick`. */
			const callsDuring = async (tick: () => unknown): Promise<string> => {
				const before = await host.calls();
				tick();
				const after = await host.calls();
				return after.slice(before.length);
			};
			const imageOrServiceChanges = /^helm upgrade /mu;

			// Another environment's promotion is verified and accepted, and restarts nothing.
			const elsewhere = await host.promoteElsewhere();
			let calls = await callsDuring(() => {
				assert.match(host.apply(), /No change: already running/u);
			});
			assert.match(calls, /^cosign verify-blob/mu);
			assert.doesNotMatch(calls, imageOrServiceChanges);
			assert.deepEqual(await host.record(), { ...first, channelCommit: elsewhere });
			assert.notEqual(host.run({ failVerification: true }).status, 0);

			// Promoting the running commit again is a new request, retried until it applies, then done.
			await host.promote(built);
			assert.notEqual(host.run({ failVerification: true }).status, 0);
			assert.deepEqual(await host.record(), { ...first, channelCommit: elsewhere });
			assert.match(await callsDuring(() => host.apply()), /^helm upgrade /mu);
			const reapplied = await host.record();
			assert.equal(reapplied?.release, built);
			assert.notEqual(reapplied.channelIdentity, first.channelIdentity);
			assert.doesNotMatch(await callsDuring(() => host.apply()), imageOrServiceChanges);

			// A record from before requests were kept learns the request at its accepted commit.
			await writeFile(appliedFile, JSON.stringify({ ...reapplied, channelIdentity: undefined }));
			calls = await callsDuring(() => {
				assert.match(host.apply(), /No change: already running/u);
			});
			assert.doesNotMatch(calls, imageOrServiceChanges);
			assert.deepEqual(await host.record(), reapplied);

			// Freezing what runs restarts nothing and becomes the request that runs.
			await host.promote(built, { freeze: true });
			calls = await callsDuring(() => {
				assert.match(host.apply(), /No change: already running/u);
			});
			assert.doesNotMatch(calls, imageOrServiceChanges);
			const frozen = await host.record();
			assert.equal(frozen?.release, built);
			assert.notEqual(frozen.channelIdentity, reapplied.channelIdentity);
			assert.match(host.apply(), /No change: already running/u);
		} finally {
			await rm(directory, { recursive: true, force: true });
		}
	},
);

await test(
	"a rollback recreates a pruned tree at the accepted commit and leaves every volume alone",
	reconcilerSubprocess,
	async () => {
		const directory = await mkdtemp(path.join(tmpdir(), "reconcile-rollback-"));
		try {
			const host = await followingHost(directory);
			const first = await host.publish();
			host.apply();
			const second = await host.publish();
			host.apply();
			const third = await host.publish();
			host.apply();
			assert.deepEqual(await host.trees(), sorted(second, third));

			await host.promote(first, { allowRollback: true });
			host.apply();
			const tree = path.join(host.releases, first);
			assert.equal(gitIn(tree, "rev-parse", "HEAD"), first);
			assert.equal(await readlink(path.join(directory, "tooling")), tree);
			const record = await host.record();
			assert.equal(record?.release, first);
			assert.equal(record.previous, third);
			assert.deepEqual(await host.trees(), sorted(first, third));
			assert.deepEqual(await host.locks(), sorted(first, third));
			assert.match(
				await host.calls(),
				new RegExp(`helm upgrade .*${tree}/deploy/chart/hephaestus`, "u"),
			);
		} finally {
			await rm(directory, { recursive: true, force: true });
		}
	},
);

await test(
	"a failed prune leaves the new release running and recorded, and the next apply finishes it",
	reconcilerSubprocess,
	async () => {
		const directory = await mkdtemp(path.join(tmpdir(), "reconcile-failed-prune-"));
		try {
			const host = await followingHost(directory);
			const first = await host.publish();
			host.apply();
			const second = await host.publish();
			host.apply();
			const third = await host.publish();

			const failed = host.run({ failWorktreeRemove: true });
			assert.notEqual(failed.status, 0);
			assert.match(failed.stderr, /git exited with code 1/u);
			const record = await host.record();
			assert.equal(record?.release, third);
			assert.equal(record.previous, second);
			const tree = path.join(host.releases, third);
			assert.equal(await readlink(path.join(directory, "tooling")), tree);
			assert.match(
				await host.calls(),
				new RegExp(`helm upgrade .*${tree}/deploy/chart/hephaestus`, "u"),
			);
			assert.deepEqual(await host.trees(), sorted(first, second, third));

			// The host recovers on its own: the next tick finds the release it runs, and the next apply
			// prunes what this one left.
			assert.match(host.apply(), /No change: already running/u);
			const fourth = await host.publish();
			host.apply();
			assert.deepEqual(await host.trees(), sorted(third, fourth));
			assert.deepEqual(await host.locks(), sorted(third, fourth));
		} finally {
			await rm(directory, { recursive: true, force: true });
		}
	},
);
