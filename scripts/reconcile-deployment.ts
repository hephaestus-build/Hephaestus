import {
	lstat,
	mkdir,
	readFile,
	readlink,
	rename,
	rm,
	stat,
	symlink,
	writeFile,
} from "node:fs/promises";
import { join } from "node:path";

import { requiredEnv } from "./lib/env.ts";
import { asRecord, asString, parseJson, readJsonFile } from "./lib/json.ts";
import { output, run, succeeds } from "./lib/process.ts";

export type Stack = "proxy" | "core" | "app";

/**
 * The application server runs the Liquibase migration the webhook runtime in `core` reads, and the
 * edge comes last so it never routes to a stack that is still starting. The push deploy declares the
 * same order in `.github/workflows/deploy-locked-compose.yml`, and one test holds the two together.
 */
const STACK_ORDER: readonly Stack[] = ["app", "core", "proxy"];

/**
 * The application server never reports ready without the broker, and the broker lives in the `core`
 * project, which Compose cannot express as a dependency. PostgreSQL needs no entry: it sits in `app`
 * beside the services that declare `depends_on` on it.
 */
const FOUNDATION: Partial<Record<Stack, readonly string[]>> = {
	core: ["nats-server"],
};

/**
 * A black-holed connection must not hold a tick until the unit's start timeout; the next tick
 * retries, and the failure metric says why this one did not.
 */
const FETCH_TIMEOUT_MS = 5 * 60_000;

/** The units this host runs, kept at the applied release by `syncUnits`. */
const UNIT_FILES = ["hephaestus-reconcile.service", "hephaestus-reconcile.timer"] as const;
const SYSTEMD_UNITS = "/etc/systemd/system";

export interface Channel {
	/** What this channel asks the host to run: a release tag, or the commit a build came from. */
	release: string;
	/** Set only by a commit channel: the digests to run, pinned by the channel's own signature. */
	images?: Readonly<Record<string, string>>;
	allowRollback?: boolean;
	freeze?: boolean;
}

export interface AppliedState {
	release: string;
	channelCommit: string;
	appliedAt: string;
	/** The release's source commit as the signed lock named it; absent in records written earlier. */
	commit?: string;
}

export type Decision =
	| { action: "apply"; release: string }
	| { action: "noop"; reason: string }
	| { action: "refuse"; reason: string };

export const RELEASE_TAG = /^v(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)$/;
const CHANNEL_NAME = /^[a-z0-9]+(?:-[a-z0-9]+)*$/;
const COMMIT_SHA = /^[0-9a-f]{40}$/;
const IMAGE_KEY = /^HEPHAESTUS_IMAGE_[A-Z0-9_]+$/;
const IMAGE_DIGEST = /^[^\s@]+@sha256:[0-9a-f]{64}$/;

export function isCommit(value: string): boolean {
	return COMMIT_SHA.test(value);
}

/** What a channel may ask a host to run: a published release, or a commit of the default branch. */
export function isTarget(value: string): boolean {
	return RELEASE_TAG.test(value) || isCommit(value);
}

function optionalBoolean(value: unknown, label: string): boolean {
	if (value === undefined) return false;
	if (typeof value !== "boolean") throw new TypeError(`${label} must be a boolean`);
	return value;
}

/**
 * A channel names either a release or a commit.
 *
 * A release is a published artifact: its lock, provenance and signature are fetched and verified
 * before anything runs, which is what production is promoted with. A commit is a build of the
 * default branch, and carries the digests to run in the channel itself — they are covered by the
 * channel's own signature, so the same authority stands behind them without a release having to
 * exist. That is what lets an environment follow the default branch continuously.
 */
export function parseChannel(value: unknown): Channel {
	const record = asRecord(value, "channel");
	const allowRollback = optionalBoolean(record.allowRollback, "channel.allowRollback");
	const freeze = optionalBoolean(record.freeze, "channel.freeze");

	if (record.commit !== undefined) {
		if (record.release !== undefined)
			throw new Error("channel names both a release and a commit; it must name one");
		const commit = asString(record.commit, "channel.commit");
		if (!isCommit(commit))
			throw new Error(`channel.commit must be a full 40-character commit, not ${commit}`);
		return { release: commit, images: parseImages(record.images), allowRollback, freeze };
	}

	const release = asString(record.release, "channel.release");
	if (!RELEASE_TAG.test(release))
		throw new Error(`channel.release must be an immutable vX.Y.Z tag, not ${release}`);
	return { release, allowRollback, freeze };
}

/** The channel file the promotion signs, in the shape `parseChannel` reads back. */
export function serializeChannel(channel: Channel): string {
	const { release, images, allowRollback = false, freeze = false } = channel;
	const record = images
		? { commit: release, images, allowRollback, freeze }
		: { release, allowRollback, freeze };
	return `${JSON.stringify(record, null, "\t")}\n`;
}

/**
 * The images a commit channel pins, as the environment the Compose files read. Every value is a
 * digest: a tag would let the registry answer with something else later, which is the whole reason
 * the release path pins digests too.
 */
function parseImages(value: unknown): Readonly<Record<string, string>> {
	const record = asRecord(value, "channel.images");
	const images: Record<string, string> = {};
	for (const [key, reference] of Object.entries(record)) {
		if (!IMAGE_KEY.test(key)) throw new Error(`channel.images has an unusable name ${key}`);
		const pinned = asString(reference, `channel.images.${key}`);
		if (!IMAGE_DIGEST.test(pinned))
			throw new Error(`channel.images.${key} must be pinned by digest, not ${pinned}`);
		images[key] = pinned;
	}
	if (Object.keys(images).length === 0) throw new Error("channel.images names no image");
	return images;
}

function compareReleases(left: string, right: string): number {
	const parts = (release: string) => release.slice(1).split(".");
	const leftParts = parts(left);
	const rightParts = parts(right);
	for (const [index, leftPart] of leftParts.entries()) {
		const rightPart = rightParts[index];
		if (rightPart === undefined) throw new Error(`invalid release ${right}`);
		if (leftPart.length !== rightPart.length) return leftPart.length - rightPart.length;
		if (leftPart !== rightPart) return leftPart < rightPart ? -1 : 1;
	}
	return 0;
}

export function decide(
	channel: Channel,
	applied: AppliedState | undefined,
	channelCommit: string,
	advances: boolean,
	/** Whether what the channel asks for is behind what is already running. */
	targetPrecedesApplied = false,
): Decision {
	if (applied && channelCommit !== applied.channelCommit && !advances)
		return {
			action: "refuse",
			reason: `channel commit ${channelCommit.slice(0, 8)} does not descend from the last accepted channel`,
		};
	if (channel.freeze) return { action: "noop", reason: "channel is frozen" };
	// A new channel commit naming the release already applied is a re-promotion, and re-applying is
	// how a host that was hand-patched during an incident converges again.
	if (applied?.release === channel.release && applied.channelCommit === channelCommit)
		return { action: "noop", reason: `already running ${channel.release}` };
	// Two builds of the default branch can finish out of order, and the later-finishing older build
	// writes the newer channel commit — so channel ancestry says nothing about which build a channel
	// carries. What runs has to be compared with what is asked for, and for commits only the host's
	// clone can answer that, so the caller resolves it and passes the answer in.
	if (applied && targetPrecedesApplied && !channel.allowRollback)
		return {
			action: "refuse",
			reason: `${channel.release} is behind the running ${applied.release}; set allowRollback to move back deliberately`,
		};
	// Releases are also ordered by version, which catches a rewind without consulting a clone.
	const ordered = RELEASE_TAG.test(channel.release) && RELEASE_TAG.test(applied?.release ?? "");
	if (
		applied &&
		ordered &&
		compareReleases(channel.release, applied.release) < 0 &&
		!channel.allowRollback
	)
		return {
			action: "refuse",
			reason: `${channel.release} precedes ${applied.release}; set allowRollback to move back deliberately`,
		};
	return { action: "apply", release: channel.release };
}

/**
 * The environment a commit channel's images become, in the shape the Compose files already read.
 * IMAGE_TAG is what the webapp reports as its version, so an environment following the default
 * branch reports the commit it is running rather than a release it is not.
 */
export function commitLockEnvironment(
	commit: string,
	images: Readonly<Record<string, string>>,
): string {
	const lines = [`IMAGE_TAG=${commit}`, `HEPHAESTUS_RELEASE_COMMIT=${commit}`];
	for (const key of Object.keys(images).toSorted()) lines.push(`${key}=${images[key]}`);
	return `${lines.join("\n")}\n`;
}

export function renderMetrics(state: {
	channel: string;
	release: string;
	commit: string;
	success: boolean;
	now: Date;
	lastSuccessAt?: Date;
	/** The applied record predates the kept commit, so the tooling waits for the next apply. */
	toolingPending?: boolean;
}): string {
	const seconds = (date: Date) => Math.floor(date.getTime() / 1000);
	const lines = [
		"# HELP hephaestus_deploy_info The release this host currently runs.",
		"# TYPE hephaestus_deploy_info gauge",
		`hephaestus_deploy_info{channel="${state.channel}",release="${state.release}",channel_commit="${state.commit}"} 1`,
		"# HELP hephaestus_deploy_reconcile_success Whether the last reconcile attempt succeeded.",
		"# TYPE hephaestus_deploy_reconcile_success gauge",
		`hephaestus_deploy_reconcile_success ${state.success ? 1 : 0}`,
		"# HELP hephaestus_deploy_reconcile_timestamp_seconds When the last reconcile attempt ran.",
		"# TYPE hephaestus_deploy_reconcile_timestamp_seconds gauge",
		`hephaestus_deploy_reconcile_timestamp_seconds ${seconds(state.now)}`,
		"# HELP hephaestus_deploy_tooling_pending Whether the host still waits for an apply to record the tooling it should run.",
		"# TYPE hephaestus_deploy_tooling_pending gauge",
		`hephaestus_deploy_tooling_pending ${state.toolingPending ? 1 : 0}`,
	];
	if (state.lastSuccessAt) {
		lines.push(
			"# HELP hephaestus_deploy_last_success_timestamp_seconds When the release this host runs was applied.",
			"# TYPE hephaestus_deploy_last_success_timestamp_seconds gauge",
			`hephaestus_deploy_last_success_timestamp_seconds ${seconds(state.lastSuccessAt)}`,
		);
	}
	return `${lines.join("\n")}\n`;
}

/**
 * The lock only guarantees anything if nothing the release renders escapes it: a compose file that
 * hardcoded a tag would otherwise deploy an image no signature covers.
 */
export function unlockedImages(rendered: readonly string[], lockEnv: string): string[] {
	const locked = new Set(
		lockEnv
			.split("\n")
			.map((line) => line.slice(line.indexOf("=") + 1).trim())
			.filter((value) => value.includes("@sha256:")),
	);
	return rendered.filter((image) => !locked.has(image));
}

function lockValues(lockEnv: string, key: string): string[] {
	return lockEnv
		.split("\n")
		.filter((line) => line.startsWith(`${key}=`))
		.map((line) => line.slice(key.length + 1));
}

export function lockedReleaseCommit(lockEnv: string): string {
	const values = lockValues(lockEnv, "HEPHAESTUS_RELEASE_COMMIT");
	if (values.length !== 1 || !isCommit(values[0] ?? ""))
		throw new Error("release lock must contain one source commit");
	return values[0] ?? "";
}

const POSTGRES_IMAGE = "HEPHAESTUS_IMAGE_POSTGRES";

/** The PostgreSQL image's build context and Dockerfile; CI's `postgres-image` filter is that tree. */
export const POSTGRES_IMAGE_INPUTS = "docker/postgres";

/**
 * The images a commit channel runs on this host: the channel's, except that PostgreSQL stays at the
 * pin the host last applied while the image's own inputs are unchanged.
 *
 * Every push to the default branch rebuilds the PostgreSQL image — `docker/postgres/Dockerfile` says
 * why — so a commit channel names a new digest for it on every commit. Compose recreates a container
 * whose image changed, and a recreated database drops every connection pool and kills the reviews in
 * flight. A host following commits therefore moves PostgreSQL only when what it is built from moved.
 * A release is applied as signed: its lock is the artifact its evidence describes, and a rebuilt
 * image is part of it.
 */
export function carryPostgresImage(
	images: Readonly<Record<string, string>>,
	appliedLockEnv: string | undefined,
	inputsChanged: boolean,
): Readonly<Record<string, string>> {
	const pinned = images[POSTGRES_IMAGE];
	if (inputsChanged || appliedLockEnv === undefined || pinned === undefined) return images;
	const [kept, ...rest] = lockValues(appliedLockEnv, POSTGRES_IMAGE);
	if (kept === undefined || rest.length > 0 || !IMAGE_DIGEST.test(kept)) return images;
	// The digest is kept, never the repository it sits in: a channel naming the image somewhere else
	// is naming a different image rather than a rebuild of this one.
	if (kept.slice(0, kept.indexOf("@")) !== pinned.slice(0, pinned.indexOf("@"))) return images;
	return { ...images, [POSTGRES_IMAGE]: kept };
}

function isStack(name: string): name is Stack {
	return STACK_ORDER.some((stack) => stack === name);
}

export function parseStacks(value: string | undefined): Stack[] {
	const names = (value ?? "").split(/[\s,]+/).filter(Boolean);
	if (names.length === 0) throw new Error("HEPHAESTUS_STACKS must name at least one stack");
	const unknown = names.filter((name) => !isStack(name));
	if (unknown.length > 0) throw new Error(`unknown stack(s): ${unknown.join(", ")}`);
	return STACK_ORDER.filter((stack) => names.includes(stack));
}

interface HostConfig {
	channel: string;
	stacks: Stack[];
	checkout: string;
	/** A link to the tree whose tooling this host runs: the checkout until the first apply. */
	tooling: string;
	stateDirectory: string;
	secretsDirectory: string;
	metricsFile?: string;
	waitTimeoutSeconds: number;
	promoteIdentity: string;
}

function hostConfig(environment: NodeJS.ProcessEnv): HostConfig {
	const channel = requiredEnv(environment, "HEPHAESTUS_CHANNEL");
	if (!CHANNEL_NAME.test(channel))
		throw new Error("HEPHAESTUS_CHANNEL must contain lowercase letters, digits, and hyphens");
	const stateDirectory = environment.STATE_DIRECTORY ?? "/var/lib/hephaestus";
	const waitTimeoutSeconds = Number(environment.HEPHAESTUS_WAIT_TIMEOUT ?? 600);
	if (!Number.isSafeInteger(waitTimeoutSeconds) || waitTimeoutSeconds <= 0)
		throw new Error("HEPHAESTUS_WAIT_TIMEOUT must be a positive integer");
	return {
		channel,
		stacks: parseStacks(environment.HEPHAESTUS_STACKS),
		checkout: join(stateDirectory, "checkout"),
		tooling: join(stateDirectory, "tooling"),
		stateDirectory,
		secretsDirectory: environment.HEPHAESTUS_SECRETS ?? "/etc/hephaestus",
		metricsFile: environment.HEPHAESTUS_METRICS_FILE,
		waitTimeoutSeconds,
		promoteIdentity: requiredEnv(environment, "HEPHAESTUS_PROMOTE_IDENTITY"),
	};
}

export async function readApplied(file: string): Promise<AppliedState | undefined> {
	try {
		const record = asRecord(await readJsonFile(file), "applied state");
		const applied = {
			release: asString(record.release, "applied.release"),
			channelCommit: asString(record.channelCommit, "applied.channelCommit"),
			appliedAt: asString(record.appliedAt, "applied.appliedAt"),
			...(record.commit === undefined ? {} : { commit: asString(record.commit, "applied.commit") }),
		};
		if (!isTarget(applied.release))
			throw new Error("applied.release must be a vX.Y.Z tag or a commit");
		if (!isCommit(applied.channelCommit))
			throw new Error("applied.channelCommit must be a Git commit");
		if (applied.commit !== undefined && !COMMIT_SHA.test(applied.commit))
			throw new Error("applied.commit must be a Git commit");
		if (
			!Number.isFinite(Date.parse(applied.appliedAt)) ||
			new Date(applied.appliedAt).toISOString() !== applied.appliedAt
		)
			throw new Error("applied.appliedAt must be an ISO timestamp");
		return applied;
	} catch (error) {
		if (error instanceof Error && "code" in error && error.code === "ENOENT") return undefined;
		throw error;
	}
}

/** Renamed into place: a scrape landing mid-write would otherwise publish a truncated series. */
async function writeAtomic(file: string, contents: string): Promise<void> {
	const temporary = `${file}.tmp`;
	await writeFile(temporary, contents, { mode: 0o644 });
	await rename(temporary, file);
}

function fetchOptions(config: HostConfig): { cwd: string; signal: AbortSignal } {
	return { cwd: config.checkout, signal: AbortSignal.timeout(FETCH_TIMEOUT_MS) };
}

async function main(): Promise<void> {
	const config = hostConfig(process.env);
	const appliedFile = join(config.stateDirectory, "applied.json");
	const applied = await readApplied(appliedFile);
	const startedAt = new Date();

	await mkdir(config.stateDirectory, { recursive: true });
	// Before anything else, and in particular before the channel is parsed: a tick that stopped between
	// recording a release and adopting its tooling would otherwise read the next channel with the
	// tooling that release replaced. The recorded release's tree is rebuilt at the commit the signed
	// lock named if it is gone and checked either way, so what is adopted is that release and nothing
	// that happens to sit at its path. Node keeps running the module it loaded, so when the link
	// moved, this tick ends here and the next one runs the adopted tooling.
	if (applied) {
		const commit = appliedCommit(applied);
		if (commit === undefined) {
			// Nothing on disk tells an accepted tree from one a failed re-promotion staged, so a record
			// from before the commit was kept is never completed from a tree or a lock: the next apply
			// records the commit, and until then the host reports the tooling as pending.
			console.log(
				`${applied.release} was recorded before its commit was kept; keeping the current tooling until the next apply`,
			);
		} else {
			const { tree } = await ensureReleaseTree(
				config.checkout,
				releasesDirectory(config),
				applied.release,
				commit,
			);
			if (await followTooling(config, tree)) {
				console.log(`Adopted the tooling of ${applied.release}; the next run uses it`);
				return;
			}
		}
	}

	await run(
		"git",
		["fetch", "--quiet", "origin", "+refs/heads/deploy-state:refs/remotes/origin/deploy-state"],
		fetchOptions(config),
	);
	// Trimmed because the SHA is compared and interpolated; the blobs below are not, since the
	// channel must reach cosign byte for byte as it was signed.
	const channelCommit = (
		await output("git", ["rev-parse", "refs/remotes/origin/deploy-state"], {
			cwd: config.checkout,
		})
	).trim();
	const channelPath = `channels/${config.channel}.json`;
	const channelJson = await output("git", ["show", `${channelCommit}:${channelPath}`], {
		cwd: config.checkout,
	});
	const signature = await output("git", ["show", `${channelCommit}:${channelPath}.sigstore.json`], {
		cwd: config.checkout,
	});

	// Verifying before parsing keeps unverified bytes from reaching any decision.
	const scratch = join(config.stateDirectory, "channel");
	await mkdir(scratch, { recursive: true });
	await writeFile(join(scratch, "channel.json"), channelJson);
	await writeFile(join(scratch, "channel.sigstore.json"), signature);
	await run("cosign", [
		"verify-blob",
		"--bundle",
		join(scratch, "channel.sigstore.json"),
		// Cosign cannot verify the certificate's environment claim, so the gate is expressed as the
		// identity of the workflow the environment protects: only an approved run can produce it.
		"--certificate-identity",
		config.promoteIdentity,
		"--certificate-oidc-issuer",
		"https://token.actions.githubusercontent.com",
		join(scratch, "channel.json"),
	]);

	const channel = parseChannel(parseJson(channelJson));
	const advances =
		applied && channelCommit !== applied.channelCommit
			? await succeeds(
					"git",
					["merge-base", "--is-ancestor", applied.channelCommit, channelCommit],
					{
						cwd: config.checkout,
					},
				)
			: true;
	// Whether the target is behind what runs. Both are commits of the default branch here, so the
	// clone answers it directly; between releases the version comparison in decide covers it, and a
	// change of form is a deliberate move only a version or an operator can order.
	//
	// The branch is fetched first: without the objects, `merge-base` fails and a failure would read
	// as "not behind", which is the wrong way for this check to be wrong.
	let targetPrecedesApplied = false;
	if (applied && !RELEASE_TAG.test(channel.release) && !RELEASE_TAG.test(applied.release)) {
		await run(
			"git",
			["fetch", "--quiet", "origin", "+refs/heads/main:refs/remotes/origin/main"],
			fetchOptions(config),
		);
		for (const commit of [channel.release, applied.release])
			if (
				!(await succeeds("git", ["cat-file", "-e", `${commit}^{commit}`], { cwd: config.checkout }))
			)
				throw new Error(
					`cannot order ${channel.release} against ${applied.release}: ${commit} is unknown here`,
				);
		targetPrecedesApplied =
			channel.release !== applied.release &&
			(await succeeds("git", ["merge-base", "--is-ancestor", channel.release, applied.release], {
				cwd: config.checkout,
			}));
	}
	const decision = decide(channel, applied, channelCommit, advances, targetPrecedesApplied);

	if (decision.action === "refuse") throw new Error(decision.reason);
	if (decision.action === "noop") {
		console.log(`No change: ${decision.reason}`);
		if (applied && applied.channelCommit !== channelCommit) {
			await writeAtomic(
				appliedFile,
				`${JSON.stringify({ ...applied, channelCommit }, null, "\t")}\n`,
			);
		}
		if (config.metricsFile && applied) {
			await writeAtomic(
				config.metricsFile,
				renderMetrics({
					channel: config.channel,
					release: applied.release,
					commit: channelCommit,
					success: true,
					now: startedAt,
					lastSuccessAt: new Date(applied.appliedAt),
					toolingPending: appliedCommit(applied) === undefined,
				}),
			);
		}
		return;
	}

	// A release arrives as a tag; a commit is only reachable through the branch it is on, because a
	// server does not serve an arbitrary object by name unless it is configured to.
	await run(
		"git",
		RELEASE_TAG.test(decision.release)
			? ["fetch", "--quiet", "origin", "tag", decision.release, "--no-tags"]
			: ["fetch", "--quiet", "origin", "+refs/heads/main:refs/remotes/origin/main"],
		fetchOptions(config),
	);
	const releaseCommit = (
		await output("git", ["rev-parse", `${decision.release}^{commit}`], { cwd: config.checkout })
	).trim();
	const { tree: releaseTree } = await ensureReleaseTree(
		config.checkout,
		releasesDirectory(config),
		decision.release,
		releaseCommit,
	);

	const lockDirectory = join(config.stateDirectory, "release-locks");
	await mkdir(lockDirectory, { recursive: true });
	const lockFile = join(lockDirectory, `${decision.release}.env`);
	if (channel.images) {
		// A commit channel carries its own digests, and the channel file they arrived in was
		// signature-verified before this point, so there is no release to fetch or verify. The
		// version the instance reports is the commit, which is what the environment is following.
		const images = await commitImages(
			config.checkout,
			lockDirectory,
			applied,
			releaseCommit,
			channel.images,
		);
		await writeFile(lockFile, commitLockEnvironment(decision.release, images), { mode: 0o600 });
	} else {
		// The verifier is the tooling this tick runs, never the release's own copy of it.
		await run(
			process.execPath,
			[join(import.meta.dirname, "prepare-release-lock.ts"), decision.release, lockFile],
			{ cwd: releaseTree },
		);
	}

	const lockEnv = await readFile(lockFile, "utf8");
	if (!channel.images && lockedReleaseCommit(lockEnv) !== releaseCommit)
		throw new Error(`signed release lock does not cover the ${decision.release} source tree`);

	const composeFor = (stack: Stack): string[] => [
		"compose",
		"--project-name",
		stack,
		"--env-file",
		join(config.secretsDirectory, `${stack}.env`),
		"--env-file",
		lockFile,
		"--file",
		join(releaseTree, `docker/compose.${stack}.yaml`),
	];

	// Every stack is verified before any container starts, so a release that renders an unlocked image
	// cannot get one running in the window before the guard refuses it.
	const composeArgsByStack = new Map<Stack, string[]>();
	for (const stack of config.stacks) {
		const composeArgs = composeFor(stack);
		const rendered = asRecord(
			parseJson(
				await output("docker", [...composeArgs, "config", "--format", "json"], {
					cwd: releaseTree,
				}),
			),
			`${stack} configuration`,
		);
		const images = Object.values(asRecord(rendered.services, `${stack}.services`)).map((service) =>
			asString(asRecord(service, `${stack} service`).image, `${stack} service image`),
		);
		const unlocked = unlockedImages(images, lockEnv);
		if (unlocked.length > 0)
			throw new Error(`${stack} renders images outside the release lock: ${unlocked.join(", ")}`);
		composeArgsByStack.set(stack, composeArgs);
	}

	for (const [stack, composeArgs] of composeArgsByStack) {
		const foundation = FOUNDATION[stack];
		if (!foundation) continue;
		await run(
			"docker",
			[
				...composeArgs,
				"up",
				"--detach",
				"--wait",
				`--wait-timeout=${config.waitTimeoutSeconds}`,
				...foundation,
			],
			{ cwd: releaseTree },
		);
	}

	for (const [, composeArgs] of composeArgsByStack) {
		await run(
			"docker",
			[
				...composeArgs,
				"up",
				"--detach",
				"--wait",
				`--wait-timeout=${config.waitTimeoutSeconds}`,
				// A service the release removed would otherwise keep running, unmanaged.
				"--remove-orphans",
			],
			{ cwd: releaseTree },
		);
	}

	const finishedAt = new Date();
	await writeAtomic(
		appliedFile,
		`${JSON.stringify({ release: decision.release, channelCommit, appliedAt: finishedAt.toISOString(), commit: releaseCommit }, null, "\t")}\n`,
	);
	if (config.metricsFile)
		await writeAtomic(
			config.metricsFile,
			renderMetrics({
				channel: config.channel,
				release: decision.release,
				commit: channelCommit,
				success: true,
				now: finishedAt,
				lastSuccessAt: finishedAt,
			}),
		);
	console.log(`Applied ${decision.release} to ${config.stacks.join(", ")}`);
	// Only now, with the release verified and running, does the host run that release's tooling.
	await followTooling(config, releaseTree);
}

/**
 * `carryPostgresImage` with what this host knows: the lock it wrote for the applied release, which
 * is its own record of the pins it verified and ran, and git's word on whether the PostgreSQL
 * image's inputs changed between the applied commit and the one being applied.
 */
export async function commitImages(
	checkout: string,
	lockDirectory: string,
	applied: AppliedState | undefined,
	releaseCommit: string,
	images: Readonly<Record<string, string>>,
): Promise<Readonly<Record<string, string>>> {
	if (applied === undefined) return images;
	const previous = appliedCommit(applied);
	if (previous === undefined) return images;
	let appliedLockEnv: string | undefined;
	try {
		appliedLockEnv = await readFile(join(lockDirectory, `${applied.release}.env`), "utf8");
	} catch (error) {
		if (!(error instanceof Error && "code" in error && error.code === "ENOENT")) throw error;
	}
	// `--quiet` exits 0 only when nothing under the path differs. A commit the checkout does not have
	// fails it too, and that reads as changed: when the host cannot tell, it runs what the channel
	// names rather than keep an image on a guess.
	const unchanged = await succeeds(
		"git",
		["diff", "--quiet", previous, releaseCommit, "--", POSTGRES_IMAGE_INPUTS],
		{ cwd: checkout },
	);
	return carryPostgresImage(images, appliedLockEnv, !unchanged);
}

/**
 * The host runs the tooling of the tree it applied: its reconciler through the `tooling` link and its
 * units in systemd. A tree that predates the link is never adopted — its unit would run the checkout
 * and its reconciler could not read a current channel — so a rollback to one keeps the tooling the
 * host has. Every step is idempotent, because a tick can stop between any two of them.
 */
async function followTooling(config: HostConfig, tree: string): Promise<boolean> {
	if (!(await carriesToolingLink(tree))) {
		console.log(`Keeping the current tooling: ${tree} predates the tooling link`);
		return false;
	}
	const moved = await adoptTooling(config.tooling, tree);
	const changed = await syncUnits(tree, SYSTEMD_UNITS);
	if (changed.length > 0) console.log(`Updated ${changed.join(", ")}`);
	// systemd itself knows whether the units it loaded match the files, so a tick that stopped
	// between writing a unit and reloading is finished by the next one.
	const stale = await output("systemctl", [
		"show",
		"--property=NeedDaemonReload",
		"--value",
		...UNIT_FILES,
	]);
	if (stale.split("\n").includes("yes")) await run("systemctl", ["daemon-reload"]);
	return moved;
}

function releasesDirectory(config: HostConfig): string {
	return join(config.stateDirectory, "releases");
}

/** The accepted source commit a record names: kept since it was recorded, or the release itself. */
export function appliedCommit(applied: AppliedState): string | undefined {
	return applied.commit ?? (COMMIT_SHA.test(applied.release) ? applied.release : undefined);
}

/**
 * The worktree for `release` under `releases`, created at `commit` when it is missing and checked
 * either way: its HEAD is that commit and no tracked file differs. The commit is what was accepted,
 * never re-read from a tag, so a moved tag changes nothing. `--force` twice lets git recreate a path
 * it still has registered — even locked — after someone deleted the directory.
 */
export async function ensureReleaseTree(
	checkout: string,
	releases: string,
	release: string,
	commit: string,
): Promise<{ tree: string; commit: string }> {
	const tree = join(releases, release);
	if (await isDirectory(tree)) {
		const head = (await output("git", ["rev-parse", "HEAD"], { cwd: tree })).trim();
		if (head !== commit)
			throw new Error(`${tree} is at ${head}, not the ${commit} accepted for ${release}`);
	} else {
		await run(
			"git",
			["worktree", "add", "--detach", "--force", "--force", "--quiet", tree, commit],
			{
				cwd: checkout,
			},
		);
	}
	// Tracked content only: Compose writes into the worktree it renders from — the proxy stack's ACME
	// store is `docker/letsencrypt/` — so counting untracked files would turn every retry after a
	// partial apply into a permanent refusal. An untracked file cannot alter a tracked Compose file,
	// and anyone who can write here already has the Docker socket.
	const changes = await output("git", ["status", "--porcelain=v1", "--untracked-files=no"], {
		cwd: tree,
	});
	if (changes) throw new Error(`${tree} differs from ${release}`);
	return { tree, commit };
}

/** Unlike `existsSync`, this reports a lookup that failed for any reason other than absence. */
async function isDirectory(path: string): Promise<boolean> {
	try {
		return (await stat(path)).isDirectory();
	} catch (error) {
		if (error instanceof Error && "code" in error && error.code === "ENOENT") return false;
		throw error;
	}
}

/** Whether a tree's own unit starts the reconciler through the tooling link, i.e. carries this model. */
export async function carriesToolingLink(tree: string): Promise<boolean> {
	let unit: string;
	try {
		unit = await readFile(join(tree, "docker/self-host/systemd", UNIT_FILES[0]), "utf8");
	} catch (error) {
		if (error instanceof Error && "code" in error && error.code === "ENOENT") return false;
		throw error;
	}
	return /^ExecStart=.*\/var\/lib\/hephaestus\/tooling\//m.test(unit);
}

/**
 * Moves the tooling link to `tree` in one rename, so a failure leaves the current tooling in place,
 * and reports whether it moved.
 */
export async function adoptTooling(link: string, tree: string): Promise<boolean> {
	if ((await readlink(link).catch(() => undefined)) === tree) return false;
	const staged = `${link}.next`;
	await rm(staged, { force: true });
	await symlink(tree, staged);
	await rename(staged, link);
	return true;
}

/**
 * Brings the units systemd reads to the copies in `tree` and returns the names it rewrote. They are
 * copies rather than links because systemd needs them on the root filesystem at boot, and each is
 * replaced by one rename, which also retires a link an earlier install may have left.
 */
export async function syncUnits(tree: string, unitsDirectory: string): Promise<string[]> {
	const changed: string[] = [];
	for (const name of UNIT_FILES) {
		const wanted = await readFile(join(tree, "docker/self-host/systemd", name), "utf8");
		const unit = join(unitsDirectory, name);
		const installed = await lstat(unit).catch(() => undefined);
		if (installed && !installed.isSymbolicLink() && (await readFile(unit, "utf8")) === wanted)
			continue;
		await writeAtomic(unit, wanted);
		changed.push(name);
	}
	return changed;
}

/** Without a series on failure, a broken reconcile is indistinguishable from a host running none. */
async function reportFailure(): Promise<void> {
	const metricsFile = process.env.HEPHAESTUS_METRICS_FILE;
	const channel = process.env.HEPHAESTUS_CHANNEL;
	if (!metricsFile || !channel) return;
	const applied = await readApplied(
		join(process.env.STATE_DIRECTORY ?? "/var/lib/hephaestus", "applied.json"),
	);
	await writeAtomic(
		metricsFile,
		renderMetrics({
			channel,
			release: applied?.release ?? "none",
			commit: applied?.channelCommit ?? "none",
			success: false,
			now: new Date(),
			lastSuccessAt: applied ? new Date(applied.appliedAt) : undefined,
			// Pending is a fact about the record, so a failed run must not read as the apply that ends it.
			toolingPending: applied !== undefined && appliedCommit(applied) === undefined,
		}),
	);
}

// The unit runs this file through the tooling link, and `process.argv[1]` keeps that path while
// `import.meta.filename` is the resolved one; only `import.meta.main` compares nothing.
if (import.meta.main) {
	try {
		await main();
	} catch (error) {
		// An unwritable metric must not replace the error that caused the failure.
		await reportFailure().catch(() => {});
		throw error;
	}
}
