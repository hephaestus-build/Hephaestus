import { mkdir, mkdtemp, realpath, symlink, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { INPUTS_FILE_SUFFIX } from "./lib/core/contract.ts";

interface StagedPracticeScripts {
	root: string;
	stage: string;
	practices: string;
}
export interface StagedInputs {
	inputs?: unknown;
	previous?: unknown;
}
export const REQUESTED_PULL_REQUEST: Required<StagedInputs> = {
	inputs: {
		contractVersion: 3,
		kind: "scm.pull_request",
		item: {
			basis: { admission_fields: [], other_fields: "CAPTURE" },
			pr_number: 1,
			pr_url: "https://example.org/team/project/pull/1",
			repository_full_name: "team/project",
			source_branch: "feature",
			target_branch: "main",
			commit_sha: "a".repeat(40),
			about_role: "AUTHOR",
			is_draft: false,
			is_merged: false,
			additions: 0,
			deletions: 0,
			changed_files: 0,
			labels: [],
			assignees: [],
		},
		about: { role: "AUTHOR", developerId: "1" },
		practice: {
			id: "1",
			versionId: "1",
			reviewDigest: "v6:test",
			slug: "fixture",
			criteria: "Review the captured work.",
		},
		scope: { kind: "ITEM", item: { id: "1", kind: "scm.pull_request" }, version: "a".repeat(40) },
		review: {
			id: "11111111-1111-4111-8111-111111111111",
			origin: "REQUEST",
			startedAt: "2026-01-01T00:00:00Z",
		},
		previous: null,
	},
	previous: null,
};

export async function stagePracticeScripts(
	scripts: Record<string, string | null>,
	inputs: StagedInputs = REQUESTED_PULL_REQUEST,
): Promise<StagedPracticeScripts> {
	return stagePrograms(scripts, inputs, "practices");
}

/** Private toolkit fixtures exercise model transport without entering the author import boundary. */
export async function stageToolkitPrograms(
	scripts: Record<string, string | null>,
	inputs: StagedInputs = REQUESTED_PULL_REQUEST,
): Promise<StagedPracticeScripts> {
	return stagePrograms(scripts, inputs, "toolkit-fixtures");
}

async function stagePrograms(
	scripts: Record<string, string | null>,
	inputs: StagedInputs,
	directory: string,
): Promise<StagedPracticeScripts> {
	const root = await realpath(await mkdtemp(path.join(tmpdir(), "practice-script-")));
	const stage = path.join(root, "stage");
	const practices = path.join(stage, directory);
	await mkdir(practices, { recursive: true });
	await writeFile(path.join(root, "package.json"), '{"type":"module"}\n');
	for (const kitDirectory of ["lib", "kits"]) {
		await symlink(path.join(import.meta.dirname, kitDirectory), path.join(stage, kitDirectory));
	}
	for (const [slug, source] of Object.entries(scripts)) {
		if (source !== null) {
			await writeFile(path.join(practices, `${slug}.ts`), source);
		}
		if (inputs.inputs !== undefined) {
			const value = inputs.inputs;
			if (
				typeof value !== "object" ||
				value === null ||
				!("practice" in value) ||
				typeof value.practice !== "object" ||
				value.practice === null
			) {
				throw new Error("staged inputs require a practice record");
			}
			await writeFile(
				path.join(practices, `${slug}${INPUTS_FILE_SUFFIX}`),
				JSON.stringify({
					...value,
					practice: { ...value.practice, slug },
					...("previous" in inputs ? { previous: inputs.previous } : {}),
				}),
			);
		}
	}
	return { root, stage, practices };
}
