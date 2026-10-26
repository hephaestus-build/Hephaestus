import { createHash } from "node:crypto";
import {
	lstat,
	mkdir,
	readdir,
	readFile,
	readlink,
	realpath,
	rename,
	rm,
	stat,
	statfs,
	symlink,
	writeFile,
} from "node:fs/promises";
import path from "node:path";

import { isCommit, parseInventory } from "./commit-image-lock.ts";
import { commitChartValues, renderChartValues } from "./kubernetes-chart.ts";
import { isSet, requiredEnv } from "./lib/env.ts";
import { asRecord, asString, parseJson, readJsonFile } from "./lib/json.ts";
import { output, run, succeeds } from "./lib/process.ts";

export type Stack = "proxy" | "core" | "app" | "chart";

/**
 * The `app` stack's migration service applies the schema the webhook runtime in `core` waits for, and
 * the edge comes last so it never routes to a stack that is still starting. `chart` is the whole
 * release on Kubernetes and stands alone.
 */
const STACK_ORDER: readonly Stack[] = ["app", "core", "proxy", "chart"];

/**
 * The application server never reports ready without the broker, and the broker lives in the `core`
 * project, which Compose cannot express as a dependency. PostgreSQL needs no entry: it sits in `app`
 * beside the services that declare `depends_on` on it.
 */
const FOUNDATION: Partial<Record<Stack, readonly string[]>> = {
	core: ["nats-server"],
};

/**
 * The `app` stack's one-shot migration, run before the worker stops and any stack replaces its
 * services (docs/admin/runtime-roles.mdx#database-migrations). `run` returns its exit status;
 * `up --wait` returns once it starts, and `wait` fails once it has exited. A release from before the
 * migrate run mode declares no such service.
 */
const MIGRATION_SERVICE = "database-migration";

/**
 * A black-holed connection must not hold a tick until the unit's start timeout; the next tick
 * retries, and the failure metric says why this one did not.
 */
const FETCH_TIMEOUT_MS = 5 * 60_000;

/** The units this host runs, kept at the applied release by `syncUnits`. */
const UNIT_FILES = ["hephaestus-reconcile.service", "hephaestus-reconcile.timer"] as const;
const SYSTEMD_UNITS = "/etc/systemd/system";

/**
 * A condition an operator resolves rather than a defect to diagnose — a host waiting for its first
 * promotion is the one that reaches production. The entry point prints the message and exits
 * non-zero; a stack trace would say a failure happened here, when what happened is that nothing has
 * been promoted yet.
 */
export class OperatorActionRequiredError extends Error {
	constructor(message: string) {
		super(message);
		this.name = "OperatorActionRequiredError";
	}
}

export interface Channel {
	/** What this channel asks the host to run: a release tag, or the commit a build came from. */
	release: string;
	/** Set only by a commit channel: the digests to run, pinned by the channel's own signature. */
	images?: Readonly<Record<string, string>>;
	allowRollback?: boolean;
	freeze?: boolean;
	/** Take this channel's PostgreSQL pin now instead of the one the host carries. */
	refreshDatabaseImage?: boolean;
}

export interface AppliedState {
	release: string;
	/** The last accepted deploy-state commit, which every environment's promotion moves. */
	channelCommit: string;
	/** The `requestIdentity` of the request last applied; absent in records written earlier. */
	channelIdentity?: string;
	appliedAt: string;
	/** The release's source commit as the signed lock named it; absent in records written earlier. */
	commit?: string;
	/** The last other release this host ran, whose tree pruning keeps for a rollback. */
	previous?: string;
}

export type Decision =
	| { action: "apply"; release: string }
	| { action: "noop"; reason: string }
	| { action: "refuse"; reason: string };

export const RELEASE_TAG = /^v(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)$/u;
const CHANNEL_NAME = /^[a-z0-9]+(?:-[a-z0-9]+)*$/u;
const SHA256_HEX = /^[0-9a-f]{64}$/u;
const IMAGE_KEY = /^HEPHAESTUS_IMAGE_[A-Z0-9_]+$/u;
const IMAGE_DIGEST = /^[^\s@]+@sha256:[0-9a-f]{64}$/u;

export { isCommit } from "./commit-image-lock.ts";

/** What a channel may ask a host to run: a published release, or a commit of the default branch. */
export function isTarget(value: string): boolean {
	return RELEASE_TAG.test(value) || isCommit(value);
}

function optionalBoolean(value: unknown, label: string): boolean {
	if (value === undefined) {
		return false;
	}
	if (typeof value !== "boolean") {
		throw new TypeError(`${label} must be a boolean`);
	}
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
	const refreshDatabaseImage = optionalBoolean(
		record.refreshDatabaseImage,
		"channel.refreshDatabaseImage",
	);

	if (record.commit !== undefined) {
		if (record.release !== undefined) {
			throw new Error("channel names both a release and a commit; it must name one");
		}
		const commit = asString(record.commit, "channel.commit");
		if (!isCommit(commit)) {
			throw new Error(`channel.commit must be a full 40-character commit, not ${commit}`);
		}
		return {
			release: commit,
			images: parseImages(record.images),
			allowRollback,
			freeze,
			refreshDatabaseImage,
		};
	}

	const release = asString(record.release, "channel.release");
	if (!RELEASE_TAG.test(release)) {
		throw new Error(`channel.release must be an immutable vX.Y.Z tag, not ${release}`);
	}
	return { release, allowRollback, freeze };
}

/** The channel file the promotion signs, in the shape `parseChannel` reads back. */
export function serializeChannel(channel: Channel): string {
	const { release, images, allowRollback = false, freeze = false } = channel;
	// Only a commit channel carries an image pin, so only it can be asked to stop carrying one.
	const record = images
		? {
				commit: release,
				images,
				allowRollback,
				freeze,
				refreshDatabaseImage: channel.refreshDatabaseImage ?? false,
			}
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
		if (!IMAGE_KEY.test(key)) {
			throw new Error(`channel.images has an unusable name ${key}`);
		}
		const pinned = asString(reference, `channel.images.${key}`);
		if (!IMAGE_DIGEST.test(pinned)) {
			throw new Error(`channel.images.${key} must be pinned by digest, not ${pinned}`);
		}
		images[key] = pinned;
	}
	if (Object.keys(images).length === 0) {
		throw new Error("channel.images names no image");
	}
	return images;
}

const releaseParts = (release: string): string[] => release.slice(1).split(".");

function compareReleases(left: string, right: string): number {
	const leftParts = releaseParts(left);
	const rightParts = releaseParts(right);
	for (const [index, leftPart] of leftParts.entries()) {
		const rightPart = rightParts[index];
		if (rightPart === undefined) {
			throw new Error(`invalid release ${right}`);
		}
		if (leftPart.length !== rightPart.length) {
			return leftPart.length - rightPart.length;
		}
		if (leftPart !== rightPart) {
			return leftPart < rightPart ? -1 : 1;
		}
	}
	return 0;
}

/**
 * The signed request a channel file and its signature bundle make together, as a SHA-256 of the two
 * as length-prefixed fields, so no other split of the same bytes names the same request. A promotion
 * signs anew every time, so promoting the same target again is a new request.
 */
export function requestIdentity(channelJson: string, signature: string): string {
	const hash = createHash("sha256");
	for (const field of [channelJson, signature]) {
		const bytes = Buffer.from(field, "utf8");
		hash.update(`${bytes.length}:`);
		hash.update(bytes);
	}
	return hash.digest("hex");
}

/**
 * Whether the channel's request is the one `applied` records. Promoting another environment moves the
 * channel commit too, so only this channel's own request says whether anything was asked. A record
 * written before requests were kept has only the commit it accepted, and the channel at that commit
 * is the request it applied.
 */
function isAppliedRequest(
	applied: AppliedState,
	channelCommit: string,
	channelIdentity: string,
): boolean {
	return applied.channelIdentity === undefined
		? applied.channelCommit === channelCommit
		: applied.channelIdentity === channelIdentity;
}

export function decide(
	channel: Channel,
	applied: AppliedState | undefined,
	channelCommit: string,
	/** The `requestIdentity` of the channel this environment reads at `channelCommit`. */
	channelIdentity: string,
	advances: boolean,
	/** Whether what the channel asks for is behind what is already running. */
	targetPrecedesApplied = false,
): Decision {
	if (applied && channelCommit !== applied.channelCommit && !advances) {
		return {
			action: "refuse",
			reason: `channel commit ${channelCommit.slice(0, 8)} does not descend from the last accepted channel`,
		};
	}
	// A new request naming the release already applied is a re-promotion, and re-applying is how a
	// host that was hand-patched during an incident converges. A freeze only keeps automatic
	// promotion away, so freezing what already runs asks the host for nothing, unless the same
	// request also asks for the commit's database image.
	const freezesWhatRuns = channel.freeze === true && channel.refreshDatabaseImage !== true;
	if (
		applied?.release === channel.release &&
		(freezesWhatRuns || isAppliedRequest(applied, channelCommit, channelIdentity))
	) {
		return { action: "noop", reason: `already running ${channel.release}` };
	}
	// Two builds of the default branch can finish out of order, and the later-finishing older build
	// writes the newer channel commit — so channel ancestry says nothing about which build a channel
	// carries. What runs has to be compared with what is asked for, and for commits only the host's
	// clone can answer that, so the caller resolves it and passes the answer in.
	if (applied && targetPrecedesApplied && channel.allowRollback !== true) {
		return {
			action: "refuse",
			reason: `${channel.release} is behind the running ${applied.release}; set allowRollback to move back deliberately`,
		};
	}
	// Releases are also ordered by version, which catches a rewind without consulting a clone.
	const ordered = RELEASE_TAG.test(channel.release) && RELEASE_TAG.test(applied?.release ?? "");
	if (
		applied &&
		ordered &&
		compareReleases(channel.release, applied.release) < 0 &&
		channel.allowRollback !== true
	) {
		return {
			action: "refuse",
			reason: `${channel.release} precedes ${applied.release}; set allowRollback to move back deliberately`,
		};
	}
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
	for (const [key, image] of Object.entries(images).toSorted(([left], [right]) =>
		left < right ? -1 : 1,
	)) {
		lines.push(`${key}=${image}`);
	}
	return `${lines.join("\n")}\n`;
}

const epochSeconds = (date: Date): number => Math.floor(date.getTime() / 1000);

export function renderMetrics(state: {
	channel: string;
	release: string;
	commit: string;
	success: boolean;
	now: Date;
	lastSuccessAt?: Date;
	/** The applied record predates the kept commit, so the tooling waits for the next apply. */
	toolingPending?: boolean;
	/** Free space on the filesystem that holds Docker's data, when it could be read. */
	freeBytes?: number;
}): string {
	const lines = [
		"# HELP hephaestus_deploy_info The release this host currently runs.",
		"# TYPE hephaestus_deploy_info gauge",
		`hephaestus_deploy_info{channel="${state.channel}",release="${state.release}",channel_commit="${state.commit}"} 1`,
		"# HELP hephaestus_deploy_reconcile_success Whether the last reconcile attempt succeeded.",
		"# TYPE hephaestus_deploy_reconcile_success gauge",
		`hephaestus_deploy_reconcile_success ${state.success ? 1 : 0}`,
		"# HELP hephaestus_deploy_reconcile_timestamp_seconds When the last reconcile attempt ran.",
		"# TYPE hephaestus_deploy_reconcile_timestamp_seconds gauge",
		`hephaestus_deploy_reconcile_timestamp_seconds ${epochSeconds(state.now)}`,
		"# HELP hephaestus_deploy_tooling_pending Whether the host still waits for an apply to record the tooling it should run.",
		"# TYPE hephaestus_deploy_tooling_pending gauge",
		`hephaestus_deploy_tooling_pending ${state.toolingPending === true ? 1 : 0}`,
	];
	if (state.freeBytes !== undefined) {
		lines.push(
			"# HELP hephaestus_deploy_free_bytes Free space on the filesystem that holds Docker's data.",
			"# TYPE hephaestus_deploy_free_bytes gauge",
			`hephaestus_deploy_free_bytes ${state.freeBytes}`,
		);
	}
	if (state.lastSuccessAt) {
		lines.push(
			"# HELP hephaestus_deploy_last_success_timestamp_seconds When the release this host runs was applied.",
			"# TYPE hephaestus_deploy_last_success_timestamp_seconds gauge",
			`hephaestus_deploy_last_success_timestamp_seconds ${epochSeconds(state.lastSuccessAt)}`,
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
		.map((line) => line.slice(key.length + 1).trim());
}

export function lockedReleaseCommit(lockEnv: string): string {
	const values = lockValues(lockEnv, "HEPHAESTUS_RELEASE_COMMIT");
	if (values.length !== 1 || !isCommit(values[0] ?? "")) {
		throw new Error("release lock must contain one source commit");
	}
	return values[0] ?? "";
}

const POSTGRES_IMAGE = "HEPHAESTUS_IMAGE_POSTGRES";

/**
 * The paths whose change makes CI rebuild the PostgreSQL image, as `cicd.yml` names them; one test
 * holds the two lists together. CI rebuilds on more than these — see `commitImages`.
 */
export const POSTGRES_IMAGE_INPUTS = [
	"docker/postgres/**",
	".github/workflows/ci-docker-build.yml",
	".github/workflows/reusable-docker-build.yml",
	"docker/docker-bake.hcl",
] as const;

/**
 * The images a commit channel runs on this host: the channel's, except that PostgreSQL stays at the
 * pin the host last applied unless `rebuilt` says to take the channel's.
 *
 * A commit channel names a new PostgreSQL digest on every commit — `docker/postgres/Dockerfile` says
 * why the image is rebuilt that often — and Compose recreates a container whose image changed, which
 * drops every connection pool and kills the practice reviews in flight. A release is applied as
 * signed: its lock is the artifact its evidence describes, so this never touches one.
 */
export function carryPostgresImage(
	images: Readonly<Record<string, string>>,
	appliedLockEnv: string | undefined,
	rebuilt: boolean,
): Readonly<Record<string, string>> {
	const pinned = images[POSTGRES_IMAGE];
	if (rebuilt || appliedLockEnv === undefined || pinned === undefined) {
		return images;
	}
	const [kept, ...rest] = lockValues(appliedLockEnv, POSTGRES_IMAGE);
	if (kept === undefined || rest.length > 0 || !IMAGE_DIGEST.test(kept)) {
		return images;
	}
	// The digest is kept, never the repository it sits in: a channel naming the image somewhere else
	// is naming a different image rather than a rebuild of this one.
	if (kept.slice(0, kept.indexOf("@")) !== pinned.slice(0, pinned.indexOf("@"))) {
		return images;
	}
	return { ...images, [POSTGRES_IMAGE]: kept };
}

/** Docker lists a Docker Hub image without its registry and without the `library/` namespace. */
function repositoryName(repository: string): string {
	return repository.replace(/^docker\.io\//u, "").replace(/^library\//u, "");
}

/** The digest-pinned image references a lock names. */
export function lockImages(lockEnvs: readonly string[]): string[] {
	return lockEnvs.flatMap((lockEnv) =>
		lockEnv
			.split("\n")
			.map((line) => line.slice(line.indexOf("=") + 1).trim())
			.filter((value) => IMAGE_DIGEST.test(value)),
	);
}

/** One image as `docker image ls --all --digests` lists it. */
export interface ListedImage {
	repository: string;
	/** `<none>` for an image pulled by digest, as every release image is. */
	tag: string;
	digest: string;
	id: string;
}

/**
 * The images a host may remove: those of a repository a kept lock uses that are neither named by a
 * kept lock nor the same image as one it names. An image of any other repository is not this
 * deployment's to judge, and so is a tagged image: a release image is pulled by digest and carries no
 * tag. The image ID decides sameness, because the digest Docker lists can be a platform manifest's
 * where the lock names the index.
 *
 * `docker image prune` cannot express this. Without `-a`, it removes only untagged images that
 * nothing references; with `-a`, it also removes the previous release's images and every idle image
 * of other workloads.
 */
export function unkeptImages(
	listed: readonly ListedImage[],
	keptReferences: readonly string[],
	keptIds: ReadonlySet<string>,
): string[] {
	const named = new Set<string>();
	const repositories = new Set<string>();
	for (const reference of keptReferences) {
		const at = reference.indexOf("@");
		const repository = repositoryName(reference.slice(0, at));
		repositories.add(repository);
		named.add(`${repository}${reference.slice(at)}`);
	}
	return listed
		.filter(({ repository, tag, digest, id }) => {
			const name = repositoryName(repository);
			return (
				tag === "<none>" &&
				repositories.has(name) &&
				!named.has(`${name}@${digest}`) &&
				!keptIds.has(id)
			);
		})
		.map(({ repository, digest }) => `${repository}@${digest}`);
}

/** The ID of a local image, or nothing when the host does not have it. */
async function localImageId(reference: string): Promise<string | undefined> {
	try {
		const id = await output("docker", ["image", "inspect", "--format", "{{.Id}}", reference]);
		return id.trim();
	} catch {
		return undefined;
	}
}

async function readLocks(
	config: HostConfig,
	releases: readonly (string | undefined)[],
): Promise<string[]> {
	const lockEnvs: string[] = [];
	for (const release of new Set(releases)) {
		if (release === undefined) {
			continue;
		}
		try {
			lockEnvs.push(await readFile(path.join(locksDirectory(config), `${release}.env`), "utf8"));
		} catch (error) {
			if (!(error instanceof Error && "code" in error && error.code === "ENOENT")) {
				throw error;
			}
		}
	}
	return lockEnvs;
}

/**
 * Removes the images that no kept lock names. Docker refuses to remove an image that a container
 * uses, running or stopped, so a refusal leaves the image and the next cleanup tries again.
 */
async function removeUnkeptImages(keptLockEnvs: readonly string[]): Promise<number> {
	const keptReferences = lockImages(keptLockEnvs);
	// Without a kept lock, nothing says which images are this deployment's, so nothing is removed.
	if (keptReferences.length === 0) {
		return 0;
	}
	const keptIds = new Set<string>();
	for (const reference of keptReferences) {
		const id = await localImageId(reference);
		if (id !== undefined) {
			keptIds.add(id);
		}
	}
	// Docker hides untagged images from the listing unless asked, and every release image is one.
	const listing = await output("docker", [
		"image",
		"ls",
		"--all",
		"--digests",
		"--no-trunc",
		"--format",
		"{{json .}}",
	]);
	const listed = listing
		.split("\n")
		.filter((line) => line.trim() !== "")
		.map((line) => asRecord(parseJson(line), "docker image ls entry"))
		.filter((image) => typeof image.Digest === "string" && image.Digest.startsWith("sha256:"))
		.map((image) => ({
			repository: asString(image.Repository, "image repository"),
			tag: asString(image.Tag, "image tag"),
			digest: asString(image.Digest, "image digest"),
			id: asString(image.ID, "image ID"),
		}));
	let removed = 0;
	for (const image of unkeptImages(listed, keptReferences, keptIds)) {
		if (await succeeds("docker", ["image", "rm", image])) {
			removed += 1;
			console.log(`Removed image ${image}`);
		} else {
			console.log(`Keeping image ${image}: Docker refused to remove it`);
		}
	}
	return removed;
}

/** Docker's image store may be containerd's, which keeps its content outside Docker's data root. */
const CONTAINERD_ROOT = "/var/lib/containerd";

/** The least free space on the filesystems that hold Docker's images, containers and volumes. */
async function dockerFreeBytes(): Promise<number> {
	const dockerRoot = await output("docker", ["info", "--format", "{{.DockerRootDir}}"]);
	const roots = [dockerRoot.trim()];
	if (await isDirectory(CONTAINERD_ROOT)) {
		roots.push(CONTAINERD_ROOT);
	}
	const free = await Promise.all(
		roots.map(async (root) => {
			const { bavail, bsize } = await statfs(root);
			return bavail * bsize;
		}),
	);
	return Math.min(...free);
}

/** For the metric only: a metric that cannot be measured is left out rather than failing the tick. */
async function readableFreeBytes(): Promise<number | undefined> {
	return dockerFreeBytes().catch(() => undefined);
}

const GIB = 1024 ** 3;

/**
 * Refuses a pull that could fill the disk. A full disk stops PostgreSQL first, so the apply would
 * take the running release down with it. Unkept images go first: that is the one cleanup that
 * cannot hurt a rollback.
 */
export async function guardFreeSpace(
	minimumBytes: number,
	measure: () => Promise<number>,
	cleanup: () => Promise<number>,
): Promise<void> {
	if ((await measure()) >= minimumBytes) {
		return;
	}
	const removed = await cleanup();
	const free = await measure();
	if (free < minimumBytes) {
		throw new OperatorActionRequiredError(
			`Only ${(free / GIB).toFixed(1)} GiB is free for Docker's data, below the ${(minimumBytes / GIB).toFixed(0)} GiB that HEPHAESTUS_MIN_FREE_GIB requires before a pull, after removing ${removed} unused release images. Free space on the host; the next tick applies the release.`,
		);
	}
}

function isStack(name: string): name is Stack {
	return STACK_ORDER.some((stack) => stack === name);
}

export function parseStacks(value: string | undefined): Stack[] {
	const names = (value ?? "").split(/[\s,]+/u).filter(Boolean);
	if (names.length === 0) {
		throw new Error("HEPHAESTUS_STACKS must name at least one stack");
	}
	const unknown = names.filter((name) => !isStack(name));
	if (unknown.length > 0) {
		throw new Error(`unknown stack(s): ${unknown.join(", ")}`);
	}
	const stacks = STACK_ORDER.filter((stack) => names.includes(stack));
	if (stacks.includes("chart") && stacks.length > 1) {
		throw new Error(
			"the chart stack runs the whole release: HEPHAESTUS_STACKS=chart takes no other stack",
		);
	}
	return stacks;
}

/** How a host that follows its channel with the Helm chart installs it. */
export interface ChartSettings {
	release: string;
	namespace: string;
	/** Values files in the chart's `profiles/`, without `.yaml`, each applied over the one before. */
	profiles: readonly string[];
	helm: string;
}

const KUBERNETES_NAME = /^[a-z0-9](?:[a-z0-9-]{0,51}[a-z0-9])?$/u;
const PROFILE_NAME = /^[a-z0-9-]+$/u;

export function chartSettings(environment: NodeJS.ProcessEnv): ChartSettings {
	const setting = (name: string, fallback: string): string => {
		const value = environment[name];
		return isSet(value) ? value : fallback;
	};
	const settings = {
		release: setting("HEPHAESTUS_CHART_RELEASE", "hephaestus"),
		namespace: setting("HEPHAESTUS_CHART_NAMESPACE", "hephaestus"),
		profiles: setting("HEPHAESTUS_CHART_PROFILE", "small")
			.split(/[\s,]+/u)
			.filter(Boolean),
		helm: setting("HEPHAESTUS_HELM", "helm"),
	};
	for (const [name, value] of [
		["HEPHAESTUS_CHART_RELEASE", settings.release],
		["HEPHAESTUS_CHART_NAMESPACE", settings.namespace],
	] as const) {
		if (!KUBERNETES_NAME.test(value)) {
			throw new Error(`${name} must be lowercase letters, digits and hyphens, not ${value}`);
		}
	}
	const unusable = settings.profiles.filter((profile) => !PROFILE_NAME.test(profile));
	if (settings.profiles.length === 0 || unusable.length > 0) {
		throw new Error(
			`HEPHAESTUS_CHART_PROFILE must name profiles of lowercase letters, digits and hyphens, not ${unusable.join(", ") || "none"}`,
		);
	}
	return settings;
}

/** The release tree's values file of each profile, in order; a profile the tree lacks is refused. */
export async function profileFiles(
	settings: ChartSettings,
	releaseTree: string,
): Promise<string[]> {
	const files = settings.profiles.map((profile) =>
		path.join(releaseTree, "deploy/chart/hephaestus/profiles", `${profile}.yaml`),
	);
	for (const [index, file] of files.entries()) {
		try {
			await stat(file);
		} catch {
			throw new Error(
				`the release has no chart profile ${settings.profiles[index] ?? ""} (${file})`,
			);
		}
	}
	return files;
}

/**
 * One `helm upgrade --install` of the release tree's chart: the profiles in order, the operator's
 * site values, and last the lock's images and release, so that no site value can run an image the
 * lock does not name.
 */
export function helmArguments(
	settings: ChartSettings,
	releaseTree: string,
	profiles: readonly string[],
	imagesFile: string,
	siteValuesFile: string,
	timeoutSeconds: number,
): string[] {
	return [
		"upgrade",
		"--install",
		settings.release,
		path.join(releaseTree, "deploy/chart/hephaestus"),
		"--namespace",
		settings.namespace,
		...profiles.flatMap((file) => ["--values", file]),
		"--values",
		siteValuesFile,
		"--values",
		imagesFile,
		"--rollback-on-failure",
		"--wait=watcher",
		"--wait-for-jobs",
		"--timeout",
		`${timeoutSeconds}s`,
	];
}

interface HostConfig {
	channel: string;
	stacks: Stack[];
	/** Set only on a host whose one stack is `chart`. */
	chart?: ChartSettings;
	checkout: string;
	/** A link to the tree whose tooling this host runs: the checkout until the first apply. */
	tooling: string;
	stateDirectory: string;
	secretsDirectory: string;
	metricsFile?: string;
	waitTimeoutSeconds: number;
	promoteIdentity: string;
	minimumFreeBytes: number;
}

function hostConfig(environment: NodeJS.ProcessEnv): HostConfig {
	const channel = requiredEnv(environment, "HEPHAESTUS_CHANNEL");
	if (!CHANNEL_NAME.test(channel)) {
		throw new Error("HEPHAESTUS_CHANNEL must contain lowercase letters, digits, and hyphens");
	}
	const stateDirectory = environment.STATE_DIRECTORY ?? "/var/lib/hephaestus";
	const stacks = parseStacks(environment.HEPHAESTUS_STACKS);
	// A new chart Pod waits up to 10 minutes for the migrated schema, so Helm needs more than that.
	const waitTimeoutSeconds = Number(
		environment.HEPHAESTUS_WAIT_TIMEOUT ?? (stacks.includes("chart") ? 1200 : 600),
	);
	if (!Number.isSafeInteger(waitTimeoutSeconds) || waitTimeoutSeconds <= 0) {
		throw new Error("HEPHAESTUS_WAIT_TIMEOUT must be a positive integer");
	}
	const minimumFreeGiB = Number(environment.HEPHAESTUS_MIN_FREE_GIB ?? 10);
	if (!Number.isFinite(minimumFreeGiB) || minimumFreeGiB <= 0) {
		throw new Error("HEPHAESTUS_MIN_FREE_GIB must be a positive number");
	}
	return {
		channel,
		stacks,
		...(stacks.includes("chart") ? { chart: chartSettings(environment) } : {}),
		checkout: path.join(stateDirectory, "checkout"),
		tooling: path.join(stateDirectory, "tooling"),
		stateDirectory,
		secretsDirectory: environment.HEPHAESTUS_SECRETS ?? "/etc/hephaestus",
		metricsFile: environment.HEPHAESTUS_METRICS_FILE,
		waitTimeoutSeconds,
		promoteIdentity: requiredEnv(environment, "HEPHAESTUS_PROMOTE_IDENTITY"),
		minimumFreeBytes: minimumFreeGiB * GIB,
	};
}

export async function readApplied(file: string): Promise<AppliedState | undefined> {
	try {
		const record = asRecord(await readJsonFile(file), "applied state");
		const applied = {
			release: asString(record.release, "applied.release"),
			channelCommit: asString(record.channelCommit, "applied.channelCommit"),
			...(record.channelIdentity === undefined
				? {}
				: { channelIdentity: asString(record.channelIdentity, "applied.channelIdentity") }),
			appliedAt: asString(record.appliedAt, "applied.appliedAt"),
			...(record.commit === undefined ? {} : { commit: asString(record.commit, "applied.commit") }),
			...(record.previous === undefined
				? {}
				: { previous: asString(record.previous, "applied.previous") }),
		};
		if (!isTarget(applied.release)) {
			throw new Error("applied.release must be a vX.Y.Z tag or a commit");
		}
		if (!isCommit(applied.channelCommit)) {
			throw new Error("applied.channelCommit must be a Git commit");
		}
		if (applied.channelIdentity !== undefined && !SHA256_HEX.test(applied.channelIdentity)) {
			throw new Error("applied.channelIdentity must be a SHA-256 digest");
		}
		if (applied.commit !== undefined && !isCommit(applied.commit)) {
			throw new Error("applied.commit must be a Git commit");
		}
		if (applied.previous !== undefined && !isTarget(applied.previous)) {
			throw new Error("applied.previous must be a vX.Y.Z tag or a commit");
		}
		if (
			!Number.isFinite(Date.parse(applied.appliedAt)) ||
			new Date(applied.appliedAt).toISOString() !== applied.appliedAt
		) {
			throw new Error("applied.appliedAt must be an ISO timestamp");
		}
		return applied;
	} catch (error) {
		if (error instanceof Error && "code" in error && error.code === "ENOENT") {
			return undefined;
		}
		throw error;
	}
}

/** Renamed into place: a scrape landing mid-write would otherwise publish a truncated series. */
async function writeAtomic(file: string, contents: string): Promise<void> {
	const temporary = `${file}.tmp`;
	await writeFile(temporary, contents);
	await rename(temporary, file);
}

function fetchOptions(config: HostConfig): { cwd: string; signal: AbortSignal } {
	return { cwd: config.checkout, signal: AbortSignal.timeout(FETCH_TIMEOUT_MS) };
}

/** The commit `ref` names in `checkout`. Trimmed because a SHA is compared and interpolated. */
async function revision(checkout: string, ref: string): Promise<string> {
	const sha = await output("git", ["rev-parse", ref], { cwd: checkout });
	return sha.trim();
}

/**
 * Whether this tick handed over to the recorded release's tooling. Runs before anything else, and in
 * particular before the channel is parsed: a tick that stopped between recording a release and
 * adopting its tooling would otherwise read the next channel with the tooling that release
 * replaced. The recorded release's tree is rebuilt at the commit the signed lock named if it is gone
 * and checked either way, so what is adopted is that release and nothing that happens to sit at its
 * path. Node keeps running the module it loaded, so when the link moved, this tick ends here and the
 * next one runs the adopted tooling.
 */
async function adoptedAppliedTooling(
	config: HostConfig,
	applied: AppliedState,
	unitsDirectory: string,
): Promise<boolean> {
	const commit = appliedCommit(applied);
	if (commit === undefined) {
		// Nothing on disk tells an accepted tree from one a failed re-promotion staged, so a record
		// from before the commit was kept is never completed from a tree or a lock: the next apply
		// records the commit, and until then the host reports the tooling as pending.
		console.log(
			`${applied.release} was recorded before its commit was kept; keeping the current tooling until the next apply`,
		);
		return false;
	}
	const { tree } = await ensureReleaseTree(
		config.checkout,
		releasesDirectory(config),
		applied.release,
		commit,
	);
	if (await followTooling(config, tree, unitsDirectory)) {
		console.log(`Adopted the tooling of ${applied.release}; the next run uses it`);
		return true;
	}
	return false;
}

/** The channel at the tip of deploy-state, parsed only after cosign accepted its signature. */
async function fetchSignedChannel(
	config: HostConfig,
): Promise<{ channel: Channel; channelCommit: string; channelIdentity: string }> {
	await run(
		"git",
		["fetch", "--quiet", "origin", "+refs/heads/deploy-state:refs/remotes/origin/deploy-state"],
		fetchOptions(config),
	);
	const channelCommit = await revision(config.checkout, "refs/remotes/origin/deploy-state");
	const channelPath = `channels/${config.channel}.json`;
	// An environment nobody has promoted yet has no channel file. That is the first thing a new
	// host meets, so say which channel is missing and what publishes it rather than letting git's
	// "path does not exist" surface as an unhandled exec failure.
	if (
		!(await succeeds("git", ["cat-file", "-e", `${channelCommit}:${channelPath}`], {
			cwd: config.checkout,
		}))
	) {
		throw new OperatorActionRequiredError(
			`no ${channelPath} on deploy-state: the "${config.channel}" environment has not been ` +
				"promoted yet. Run the Release / Promote workflow for it and this host applies it on the next tick.",
		);
	}
	// Not trimmed: the channel must reach cosign byte for byte as it was signed.
	const channelJson = await output("git", ["show", `${channelCommit}:${channelPath}`], {
		cwd: config.checkout,
	});
	const signature = await output("git", ["show", `${channelCommit}:${channelPath}.sigstore.json`], {
		cwd: config.checkout,
	});

	// Verifying before parsing keeps unverified bytes from reaching any decision.
	const scratch = path.join(config.stateDirectory, "channel");
	await mkdir(scratch, { recursive: true });
	await writeFile(path.join(scratch, "channel.json"), channelJson);
	await writeFile(path.join(scratch, "channel.sigstore.json"), signature);
	await run("cosign", [
		"verify-blob",
		"--bundle",
		path.join(scratch, "channel.sigstore.json"),
		// Cosign cannot verify the certificate's environment claim, so the gate is expressed as the
		// identity of the workflow the environment protects: only an approved run can produce it.
		"--certificate-identity",
		config.promoteIdentity,
		"--certificate-oidc-issuer",
		"https://token.actions.githubusercontent.com",
		path.join(scratch, "channel.json"),
	]);
	return {
		channel: parseChannel(parseJson(channelJson)),
		channelCommit,
		channelIdentity: requestIdentity(channelJson, signature),
	};
}

/**
 * Whether the target is behind what runs. Both are commits of the default branch here, so the
 * clone answers it directly; between releases the version comparison in decide covers it, and a
 * change of form is a deliberate move only a version or an operator can order.
 *
 * The branch is fetched first: without the objects, `merge-base` fails and a failure would read
 * as "not behind", which is the wrong way for this check to be wrong.
 */
async function targetIsBehindApplied(
	config: HostConfig,
	channel: Channel,
	applied: AppliedState | undefined,
): Promise<boolean> {
	if (!applied || RELEASE_TAG.test(channel.release) || RELEASE_TAG.test(applied.release)) {
		return false;
	}
	await run(
		"git",
		["fetch", "--quiet", "origin", "+refs/heads/main:refs/remotes/origin/main"],
		fetchOptions(config),
	);
	for (const commit of [channel.release, applied.release]) {
		if (
			!(await succeeds("git", ["cat-file", "-e", `${commit}^{commit}`], { cwd: config.checkout }))
		) {
			throw new Error(
				`cannot order ${channel.release} against ${applied.release}: ${commit} is unknown here`,
			);
		}
	}
	return (
		channel.release !== applied.release &&
		(await succeeds("git", ["merge-base", "--is-ancestor", channel.release, applied.release], {
			cwd: config.checkout,
		}))
	);
}

/**
 * Moves the accepted channel commit forward. What runs, since when, and what to roll back to are facts
 * of the last apply, so they stay; `channelIdentity` is the request that runs.
 */
async function recordUnchanged(
	config: HostConfig,
	appliedFile: string,
	applied: AppliedState | undefined,
	channelCommit: string,
	channelIdentity: string | undefined,
	startedAt: Date,
): Promise<void> {
	if (
		applied &&
		(applied.channelCommit !== channelCommit || applied.channelIdentity !== channelIdentity)
	) {
		await writeAtomic(
			appliedFile,
			`${JSON.stringify({ ...applied, channelCommit, channelIdentity }, null, "\t")}\n`,
		);
	}
	if (isSet(config.metricsFile) && applied) {
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
				freeBytes: config.chart === undefined ? await readableFreeBytes() : undefined,
			}),
		);
	}
}

/** The release lock's env file, written for a commit channel and verified for a release. */
async function prepareLock(
	config: HostConfig,
	channel: Channel,
	applied: AppliedState | undefined,
	release: string,
	releaseCommit: string,
	releaseTree: string,
): Promise<{ lockEnv: string; lockFile: string }> {
	const lockDirectory = locksDirectory(config);
	await mkdir(lockDirectory, { recursive: true });
	const lockFile = path.join(lockDirectory, `${release}.env`);
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
			channel.refreshDatabaseImage,
		);
		await writeFile(lockFile, commitLockEnvironment(release, images), { mode: 0o600 });
	} else {
		// The verifier is the tooling this tick runs, never the release's own copy of it.
		await run(
			process.execPath,
			[path.join(import.meta.dirname, "prepare-release-lock.ts"), release, lockFile],
			{ cwd: releaseTree },
		);
	}
	const lockEnv = await readFile(lockFile, "utf8");
	if (!channel.images && lockedReleaseCommit(lockEnv) !== releaseCommit) {
		throw new Error(`signed release lock does not cover the ${release} source tree`);
	}
	return { lockEnv, lockFile };
}

interface RenderedStack {
	composeArgs: string[];
	/** The services the release declares for the stack, as Compose rendered them. */
	services: readonly string[];
}

const WORKER_SERVICE = "application-worker";

/**
 * The compose arguments of each stack, each rendered and checked against the lock first: a release
 * that renders an unlocked image cannot get one running in the window before the guard refuses it.
 */
async function verifiedComposeArgs(
	config: HostConfig,
	stacks: readonly Stack[],
	releaseTree: string,
	lockFile: string,
	lockEnv: string,
): Promise<Map<Stack, RenderedStack>> {
	const composeArgsByStack = new Map<Stack, RenderedStack>();
	for (const stack of stacks) {
		const composeArgs = [
			"compose",
			"--project-name",
			stack,
			"--env-file",
			path.join(config.secretsDirectory, `${stack}.env`),
			"--env-file",
			lockFile,
			"--file",
			path.join(releaseTree, `docker/compose.${stack}.yaml`),
		];
		const rendered = asRecord(
			parseJson(
				await output("docker", [...composeArgs, "config", "--format", "json"], {
					cwd: releaseTree,
				}),
			),
			`${stack} configuration`,
		);
		const services = asRecord(rendered.services, `${stack}.services`);
		const images = Object.values(services).map((service) =>
			asString(asRecord(service, `${stack} service`).image, `${stack} service image`),
		);
		const unlocked = unlockedImages(images, lockEnv);
		if (unlocked.length > 0) {
			throw new Error(`${stack} renders images outside the release lock: ${unlocked.join(", ")}`);
		}
		composeArgsByStack.set(stack, { composeArgs, services: Object.keys(services) });
	}
	return composeArgsByStack;
}

/**
 * Downloads every verified stack's images while the applied release still runs, so the worker is not
 * stopped for the length of a download. `pull` starts no container, and `--policy missing` leaves an
 * image the host already has alone.
 */
async function pullStacks(
	releaseTree: string,
	composeArgsByStack: ReadonlyMap<Stack, RenderedStack>,
): Promise<void> {
	for (const [, { composeArgs }] of composeArgsByStack) {
		await run("docker", [...composeArgs, "pull", "--policy", "missing"], { cwd: releaseTree });
	}
}

interface AppliedWorker {
	composeArgs: string[];
	tree: string;
}

/**
 * The worker the applied release runs, from that release's own app configuration and lock, so it is
 * stopped before anything it depends on is replaced: `stop` waits up to the stop_grace_period the
 * running worker was started with, whatever the candidate declares, and the candidate's `up` starts
 * what it declares. A first install, or an applied release without the worker, has none to stop.
 * Every refusal here comes before any image is pulled.
 */
async function appliedWorker(
	config: HostConfig,
	applied: AppliedState | undefined,
): Promise<AppliedWorker | undefined> {
	if (!applied || !config.stacks.includes("app")) {
		return undefined;
	}
	// As for its tooling, only the commit the record keeps identifies what runs: a retained lock or tree
	// cannot tell the accepted source from one a failed re-promotion staged. A worker that may be running
	// is never skipped silently, so a record without it, or a lock for other source, stops the apply.
	const commit = appliedCommit(applied);
	if (commit === undefined) {
		throw new OperatorActionRequiredError(
			`${applied.release} was recorded without its source commit, so the worker it runs cannot be ` +
				"stopped as it was started. Verify the revision this host runs before it applies another release.",
		);
	}
	const lockFile = path.join(locksDirectory(config), `${applied.release}.env`);
	const lockEnv = await readFile(lockFile, "utf8");
	if (lockedReleaseCommit(lockEnv) !== commit) {
		throw new Error(`the ${applied.release} lock does not cover the commit recorded for it`);
	}
	const { tree } = await ensureReleaseTree(
		config.checkout,
		releasesDirectory(config),
		applied.release,
		commit,
	);
	const rendered = await verifiedComposeArgs(config, ["app"], tree, lockFile, lockEnv);
	const app = rendered.get("app");
	return app !== undefined && app.services.includes(WORKER_SERVICE)
		? { composeArgs: app.composeArgs, tree }
		: undefined;
}

async function migrate(
	releaseTree: string,
	composeArgsByStack: ReadonlyMap<Stack, RenderedStack>,
): Promise<void> {
	const app = composeArgsByStack.get("app");
	if (app?.services.includes(MIGRATION_SERVICE) !== true) {
		return;
	}
	// `run` starts the dependencies, and recreates PostgreSQL if the release changed it, under the
	// running roles. A running PostgreSQL is left to the `up` that replaces the roles.
	const postgres = await output(
		"docker",
		[...app.composeArgs, "ps", "--status", "running", "--quiet", "postgres"],
		{ cwd: releaseTree },
	);
	const deps = postgres.trim() === "" ? [] : ["--no-deps"];
	await run("docker", [...app.composeArgs, "run", "--rm", "-T", ...deps, MIGRATION_SERVICE], {
		cwd: releaseTree,
	});
}

async function startStacks(
	config: HostConfig,
	releaseTree: string,
	composeArgsByStack: ReadonlyMap<Stack, RenderedStack>,
): Promise<void> {
	for (const [stack, { composeArgs }] of composeArgsByStack) {
		const foundation = FOUNDATION[stack];
		if (!foundation) {
			continue;
		}
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

	for (const [, { composeArgs }] of composeArgsByStack) {
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
}

export async function main(unitsDirectory = SYSTEMD_UNITS): Promise<void> {
	const config = hostConfig(process.env);
	const appliedFile = path.join(config.stateDirectory, "applied.json");
	const applied = await readApplied(appliedFile);
	const startedAt = new Date();

	await mkdir(config.stateDirectory, { recursive: true });
	if (applied && (await adoptedAppliedTooling(config, applied, unitsDirectory))) {
		return;
	}

	const { channel, channelCommit, channelIdentity } = await fetchSignedChannel(config);
	refuseReleaseChannelOnChart(config, channel);
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
	const decision = decide(
		channel,
		applied,
		channelCommit,
		channelIdentity,
		advances,
		await targetIsBehindApplied(config, channel, applied),
	);

	if (decision.action === "refuse") {
		throw new Error(decision.reason);
	}
	if (decision.action === "noop") {
		console.log(`No change: ${decision.reason}`);
		// A no-op means this request is the one that runs, which a record from before requests were kept
		// learns here.
		await recordUnchanged(config, appliedFile, applied, channelCommit, channelIdentity, startedAt);
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
	const releaseCommit = await revision(config.checkout, `${decision.release}^{commit}`);
	const { tree: releaseTree } = await ensureReleaseTree(
		config.checkout,
		releasesDirectory(config),
		decision.release,
		releaseCommit,
	);
	// Read before the new lock is written: a re-promotion of the running release rewrites its lock.
	const runningLocks = await readLocks(config, [applied?.release, applied?.previous]);
	const { lockEnv, lockFile } = await prepareLock(
		config,
		channel,
		applied,
		decision.release,
		releaseCommit,
		releaseTree,
	);
	// A re-promotion of the running release keeps the rollback target the host already had.
	const previous = applied?.release === decision.release ? applied.previous : applied?.release;
	if (config.chart === undefined) {
		const composeArgsByStack = await verifiedComposeArgs(
			config,
			config.stacks,
			releaseTree,
			lockFile,
			lockEnv,
		);
		const worker = await appliedWorker(config, applied);
		// A rollback or re-promotion whose images are all on the host pulls nothing, so a full disk
		// must not stop it.
		const needed = lockImages([lockEnv]);
		const present = await Promise.all(needed.map(async (image) => localImageId(image)));
		if (present.includes(undefined)) {
			await guardFreeSpace(config.minimumFreeBytes, dockerFreeBytes, async () =>
				removeUnkeptImages([...runningLocks, lockEnv]),
			);
		}
		await pullStacks(releaseTree, composeArgsByStack);
		await migrate(releaseTree, composeArgsByStack);
		if (worker !== undefined) {
			await run("docker", [...worker.composeArgs, "stop", WORKER_SERVICE], { cwd: worker.tree });
		}
		await startStacks(config, releaseTree, composeArgsByStack);
	} else {
		await applyChart(config, config.chart, decision.release, previous, releaseTree, lockEnv);
	}

	const finishedAt = new Date();
	await writeAtomic(
		appliedFile,
		`${JSON.stringify({ release: decision.release, channelCommit, channelIdentity, appliedAt: finishedAt.toISOString(), commit: releaseCommit, previous }, null, "\t")}\n`,
	);
	if (isSet(config.metricsFile)) {
		await writeAtomic(
			config.metricsFile,
			renderMetrics({
				channel: config.channel,
				release: decision.release,
				commit: channelCommit,
				success: true,
				now: finishedAt,
				lastSuccessAt: finishedAt,
				freeBytes: config.chart === undefined ? await readableFreeBytes() : undefined,
			}),
		);
	}
	console.log(`Applied ${decision.release} to ${config.stacks.join(", ")}`);
	// Only now, with the release verified and running, does the host run that release's tooling.
	await followTooling(config, releaseTree, unitsDirectory);
	// Last, so a prune that fails leaves this release running, recorded and adopted; the next apply
	// prunes again.
	await pruneReleases(config, [decision.release, previous]);
	// On a chart host the kubelet's image garbage collection owns the images, in containerd.
	if (config.chart === undefined) {
		await removeUnkeptImages(await readLocks(config, [decision.release, previous]));
	}
}

/** ADR 0058: a release applies its signed chart package, which the chart stack cannot verify yet. */
function refuseReleaseChannelOnChart(config: HostConfig, channel: Channel): void {
	if (config.chart !== undefined && channel.images === undefined) {
		throw new Error(
			`a release channel applies the signed chart package; the chart stack does not support that yet, so ${config.channel} must be a commit channel`,
		);
	}
}

/** The images a verified lock names, by the `HEPHAESTUS_IMAGE_*` keys that the lock writers use. */
function lockedImages(lockEnv: string): Record<string, string> {
	const images: Record<string, string> = {};
	for (const line of lockEnv.split("\n")) {
		const separator = line.indexOf("=");
		const key = line.slice(0, separator);
		if (separator > 0 && IMAGE_KEY.test(key)) {
			images[key] = line.slice(separator + 1).trim();
		}
	}
	return images;
}

const HELM_PENDING = /^pending-(?:install|upgrade|rollback)$/u;

/**
 * Helm leaves a release in a pending status when its upgrade was killed, by the unit's timeout, a
 * reboot or a power cut, and then refuses every later upgrade. Rolling back to the last deployed
 * revision ends that state, so the tick that follows a cut-off upgrade repairs it.
 */
async function recoverPendingRelease(config: HostConfig, settings: ChartSettings): Promise<void> {
	let status: string;
	try {
		const info = asRecord(
			parseJson(
				await output(settings.helm, [
					"status",
					settings.release,
					"--namespace",
					settings.namespace,
					"--output",
					"json",
				]),
			),
			"helm status",
		);
		status = asString(asRecord(info.info, "helm status info").status, "helm status");
	} catch {
		// No release yet, or a cluster Helm cannot read: the upgrade itself reports either.
		return;
	}
	if (HELM_PENDING.test(status)) {
		console.log(`Rolling back ${settings.release}: an earlier upgrade stopped in ${status}`);
		await run(
			settings.helm,
			[
				"rollback",
				settings.release,
				"--namespace",
				settings.namespace,
				"--wait",
				"--timeout",
				`${config.waitTimeoutSeconds}s`,
			],
			{ stdin: "ignore" },
		);
	}
}

/**
 * Installs or upgrades the release tree's chart with the commit lock's images. Helm migrates in the
 * revision's Job, waits for every Pod and that Job, and rolls back to the previous revision when
 * either fails; the thrown error then fails this tick as a failed Compose apply does.
 */
async function applyChart(
	config: HostConfig,
	settings: ChartSettings,
	release: string,
	previous: string | undefined,
	releaseTree: string,
	lockEnv: string,
): Promise<void> {
	const values = commitChartValues(
		lockedImages(lockEnv),
		parseInventory(await readJsonFile(path.join(releaseTree, "security/release-images.json"))),
		lockedReleaseCommit(lockEnv),
	);
	const profiles = await profileFiles(settings, releaseTree);
	const directory = path.join(config.stateDirectory, "chart");
	await mkdir(directory, { recursive: true });
	const imagesFile = path.join(directory, `${release}.images.yaml`);
	await writeAtomic(imagesFile, renderChartValues(values));
	await recoverPendingRelease(config, settings);
	await run(
		settings.helm,
		helmArguments(
			settings,
			releaseTree,
			profiles,
			imagesFile,
			path.join(config.secretsDirectory, "chart.yaml"),
			config.waitTimeoutSeconds,
		),
		{ cwd: releaseTree, stdin: "ignore" },
	);
	// The previous release's file stays for a manual rollback, as its tree does.
	const kept = new Set([release, previous ?? release].map((name) => `${name}.images.yaml`));
	for (const entry of await readdir(directory)) {
		if (entry.endsWith(".images.yaml") && !kept.has(entry)) {
			await rm(path.join(directory, entry), { force: true });
		}
	}
}

const DAY_SECONDS = 24 * 60 * 60;

/**
 * Whether the two commits carry committer dates in the same UTC day. Bucketing the commits rather
 * than reading a clock keeps the answer the same on every tick and on every host, so a run that is
 * retried does not decide differently from the one before it. A commit the checkout does not have
 * answers no, which is the direction that applies the channel.
 */
async function sameDay(checkout: string, previous: string, target: string): Promise<boolean> {
	let stamps: number[];
	try {
		// One lookup per commit: `git show` prints a revision named twice only once.
		const shown = await Promise.all(
			[previous, target].map(async (commit) =>
				output("git", ["show", "--no-patch", "--format=%ct", commit], { cwd: checkout }),
			),
		);
		stamps = shown.flatMap((stamp) => stamp.trim().split("\n")).map(Number);
	} catch {
		return false;
	}
	const [before, after] = stamps;
	if (stamps.length !== 2 || before === undefined || after === undefined) {
		return false;
	}
	if (!Number.isSafeInteger(before) || !Number.isSafeInteger(after)) {
		return false;
	}
	return Math.floor(before / DAY_SECONDS) === Math.floor(after / DAY_SECONDS);
}

/**
 * `carryPostgresImage` with what this host knows: the lock it wrote for the applied release, which
 * is its own record of the pins it verified and ran, and git's word on the two commits.
 *
 * CI rebuilds the PostgreSQL image whenever `POSTGRES_IMAGE_INPUTS` changed, and on top of that
 * unconditionally for every push to the default branch. The host takes the first rebuild the commit
 * it is applying earned, and the unconditional one once a day rather than on every apply — so the
 * `apt-get upgrade` the image exists to run reaches a following host within a day of the push that
 * built it, instead of the host freezing on one digest between changes to the image's own tree.
 * `refresh` is the operator asking for the channel's pin before either of those says so.
 */
export async function commitImages(
	checkout: string,
	lockDirectory: string,
	applied: AppliedState | undefined,
	releaseCommit: string,
	images: Readonly<Record<string, string>>,
	refresh = false,
): Promise<Readonly<Record<string, string>>> {
	if (applied === undefined || refresh) {
		return images;
	}
	const previous = appliedCommit(applied);
	if (previous === undefined) {
		return images;
	}
	let appliedLockEnv: string | undefined;
	try {
		appliedLockEnv = await readFile(path.join(lockDirectory, `${applied.release}.env`), "utf8");
	} catch (error) {
		if (!(error instanceof Error && "code" in error && error.code === "ENOENT")) {
			throw error;
		}
	}
	// `--quiet` exits 0 only when nothing under the paths differs. A commit the checkout does not have
	// fails it too, and that reads as changed: when the host cannot tell, it runs what the channel
	// names rather than keep an image on a guess.
	const unchanged = await succeeds(
		"git",
		["diff", "--quiet", previous, releaseCommit, "--", ...POSTGRES_IMAGE_INPUTS],
		{ cwd: checkout },
	);
	const rebuilt = !unchanged || !(await sameDay(checkout, previous, releaseCommit));
	return carryPostgresImage(images, appliedLockEnv, rebuilt);
}

/**
 * The host runs the tooling of the tree it applied: its reconciler through the `tooling` link and its
 * units in systemd. A tree that predates the link is never adopted — its unit would run the checkout
 * and its reconciler could not read a current channel — so a rollback to one keeps the tooling the
 * host has. Every step is idempotent, because a tick can stop between any two of them.
 */
async function followTooling(
	config: HostConfig,
	tree: string,
	unitsDirectory: string,
): Promise<boolean> {
	if (!(await carriesToolingLink(tree))) {
		console.log(`Keeping the current tooling: ${tree} predates the tooling link`);
		return false;
	}
	const moved = await adoptTooling(config.tooling, tree);
	const changed = await syncUnits(tree, unitsDirectory);
	if (changed.length > 0) {
		console.log(`Updated ${changed.join(", ")}`);
	}
	// systemd itself knows whether the units it loaded match the files, so a tick that stopped
	// between writing a unit and reloading is finished by the next one.
	const stale = await output("systemctl", [
		"show",
		"--property=NeedDaemonReload",
		"--value",
		...UNIT_FILES,
	]);
	if (stale.split("\n").includes("yes")) {
		await run("systemctl", ["daemon-reload"]);
	}
	return moved;
}

function releasesDirectory(config: HostConfig): string {
	return path.join(config.stateDirectory, "releases");
}

function locksDirectory(config: HostConfig): string {
	return path.join(config.stateDirectory, "release-locks");
}

/** The accepted source commit a record names: kept since it was recorded, or the release itself. */
export function appliedCommit(applied: AppliedState): string | undefined {
	return applied.commit ?? (isCommit(applied.release) ? applied.release : undefined);
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
	const tree = path.join(releases, release);
	if (await isDirectory(tree)) {
		const head = await revision(tree, "HEAD");
		if (head !== commit) {
			throw new Error(`${tree} is at ${head}, not the ${commit} accepted for ${release}`);
		}
	} else {
		await run(
			"git",
			["worktree", "add", "--detach", "--force", "--force", "--quiet", tree, commit],
			{
				cwd: checkout,
			},
		);
	}
	// Tracked content only: Compose writes into the worktree it renders from — an older release's
	// proxy keeps its ACME store in `docker/letsencrypt/` — so counting untracked files would turn
	// every retry after a partial apply into a permanent refusal. An untracked file cannot alter a
	// tracked Compose file, and anyone who can write here already has the Docker socket.
	const changes = await output("git", ["status", "--porcelain=v1", "--untracked-files=no"], {
		cwd: tree,
	});
	if (changes) {
		throw new Error(`${tree} differs from ${release}`);
	}
	return { tree, commit };
}

/**
 * Removes the release trees nothing needs after an apply, then the lock of every release left without
 * a tree. Kept are the releases in `kept`, the tree the tooling link names, a tree locked with
 * `git worktree lock` — how anything else working in a tree says so — and a tree holding anything its
 * release does not, such as the `acme.json` an older release's proxy keeps in `docker/letsencrypt/`.
 * That file is ignored, and `git worktree remove` refuses only untracked and modified content, so the
 * check here includes ignored files. The next apply finishes a prune that stopped partway.
 */
async function pruneReleases(
	config: HostConfig,
	kept: readonly (string | undefined)[],
): Promise<void> {
	// A tree deleted by hand leaves its registration behind, and `remove` cannot act on that.
	await run("git", ["worktree", "prune"], { cwd: config.checkout });
	// Git lists resolved paths, so the directory is resolved too.
	const releases = await realpath(releasesDirectory(config));
	const remaining = new Set(kept);
	const tooling = await realpath(config.tooling);
	if (path.dirname(tooling) === releases) {
		remaining.add(path.basename(tooling));
	}
	const listing = await output("git", ["worktree", "list", "--porcelain", "-z"], {
		cwd: config.checkout,
	});
	for (const record of listing.split("\0\0")) {
		const lines = record.split("\0");
		const tree = lines.find((line) => line.startsWith("worktree "))?.slice("worktree ".length);
		if (tree === undefined || path.dirname(tree) !== releases) {
			continue;
		}
		const release = path.basename(tree);
		if (remaining.has(release)) {
			continue;
		}
		const lock = lines.find((line) => line === "locked" || line.startsWith("locked "));
		const reason = (
			lock ?? (await output("git", ["status", "--porcelain=v1", "--ignored"], { cwd: tree }))
		).trimEnd();
		if (reason) {
			console.log(`Keeping ${tree}: ${reason.replaceAll("\n", ", ")}`);
			remaining.add(release);
			continue;
		}
		// Never forced, so git still refuses a tree locked or given an untracked file since the check.
		await run("git", ["worktree", "remove", tree], { cwd: config.checkout });
		console.log(`Removed ${tree}`);
	}
	const locks = locksDirectory(config);
	for (const file of await readdir(locks)) {
		const { name, ext } = path.parse(file);
		if (ext === ".env" && isTarget(name) && !remaining.has(name)) {
			await rm(path.join(locks, file));
			console.log(`Removed ${path.join(locks, file)}`);
		}
	}
}

/** Unlike `existsSync`, this reports a lookup that failed for any reason other than absence. */
async function isDirectory(candidate: string): Promise<boolean> {
	try {
		const stats = await stat(candidate);
		return stats.isDirectory();
	} catch (error) {
		if (error instanceof Error && "code" in error && error.code === "ENOENT") {
			return false;
		}
		throw error;
	}
}

/** Whether a tree's own unit starts the reconciler through the tooling link, i.e. carries this model. */
export async function carriesToolingLink(tree: string): Promise<boolean> {
	let unit: string;
	try {
		unit = await readFile(path.join(tree, "docker/self-host/systemd", UNIT_FILES[0]), "utf8");
	} catch (error) {
		if (error instanceof Error && "code" in error && error.code === "ENOENT") {
			return false;
		}
		throw error;
	}
	return /^ExecStart=.*\/var\/lib\/hephaestus\/tooling\//mu.test(unit);
}

/**
 * Moves the tooling link to `tree` in one rename, so a failure leaves the current tooling in place,
 * and reports whether it moved.
 */
export async function adoptTooling(link: string, tree: string): Promise<boolean> {
	if ((await readlink(link).catch(() => undefined)) === tree) {
		return false;
	}
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
		const wanted = await readFile(path.join(tree, "docker/self-host/systemd", name), "utf8");
		const unit = path.join(unitsDirectory, name);
		const installed = await lstat(unit).catch(() => undefined);
		if (installed && !installed.isSymbolicLink() && (await readFile(unit, "utf8")) === wanted) {
			continue;
		}
		await writeAtomic(unit, wanted);
		changed.push(name);
	}
	return changed;
}

/** Without a series on failure, a broken reconcile is indistinguishable from a host running none. */
async function reportFailure(): Promise<void> {
	const metricsFile = process.env.HEPHAESTUS_METRICS_FILE;
	const channel = process.env.HEPHAESTUS_CHANNEL;
	if (!isSet(metricsFile) || !isSet(channel)) {
		return;
	}
	const applied = await readApplied(
		path.join(process.env.STATE_DIRECTORY ?? "/var/lib/hephaestus", "applied.json"),
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
			// The free space is Docker's root, which a chart host does not use.
			freeBytes: (process.env.HEPHAESTUS_STACKS ?? "").split(/[\s,]+/u).includes("chart")
				? undefined
				: await readableFreeBytes(),
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
		await reportFailure().catch(() => undefined);
		// A host waiting to be promoted is a state an operator resolves, not a defect: the journal
		// gets the sentence that says what to do. Everything else keeps its stack, because a stack is
		// what a defect is diagnosed from.
		if (error instanceof OperatorActionRequiredError) {
			console.error(error.message);
			process.exitCode = 1;
		} else {
			throw error;
		}
	}
}
