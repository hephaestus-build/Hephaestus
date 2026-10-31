import { access } from "node:fs/promises";
import path from "node:path";
import { pathToFileURL } from "node:url";

import { isSet } from "./lib/env.ts";
import { asRecord, asString, parseJson } from "./lib/json.ts";
import { output, run, succeeds } from "./lib/process.ts";

const generated = (file: string) =>
	file === "server/openapi.yaml" ||
	file.startsWith("webapp/src/api/") ||
	file.startsWith("extension/src/api/") ||
	file === "docs/admin/configuration-reference.mdx";

export interface PullRequest {
	head: string;
	branch: string;
	base: string;
}

export function parsePullRequest(value: unknown, repository: string): PullRequest {
	const data = asRecord(asRecord(value, "response").data, "data");
	const repo = asRecord(data.repository, "repository");
	const pr = asRecord(repo.pullRequest, "pullRequest");
	if (pr.state !== "OPEN" || pr.mergeQueueEntry !== null) {
		throw new Error(
			"The pull request must be open and outside the merge queue. Ask its owner to dequeue it first.",
		);
	}
	if (
		asString(
			asRecord(pr.headRepository, "headRepository").nameWithOwner,
			"head repository",
		).toLowerCase() !== repository.toLowerCase()
	) {
		throw new Error("Fork branches need their owner's repair procedure.");
	}
	const head = asString(pr.headRefOid, "headRefOid");
	const branch = asString(pr.headRefName, "headRefName");
	const base = asString(pr.baseRefName, "baseRefName");
	if (!/^[a-f0-9]{40}$/u.test(head) || base !== "main" || !branch || branch === "main") {
		throw new Error("Expected a feature branch targeting main and a full commit SHA.");
	}
	return { head, branch, base };
}

const git = async (cwd: string, args: string[]) => output("git", args, { cwd });
const revision = async (cwd: string, ref: string) => {
	const value = await git(cwd, ["rev-parse", ref]);
	return value.trim();
};

async function inRebase(cwd: string): Promise<boolean> {
	for (const name of ["rebase-merge", "rebase-apply"]) {
		const location = await git(cwd, ["rev-parse", "--git-path", name]);
		const file = location.trim();
		if (
			await access(path.resolve(cwd, file)).then(
				() => true,
				() => false,
			)
		) {
			return true;
		}
	}
	return false;
}

/** Refuse before fetching or rewriting anything owned by another Git operation. */
export async function requireCleanBranch(cwd: string, pr: PullRequest): Promise<void> {
	if (isSet(process.env.HEPHAESTUS_APPLICATION_JAR)) {
		throw new Error("Unset HEPHAESTUS_APPLICATION_JAR: regeneration must build this checkout.");
	}
	if (await inRebase(cwd)) {
		throw new Error("Finish the existing rebase or git am operation first.");
	}
	for (const name of ["MERGE_HEAD", "CHERRY_PICK_HEAD", "REVERT_HEAD", "sequencer"]) {
		const location = await git(cwd, ["rev-parse", "--git-path", name]);
		const file = location.trim();
		if (
			await access(path.resolve(cwd, file)).then(
				() => true,
				() => false,
			)
		) {
			throw new Error("Finish the existing Git operation first.");
		}
	}
	if ((await git(cwd, ["status", "--porcelain=v1", "--untracked-files=all"])) !== "") {
		throw new Error("The worktree and index must be clean, including untracked files.");
	}
	const branch = await git(cwd, ["branch", "--show-current"]);
	if (branch.trim() !== pr.branch || (await revision(cwd, "HEAD")) !== pr.head) {
		throw new Error("Check out the pull request branch at its recorded remote head first.");
	}
}

/** Only generated conflicts are disposable. Handwritten conflicts restore the original branch. */
export async function rebaseGenerated(
	cwd: string,
	pr: PullRequest,
	base: string,
	regenerate: () => Promise<void>,
): Promise<void> {
	await requireCleanBranch(cwd, pr);
	if (!/^[a-f0-9]{40}$/u.test(base)) {
		throw new Error("The base must be a frozen full commit SHA.");
	}
	const merges = await git(cwd, ["rev-list", "--min-parents=2", `${base}..HEAD`]);
	if (merges.trim() !== "") {
		throw new Error("This branch has merge commits. Rebase it manually to preserve its intent.");
	}
	try {
		let completed = await run(
			"git",
			[
				"-c",
				"rerere.enabled=false",
				"rebase",
				"--no-autostash",
				"--no-update-refs",
				"--empty=drop",
				base,
			],
			{ cwd },
		).then(
			() => true,
			() => false,
		);
		while (!completed) {
			if (!(await inRebase(cwd))) {
				throw new Error("Git could not start the rebase.");
			}
			const conflicted = await git(cwd, ["diff", "--name-only", "--diff-filter=U", "-z"]);
			const conflicts = conflicted.split("\0").filter(Boolean);
			if (conflicts.length === 0 || conflicts.some((file) => !generated(file))) {
				throw new Error(
					`Resolve handwritten conflicts manually:\n${conflicts.join("\n") || "Inspect the rebase failure."}`,
				);
			}
			for (const file of conflicts) {
				// During a rebase, HEAD is the new base plus the commits already replayed.
				const exists = await succeeds("git", ["cat-file", "-e", `HEAD:${file}`], { cwd });
				const args = exists
					? [
							"--literal-pathspecs",
							"restore",
							"--source=HEAD",
							"--staged",
							"--worktree",
							"--",
							file,
						]
					: ["--literal-pathspecs", "rm", "--", file];
				await run("git", args, { cwd });
			}
			const empty = await succeeds("git", ["diff", "--cached", "--quiet"], { cwd });
			if (empty) {
				console.log(
					`Dropping generated-only commit ${await revision(cwd, "REBASE_HEAD")}; its outputs will be regenerated.`,
				);
			}
			completed = await run(
				"git",
				["-c", "rerere.enabled=false", "rebase", empty ? "--skip" : "--continue"],
				{
					cwd,
					env: { GIT_EDITOR: "true" },
				},
			).then(
				() => true,
				() => false,
			);
		}
	} catch (error) {
		if (await inRebase(cwd)) {
			await run("git", ["-c", "rerere.enabled=false", "rebase", "--abort"], { cwd });
		}
		throw error;
	}
	// After a successful rebase, preserve partial generator output if generation fails.
	// A reset here could discard edits made while the build was running.
	await regenerate();
	console.log(`Rebased onto ${base}. Original head for the push lease: ${pr.head}`);
	console.log(
		"Review the regenerated diff, commit it, then run format, check and the affected tests before a leased push.",
	);
}

function isFactory(value: unknown): value is () => unknown {
	return typeof value === "function";
}

export async function regenerateArtifacts(cwd: string): Promise<void> {
	// An older branch can use this script from another checkout. Load the builder only after the
	// rebase, from the target checkout, so neither its source nor its JAR comes from that other tree.
	const processModule: unknown = await import(
		pathToFileURL(path.join(cwd, "scripts/lib/process.ts")).href
	);
	const resolveCli = asRecord(processModule, "process module").repositoryCli;
	if (!isFactory(resolveCli)) {
		throw new Error("The target checkout must export repositoryCli from scripts/lib/process.ts.");
	}
	const cli = () => asString(resolveCli(), "Vite CLI path");
	await run(process.execPath, [cli(), "install", "--frozen-lockfile"], { cwd });
	const module: unknown = await import(
		pathToFileURL(path.join(cwd, "scripts/lib/application-jar.ts")).href
	);
	const build = asRecord(module, "application JAR module").executableJar;
	if (!isFactory(build)) {
		throw new Error(
			"The rebased checkout must export executableJar from scripts/lib/application-jar.ts.",
		);
	}
	const jar = asString(await build(), "application JAR path");
	for (const task of ["generate:api", "generate:configuration-reference"]) {
		await run(process.execPath, [cli(), "run", task], {
			cwd,
			env: { HEPHAESTUS_APPLICATION_JAR: jar },
		});
	}
}

async function main(): Promise<void> {
	const number = process.argv[2] ?? "";
	if (process.argv.length !== 3 || !/^[1-9]\d*$/u.test(number)) {
		throw new Error("usage: vp run pr:rebase-generated <pr-number>");
	}
	const root = await git(process.cwd(), ["rev-parse", "--show-toplevel"]);
	const cwd = root.trim();
	const remote = await git(cwd, ["remote", "get-url", "origin"]);
	const match =
		/^(?:https:\/\/github\.com\/|git@github\.com:)(?<owner>[^/]+)\/(?<name>[^/]+?)(?:\.git)?$/u.exec(
			remote.trim(),
		);
	const owner = match?.groups?.owner;
	const name = match?.groups?.name;
	if (owner === undefined || name === undefined) {
		throw new Error("origin must name the GitHub repository with HTTPS or SSH.");
	}
	const repository = `${owner}/${name}`;
	const readPr = async () =>
		parsePullRequest(
			parseJson(
				await output(
					"gh",
					[
						"api",
						"graphql",
						"-f",
						"query=query($owner:String!,$name:String!,$number:Int!){repository(owner:$owner,name:$name){pullRequest(number:$number){state headRefOid headRefName baseRefName mergeQueueEntry{id} headRepository{nameWithOwner}}}}",
						"-f",
						`owner=${owner}`,
						"-f",
						`name=${name}`,
						"-F",
						`number=${number}`,
					],
					{ cwd },
				),
			),
			repository,
		);
	const pr = await readPr();
	await requireCleanBranch(cwd, pr);
	await run("git", ["fetch", "origin", "main"], { cwd });
	const base = await revision(cwd, "FETCH_HEAD");
	await run("git", ["fetch", "origin", `refs/heads/${pr.branch}`], { cwd });
	if ((await revision(cwd, "FETCH_HEAD")) !== pr.head) {
		throw new Error("The remote head changed. Fetch and inspect it before retrying.");
	}
	const current = await readPr();
	if (current.head !== pr.head || current.branch !== pr.branch) {
		throw new Error("The pull request changed while fetching. Inspect it before retrying.");
	}
	await rebaseGenerated(cwd, pr, base, async () => regenerateArtifacts(cwd));
}

if (process.argv[1] === import.meta.filename) {
	await main().catch((error: unknown) => {
		console.error(error instanceof Error ? error.message : String(error));
		process.exitCode = 1;
	});
}
