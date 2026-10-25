import { mkdirSync, writeFileSync } from "node:fs";
import nodePath from "node:path";

export const AUTHENTICATION_CRITERION = "Added authentication calls use checked(), not insecure().";
export const AUTHENTICATION_PRACTICE = `# Uses secure authentication\n\n## Standard\n${AUTHENTICATION_CRITERION}\n`;

export const LEAD_LABELS = {
	"insecure-call": "An added call to insecure(); check whether a checked() call guards it.",
	"auth-helper": "The repository's authentication helper that the change could call instead.",
	"review-record": "The pull request record that names the change's intent.",
} as const;

export interface GoldenLead {
	at:
		| { change: string; line: number }
		| { file: string; line: number }
		| { record: string; line: number };
	kind: keyof typeof LEAD_LABELS;
	facts?: Record<string, string | number | boolean>;
}

export function goldenLeads(many: boolean): GoldenLead[] {
	return [
		{ at: { change: "src/Auth.java", line: 10 }, kind: "insecure-call", facts: { guarded: false } },
		{ at: { file: "src/Checked.java", line: 2 }, kind: "auth-helper" },
		{ at: { record: "metadata.json", line: 1 }, kind: "review-record" },
		...(many
			? Array.from({ length: 60 }, (_, i) => ({
					at: { file: "src/Checked.java", line: i + 1 },
					kind: "auth-helper" as const,
					facts: { index: i },
				}))
			: []),
	];
}

export const GOLDEN_FACTS = { insecureCalls: 1, checkedCalls: 0 };
export const GOLDEN_DIRECTIONS = ["Check each added authentication call against the criteria."];
export const GOLDEN_UNRATED = [
	{ what: "added calls in generated files", count: 2, reason: "too-large" as const },
];

export const GOLDEN_RECORD = {
	basis: { admission_fields: [], other_fields: "CAPTURE" },
	pr_number: 3,
	pr_url: "https://gitlab.example/group/repo/-/merge_requests/3",
	repository_full_name: "group/repo",
	source_branch: "login",
	target_branch: "main",
	commit_sha: "a".repeat(40),
	about_role: "AUTHOR",
	title: "Add login",
	is_draft: false,
	is_merged: false,
	additions: 1,
	deletions: 0,
	changed_files: 1,
	labels: [],
	assignees: [],
};

export const GOLDEN_INPUTS = {
	contractVersion: 3,
	kind: "scm.pull_request",
	item: GOLDEN_RECORD,
	about: { role: "AUTHOR", developerId: "1" },
	practice: {
		id: "1",
		versionId: "7",
		reviewDigest: "v6:golden",
		slug: "test-practice",
		criteria: "Added authentication calls use checked(), not insecure().",
	},
	scope: {
		kind: "ITEM",
		item: { id: "3", kind: "scm.pull_request" },
		version: "a".repeat(40),
		baseVersion: "b".repeat(40),
	},
	review: {
		id: "11111111-1111-4111-8111-111111111111",
		origin: "REQUEST",
		startedAt: "2026-01-01T00:00:00Z",
	},
	previous: null,
};

export const PRACTICES_DIR = "work/practice-scripts/practices";

export function stageGoldenWorkspace(
	cwd: string,
	schemaVersion: number,
	orchestrator: string,
): void {
	const write = (file: string, text: string) => {
		mkdirSync(nodePath.dirname(nodePath.join(cwd, file)), { recursive: true });
		writeFileSync(nodePath.join(cwd, file), text);
	};
	write("AGENTS.md", orchestrator);
	write("evidence/metadata.json", JSON.stringify(GOLDEN_RECORD));
	write(
		"evidence/change.json",
		JSON.stringify({ base_sha: "b".repeat(40), head_sha: "a".repeat(40) }),
	);
	write(
		"work/change/diff.patch",
		"diff --git a/src/Auth.java b/src/Auth.java\n--- a/src/Auth.java\n+++ b/src/Auth.java\n@@ -10,0 +10,1 @@\n[L10] + insecure();\n",
	);
	write(
		"repos/primary/src/Checked.java",
		`${Array.from({ length: 70 }, (_, i) => (i === 1 ? "  checked(token);" : `  line${i + 1}();`)).join("\n")}\n`,
	);
	mkdirSync(nodePath.join(cwd, "repos/primary/.git"), { recursive: true });
	write(
		"catalog/practices/test-practice.md",
		`${AUTHENTICATION_PRACTICE}\n## applicability\nAn authentication call was added.\n\n## Judge\nMET for checked(); NOT_MET for insecure().\n\n## Severity\nMAJOR for an added insecure() call.\n\n## Defer\nOther code is outside this practice.\n`,
	);
	write(
		"catalog/practices/index.json",
		JSON.stringify([
			{
				slug: "test-practice",
				name: "Authentication review",
				group: "security",
				readsSources: ["scm.pull-request.core", "scm.pull-request.diff"],
				exhaustiveSources: ["scm.pull-request.diff"],
				versionId: "7",
			},
		]),
	);
	write(`${PRACTICES_DIR}/test-practice.inputs.json`, JSON.stringify(GOLDEN_INPUTS));
	write(
		"evidence/manifest.json",
		JSON.stringify({
			itemKind: "scm.pull_request",
			files: [
				{ kind: "scm.pull-request.core", file: { path: "evidence/metadata.json" } },
				{ kind: "scm.pull-request.diff", file: { path: "evidence/change.json" } },
			],
			sources: [
				{
					kind: "scm.pull-request.core",
					citation: { side: "FORBIDDEN", commit: "FORBIDDEN" },
					state: { availability: "AVAILABLE" },
					files: [{ path: "evidence/metadata.json" }],
				},
				{
					kind: "scm.pull-request.diff",
					citation: { side: "REQUIRED", commit: "OPTIONAL" },
					state: { availability: "AVAILABLE" },
					files: [{ path: "evidence/change.json" }],
				},
			],
		}),
	);
	write(
		"pi-provider.json",
		JSON.stringify({ apiProtocol: "openai-completions", modelId: "test-model" }),
	);
	write(
		"task.json",
		JSON.stringify({
			schemaVersion,
			reviewId: GOLDEN_INPUTS.review.id,
			jobId: "22222222-2222-4222-8222-222222222222",
			attempt: 1,
			workspaceId: "1",
			contextRoot: "evidence",
			repositoryRoot: "repos/primary",
			manifest: "evidence/manifest.json",
			practiceIndex: "catalog/practices/index.json",
			preparedFeedback: "history/prepared.json",
			practiceScripts: PRACTICES_DIR,
			prompt: "Review the practice.",
			repositoryFullName: "group/repo",
			pullRequestNumber: 3,
		}),
	);
}
