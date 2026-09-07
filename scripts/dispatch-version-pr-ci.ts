// Version-PR trigger contract: docs/contributor/ci-cd.mdx.
import { asArray, asRecord, asString, readJsonFile } from "./lib/json.ts";
import { output } from "./lib/process.ts";

export const CI_WORKFLOW = "cicd.yml";

export function versionBranch(config: unknown): string {
	const baseBranch = asString(asRecord(config, "changeset config").baseBranch, "baseBranch");
	if (!baseBranch) throw new Error("changeset config declares no baseBranch");
	return `changeset-release/${baseBranch}`;
}

export interface WorkflowRun {
	readonly headSha: string;
	readonly conclusion: string | null;
}

// Failed validation requires an explicit rerun; cancellation does not.
export function needsDispatch(headSha: string, runs: readonly WorkflowRun[]): boolean {
	return !runs.some((run) => run.headSha === headSha && run.conclusion !== "cancelled");
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
		JSON.parse(await gh(["api", `repos/${repository}/git/matching-refs/heads/${branch}`])),
		branch,
	);
}

if (import.meta.main) {
	const repository = process.env.GITHUB_REPOSITORY;
	if (!repository) throw new Error("GITHUB_REPOSITORY is required");
	const branch = versionBranch(await readJsonFile(".changeset/config.json"));
	const headSha = await branchHead(repository, branch);
	if (!headSha) {
		process.stdout.write(`No ${branch} branch; nothing to validate.\n`);
	} else {
		const runs = parseRuns(
			JSON.parse(
				await gh([
					"api",
					`repos/${repository}/actions/workflows/${CI_WORKFLOW}/runs?branch=${encodeURIComponent(branch)}&per_page=100`,
					"--jq",
					"{workflow_runs: [.workflow_runs[] | {head_sha, conclusion}]}",
				]),
			),
		);
		if (needsDispatch(headSha, runs)) {
			await gh(["workflow", "run", CI_WORKFLOW, "--ref", branch, "-f", "release-preflight=true"]);
			process.stdout.write(`Dispatched CI/CD on ${branch} at ${headSha}.\n`);
		} else {
			process.stdout.write(`CI/CD already ran for ${branch} at ${headSha}.\n`);
		}
	}
}
