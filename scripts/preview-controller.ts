import { requiredEnv, requiredPositiveInteger } from "./lib/env.ts";

type ApiMethod<T> = (params: Record<string, unknown>) => Promise<{ data: T }>;

interface PullRequest {
	readonly state: string;
	readonly html_url: string;
	readonly title: string;
	readonly author_association: string;
	readonly labels: readonly { readonly name: string }[];
	readonly base: { readonly ref: string };
	readonly head: {
		readonly ref: string;
		readonly sha: string;
		readonly repo?: { readonly full_name: string } | null;
	};
}

interface PullRequestFile {
	readonly filename: string;
}

interface Deployment {
	readonly environment: string;
	readonly id: number;
	readonly sha: string;
}

interface RetirementOptions {
	readonly description?: string;
	readonly forceStatus?: boolean;
	readonly keepDeploymentId?: number;
	readonly keepRecords?: boolean;
}

export interface GitHubApi {
	readonly paginate: <T>(endpoint: ApiMethod<T[]>, params: Record<string, unknown>) => Promise<T[]>;
	readonly rest: {
		readonly pulls: {
			readonly get: ApiMethod<PullRequest>;
		};
		readonly repos: {
			readonly compareCommitsWithBasehead: ApiMethod<{ files?: PullRequestFile[] }>;
			readonly createDeployment: ApiMethod<Deployment>;
			readonly createDeploymentStatus: ApiMethod<unknown>;
			readonly deleteDeployment: ApiMethod<unknown>;
			readonly listDeployments: ApiMethod<Deployment[]>;
			readonly listDeploymentStatuses: ApiMethod<
				{ readonly description?: string | null; readonly state: string }[]
			>;
		};
	};
}

interface ActionsContext {
	readonly repo: { readonly owner: string; readonly repo: string };
	readonly payload: {
		readonly repository: { readonly default_branch: string };
		readonly pull_request?: { readonly number: number };
	};
}

interface ActionsCore {
	readonly notice: (message: string) => void;
	readonly setFailed: (message: string) => void;
	readonly setOutput: (name: string, value: string) => void;
}

interface ControllerInput {
	readonly github: GitHubApi;
	readonly context: ActionsContext;
	readonly core: ActionsCore;
}

const PREVIEW_LABEL = "preview";
/**
 * The changelog alone decides whether a restored staging database still fits an older application.
 * Entities are deliberately not listed: `db:check-drift` keeps them moving with the changelog, so
 * the changelog is the whole signal, and matching on Java would refuse a preview for any merge.
 */
const SCHEMA_PATHS = ["server/application/src/main/resources/db/"] as const;

const TRUSTED_ASSOCIATIONS = new Set(["COLLABORATOR", "MEMBER", "OWNER"]);
// GitHub's comparison endpoint reports at most this many files and gives no truncation flag.
const COMPARE_FILE_LIMIT = 300;
const DEFAULT_MAX_ACTIVE = 3;

/** One night's sweep. Reached only if teardown has been failing, which is when a bound matters. */
const RECONCILE_LIMIT = 100;
const LIVE_STATES = new Set(["in_progress", "pending", "queued", "success"]);
const TEARDOWN_REQUESTED_DESCRIPTION =
	"Preview teardown requested; awaiting Coolify reconciliation.";

const hasPreviewLabel = (pull: PullRequest): boolean =>
	pull.labels.some((label) => label.name === PREVIEW_LABEL);

const maxActivePreviews = (): number =>
	process.env.PREVIEW_MAX_ACTIVE
		? requiredPositiveInteger(process.env, "PREVIEW_MAX_ACTIVE")
		: DEFAULT_MAX_ACTIVE;

/**
 * Environments still holding a slot. A pull request whose teardown has been requested does not hold
 * one: the request is the same close event Coolify acts on, and the nightly reconcile re-sends it if
 * that was missed. Nothing here reads the host, so a slot is freed on the request, not on proof.
 */
const occupiedEnvironments = async (
	github: GitHubApi,
	owner: string,
	repo: string,
): Promise<string[]> => {
	const deployments = await github.paginate(github.rest.repos.listDeployments, {
		owner,
		repo,
		task: "deploy:preview",
		per_page: 100,
	});
	const occupied = new Set<string>();
	for (const deployment of deployments) {
		if (occupied.has(deployment.environment)) continue;
		const statuses = await github.rest.repos.listDeploymentStatuses({
			owner,
			repo,
			deployment_id: deployment.id,
			per_page: 1,
		});
		const latest = statuses.data[0]?.state;
		// A requested teardown has been handed to Coolify and a failed deploy never reached the host,
		// so neither holds anything — counting them would report a full host that is empty.
		if (latest === "failure" || latest === "error") continue;
		if (latest === "inactive" && statuses.data[0]?.description === TEARDOWN_REQUESTED_DESCRIPTION) {
			continue;
		}
		occupied.add(deployment.environment);
	}
	return [...occupied].toSorted();
};

const resolve = async ({ github, context, core }: ControllerInput): Promise<void> => {
	const { owner, repo } = context.repo;
	if (!context.payload.pull_request) throw new Error("Pull request payload is incomplete.");
	const number = context.payload.pull_request.number;
	const { data: pull } = await github.rest.pulls.get({ owner, repo, pull_number: number });
	const defaultBranch = context.payload.repository.default_branch;
	const environment = `preview/pr-${number}`;
	const labelled = hasPreviewLabel(pull);
	core.setOutput("pr_number", String(number));
	core.setOutput("environment", environment);
	core.setOutput("announce", "false");

	// Only a labelled pull request, and only for a reason someone can act on, earns a status comment.
	const skip = (reason: string, quiet = false): void => {
		core.notice(reason);
		core.setOutput("eligible", "false");
		core.setOutput("reason", reason);
		core.setOutput("announce", String(labelled && !quiet));
	};

	if (!labelled) return skip(`PR #${number} does not carry the \`${PREVIEW_LABEL}\` label.`);
	if (pull.state !== "open") return skip(`PR #${number} is closed.`);
	if (pull.head.repo?.full_name !== `${owner}/${repo}`) {
		return skip(
			`PR #${number} comes from a fork. Previews run only for branches in this repository.`,
		);
	}
	// Coolify is handed this association and refuses an untrusted one. Checking it here turns that
	// into a skip reason on the pull request instead of a failure after the deployment is announced.
	if (!TRUSTED_ASSOCIATIONS.has(pull.author_association)) {
		return skip(
			`PR #${number} was opened by a ${pull.author_association.toLowerCase()}, not a repository collaborator.`,
		);
	}

	// Compared against the default branch rather than this pull request's own base: a stacked layer's
	// diff hides whatever the layers beneath it changed, and those commits are in the head that
	// Coolify deploys.
	const comparison = await github.rest.repos.compareCommitsWithBasehead({
		owner,
		repo,
		basehead: `${defaultBranch}...${pull.head.sha}`,
	});
	const files = comparison.data.files ?? [];
	if (files.length >= COMPARE_FILE_LIMIT) {
		return skip(
			`PR #${number} changes ${files.length}+ files, too many for GitHub to report in one comparison, so deployment policy cannot be verified.`,
		);
	}
	// A preview restores staging's database — the default branch's schema — into an application
	// built from this pull request. Hibernate boots with `ddl-auto: validate`, so a branch that
	// predates a schema change on the default branch maps entities to tables the restored database
	// no longer has, and the container exits its healthcheck with nothing on the pull request to
	// say why. Naming it here costs a comparison; discovering it costs the whole deployment.
	const behind = await github.rest.repos.compareCommitsWithBasehead({
		owner,
		repo,
		basehead: `${pull.head.sha}...${defaultBranch}`,
	});
	const missingSchemaChange = (behind.data.files ?? []).find((file) =>
		SCHEMA_PATHS.some((path) => file.filename.startsWith(path)),
	);
	if (missingSchemaChange) {
		return skip(
			`PR #${number} is behind ${defaultBranch} on \`${missingSchemaChange.filename}\`. A preview ` +
				`restores ${defaultBranch}'s schema, which this branch's entities no longer match, so the ` +
				`application would fail validation on boot. Update the branch to preview it.`,
		);
	}

	const protectedFile = files.find(
		(file) =>
			file.filename.startsWith("docker/preview/") ||
			file.filename.startsWith(".github/workflows/") ||
			file.filename.startsWith(".github/actions/"),
	);
	if (protectedFile) {
		return skip(
			`PR #${number} changes trusted deployment policy (\`${protectedFile.filename}\`), so it cannot deploy until that change is merged.`,
		);
	}

	const deployments = await github.rest.repos.listDeployments({
		owner,
		repo,
		environment,
		per_page: 1,
	});
	const current = deployments.data[0];
	if (current?.sha === pull.head.sha) {
		const statuses = await github.rest.repos.listDeploymentStatuses({
			owner,
			repo,
			deployment_id: current.id,
			per_page: 1,
		});
		if (LIVE_STATES.has(statuses.data[0]?.state ?? "")) {
			return skip(`PR #${number} already has a current preview deployment.`, true);
		}
	}

	const maxActive = maxActivePreviews();
	const occupied = await occupiedEnvironments(github, owner, repo);
	if (!occupied.includes(environment) && occupied.length >= maxActive) {
		const holders = occupied.map((slot) => `#${slot.replace("preview/pr-", "")}`).join(", ");
		return skip(
			`The preview host is full (${occupied.length}/${maxActive}). Remove the \`${PREVIEW_LABEL}\` label from ${holders} to free a slot.`,
		);
	}

	const previewTemplate = requiredEnv(process.env, "COOLIFY_PREVIEW_URL_TEMPLATE");
	if (!previewTemplate.includes("{pr}")) {
		throw new Error("COOLIFY_PREVIEW_URL_TEMPLATE must contain {pr}.");
	}
	const coolifyUrl = new URL(requiredEnv(process.env, "COOLIFY_URL"));
	const previewUrl = new URL(previewTemplate.replace("{pr}", String(number)));
	if (coolifyUrl.protocol !== "https:" || previewUrl.protocol !== "https:") {
		throw new Error("Coolify and preview URLs must use HTTPS.");
	}
	core.setOutput("eligible", "true");
	core.setOutput("pr_url", pull.html_url);
	core.setOutput("pr_title", pull.title);
	core.setOutput("author_association", pull.author_association);
	core.setOutput("head_ref", pull.head.ref);
	// Coolify selects the preview application by the webhook's base ref, and that application is
	// configured for the default branch. It is a routing key here, not a description of the stack.
	core.setOutput("base_ref", defaultBranch);
	core.setOutput("head_sha", pull.head.sha);
	core.setOutput("preview_url", previewUrl.href);
};

/**
 * Last check before the approved head is handed to Coolify. Losing the race is normal — someone
 * dropped the label, or pushed again — and neither is a fault, so this stops the deployment through
 * `proceed` rather than failing the run and leaving a red mark the author cannot act on.
 */
const recheck = async ({ github, context, core }: ControllerInput): Promise<void> => {
	const { owner, repo } = context.repo;
	const number = requiredPositiveInteger(process.env, "PR_NUMBER");
	const headSha = requiredEnv(process.env, "HEAD_SHA");
	const { data: pull } = await github.rest.pulls.get({ owner, repo, pull_number: number });
	const halt = (reason: string): void => {
		core.notice(reason);
		core.setOutput("proceed", "false");
	};
	if (pull.state !== "open" || !hasPreviewLabel(pull)) {
		return halt(`PR #${number} opted out while deploying; cleanup takes it from here.`);
	}
	if (pull.head.sha !== headSha) {
		return halt(`PR #${number} moved to a newer head; its own CI run will deploy it.`);
	}
	if (pull.head.repo?.full_name !== `${owner}/${repo}`) {
		core.setFailed(
			`PR #${number} became a fork pull request during preflight; refusing to deploy.`,
		);
		return;
	}
	core.setOutput("proceed", "true");
};

const create = async ({ github, context, core }: ControllerInput): Promise<void> => {
	const { owner, repo } = context.repo;
	const headSha = requiredEnv(process.env, "HEAD_SHA");
	const environment = requiredEnv(process.env, "ENVIRONMENT");
	const response = await github.rest.repos.createDeployment({
		owner,
		repo,
		ref: headSha,
		task: "deploy:preview",
		auto_merge: false,
		required_contexts: [],
		environment,
		description: `Coolify preview for PR #${requiredEnv(process.env, "PR_NUMBER")}`,
		transient_environment: true,
		production_environment: false,
	});
	if (response.data.sha !== headSha) {
		throw new Error("GitHub did not register the deployment against the requested head SHA.");
	}
	const deploymentId = response.data.id;
	core.setOutput("deployment_id", String(deploymentId));
	await github.rest.repos.createDeploymentStatus({
		owner,
		repo,
		deployment_id: deploymentId,
		state: "queued",
		description: "Admission reserved; Coolify queue follows.",
		environment,
		environment_url: requiredEnv(process.env, "PREVIEW_URL"),
		log_url: requiredEnv(process.env, "SOURCE_RUN_URL"),
	});
};

/**
 * Moves the deployment GitHub already shows to its next state. Without this the record is created
 * and then says nothing until the run ends, which for a preview waiting on CI images is most of its
 * life — and a deployment that never moves is indistinguishable from one that is stuck.
 */
const progress = async ({ github, context }: ControllerInput): Promise<void> => {
	const { owner, repo } = context.repo;
	const state = requiredEnv(process.env, "STATE");
	if (state !== "queued" && state !== "in_progress") {
		throw new Error(`progress reports queued or in_progress, not ${state}.`);
	}
	await github.rest.repos.createDeploymentStatus({
		owner,
		repo,
		deployment_id: requiredPositiveInteger(process.env, "DEPLOYMENT_ID"),
		state,
		description: requiredEnv(process.env, "DESCRIPTION").slice(0, 140),
		environment: requiredEnv(process.env, "ENVIRONMENT"),
		environment_url: requiredEnv(process.env, "PREVIEW_URL"),
		log_url: requiredEnv(process.env, "SOURCE_RUN_URL"),
	});
};

const finalize = async ({ github, context, core }: ControllerInput): Promise<void> => {
	const { owner, repo } = context.repo;
	const deploymentId = requiredPositiveInteger(process.env, "DEPLOYMENT_ID");
	const environment = requiredEnv(process.env, "ENVIRONMENT");
	const previewUrl = requiredEnv(process.env, "PREVIEW_URL");
	const sourceRunUrl = requiredEnv(process.env, "SOURCE_RUN_URL");
	const allowedStates = new Set(["error", "failure", "inactive", "success"]);
	const finalState = requiredEnv(process.env, "FINAL_STATE");
	let state = allowedStates.has(finalState) ? finalState : "error";
	let description = requiredEnv(process.env, "DESCRIPTION");
	const pull = await github.rest.pulls.get({
		owner,
		repo,
		pull_number: requiredPositiveInteger(process.env, "PR_NUMBER"),
	});
	if (pull.data.state !== "open" || !hasPreviewLabel(pull.data)) {
		state = "inactive";
		description = "Preview opted out while deploying; cleanup owns the final state.";
	}
	const isHttps = (value: string): boolean => {
		try {
			return new URL(value).protocol === "https:";
		} catch {
			return false;
		}
	};
	await github.rest.repos.createDeploymentStatus({
		owner,
		repo,
		deployment_id: deploymentId,
		state,
		description: description.slice(0, 140),
		environment,
		environment_url: previewUrl,
		log_url: isHttps(requiredEnv(process.env, "LOG_URL"))
			? requiredEnv(process.env, "LOG_URL")
			: sourceRunUrl,
	});

	core.setOutput("final_state", state);
	if (state !== "success") return;
	await retireDeployments(github, owner, repo, environment, { keepDeploymentId: deploymentId });
};

async function retireDeployments(
	github: GitHubApi,
	owner: string,
	repo: string,
	environment: string,
	options: RetirementOptions = {},
): Promise<void> {
	const {
		description = "Preview resources are absent or this deployment was superseded.",
		forceStatus = false,
		keepDeploymentId,
		keepRecords = false,
	} = options;
	const deployments = await github.paginate(github.rest.repos.listDeployments, {
		owner,
		repo,
		environment,
		per_page: 100,
	});
	for (const deployment of deployments) {
		if (deployment.id === keepDeploymentId) continue;
		const statuses = await github.rest.repos.listDeploymentStatuses({
			owner,
			repo,
			deployment_id: deployment.id,
			per_page: 1,
		});
		if (forceStatus || statuses.data[0]?.state !== "inactive") {
			await github.rest.repos.createDeploymentStatus({
				owner,
				repo,
				deployment_id: deployment.id,
				state: "inactive",
				description,
				environment,
			});
		}
		if (!keepRecords) {
			await github.rest.repos.deleteDeployment({ owner, repo, deployment_id: deployment.id });
		}
	}
}

const inactivate = async ({ github, context }: ControllerInput): Promise<void> => {
	const { owner, repo } = context.repo;
	await retireDeployments(github, owner, repo, requiredEnv(process.env, "ENVIRONMENT"), {
		description: TEARDOWN_REQUESTED_DESCRIPTION,
		forceStatus: true,
		keepRecords: true,
	});
};

/** Whether a preview found on the host or in GitHub's records should still be holding its slot. */
const assess = async ({ github, context, core }: ControllerInput): Promise<void> => {
	const { owner, repo } = context.repo;
	const number = requiredPositiveInteger(process.env, "PR_NUMBER");
	const { data: pull } = await github.rest.pulls.get({ owner, repo, pull_number: number });
	const stale = pull.state !== "open" || !hasPreviewLabel(pull);
	core.setOutput("stale", String(stale));
	core.setOutput("closed", String(pull.state !== "open"));
	if (!stale) {
		core.notice(`PR #${number} still wants its preview; leaving it untouched.`);
		return;
	}
	core.setOutput("url", pull.html_url);
	core.setOutput("title", pull.title);
	core.setOutput("association", pull.author_association);
	core.setOutput("head_ref", pull.head.ref);
	core.setOutput("head_sha", pull.head.sha);
	core.setOutput("base_ref", context.payload.repository.default_branch);
};

/**
 * Candidates for the nightly sweep: the environments admission still believes are occupied. A
 * preview whose teardown was already recorded holds nothing, so re-sending its close event would
 * ask Coolify to remove a stack that is gone — every night, for every preview ever deployed. Worse,
 * the sweep is bounded, and a list that only grows would fill that bound with previews already dealt
 * with, leaving a genuinely leaked one unreached.
 */
const inventory = async ({ github, context, core }: ControllerInput): Promise<void> => {
	const { owner, repo } = context.repo;
	const occupied = await occupiedEnvironments(github, owner, repo);
	const numbers = occupied
		.map((environment) => Number(environment.slice("preview/pr-".length)))
		.filter((number) => Number.isSafeInteger(number) && number > 0)
		.toSorted((left, right) => left - right);
	core.setOutput("previews", JSON.stringify(numbers.slice(0, RECONCILE_LIMIT)));
	if (numbers.length > RECONCILE_LIMIT) {
		core.notice(
			`Found ${numbers.length} previews still holding a slot; reconciling the oldest ${RECONCILE_LIMIT}.`,
		);
	}
};

const retire = async ({ github, context }: ControllerInput): Promise<void> => {
	const { owner, repo } = context.repo;
	await retireDeployments(github, owner, repo, requiredEnv(process.env, "ENVIRONMENT"));
};

export {
	progress,
	TEARDOWN_REQUESTED_DESCRIPTION,
	PREVIEW_LABEL,
	assess,
	create,
	inventory,
	finalize,
	inactivate,
	recheck,
	resolve,
	retire,
};
