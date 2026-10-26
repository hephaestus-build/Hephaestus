import assert from "node:assert/strict";
import {
	mkdirSync,
	mkdtempSync,
	realpathSync,
	readFileSync,
	renameSync,
	rmSync,
	writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import nodePath from "node:path";
import { after, test } from "node:test";

import { executionReportSchema } from "../../../../../../docker/agents/practice-scripts/lib/core/contract.ts";
import { practiceIndexSchema } from "../../../../../../docker/agents/practice-scripts/lib/core/runtime-inputs.ts";
import { AUTHENTICATION_CRITERION, PRACTICES_DIR } from "./golden-review-workspace.ts";
import {
	directProgram,
	field,
	readJsonFile,
	runRunner,
	stagePractices,
	INVALID_META_PROGRAM,
} from "./program-host-runner.ts";

import {
	admissionResponseSchema,
	parseAdmission,
} from "../../../main/resources/agent/pi-admitted-results.ts";

const OUTPUT = nodePath.join(import.meta.dirname, "../practice-results/runner-output");
const UPDATE = process.env.UPDATE_RUNNER_OUTPUT === "1";

const CRITERION = AUTHENTICATION_CRITERION;

const PRACTICES = [
	{ slug: "auth-review" },
	{ slug: "ui-scope", program: directProgram() },
	{ slug: "zz-invalid-meta", program: INVALID_META_PROGRAM },
] as const;

const cwd = realpathSync(mkdtempSync(nodePath.join(tmpdir(), "pi-runner-server-contract-")));
after(() => {
	rmSync(cwd, { recursive: true, force: true });
});
stagePractices(cwd, PRACTICES);

// Canonical citations must use server context paths; the golden fixture stages the same records under evidence/.
function toServerContextRoot(): void {
	renameSync(nodePath.join(cwd, "evidence"), nodePath.join(cwd, "context"));
	const task = readJsonFile(cwd, "task.json");
	const relocated = (key: string) => String(field(task, key)).replace(/^evidence\//u, "context/");
	writeFileSync(
		nodePath.join(cwd, "task.json"),
		JSON.stringify({
			...(typeof task === "object" ? task : {}),
			contextRoot: "context",
			manifest: relocated("manifest"),
		}),
	);
	const manifest = nodePath.join(cwd, "context/manifest.json");
	writeFileSync(manifest, readFileSync(manifest, "utf8").replaceAll('"evidence/', '"context/'));
	const golden = readJsonFile(cwd, "context/metadata.json");
	writeFileSync(
		nodePath.join(cwd, "context/metadata.json"),
		`${JSON.stringify({ ...(typeof golden === "object" ? golden : {}), body: "Adds a login form." }, null, 2)}\n`,
	);
}
toServerContextRoot();
const record = readFileSync(nodePath.join(cwd, "context/metadata.json"), "utf8");
const change = readFileSync(nodePath.join(cwd, "context/change.json"), "utf8");
const TITLE_LINE = '  "title": "Add login",';
const titleLine = record.split("\n").indexOf(TITLE_LINE) + 1;

const sessionCandidate = {
	outcome: "NOT_MET",
	summary: "The pull request asks for no checked() sign-in",
	explanation: "The record of the pull request names a login change and no checked() call.",
	citations: [],
	problems: [
		{
			severity: "MAJOR",
			summary: "Sign-in is added without checked()",
			explanation: "The criteria ask that an added authentication call use checked().",
			citations: [
				{
					sourceKind: "scm.pull-request.core",
					sourcePath: "context/metadata.json",
					path: "context/metadata.json",
					startLine: titleLine,
					endLine: titleLine,
					quote: TITLE_LINE,
				},
			],
			basis: {
				type: "duty",
				criterionQuote: CRITERION,
				event: 0,
				explanation:
					"The pull request adds a sign-in, which the criteria require to use checked().",
			},
			nextStep: "Call checked(token) before the session starts.",
		},
	],
	evidence: {},
};

const exit = await runRunner(
	cwd,
	{},
	{ PI_HOST_SESSION_CANDIDATE: JSON.stringify(sessionCandidate) },
);

function stableReport(): unknown {
	const report = executionReportSchema.parse(readJsonFile(cwd, "out/practice-scripts.json"));
	return {
		...report,
		practices: report.practices.map((entry) => ({
			...entry,
			durationMs: entry.durationMs === null ? null : 0,
		})),
	};
}

function staged(): unknown {
	return {
		criteria: readFileSync(nodePath.join(cwd, "catalog/practices/auth-review.md"), "utf8"),
		programs: Object.fromEntries(
			PRACTICES.flatMap(({ slug }) =>
				slug === "auth-review"
					? []
					: [[slug, readFileSync(nodePath.join(cwd, PRACTICES_DIR, `${slug}.ts`), "utf8")]],
			),
		),
		captured: { "context/metadata.json": record, "context/change.json": change },
	};
}

const files: Record<string, () => unknown> = {
	"result.json": () => readJsonFile(cwd, "out/result.json"),
	"practice-scripts.json": stableReport,
	"staged.json": staged,
};

void test("the runner admits the built-in's and the direct program's results, and exits", () => {
	assert.equal(exit.status, 0, exit.stderr);
});

void test("the server's runner output fixtures are what this run wrote", () => {
	if (UPDATE) {
		mkdirSync(OUTPUT, { recursive: true });
	}
	for (const [name, read] of Object.entries(files)) {
		const file = nodePath.join(OUTPUT, name);
		const written = read();
		if (UPDATE) {
			writeFileSync(file, `${JSON.stringify(written, null, "\t")}\n`);
		}
		const parsed: unknown = JSON.parse(readFileSync(file, "utf8"));
		assert.deepStrictEqual(
			written,
			parsed,
			`${name}: run with UPDATE_RUNNER_OUTPUT=1 to write it again`,
		);
	}
});

void test("the runner reads the assigned practice index", () => {
	const index: unknown = JSON.parse(
		readFileSync(new URL("practice-index.json", import.meta.url), "utf8"),
	);
	assert.deepEqual(practiceIndexSchema.parse(index), index);
});

void test("the runner reads the server's admission envelope", () => {
	const response: unknown = JSON.parse(
		readFileSync(new URL("admission-response.json", import.meta.url), "utf8"),
	);
	const admission = parseAdmission(response);
	assert.equal(admission.results[0]?.practiceSlug, "handles-errors");
	assert.ok(admissionResponseSchema.safeParse(response).success);
});
