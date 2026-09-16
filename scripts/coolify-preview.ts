import { spawnSync } from "node:child_process";
import { createHmac } from "node:crypto";
import { appendFileSync } from "node:fs";
import { setTimeout as sleep } from "node:timers/promises";

import { requiredEnv as required, requiredPositiveInteger } from "./lib/env.ts";
import { isRecord } from "./lib/json.ts";

import { CAPTURE_LIMIT_BYTES } from "./lib/process.ts";

type WebhookAction = "closed" | "opened";
type FinalState = "error" | "failure" | "success";

type ReadConfig = CommonConfig & { readToken: string };

interface CommonConfig {
	appUuid: string;
	coolifyUrl: URL;
	prNumber: number;
}

export interface PullRequestConfig extends CommonConfig {
	authorAssociation: string;
	baseRef: string;
	deliveryId: string;
	headRef: string;
	headSha: string;
	prTitle: string;
	prUrl: URL;
	repository: string;
	webhookSecret: string;
}

export interface DeploymentConfig extends CommonConfig {
	deploymentUuid: string;
	expectedSha: string;
	previewUrl: URL;
	readToken: string;
}

export interface QueueConfig extends PullRequestConfig {
	readToken: string;
}

interface DeploymentRecord {
	commit: string;
	createdAt: string;
	deploymentUrl?: string;
	deploymentUuid: string;
	pullRequestId: number;
	status: string;
}

export interface Dependencies {
	fetch: (...arguments_: Parameters<typeof fetch>) => ReturnType<typeof fetch>;
	now: () => number;
	sleep: (milliseconds: number) => Promise<void>;
}

export interface WaitResult {
	description: string;
	logUrl: string;
	state: FinalState;
}

const defaultDependencies: Dependencies = {
	fetch,
	now: Date.now,
	sleep,
};

const DEPLOYMENT_STATES = new Set([
	"cancelled",
	"cancelled-by-user",
	"failed",
	"finished",
	"in_progress",
	"queued",
]);
// The images are already built by the time this runs, so this budget covers Coolify pulling and
// starting them rather than a build.
const BUILD_BUDGET_MS = 1_200_000;
// Coolify reports `finished` once the containers are up; the preview URL only answers once the
// application server clears its own healthcheck start period. This is additive on purpose: clamping
// it to the build deadline meant a slow build left the probe no time and reported a healthy preview
// as broken.
const REACHABILITY_BUDGET_MS = 300_000;
const POLL_INTERVAL_MS = 5000;
const QUEUE_POLL_ATTEMPTS = 18;
/** CI publishes the commit-addressed tags while its tests are still running, so a missing tag means
 * "not built yet", not "will never exist". Only this message is retried. */
export const IMAGE_PENDING = "Image not published yet";

/** Thrown where the read token is refused, so the retry loop can stop instead of waiting it out. */
export class CoolifyAuthError extends Error {
	constructor() {
		super("COOLIFY_PREVIEW_READ_TOKEN cannot read deployment status.");
		this.name = "CoolifyAuthError";
	}
}
const IMAGE_POLL_ATTEMPTS = 60;
const SHA_PATTERN = /^[a-f0-9]{40}$/u;
const IDENTIFIER_PATTERN = /^[A-Za-z0-9_-]+$/u;
const REF_PATTERN = /^[A-Za-z0-9._/-]+$/u;

// Not a wrapper for its own sake: `Array.isArray` narrows `unknown` to `any[]`, which makes every
// element access an `any` and trips no-unsafe-assignment. This keeps the elements `unknown`.
function isUnknownArray(value: unknown): value is unknown[] {
	return Array.isArray(value);
}

function parseHttpsUrl(value: string, name: string): URL {
	const url = new URL(value);
	if (url.protocol !== "https:") {
		throw new Error(`${name} must use HTTPS.`);
	}
	return url;
}

function commonConfig(environment: NodeJS.ProcessEnv): CommonConfig {
	const appUuid = required(environment, "COOLIFY_APP_UUID");
	if (!IDENTIFIER_PATTERN.test(appUuid)) {
		throw new Error("COOLIFY_APP_UUID is malformed.");
	}
	return {
		appUuid,
		coolifyUrl: parseHttpsUrl(required(environment, "COOLIFY_URL"), "COOLIFY_URL"),
		prNumber: requiredPositiveInteger(environment, "PR_NUMBER"),
	};
}

function pullRequestConfig(environment: NodeJS.ProcessEnv): PullRequestConfig {
	const headSha = required(environment, "HEAD_SHA");
	const baseRef = required(environment, "BASE_REF");
	const headRef = required(environment, "HEAD_REF");
	const authorAssociation = required(environment, "AUTHOR_ASSOCIATION");
	if (!SHA_PATTERN.test(headSha)) {
		throw new Error("HEAD_SHA must be a full lowercase commit SHA.");
	}
	if (!REF_PATTERN.test(baseRef) || !REF_PATTERN.test(headRef)) {
		throw new Error("Pull-request refs are malformed.");
	}
	if (!/^[A-Z_]+$/u.test(authorAssociation)) {
		throw new Error("AUTHOR_ASSOCIATION is malformed.");
	}
	return {
		...commonConfig(environment),
		authorAssociation,
		baseRef,
		deliveryId: `preview-${required(environment, "GITHUB_RUN_ID")}-${required(environment, "GITHUB_RUN_ATTEMPT")}`,
		headRef,
		headSha,
		prTitle: required(environment, "PR_TITLE"),
		prUrl: parseHttpsUrl(required(environment, "PR_URL"), "PR_URL"),
		repository: required(environment, "GITHUB_REPOSITORY"),
		webhookSecret: required(environment, "COOLIFY_WEBHOOK_SECRET"),
	};
}

function queueConfig(environment: NodeJS.ProcessEnv): QueueConfig {
	// The association is checked by resolve() before this runs and by Coolify after; a third copy of
	// the allowlist here would only be a third thing to keep in step.
	return { ...pullRequestConfig(environment), readToken: required(environment, "COOLIFY_TOKEN") };
}

function deploymentConfig(environment: NodeJS.ProcessEnv): DeploymentConfig {
	const expectedSha = required(environment, "EXPECTED_SHA");
	const deploymentUuid = required(environment, "DEPLOYMENT_UUID");
	if (!SHA_PATTERN.test(expectedSha)) {
		throw new Error("EXPECTED_SHA must be a full lowercase commit SHA.");
	}
	if (!IDENTIFIER_PATTERN.test(deploymentUuid)) {
		throw new Error("DEPLOYMENT_UUID is malformed.");
	}
	return {
		...commonConfig(environment),
		deploymentUuid,
		expectedSha,
		previewUrl: parseHttpsUrl(required(environment, "PREVIEW_URL"), "PREVIEW_URL"),
		readToken: required(environment, "COOLIFY_TOKEN"),
	};
}

export function buildWebhookPayload(config: PullRequestConfig, action: WebhookAction) {
	return {
		action,
		after: config.headSha,
		before: config.headSha,
		number: config.prNumber,
		pull_request: {
			author_association: config.authorAssociation,
			base: { ref: config.baseRef, repo: { full_name: config.repository } },
			head: {
				ref: config.headRef,
				repo: { full_name: config.repository },
				sha: config.headSha,
			},
			html_url: config.prUrl.href,
			title: config.prTitle,
		},
		repository: { full_name: config.repository },
	};
}

export function assertWebhookAccepted(value: unknown): void {
	if (!isUnknownArray(value)) {
		throw new Error("Coolify returned a malformed webhook response.");
	}
	const queued = value.filter((entry) => isRecord(entry) && entry.status === "queued");
	if (value.length === 1 && queued.length === 1) {
		return;
	}
	const rejected = value.find((entry) => isRecord(entry) && entry.status !== "queued");
	const message =
		isRecord(rejected) && typeof rejected.message === "string"
			? rejected.message.replaceAll(/[\r\n]+/gu, " ").slice(0, 200)
			: "No application accepted the webhook.";
	throw new Error(message);
}

function deploymentRecord(value: unknown): DeploymentRecord | undefined {
	if (!isRecord(value)) {
		return undefined;
	}
	const pullRequestId = Number(value.pull_request_id);
	if (
		typeof value.commit !== "string" ||
		typeof value.created_at !== "string" ||
		typeof value.deployment_uuid !== "string" ||
		typeof value.status !== "string" ||
		!Number.isSafeInteger(pullRequestId)
	) {
		return undefined;
	}
	if (!IDENTIFIER_PATTERN.test(value.deployment_uuid)) {
		return undefined;
	}
	return {
		commit: value.commit,
		createdAt: value.created_at,
		deploymentUrl: typeof value.deployment_url === "string" ? value.deployment_url : undefined,
		deploymentUuid: value.deployment_uuid,
		pullRequestId,
		status: value.status,
	};
}

export function selectExactDeployment(
	value: unknown,
	prNumber: number,
	headSha: string,
	excludedUuids: ReadonlySet<string> = new Set(),
): DeploymentRecord | undefined {
	return deploymentInventory(value)
		.records.filter(
			(record) =>
				record.pullRequestId === prNumber &&
				record.commit === headSha &&
				!excludedUuids.has(record.deploymentUuid) &&
				DEPLOYMENT_STATES.has(record.status),
		)
		.toSorted((left, right) => left.createdAt.localeCompare(right.createdAt))
		.at(-1);
}

function deploymentInventory(value: unknown): { records: DeploymentRecord[]; total: number } {
	if (!isRecord(value) || !Array.isArray(value.deployments)) {
		throw new Error("Coolify returned a malformed deployment inventory.");
	}
	const total = Number(value.count);
	if (!Number.isSafeInteger(total) || total < value.deployments.length) {
		throw new Error("Coolify returned a malformed deployment inventory count.");
	}
	const records = value.deployments.map((entry) => {
		const record = deploymentRecord(entry);
		if (!record) {
			throw new Error("Coolify returned a malformed deployment inventory record.");
		}
		return record;
	});
	return { records, total };
}

export function validateDeploymentProvenance(
	value: unknown,
	prNumber: number,
	expectedSha: string,
	expectedUuid: string,
): DeploymentRecord {
	const record = deploymentRecord(value);
	if (!record) {
		throw new Error("Coolify returned a malformed deployment record.");
	}
	if (
		record.pullRequestId !== prNumber ||
		record.commit !== expectedSha ||
		record.deploymentUuid !== expectedUuid
	) {
		throw new Error("Coolify queue provenance does not match the approved PR commit.");
	}
	return record;
}

async function jsonResponse(response: Response, operation: string): Promise<unknown> {
	const text = await response.text();
	if (!response.ok) {
		throw new Error(`${operation} returned HTTP ${response.status}.`);
	}
	try {
		return JSON.parse(text) as unknown;
	} catch {
		throw new Error(`${operation} returned malformed JSON.`);
	}
}

interface Attempt {
	readonly error?: string;
	readonly response?: Response;
}

// Returns the failure rather than stashing it: a connection refused from a still-booting preview and
// a DNS failure reaching Coolify are different diagnoses, and a shared global reported one as the
// other.
async function attemptFetch(
	dependencies: Dependencies,
	input: URL,
	init: RequestInit,
): Promise<Attempt> {
	try {
		return { response: await dependencies.fetch(input, init) };
	} catch (error) {
		return { error: error instanceof Error ? error.message : "unknown transport error" };
	}
}

async function sendWebhook(
	config: PullRequestConfig,
	action: WebhookAction,
	dependencies: Dependencies,
): Promise<void> {
	const payload = JSON.stringify(buildWebhookPayload(config, action));
	const signature = createHmac("sha256", config.webhookSecret).update(payload).digest("hex");
	const response = await dependencies.fetch(
		new URL("/webhooks/source/github/events/manual", config.coolifyUrl),
		{
			body: payload,
			headers: {
				Accept: "application/json",
				"Content-Type": "application/json",
				"X-GitHub-Delivery": `${config.deliveryId}-${action}`,
				"X-GitHub-Event": "pull_request",
				"X-Hub-Signature-256": `sha256=${signature}`,
			},
			method: "POST",
			signal: AbortSignal.timeout(30_000),
		},
	);
	assertWebhookAccepted(await jsonResponse(response, "Coolify webhook"));
}

// The newest 100 records are enough: every caller is looking for a deployment this job just queued.
async function readDeploymentInventory(
	config: ReadConfig,
	dependencies: Dependencies,
): Promise<unknown> {
	const { response, error } = await attemptFetch(
		dependencies,
		new URL(
			`/api/v1/deployments/applications/${config.appUuid}?skip=0&take=100`,
			config.coolifyUrl,
		),
		{
			headers: { Accept: "application/json", Authorization: `Bearer ${config.readToken}` },
			signal: AbortSignal.timeout(15_000),
		},
	);
	if (!response) {
		throw new Error(`Could not reach Coolify: ${error ?? "unknown"}.`);
	}
	if (response.status === 401 || response.status === 403) {
		throw new CoolifyAuthError();
	}
	if (!response.ok) {
		throw new Error(`Coolify deployment inventory returned HTTP ${response.status}.`);
	}
	const value = await jsonResponse(response, "Coolify deployment inventory");
	deploymentInventory(value);
	return value;
}

export async function queuePreview(
	config: QueueConfig,
	dependencies: Dependencies = defaultDependencies,
): Promise<string> {
	const before = await readDeploymentInventory(config, dependencies);
	const previous = deploymentInventory(before).records.filter(
		(record) => record.pullRequestId === config.prNumber && record.commit === config.headSha,
	);
	const active = previous
		.filter((record) => record.status === "queued" || record.status === "in_progress")
		.toSorted((left, right) => left.createdAt.localeCompare(right.createdAt))
		.at(-1);
	if (active) {
		return active.deploymentUuid;
	}
	const excludedUuids = new Set<string>();
	for (const record of previous) {
		excludedUuids.add(record.deploymentUuid);
	}
	await sendWebhook(config, "opened", dependencies);
	for (let attempt = 0; attempt < QUEUE_POLL_ATTEMPTS; attempt += 1) {
		const pause = async (): Promise<void> => {
			if (attempt < QUEUE_POLL_ATTEMPTS - 1) {
				await dependencies.sleep(POLL_INTERVAL_MS);
			}
		};
		let inventory: unknown;
		try {
			inventory = await readDeploymentInventory(config, dependencies);
		} catch (error) {
			if (error instanceof CoolifyAuthError) {
				throw error;
			}
			await pause();
			continue;
		}
		const deployment = selectExactDeployment(
			inventory,
			config.prNumber,
			config.headSha,
			excludedUuids,
		);
		if (deployment) {
			return deployment.deploymentUuid;
		}
		await pause();
	}
	throw new Error("Coolify accepted the webhook but no exact-SHA deployment record appeared.");
}

// Narrower than DeploymentConfig on purpose: this only needs somewhere to fall back to.
export function deploymentLogUrl(
	config: Pick<DeploymentConfig, "coolifyUrl">,
	candidate?: string,
): string {
	if (candidate === undefined || candidate === "" || /\s/u.test(candidate)) {
		return config.coolifyUrl.href;
	}
	if (candidate.startsWith("/project/")) {
		return new URL(candidate, config.coolifyUrl).href;
	}
	try {
		const parsed = new URL(candidate);
		return parsed.protocol === "https:" ? parsed.href : config.coolifyUrl.href;
	} catch {
		return config.coolifyUrl.href;
	}
}

/** Coolify has finished; the preview itself still has to answer. */
async function waitForReachability(
	config: DeploymentConfig,
	dependencies: Dependencies,
	logUrl: string,
): Promise<WaitResult> {
	const healthDeadline = dependencies.now() + REACHABILITY_BUDGET_MS;
	while (dependencies.now() < healthDeadline) {
		// Scoped to this probe: a connection refused from a still-booting preview must
		// not be reported as a failure to reach Coolify.
		const { response: health } = await attemptFetch(dependencies, config.previewUrl, {
			redirect: "manual",
			signal: AbortSignal.timeout(10_000),
		});
		if (health?.ok === true) {
			return {
				description: "Approved preview is deployed and reachable.",
				logUrl,
				state: "success",
			};
		}
		await dependencies.sleep(POLL_INTERVAL_MS);
	}
	return {
		description: "Coolify finished, but the preview did not return HTTP 2xx.",
		logUrl,
		state: "failure",
	};
}

export async function waitForDeployment(
	config: DeploymentConfig,
	dependencies: Dependencies = defaultDependencies,
): Promise<WaitResult> {
	const deadline = dependencies.now() + BUILD_BUDGET_MS;
	let logUrl = config.coolifyUrl.href;
	let coolifyError = "";
	while (dependencies.now() < deadline) {
		const { response, error } = await attemptFetch(
			dependencies,
			new URL(`/api/v1/deployments/${config.deploymentUuid}`, config.coolifyUrl),
			{
				headers: { Accept: "application/json", Authorization: `Bearer ${config.readToken}` },
				signal: AbortSignal.timeout(10_000),
			},
		);
		if (!response) {
			coolifyError = error ?? coolifyError;
			await dependencies.sleep(POLL_INTERVAL_MS);
			continue;
		}
		if (response.status === 401 || response.status === 403) {
			return {
				description: "Coolify read token cannot read deployment status.",
				logUrl,
				state: "error",
			};
		}
		if (response.ok) {
			try {
				const record = validateDeploymentProvenance(
					await jsonResponse(response, "Coolify deployment status"),
					config.prNumber,
					config.expectedSha,
					config.deploymentUuid,
				);
				logUrl = deploymentLogUrl(config, record.deploymentUrl);
				if (record.status === "finished") {
					return await waitForReachability(config, dependencies, logUrl);
				}
				if (record.status === "failed") {
					return {
						description: "Coolify reported a failed preview deployment.",
						logUrl,
						state: "failure",
					};
				}
				if (record.status === "cancelled" || record.status === "cancelled-by-user") {
					return { description: "Preview deployment was cancelled.", logUrl, state: "failure" };
				}
			} catch (invalidRecordError) {
				return {
					description:
						invalidRecordError instanceof Error
							? invalidRecordError.message
							: "Invalid Coolify deployment response.",
					logUrl,
					state: "failure",
				};
			}
		}
		await dependencies.sleep(POLL_INTERVAL_MS);
	}
	const timeout = coolifyError
		? `Gave up reaching Coolify: ${coolifyError}.`
		: "Timed out waiting for Coolify to finish building and starting the preview.";
	return { description: timeout, logUrl, state: "error" };
}

export interface CommandRunner {
	run: (
		command: string,
		arguments_: readonly string[],
		options?: { input?: string },
	) => { status: number | null; stderr: string; stdout: string };
}

const systemCommandRunner: CommandRunner = {
	run: (command, arguments_, options) => {
		const result = spawnSync(command, [...arguments_], {
			encoding: "utf8",
			maxBuffer: CAPTURE_LIMIT_BYTES,
			input: options?.input,
			stdio: ["pipe", "pipe", "pipe"],
		});
		return { status: result.status, stderr: result.stderr, stdout: result.stdout };
	},
};

/**
 * Every image the preview runs must be the artifact CI published for this exact commit, and must
 * carry an attestation naming this repository's build workflow as its signer. That is what makes a
 * preview the shipped thing rather than a lookalike.
 */
export function loginToRegistry(
	environment: NodeJS.ProcessEnv,
	runner: CommandRunner = systemCommandRunner,
): void {
	const login = runner.run(
		"docker",
		["login", "ghcr.io", "-u", required(environment, "GITHUB_ACTOR"), "--password-stdin"],
		{ input: required(environment, "GHCR_TOKEN") },
	);
	if (login.status !== 0) {
		throw new Error("Could not authenticate to GHCR.");
	}
}

export function checkImages(
	environment: NodeJS.ProcessEnv,
	runner: CommandRunner = systemCommandRunner,
	log: (message: string) => void = console.log,
): void {
	const headSha = required(environment, "HEAD_SHA");
	const repository = required(environment, "GITHUB_REPOSITORY");
	if (!SHA_PATTERN.test(headSha)) {
		throw new Error("HEAD_SHA must be a full lowercase commit SHA.");
	}
	if (!/^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/u.test(repository)) {
		throw new Error("GITHUB_REPOSITORY is malformed.");
	}
	for (const image of ["application-server", "webapp", "postgres"]) {
		const repositoryPath = `ghcr.io/hephaestus-build/${image}`;
		const reference = `${repositoryPath}:${headSha}`;
		const inspect = runner.run("docker", [
			"buildx",
			"imagetools",
			"inspect",
			reference,
			"--format",
			"{{.Manifest.Digest}}",
		]);
		const digest = inspect.stdout.trim();
		if (inspect.status !== 0 || !/^sha256:[a-f0-9]{64}$/u.test(digest)) {
			throw new Error(`${IMAGE_PENDING}: ${reference}`);
		}
		// `gh attestation verify` exits non-zero unless at least one attestation for this digest was
		// signed by that workflow in this repository, so its status is the whole check. The commit it
		// names is not compared: for a component the pull request did not change, CI re-tags the base
		// commit's image, so the attested revision is legitimately not this head.
		const verify = runner.run("gh", [
			"attestation",
			"verify",
			`oci://${repositoryPath}@${digest}`,
			"--repo",
			repository,
			"--signer-workflow",
			`${repository}/.github/workflows/reusable-docker-build.yml`,
			// A GitHub-hosted runner is part of what the signature attests to; a self-hosted one is not
			// in this repository's build path at all.
			"--deny-self-hosted-runners",
			"--predicate-type",
			"https://slsa.dev/provenance/v1",
		]);
		if (verify.status !== 0) {
			throw new Error(`${image} does not have trusted build provenance.`);
		}
		log(`${image}: ${digest}`);
	}
}

/**
 * Waits for this commit's images rather than for its test suite. CI builds images in parallel with
 * the tests, so a preview can be up minutes before the run finishes — and can exist at all for a
 * pull request whose tests are red, which is when looking at the running app helps most.
 */
export async function awaitImages(
	environment: NodeJS.ProcessEnv,
	runner: CommandRunner = systemCommandRunner,
	log: (message: string) => void = console.log,
	dependencies: Dependencies = defaultDependencies,
): Promise<void> {
	loginToRegistry(environment, runner);
	for (let attempt = 0; attempt < IMAGE_POLL_ATTEMPTS; attempt += 1) {
		try {
			checkImages(environment, runner, log);
			return;
		} catch (error) {
			if (!(error instanceof Error) || !error.message.startsWith(IMAGE_PENDING)) {
				throw error;
			}
			if (attempt === 0) {
				log("::notice::Waiting for CI to publish this commit's images.");
			}
			if (attempt === IMAGE_POLL_ATTEMPTS - 1) {
				throw new Error("CI never published images for this commit.", { cause: error });
			}
			await dependencies.sleep(POLL_INTERVAL_MS * 2);
		}
	}
}

// A newline in a value would let a Coolify-supplied string forge further step outputs, so the
// single-line form is only safe once newlines are gone.
export function formatOutputs(outputs: Record<string, string>): string {
	return `${Object.entries(outputs)
		.map(([key, value]) => `${key}=${value.replaceAll(/[\r\n]+/gu, " ")}`)
		.join("\n")}\n`;
}

function appendOutputs(outputs: Record<string, string>): void {
	appendFileSync(required(process.env, "GITHUB_OUTPUT"), formatOutputs(outputs));
}

async function main(): Promise<void> {
	const command = process.argv[2];
	if (command === "images") {
		await awaitImages(process.env);
		return;
	}
	if (command === "queue") {
		const config = queueConfig(process.env);
		const deploymentUuid = await queuePreview(config);
		appendOutputs({ deployment_uuid: deploymentUuid });
		console.log(`::notice::Queued exact commit ${config.headSha} for PR #${config.prNumber}.`);
		return;
	}
	if (command === "close") {
		const config = pullRequestConfig(process.env);
		await sendWebhook(config, "closed", defaultDependencies);
		console.log(`::notice::Coolify accepted the close event for PR #${config.prNumber}.`);
		return;
	}
	if (command === "wait") {
		const result = await waitForDeployment(deploymentConfig(process.env));
		appendOutputs({ description: result.description, log_url: result.logUrl, state: result.state });
		return;
	}
	throw new Error("usage: coolify-preview.ts {images|queue|close|wait}");
}

if (import.meta.main) {
	try {
		await main();
	} catch (error) {
		const message = error instanceof Error ? error.message : "Unknown Coolify preview error.";
		console.error(`::error::${message.replaceAll(/[\r\n]+/gu, " ")}`);
		process.exitCode = 1;
	}
}
