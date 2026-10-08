// Version-PR trigger contract: docs/contributor/ci-cd.mdx.
import { isSet, requiredEnv } from "./lib/env.ts";
import { asArray, asRecord, asString, readJsonFile } from "./lib/json.ts";
import { output } from "./lib/process.ts";

export const CI_WORKFLOW = "cicd.yml";

export function versionBranch(config: unknown): string {
	const baseBranch = asString(asRecord(config, "changeset config").baseBranch, "baseBranch");
	if (!baseBranch) {
		throw new Error("changeset config declares no baseBranch");
	}
	return `changeset-release/${baseBranch}`;
}

export interface WorkflowRun {
	readonly headSha: string;
	readonly conclusion: string | null;
	readonly event: string;
	/** `owner/name` of the repository the head commit came from; null once that repository is gone. */
	readonly headRepository: string | null;
}

export type Disposition = "covered" | "awaiting-approval" | "dispatch";

/**
 * An authentic same-head pull-request run awaiting approval owns that invocation. Waiting avoids
 * a competing dispatch in the same concurrency group; actual failures still need an explicit rerun.
 */
export function disposition(
	headSha: string,
	repository: string,
	runs: readonly WorkflowRun[],
): Disposition {
	if (
		runs.some(
			(run) =>
				run.headSha === headSha &&
				run.conclusion !== "cancelled" &&
				run.conclusion !== "action_required",
		)
	) {
		return "covered";
	}
	return runs.some(
		(run) =>
			run.headSha === headSha &&
			run.event === "pull_request" &&
			run.headRepository === repository &&
			run.conclusion === "action_required",
	)
		? "awaiting-approval"
		: "dispatch";
}

export function parseRuns(value: unknown): WorkflowRun[] {
	return asArray(asRecord(value, "workflow runs").workflow_runs, "workflow_runs").map(
		(run, index) => {
			const record = asRecord(run, `run ${index}`);
			return {
				headSha: asString(record.head_sha, `run ${index} head_sha`),
				conclusion:
					record.conclusion === null
						? null
						: asString(record.conclusion, `run ${index} conclusion`),
				event: asString(record.event, `run ${index} event`),
				headRepository:
					record.head_repository === null
						? null
						: asString(record.head_repository, `run ${index} head_repository`),
			};
		},
	);
}

async function gh(args: string[]): Promise<string> {
	return output("gh", args);
}

export function parseBranchHead(value: unknown, branch: string): string | undefined {
	const refs = asArray(value, "matching refs").map((ref) => asRecord(ref, "ref"));
	const ref = refs.find(
		(candidate) => asString(candidate.ref, "ref name") === `refs/heads/${branch}`,
	);
	return ref ? asString(asRecord(ref.object, "ref object").sha, "branch sha") : undefined;
}

async function branchHead(repository: string, branch: string): Promise<string | undefined> {
	// Missing branches return no matching refs; transport and authentication errors still fail.
	return parseBranchHead(
		JSON.parse(
			await gh([
				"api",
				`repos/${repository}/git/matching-refs/heads/${branch.split("/").map(encodeURIComponent).join("/")}`,
			]),
		),
		branch,
	);
}

async function main(): Promise<void> {
	const repository = requiredEnv(process.env, "GITHUB_REPOSITORY");
	const branch = versionBranch(await readJsonFile(".changeset/config.json"));
	const headSha = await branchHead(repository, branch);
	if (!isSet(headSha)) {
		process.stdout.write(`No ${branch} branch; nothing to validate.\n`);
		return;
	}
	const runs = parseRuns(
		JSON.parse(
			await gh([
				"api",
				`repos/${repository}/actions/workflows/${CI_WORKFLOW}/runs?branch=${encodeURIComponent(branch)}&per_page=100`,
				"--jq",
				"{workflow_runs: [.workflow_runs[] | {head_sha, conclusion, event, head_repository: .head_repository.full_name}]}",
			]),
		),
	);
	switch (disposition(headSha, repository, runs)) {
		case "dispatch": {
			await gh(["workflow", "run", CI_WORKFLOW, "--ref", branch, "-f", "release-preflight=true"]);
			process.stdout.write(`Dispatched CI/CD on ${branch} at ${headSha}.\n`);
			break;
		}
		case "awaiting-approval": {
			process.stdout.write(
				`CI/CD for the ${branch} pull request at ${headSha} awaits approval. ` +
					"Approve its pull_request run in Actions; no run was dispatched.\n",
			);
			break;
		}
		case "covered": {
			process.stdout.write(`CI/CD already ran for ${branch} at ${headSha}.\n`);
			break;
		}
	}
}

if (import.meta.main) {
	await main();
}
