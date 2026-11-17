import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { chmod, mkdir, mkdtemp, readFile, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";

import {
	expandExpressions,
	parseDocument,
	runScript,
	runStep,
	runnerStepsOnly,
} from "./ci-contract-support.ts";
import { environmentForGitFixture } from "./lib/git-environment.ts";
import { asArray, asRecord, asString } from "./lib/json.ts";

const WORKFLOW = ".github/workflows/feedback-eval-decision.yml";

/** The `github` context of a decision run: its event, the action and label, and the head's repository. */
function context(
	event: string,
	action: string,
	label?: string,
	headRepository = "hephaestus/hephaestus",
) {
	return {
		github: {
			event_name: event,
			repository: "hephaestus/hephaestus",
			event: {
				action,
				...(label === undefined ? {} : { label: { name: label } }),
				pull_request: { number: 7, head: { repo: { full_name: headRepository } } },
			},
		},
		vars: {},
	};
}

void test("only a person starts a decision: a manual run or adding eval:decide, never a push, a path change or a schedule", async () => {
	const workflow = parseDocument(await readFile(WORKFLOW, "utf8"));
	const triggers = asRecord(asRecord(workflow.toJS(), "decision workflow").on, "triggers");
	assert.deepEqual(Object.keys(triggers).toSorted(), ["pull_request", "workflow_dispatch"]);
	// Evaluations are manual-first: no pull request event but the label runs a job, and no path filters one.
	assert.deepEqual(asRecord(triggers.pull_request, "pull_request"), { types: ["labeled"] });
	const calls = (
		job: string,
		event: string,
		action: string,
		label?: string,
		headRepository?: string,
	) =>
		expandExpressions(
			`\${{ ${asString(workflow.getIn(["jobs", job, "if"]), `${job} if`)} }}`,
			context(event, action, label, headRepository),
		) === "true";
	assert.equal(calls("provider", "workflow_dispatch", ""), true);
	assert.equal(calls("provider", "pull_request", "labeled", "eval:decide"), true);
	// The panel's label starts the panel only: one label never spends two runs' model calls.
	assert.equal(calls("provider", "pull_request", "labeled", "eval:run"), false);
	assert.equal(calls("provider", "pull_request", "synchronize"), false);
	assert.equal(calls("provider", "pull_request", "opened"), false);
	// A fork gets no secrets, so it never calls a model, labelled or not.
	assert.equal(
		calls("provider", "pull_request", "labeled", "eval:decide", "someone/hephaestus"),
		false,
	);
});

void test("the replicates compose one after another, so one composer calls the endpoint, as the noise plan measured", async () => {
	const workflow = parseDocument(await readFile(WORKFLOW, "utf8"));
	const jobs = asRecord(asRecord(workflow.toJS(), "decision workflow").jobs, "jobs");
	const strategy = asRecord(asRecord(jobs.compose, "compose").strategy, "compose strategy");
	assert.equal(strategy["max-parallel"], 1);
	// Across runs and pull requests too: the decision and the panel hold one lock on the endpoint, and none cancels another.
	const panel = asRecord(
		asRecord(
			asRecord(
				parseDocument(await readFile(".github/workflows/feedback-eval-panel.yml", "utf8")).toJS(),
				"panel",
			).jobs,
			"panel jobs",
		).panel,
		"panel job",
	);
	assert.deepEqual(asRecord(jobs.compose, "compose").concurrency, {
		group: "ci-model-endpoint",
		"cancel-in-progress": false,
	});
	assert.deepEqual(panel.concurrency, asRecord(jobs.compose, "compose").concurrency);
	const replicates = asArray(asRecord(strategy.matrix, "matrix").replicate, "replicates");
	// One job for each trial that the suite gives its most-replicated cases.
	const suite = asRecord(
		JSON.parse(await readFile("scripts/feedback-eval/ichnaea/suite.json", "utf8")),
		"suite",
	);
	const most = Math.max(
		Number(suite.replicates),
		...asArray(suite.replicatesFor ?? [], "replicatesFor").map((entry) =>
			Number(asRecord(entry, "entry").replicates),
		),
	);
	assert.deepEqual(
		replicates,
		Array.from({ length: most }, (_, index) => index),
	);
});

void test("the decision's own files are feedback eval paths, so the deterministic gate checks a change to them", async () => {
	// The filter's entries, up to the first line that is neither an entry nor a comment: it is the last filter.
	const source = await readFile(".github/workflows/cicd.yml", "utf8");
	const filter: string[] = [];
	for (const line of source
		.slice(source.indexOf("\n            feedback-eval:\n") + 1)
		.split("\n")
		.slice(1)) {
		const entry = /^ {14}- '(?<glob>[^']+)'$/u.exec(line)?.groups?.glob;
		if (entry !== undefined) {
			filter.push(entry);
		} else if (!/^ {14}#/u.test(line)) {
			break;
		}
	}
	for (const file of [
		WORKFLOW,
		"scripts/feedback-eval-decision.ts",
		"scripts/feedback-eval-configuration.ts",
	]) {
		assert.ok(filter.includes(file), `no feedback-eval path covers ${file}`);
	}
});

void test("a secret reaches a step only through its environment", async () => {
	const workflow = asRecord(
		parseDocument(await readFile(WORKFLOW, "utf8")).toJS(),
		"decision workflow",
	);
	const references: string[] = [];
	for (const [name, job] of Object.entries(asRecord(workflow.jobs, "jobs"))) {
		const record = asRecord(job, name);
		assert.doesNotMatch(JSON.stringify(record.if ?? ""), /secrets\./u, `${name} if`);
		for (const each of asArray(record.steps ?? [], `${name} steps`)) {
			const { env, ...rest } = asRecord(each, `${name} step`);
			assert.doesNotMatch(JSON.stringify(rest), /secrets\./u, JSON.stringify(rest).slice(0, 80));
			for (const value of Object.values(asRecord(env ?? {}, `${name} env`))) {
				if (String(value).includes("secrets.")) {
					references.push(String(value));
				}
			}
		}
	}
	assert.ok(references.length > 0);
	for (const reference of references) {
		assert.match(reference, /^\$\{\{ secrets\.CI_LLM_\w+(?: != '')? \}\}$/u, reference);
	}
});

void test(
	"the check's colour follows the verdict alone: PASS succeeds, REGRESSION fails, all else is neutral",
	runnerStepsOnly,
	async () => {
		const workflow = parseDocument(await readFile(WORKFLOW, "utf8"));
		const shell = runScript(
			workflow,
			["jobs", "report"],
			"Post the result under the same check name",
		);
		const directory = await mkdtemp(path.join(tmpdir(), "decision-report-"));
		const calls = path.join(directory, "calls");
		await writeFile(path.join(directory, "gh"), `#!/bin/sh\nprintf '%s\\n' "$@" > "${calls}"\n`);
		await chmod(path.join(directory, "gh"), 0o755);
		const post = async (environment: Record<string, string>) => {
			const run = await runStep(shell, {
				PATH: `${directory}${path.delimiter}${process.env.PATH ?? ""}`,
				REPOSITORY: "hephaestus/hephaestus",
				HEAD_SHA: "0".repeat(40),
				RUN_URL: "https://example.invalid/run",
				GITHUB_STEP_SUMMARY: path.join(directory, "summary"),
				CONFIGURED: "true",
				CASES: "same",
				RESULTS: "success success success",
				...environment,
			});
			assert.equal(run.failed, false, run.diagnosis);
			const written = await readFile(calls, "utf8");
			const args = written.split("\n");
			const field = (name: string) =>
				args.find((arg) => arg.startsWith(`${name}=`))?.slice(name.length + 1);
			return { conclusion: field("conclusion"), title: field("output[title]") };
		};
		const pass = await post({ VERDICT: "PASS", SCOPE: "on these 72 cases" });
		assert.equal(pass.conclusion, "success");
		const regression = await post({
			VERDICT: "REGRESSION",
			REGRESSED: "hephaestus/withholds-expected",
		});
		assert.equal(regression.conclusion, "failure");
		assert.match(regression.title ?? "", /withholds-expected/u);
		for (const verdict of ["INCONCLUSIVE", "ESTIMATE-ONLY", "INVALID", ""]) {
			const posted = await post({ VERDICT: verdict, REASON: "a cause" });
			assert.equal(posted.conclusion, "neutral", verdict);
		}
		const unconfigured = await post({ VERDICT: "", CONFIGURED: "false" });
		assert.match(unconfigured.title ?? "", /not configured/u);
		const errored = await post({ VERDICT: "", RESULTS: "success failure skipped" });
		assert.match(errored.title ?? "", /errored/u);
		// A change to the cases composes nothing, so its skipped jobs read as that cause, never as an unfinished run.
		const cases = await post({
			VERDICT: "",
			CASES: "changed",
			RESULTS: "success success skipped skipped",
		});
		assert.equal(cases.conclusion, "neutral");
		assert.match(cases.title ?? "", /edits the composition cases/u);
	},
);

void test(
	"every decision run of a head stays listed with its verdict, so a rerun never hides an earlier REGRESSION",
	runnerStepsOnly,
	async () => {
		const workflow = parseDocument(await readFile(WORKFLOW, "utf8"));
		const shell = runScript(
			workflow,
			["jobs", "report"],
			"Post the result under the same check name",
		);
		const directory = await mkdtemp(path.join(tmpdir(), "decision-runs-"));
		const posted = path.join(directory, "posted");
		// GitHub as far as the step reads it: a POST adds a check run to the commit, and a GET lists the commit's
		// runs in the form of the step's --jq, oldest first.
		await writeFile(
			path.join(directory, "gh"),
			[
				"#!/bin/sh",
				'case "$*" in',
				`  *"--method GET"*) if [ -f "${posted}" ]; then sed 's/^/- /' "${posted}"; fi; exit 0 ;;`,
				"esac",
				'for arg in "$@"; do',
				'  case "$arg" in',
				`    "output[title]="*) title="\${arg#output\\[title\\]=}" ;;`,
				`    "details_url="*) url="\${arg#details_url=}" ;;`,
				`    "output[summary]="*) summary="\${arg#output\\[summary\\]=}" ;;`,
				"  esac",
				"done",
				`printf '%s: %s\\n' "$title" "$url" >> "${posted}"`,
				`printf '%s' "$title" > "${path.join(directory, "title")}"`,
				`printf '%s' "$summary" > "${path.join(directory, "summary-posted")}"`,
				"",
			].join("\n"),
		);
		await chmod(path.join(directory, "gh"), 0o755);
		const decide = async (run: number, environment: Record<string, string>) => {
			const step = await runStep(shell, {
				PATH: `${directory}${path.delimiter}${process.env.PATH ?? ""}`,
				REPOSITORY: "hephaestus/hephaestus",
				HEAD_SHA: "1".repeat(40),
				RUN_URL: `https://example.invalid/run/${String(run)}`,
				GITHUB_STEP_SUMMARY: path.join(directory, "summary"),
				CONFIGURED: "true",
				CASES: "same",
				RESULTS: "success success success",
				...environment,
			});
			assert.equal(step.failed, false, step.diagnosis);
			return {
				title: await readFile(path.join(directory, "title"), "utf8"),
				summary: await readFile(path.join(directory, "summary-posted"), "utf8"),
			};
		};
		const first = await decide(1, {
			VERDICT: "REGRESSION",
			REGRESSED: "hephaestus/withholds-expected",
		});
		assert.doesNotMatch(first.summary, /Earlier decision runs/u);
		// The same head, run again, gives no regression: the earlier REGRESSION stays in its title and its list.
		const second = await decide(2, { VERDICT: "INCONCLUSIVE", REASON: "a cause" });
		assert.match(
			second.title,
			/^An earlier run of this commit gave REGRESSION\. This run: Decision INCONCLUSIVE/u,
		);
		assert.ok(
			second.summary.includes(
				"- Decision REGRESSION on hephaestus/withholds-expected: https://example.invalid/run/1",
			),
			second.summary,
		);
		const third = await decide(3, { VERDICT: "INCONCLUSIVE", REASON: "a cause" });
		const listed = third.summary.split("\n").filter((line) => line.startsWith("- "));
		assert.equal(listed.length, 2, third.summary);
		assert.match(listed[0] ?? "", /run\/1$/u);
		assert.match(listed[1] ?? "", /run\/2$/u);
		assert.match(third.title, /^An earlier run of this commit gave REGRESSION/u);
		// Every run of the head is a check run of its own, so all three appear.
		const runs = await readFile(posted, "utf8");
		assert.equal(runs.trim().split("\n").length, 3);
	},
);

void test("every job that composes or decides checks out the head that a person asked about, never the merge commit", async () => {
	const workflow = parseDocument(await readFile(WORKFLOW, "utf8"));
	for (const name of ["commits", "compose", "decide"]) {
		const checkout = asRecord(
			asArray(
				asRecord(asRecord(asRecord(workflow.toJS(), "w").jobs, "jobs")[name], name).steps,
				`${name} steps`,
			)[0],
			`${name} checkout`,
		);
		assert.match(String(checkout.uses), /^actions\/checkout@/u, name);
		const ref = asString(asRecord(checkout.with, `${name} with`).ref, `${name} ref`);
		assert.match(
			ref,
			name === "commits"
				? /pull_request\.head\.sha \|\| github\.sha/u
				: /needs\.commits\.outputs\.head/u,
			name,
		);
	}
});

void test(
	"a manual run's base resolves from a branch name or a commit, never as an option, and a head that edits the cases composes nothing",
	runnerStepsOnly,
	async () => {
		const workflow = parseDocument(await readFile(WORKFLOW, "utf8"));
		const shell = runScript(workflow, ["jobs", "commits"], "Resolve the head and the merge base");
		const repository = await mkdtemp(path.join(tmpdir(), "decision-base-"));
		const git = (...args: string[]) =>
			spawnSync("git", ["-C", repository, ...args], {
				encoding: "utf8",
				env: environmentForGitFixture(),
			}).stdout.trim();
		git("init", "-q", "-b", "feature");
		git(
			"-c",
			"user.name=t",
			"-c",
			"user.email=t@example.invalid",
			"commit",
			"-q",
			"--allow-empty",
			"-m",
			"base",
		);
		const base = git("rev-parse", "HEAD");
		// The checkout layout of a manual run from a feature branch: main exists only as origin/main.
		git("update-ref", "refs/remotes/origin/main", base);
		git(
			"-c",
			"user.name=t",
			"-c",
			"user.email=t@example.invalid",
			"commit",
			"-q",
			"--allow-empty",
			"-m",
			"head",
		);
		const resolve = async (ref: string) =>
			runStep(`cd ${repository}\n${shell}`, {
				BASE_REF: ref,
				GITHUB_STEP_SUMMARY: path.join(repository, "summary"),
			});
		const byName = await resolve("main");
		assert.equal(byName.failed, false, byName.diagnosis);
		assert.equal(byName.outputs.base, base);
		assert.equal(byName.outputs.head, git("rev-parse", "HEAD"));
		assert.equal(byName.outputs.cases, "same");
		const byCommit = await resolve(base);
		assert.equal(byCommit.outputs.base, base);
		// Each arm reads the cases from its own commit, so a head that edits them composes nothing.
		await mkdir(path.join(repository, "scripts", "feedback-eval"), { recursive: true });
		await writeFile(path.join(repository, "scripts", "feedback-eval", "compositions.json"), "{}\n");
		git("add", "scripts/feedback-eval/compositions.json");
		git("-c", "user.name=t", "-c", "user.email=t@example.invalid", "commit", "-q", "-m", "cases");
		const edited = await resolve("main");
		assert.equal(edited.failed, false, edited.diagnosis);
		assert.equal(edited.outputs.cases, "changed");
		const option = await resolve("--output=/tmp/x");
		assert.equal(option.failed, true);
	},
);
