import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import {
	appendFileSync,
	existsSync,
	mkdirSync,
	mkdtempSync,
	readFileSync,
	rmSync,
	writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { mock, test } from "node:test";

// The runner reads /workspace and the environment at module scope, so each scenario is a child
// process: this file re-enters itself with the SDK mocked and drives one review through it.

const admittedObservation = {
	assessmentStatus: "ASSESSED",
	presence: "PRESENT",
	outcome: "NEGATIVE",
	id: "observation-1",
	practiceSlug: "test-practice",
	assessment: "BAD",
	severity: "MAJOR",
	anchorable: true,
	citations: [
		{
			index: 0,
			sourceKind: "scm.pull-request.diff",
			path: "src/Auth.java",
			side: "NEW",
			startLine: 10,
			endLine: 10,
			quote: "+ insecure();",
			verification: { status: "VERIFIED", scope: "EXACT_LOCATION" },
			anchorable: true,
		},
	],
	evidence: {
		citations: [{ path: "src/Auth.java", quote: "+ insecure();" }],
		search: { consulted: ["scm.pull-request.diff"], lookedFor: "x", boundary: "y" },
	},
};

const changeCitation = {
	sourceKind: "scm.pull-request.diff",
	artifactPath: "evidence/change.json",
	path: "src/Auth.java",
	side: "NEW",
	startLine: 10,
	endLine: 10,
	quote: "+ insecure();",
};

function observation(slug: string, summary: string, citation: unknown = changeCitation) {
	return {
		practiceSlug: slug,
		summary,
		assessmentStatus: "ASSESSED",
		presence: "PRESENT",
		assessment: "BAD",
		severity: "MAJOR",
		evidenceRationale: "The changed authentication code calls insecure().",
		evidence: { citations: [citation] },
	};
}

interface CustomTool {
	name: string;
	description: string;
	parameters: unknown;
	execute: (id: string, input: unknown) => Promise<unknown>;
}

const scenario = process.env.PI_ORCHESTRATION_SCENARIO;
if (scenario) {
	const cwd = process.env.PI_RUNNER_CWD;
	assert.ok(cwd);
	let now = 1_000_000;
	mock.method(Date, "now", () => now);
	const record = (event: string) => appendFileSync(join(cwd, "events"), `${event}\n`);
	mock.method(globalThis, "fetch", () =>
		Promise.resolve(
			Response.json({
				schemaVersion: 1,
				admissionDigest: "admitted-digest",
				observations: [
					scenario === "compose-quiet"
						? { ...admittedObservation, outcome: "POSITIVE", assessment: "GOOD", severity: null }
						: admittedObservation,
				],
			}),
		),
	);
	const manager = { getSessionFile: () => undefined, getSessionId: () => "test-session" };
	let prompts = 0;
	/** The overrun scenario's first prompt ends only when the runner aborts it, like a call in flight. */
	let releasePrompt: (() => void) | undefined;
	/** A threshold compaction in flight: a model call of its own, which abort() alone does not end. */
	let compacting = false;
	let idleWaiters: (() => void)[] = [];
	const settleIdle = () => {
		if (releasePrompt || compacting) return;
		for (const resolve of idleWaiters) resolve();
		idleWaiters = [];
	};
	const waitForIdle = () =>
		new Promise<void>((resolve) => {
			idleWaiters.push(resolve);
			settleIdle();
		});
	/** The session's event handler, so a scenario can emit what the SDK would. */
	let emit: (event: unknown) => void = () => {};
	mock.module("@earendil-works/pi-coding-agent", {
		namedExports: {
			defineTool: (tool: unknown) => tool,
			getAgentDir: () => cwd,
			DefaultResourceLoader: class {
				reload() {
					return Promise.resolve();
				}
			},
			SettingsManager: { create: () => ({}) },
			SessionManager: { create: () => manager, inMemory: () => manager, open: () => manager },
			ModelRuntime: {
				create() {
					if (scenario === "setup") now += 20_000;
					return Promise.resolve({
						registerProvider() {},
						getModel: () => ({ contextWindow: 128_000, maxTokens: 16_384 }),
					});
				},
			},
			createAgentSession(options: { tools: string[]; customTools: CustomTool[] }) {
				record(`create:session tools=${options.tools.join(",")}`);
				if (scenario === "session-init") throw new Error("session initialization failed");
				const tool = (name: string) => {
					const found = options.customTools.find((item) => item.name === name);
					assert.ok(found, `${name} is registered`);
					return found;
				};
				return Promise.resolve({
					extensionsResult: { errors: [] },
					session: {
						state: { messages: [] },
						sessionManager: manager,
						subscribe(handler: (event: unknown) => void) {
							emit = handler;
							return () => {};
						},
						clearQueue() {},
						getContextUsage: () => ({
							tokens: scenario === "compose-overflow" ? 120_000 : 1_000,
							contextWindow: 128_000,
							percent: 0,
						}),
						compact: () => {
							record("compact");
							return Promise.resolve({});
						},
						get isStreaming() {
							return releasePrompt !== undefined || compacting;
						},
						waitForIdle,
						abortCompaction() {
							if (!compacting) return;
							record("abort-compaction");
							compacting = false;
							settleIdle();
						},
						abort: () => {
							record("abort");
							// The aborted call ends a moment later, and abort() settles once the session is
							// idle — which a compaction still in flight keeps it from being.
							setTimeout(() => releasePrompt?.(), 50);
							return waitForIdle();
						},
						dispose: () => record("dispose"),
						steer: () => Promise.resolve(record("steer")),
						async prompt(text: string) {
							prompts++;
							record(`prompt:${prompts}`);
							writeFileSync(join(cwd, `prompt-${prompts}.md`), text);
							if (scenario === "budget") {
								now += 20_000;
								return;
							}
							if (scenario === "provider-error") {
								// The provider answers every call with an error the SDK does not retry.
								emit({
									type: "message_end",
									message: {
										role: "assistant",
										stopReason: "error",
										errorMessage: "404: No available model deployments for model 'm' for this key",
										content: [],
									},
								});
								return;
							}
							// Like the SDK: a prompt while the last one is still ending is refused, and the
							// aborted call ends only when abort() is called.
							if (releasePrompt) throw new Error("Agent is already processing.");
							if (scenario === "overrun" && prompts === 1) {
								// The turn crossed the compaction threshold: the session is compacting when the
								// share runs out.
								compacting = true;
								await new Promise<void>((resolve) => {
									releasePrompt = resolve;
								});
								releasePrompt = undefined;
								settleIdle();
								return;
							}
							if (text.includes("## Nothing persisted")) {
								// The composer's finishing prompt: the runner asks once more for the practices
								// with a NEGATIVE observation, and a WITHHOLD is a recorded decision.
								const withheld = await tool("report_feedback").execute("f-9", {
									units: [
										{
											channel: "IN_APP",
											practiceSlug: "test-practice",
											basedOn: ["observation-1"],
											action: "WITHHOLD",
											withholdReason: "below_bar",
										},
									],
								});
								record(`feedback-finish:${JSON.stringify(withheld)}`);
								return;
							}
							if (text.includes("## This turn")) {
								// The composition turn, in the same session.
								if (scenario === "compose-quiet") {
									// Nothing to withhold on a practice that is not NEGATIVE: the unit is skipped
									// with the reason, and with no negatives the runner does not ask again.
									const quiet = await tool("report_feedback")
										.execute("f-q", {
											units: [
												{
													channel: "IN_APP",
													practiceSlug: "test-practice",
													basedOn: ["observation-1"],
													action: "WITHHOLD",
													withholdReason: "BELOW_BAR",
												},
											],
										})
										.then(() => "accepted")
										.catch((error: unknown) =>
											error instanceof Error ? error.message : String(error),
										);
									record(`feedback-quiet:${quiet}`);
									return;
								}
								if (scenario === "compose-silent") {
									// A composer that reads its way through the practice files: at the twelfth call
									// without a recording call it is told to persist, once.
									for (let call = 1; call <= 13; call++) {
										emit({
											type: "tool_execution_start",
											toolCallId: `r-${call}`,
											toolName: "read",
											args: { path: `catalog/practices/${call}.md` },
										});
									}
									return;
								}
								if (scenario === "compose-loop") {
									// A session that keeps calling a recording tool without recording anything: the
									// SDK emits the start of every call whether or not its schema check let it
									// through, and the runner ends the turn after enough of them.
									for (let call = 1; call <= 24; call++) {
										emit({
											type: "tool_execution_start",
											toolCallId: `s-${call}`,
											toolName: "report_summary",
											args: {},
										});
										emit({
											type: "tool_execution_end",
											toolCallId: `s-${call}`,
											toolName: "report_summary",
											isError: true,
											result: {
												content: [
													{
														type: "text",
														text: 'Validation failed for tool "report_summary":\n  - /lead: Expected string',
													},
												],
											},
										});
									}
									return;
								}
								const card = {
									channel: "IN_APP",
									practiceSlug: "test-practice",
									basedOn: ["observation-1"],
									action: "NEW",
									title: "Insecure call",
									body: "The pattern across your work.",
									nextStep: "Check the call before pushing.",
								};
								const feedback = tool("report_feedback");
								// The schema carries the shape and the vocabulary and no rule: a rule is applied
								// per unit, by the tool, so one wrong unit never discards the others in the call.
								const schema = JSON.stringify(feedback.parameters);
								for (const keyword of ["maxLength", "additionalProperties", "minItems", "oneOf"]) {
									assert.ok(
										!schema.includes(`"${keyword}"`),
										`${keyword} in ${schema.slice(0, 200)}`,
									);
								}
								assert.match(schema, /One of: IN_CONTEXT, IN_APP\./);
								assert.match(schema, /Required: channel, practiceSlug, basedOn, action\./);
								assert.match(
									schema,
									/SUPERSEDE to replace a message that is queued and unread; WITHHOLD/,
								);
								// One occurrence is not a pattern: with no history, the card is refused, and a
								// call that stored nothing is an error the session must correct.
								const alone = await feedback
									.execute("f-0", { units: [card] })
									.then(() => "accepted")
									.catch((error: unknown) =>
										error instanceof Error ? error.message : String(error),
									);
								record(`feedback-alone:${alone}`);
								// A decision to stay quiet on the work, stored before the card: written after it,
								// since the server reads the first thirty units and delivers no WITHHOLD.
								await feedback.execute("f-w", {
									units: [
										{
											channel: "IN_CONTEXT",
											practiceSlug: "test-practice",
											basedOn: ["observation-1"],
											action: "WITHHOLD",
											withholdReason: "ALREADY_SAID",
										},
									],
								});
								mkdirSync(join(cwd, "history"), { recursive: true });
								writeFileSync(
									join(cwd, "history/observations.json"),
									JSON.stringify({
										observations: [
											{
												practiceSlug: "test-practice",
												outcome: "NEGATIVE",
												artifact: { kind: "scm.pull_request", number: 7, title: "Earlier change" },
											},
										],
									}),
								);
								// Trying to break it: every unit but the first is wrong in its own way, and each
								// is answered on its own while the first is stored.
								const reply = await feedback.execute("f-1", {
									units: [
										{ ...card, channel: "in_app", basedOn: "observation-1" },
										{
											channel: "IN_APP",
											practiceSlug: "test-practice",
											action: "NEW",
											basedOn: [],
										},
										{ ...card, verdict: "BAD" },
										{ ...card, body: "x".repeat(8001) },
										{ ...card, channel: "IN_CHAT", withholdReason: "NOT_A_REASON" },
										{ ...card, practiceSlug: "other-practice" },
										{
											...card,
											channel: "IN_CONTEXT",
											body: undefined,
											placement: {
												kind: "diff",
												observationId: "observation-1",
												citationIndex: "0",
											},
										},
										"a unit as a string",
									],
								});
								record(`feedback:${JSON.stringify(reply)}`);
								// A single unit sent bare where the list was asked for is the list of one.
								const bare = await feedback
									.execute("f-2", {
										units: {
											...card,
											channel: "IN_CHAT",
											body: undefined,
											nextStep: undefined,
											notes: {
												situation: "s",
												capability: "c",
												evidenceSummary: "e",
												inConversationSignal: "i",
											},
										},
									})
									.then((result) => JSON.stringify(result))
									.catch((error: unknown) =>
										error instanceof Error ? error.message : String(error),
									);
								record(`feedback-bare:${bare}`);
								const summary = tool("report_summary");
								assert.ok(!JSON.stringify(summary.parameters).includes('"minLength"'));
								const long = "A sentence that runs on and on without ever ending ".repeat(6);
								const lead = await summary
									.execute("l-1", { lead: long })
									.then(() => "accepted")
									.catch((error: unknown) =>
										error instanceof Error ? error.message : String(error),
									);
								record(`lead-long:${lead}`);
								const cut = await summary.execute("l-2", {
									lead: `The auth change is the one to read first. ${long}`,
								});
								record(`lead-cut:${JSON.stringify(cut)}`);
								return;
							}
							if (text.includes("## Unfinished practices")) {
								const unfinished =
									scenario === "overrun" || scenario === "repeat"
										? "test-practice"
										: "second-practice";
								const reply = await tool("report_observation").execute("o-3", {
									observations: [observation(unfinished, "Recorded on the finishing turn")],
								});
								record(`finish:${JSON.stringify(reply)}`);
								return;
							}
							const report = tool("report_observation");
							assert.match(report.description, /local review state/);
							// The SDK checks a call against this schema all or nothing, so it carries the shape and
							// the vocabulary and no rule: a rule is applied per observation, by the tool.
							const schema = JSON.stringify(report.parameters);
							for (const keyword of ["maxLength", "additionalProperties", "enum", "pattern"]) {
								assert.ok(
									!schema.includes(`"${keyword}"`),
									`${keyword} in ${schema.slice(0, 200)}`,
								);
							}
							assert.match(schema, /One of: evidence\/change\.json, evidence\/metadata\.json/);
							assert.match(schema, /One of: OLD, NEW/);
							// Below the root, an object or a list is documented by its properties and items and
							// typed by neither, since a string where an object goes must not refuse the call; a
							// scalar keeps its type, which the SDK coerces rather than refuses.
							const parameters: unknown = report.parameters;
							assert.ok(typeof parameters === "object" && parameters !== null);
							const properties: unknown = Reflect.get(parameters, "properties");
							assert.ok(typeof properties === "object" && properties !== null);
							const items = JSON.stringify(Reflect.get(properties, "observations"));
							assert.ok(!items.includes('"type":"object"'), items.slice(0, 200));
							assert.ok(!items.includes('"type":"array"'), items.slice(0, 200));
							assert.match(items, /"startLine":\{"description":"[^"]*","type":"integer"\}/);
							assert.match(items, /Required: practiceSlug, summary/);
							if (scenario === "repeat") {
								// The same bash call, six times: the SDK emits each start with its arguments, and
								// the runner nudges at the third and ends the turn at the sixth.
								for (let call = 1; call <= 6; call++) {
									emit({
										type: "tool_execution_start",
										toolCallId: `b-${call}`,
										toolName: "bash",
										args: { command: "git show abc --stat | head" },
									});
								}
								return;
							}
							if (scenario === "refusal-cap") {
								const wrong = observation("test-practice", "Wrong quote", {
									...changeCitation,
									quote: "+ notInTheDiff();",
								});
								for (let attempt = 1; attempt <= 9; attempt++) {
									await report
										.execute(`o-${attempt}`, { observations: [wrong] })
										.then(() => record(`refusal-${attempt}:accepted`))
										.catch((error: unknown) =>
											record(
												`refusal-${attempt}:${error instanceof Error ? error.message : String(error)}`,
											),
										);
								}
								return;
							}
							if (scenario === "tree-citation") {
								const cite = (
									path: string,
									quote: string,
									summary = "Unsafe authentication call",
								) =>
									report.execute("o-1", {
										observations: [
											observation("test-practice", summary, {
												sourceKind: "scm.repository.tree",
												artifactPath: "repos/primary/.git/HEAD",
												path,
												startLine: 2,
												quote,
											}),
										],
									});
								await assert.rejects(
									cite("src/Auth.java", "insecure(user);"),
									/\[L2\] reads " {2}insecure\(\);", not "insecure\(user\);"/,
								);
								await assert.rejects(cite("src/Missing.java", "insecure();"), /no such file/);
								await assert.rejects(
									cite("src/logo.png", "PNG"),
									/binary and has no lines to quote/,
								);
								await assert.rejects(cite("../task.json", "schemaVersion"), /no such file/);
								record("citation:refused");
								await cite("src/Auth.java", "insecure();");
								record("citation:stored");
								// A citation at a revision in the history is read through .git; a wrong line is
								// corrected there too, and an unknown revision is refused.
								const historySha = readFileSync(join(cwd, "history-sha"), "utf8");
								const atRevision = (revision: string, startLine: number) =>
									report.execute("o-2", {
										observations: [
											observation("test-practice", `At revision ${startLine}`, {
												sourceKind: "scm.repository.tree",
												artifactPath: "repos/primary/.git/HEAD",
												path: "src/Auth.java",
												revision,
												startLine,
												quote: "insecure();",
											}),
										],
									});
								const relocated: unknown = await atRevision(historySha, 3);
								record(
									`citation:history:${typeof relocated === "object" && relocated !== null && "details" in relocated && typeof relocated.details === "object" && relocated.details !== null && "inserted" in relocated.details ? String(relocated.details.inserted) : "?"}`,
								);
								await assert.rejects(atRevision("b".repeat(40), 2), /no such file at revision/);
								record("citation:history-refused");
								// Coordinates alone: the runner records the line and echoes what it recorded.
								const echoed: unknown = await cite("src/Auth.java", "", "Cited by line alone");
								const firstContent: unknown =
									typeof echoed === "object" &&
									echoed !== null &&
									"content" in echoed &&
									Array.isArray(echoed.content)
										? echoed.content[0]
										: undefined;
								const echoedText =
									typeof firstContent === "object" &&
									firstContent !== null &&
									"text" in firstContent &&
									typeof firstContent.text === "string"
										? firstContent.text
										: "";
								record(`citation:echo:${echoedText.split("\n")[1] ?? ""}`);
								writeFileSync(join(cwd, "out", "stray.txt"), "left by a session");
								return;
							}
							// An observation that decides nothing must show it read the change, as admission demands.
							const undecided = (consulted: string[]) => ({
								practiceSlug: "test-practice",
								summary: "Nothing to assess in this change",
								assessmentStatus: "NOT_APPLICABLE",
								presence: null,
								assessment: null,
								severity: null,
								evidenceRationale: "The change touches only metadata.",
								evidence: {
									citations: [
										{
											sourceKind: "scm.pull-request.core",
											artifactPath: "evidence/metadata.json",
											path: "evidence/metadata.json",
											startLine: 1,
											quote: '"title": "Add login"',
										},
									],
									inapplicability: {
										consulted,
										subject: "authentication calls",
										ruledOutBy: "no code changed",
									},
								},
							});
							await report
								.execute("o-na", { observations: [undecided(["scm.pull-request.core"])] })
								.then(() => record("undecided:accepted"))
								.catch((error: unknown) =>
									record(`undecided:${error instanceof Error ? error.message : String(error)}`),
								);
							const consultedDiff = await report.execute("o-na2", {
								observations: [undecided(["scm.pull-request.core", "scm.pull-request.diff"])],
							});
							record(`undecided-consulted:${JSON.stringify(consultedDiff)}`);
							// A list sent as a string with one closing brace too many is repaired and read; one
							// that is not JSON is refused with the parse error, never silently emptied.
							const oneBraceTooMany = `${JSON.stringify([observation("test-practice", "Sent as a string with an extra brace")]).slice(0, -1)}}]`;
							const repaired = await report.execute("o-00", { observations: oneBraceTooMany });
							record(`repaired:${JSON.stringify(repaired)}`);
							// One brace too many on a nested object closes the item before its last member: the
							// member that follows belongs to the item, so the early closer is what goes.
							const intact = JSON.stringify([
								observation("test-practice", "Sent as a string closed one brace early"),
							]);
							const early = intact.replace(',"evidence":{', '},"evidence":{');
							assert.notEqual(early, intact);
							const earlyReply = await report.execute("o-01", { observations: early });
							record(`repaired-early:${JSON.stringify(earlyReply)}`);
							// One brace too few: the next item starts inside the first one's evidence object. An
							// object's members are keys, never a bare object, so the closers owed are inserted.
							const two = JSON.stringify([
								observation("test-practice", "First of two, its evidence left open"),
								observation("test-practice", "Second of two, starting inside the first"),
							]);
							const unclosed = two.replace('}]}},{"practiceSlug"', '}]},{"practiceSlug"');
							assert.notEqual(unclosed, two);
							const unclosedReply = await report.execute("o-02", { observations: unclosed });
							record(`repaired-unclosed:${JSON.stringify(unclosedReply)}`);
							await report
								.execute("o-0", { observations: "[{not json" })
								.then(() => record("unparsed:accepted"))
								.catch((error: unknown) =>
									record(`unparsed:${error instanceof Error ? error.message : String(error)}`),
								);
							// The ordinary turn: two observations in one call, one of them refused. The first names
							// no side; the runner records the side the text is found on.
							const { side: _side, ...sideless } = changeCitation;
							const reply = await report.execute("o-1", {
								observations: [
									observation("test-practice", "Unsafe authentication call", sideless),
									observation("test-practice", "A quote that is not in the change", {
										...changeCitation,
										quote: "+ somethingElse();",
									}),
									observation("test-practice", "A summary that runs on ".repeat(8).trim()),
									// The artifact is the pinned change; the source kind named is not the one that
									// staged it. The manifest decides, and the correction is echoed.
									observation("test-practice", "Cited under the wrong source kind", {
										...changeCitation,
										sourceKind: "scm.pull-request.core",
									}),
								],
							});
							record(`batch:${JSON.stringify(reply)}`);
						},
					},
				});
			},
		},
	});
	await import("../../../main/resources/agent/pi-runner.ts");
} else {
	for (const stage of [
		"setup",
		"session-init",
		"budget",
		"overrun",
		"provider-error",
		"batch",
		"finish",
		"refusal-cap",
		"repeat",
		"tree-citation",
		"compose",
		"compose-overflow",
		"compose-silent",
		"compose-loop",
		"compose-quiet",
	]) {
		void test(
			{
				setup: "does not start a session when setup exhausts the budget",
				"session-init": "fails cleanly when the session cannot be created",
				budget: "aborts a turn that runs past its share and reports the practices as not reached",
				overrun:
					"ends a compaction with the aborted turn and waits for the session before the next turn is sent",
				"provider-error":
					"a provider error the SDK does not retry is a failure of the provider, not a review that found nothing",
				batch: "stores several observations from one call and answers per item",
				finish: "asks once more, in the same session, for the practices no turn recorded",
				"refusal-cap": "stops accepting a practice after eight refused submissions",
				repeat: "nudges a turn that repeats one call and ends it when the call keeps coming",
				"tree-citation":
					"verifies a HEAD repository citation against the checkout and finalizes out/",
				compose: "composes feedback in the same session from the admitted observations",
				"compose-overflow":
					"compacts the session before a composition prompt that would not fit beside what it holds",
				"compose-silent":
					"asks once more when the composition ended without a recording call and negatives await a decision",
				"compose-loop":
					"ends a composition that keeps calling a recording tool without recording anything",
				"compose-quiet": "skips a WITHHOLD on a practice with nothing to withhold and asks no more",
			}[stage] ?? stage,
			() => {
				const cwd = mkdtempSync(join(tmpdir(), "pi-orchestration-"));
				try {
					mkdirSync(join(cwd, "catalog/practices"), { recursive: true });
					mkdirSync(join(cwd, "evidence"), { recursive: true });
					mkdirSync(join(cwd, "work/change"), { recursive: true });
					writeFileSync(join(cwd, "AGENTS.md"), "Review the staged evidence.");
					writeFileSync(join(cwd, "feedback-composer.md"), "Compose from admitted observations.");
					writeFileSync(join(cwd, "events"), "");
					writeFileSync(
						join(cwd, "evidence/metadata.json"),
						JSON.stringify({ title: "Add login" }),
					);
					writeFileSync(
						join(cwd, "evidence/change.json"),
						JSON.stringify({ base_sha: "b".repeat(40), head_sha: "a".repeat(40) }),
					);
					// The change view the container derives from the checkout before the runner starts.
					writeFileSync(
						join(cwd, "work/change/diff.patch"),
						"diff --git a/src/Auth.java b/src/Auth.java\n--- a/src/Auth.java\n+++ b/src/Auth.java\n@@ -10,0 +10,1 @@\n[L10] + insecure();\n",
					);
					writeFileSync(
						join(cwd, "catalog/practices/test-practice.md"),
						"# Test practice\nCriteria.",
					);
					if (stage.startsWith("compose")) {
						writeFileSync(
							join(cwd, "evidence/composition.json"),
							JSON.stringify({
								enabled: true,
								channels: {
									IN_APP: { enabled: true, maxUnits: 1 },
									IN_CONTEXT: { enabled: true, maxUnits: 1 },
								},
								inContextPlacementKinds: ["ARTIFACT"],
							}),
						);
					}
					if (stage === "tree-citation") {
						// A real checkout with one commit in its history, so a citation at a revision is read
						// through .git like admission reads it.
						mkdirSync(join(cwd, "repos/primary/src"), { recursive: true });
						writeFileSync(
							join(cwd, "repos/primary/src/Auth.java"),
							"class Auth {\n  insecure();\n}\n",
						);
						const git = (...args: string[]) =>
							spawnSync("git", ["-C", join(cwd, "repos/primary"), ...args], {
								encoding: "utf8",
								env: {
									...process.env,
									GIT_AUTHOR_NAME: "t",
									GIT_AUTHOR_EMAIL: "t@t",
									GIT_COMMITTER_NAME: "t",
									GIT_COMMITTER_EMAIL: "t@t",
								},
							});
						writeFileSync(
							join(cwd, "repos/primary/src/logo.png"),
							Buffer.from([0x89, 0x50, 0x4e, 0x47, 0, 1, 2]),
						);
						git("init", "-q");
						git("add", ".");
						git("commit", "-q", "-m", "first");
						writeFileSync(
							join(cwd, "repos/primary/src/Auth.java"),
							"class Auth {\n  insecure();\n  more();\n}\n",
						);
						git("commit", "-q", "-am", "second");
						writeFileSync(join(cwd, "history-sha"), git("rev-parse", "HEAD~1").stdout.trim());
					}
					writeFileSync(
						join(cwd, "evidence/manifest.json"),
						JSON.stringify({
							sources: [
								{
									kind: "scm.pull-request.core",
									state: { availability: "AVAILABLE" },
									artifacts: [{ path: "evidence/metadata.json" }],
								},
								{
									kind: "scm.pull-request.diff",
									state: { availability: "AVAILABLE" },
									artifacts: [{ path: "evidence/change.json" }],
								},
								...(stage === "tree-citation"
									? [
											{
												kind: "scm.repository.tree",
												state: { availability: "AVAILABLE" },
												artifacts: [{ path: "repos/primary/.git/HEAD" }],
											},
										]
									: []),
							],
						}),
					);
					writeFileSync(
						join(cwd, "catalog/practices/index.json"),
						JSON.stringify(
							stage === "finish"
								? [
										{ slug: "test-practice", group: "code" },
										{ slug: "second-practice", group: "code" },
									]
								: [{ slug: "test-practice", group: "code" }],
						),
					);
					writeFileSync(
						join(cwd, "pi-provider.json"),
						JSON.stringify({ apiProtocol: "openai-completions", modelId: "test-model" }),
					);
					writeFileSync(
						join(cwd, "task.json"),
						JSON.stringify({
							schemaVersion: 2,
							paths: {
								contextRoot: "evidence",
								repositoryRoot: "repos/primary",
								manifest: "evidence/manifest.json",
								practiceIndex: "catalog/practices/index.json",
								compositionRequest: "evidence/composition.json",
								preparedFeedback: "history/prepared.json",
								precomputeScripts: "scripts/practices",
							},
							task: { kind: "practice_review", prompt: "Review the practice." },
						}),
					);
					const child = spawnSync(
						process.execPath,
						["--experimental-test-module-mocks", import.meta.filename],
						{
							env: {
								...process.env,
								PI_ORCHESTRATION_SCENARIO: stage,
								PI_RUNNER_CWD: cwd,
								PI_CODING_AGENT_DIR: cwd,
								AGENT_BUDGET_MS: stage === "overrun" ? "3000" : "10000",
								LLM_PROXY_URL: "https://unused.invalid",
								LLM_PROXY_TOKEN: "test-token",
							},
							encoding: "utf8",
							timeout: 10_000,
						},
					);
					assert.equal(child.error, undefined);
					const events = readFileSync(join(cwd, "events"), "utf8")
						.trim()
						.split("\n")
						.filter(Boolean);
					const coverage: unknown = JSON.parse(
						readFileSync(join(cwd, "out/practice-coverage.json"), "utf8"),
					);
					const reached = (slugs: Record<string, "EVALUATED" | "NOT_REACHED">) =>
						assert.deepEqual(coverage, {
							eligible: Object.keys(slugs).length,
							evaluated: Object.values(slugs).filter((outcome) => outcome === "EVALUATED").length,
							outcomes: Object.entries(slugs).map(([practiceSlug, outcome]) => ({
								practiceSlug,
								outcome,
							})),
						});
					switch (stage) {
						case "setup":
							assert.equal(child.status, 1, child.stderr);
							assert.deepEqual(events, []);
							reached({ "test-practice": "NOT_REACHED" });
							break;
						case "session-init":
							assert.equal(child.status, 2, child.stderr);
							assert.equal(events.length, 1);
							break;
						case "budget":
							assert.equal(child.status, 1, child.stderr);
							assert.ok(events.includes("prompt:1"), child.stderr);
							assert.ok(events.includes("abort"), child.stderr);
							assert.ok(!events.includes("prompt:2"), events.join("\n"));
							reached({ "test-practice": "NOT_REACHED" });
							break;
						case "provider-error":
							// Exit 76: the server queues the review again instead of recording a failure.
							assert.equal(child.status, 76, child.stderr);
							assert.match(
								child.stderr,
								/UNREACHABLE: this review reached no practice, and \d+ model call\(s\)/,
							);
							reached({ "test-practice": "NOT_REACHED" });
							break;
						case "overrun": {
							// The first turn is aborted at its share, which ends the compaction it was in; the
							// finishing turn is sent once the session is idle, in the same session, and records
							// the practice.
							assert.equal(child.status, 0, child.stderr);
							const order = events.filter((event) =>
								["prompt:1", "steer", "abort-compaction", "abort", "prompt:2"].includes(event),
							);
							assert.deepEqual(order.slice(0, 5), [
								"prompt:1",
								"steer",
								"abort-compaction",
								"abort",
								"prompt:2",
							]);
							assert.match(events.find((event) => event.startsWith("finish:")) ?? "", /stored/);
							assert.match(child.stderr, /share exhausted — aborting this turn/);
							assert.doesNotMatch(child.stderr, /already processing/);
							reached({ "test-practice": "EVALUATED" });
							break;
						}
						case "batch": {
							assert.equal(child.status, 0, child.stderr);
							assert.match(
								events.find((event) => event.startsWith("undecided:")) ?? "",
								/must show it read the change/,
							);
							assert.match(
								events.find((event) => event.startsWith("undecided-consulted:")) ?? "",
								/#1 test-practice: stored\./,
							);
							assert.match(
								events.find((event) => event.startsWith("repaired:")) ?? "",
								/#1 test-practice: stored \(negative\)/,
							);
							assert.match(
								events.find((event) => event.startsWith("repaired-early:")) ?? "",
								/#1 test-practice: stored \(negative\)/,
							);
							assert.match(
								events.find((event) => event.startsWith("repaired-unclosed:")) ?? "",
								/#1 test-practice: stored \(negative\)[\s\S]*#2 test-practice: stored \(negative\)/,
							);
							assert.match(
								events.find((event) => event.startsWith("unparsed:")) ?? "",
								/observations refused — the list arrived as a string that is not a JSON array/,
							);
							const reply = events.find((event) => event.startsWith("batch:")) ?? "";
							assert.match(reply, /#1 test-practice: stored \(negative\)/);
							assert.match(
								reply,
								/#2 test-practice: refused — .*not in the diff|#2 test-practice: refused/,
							);
							assert.match(
								reply,
								/#3 test-practice: refused — summary must be at most 160 characters/,
							);
							assert.match(
								reply,
								/#4 test-practice: stored \(negative\)\.\\n {3}evidence\/change\.json is staged by scm\.pull-request\.diff, not scm\.pull-request\.core; recorded as scm\.pull-request\.diff/,
							);
							assert.match(reply, /Every practice of this turn has a recorded result/);
							// One session, one measuring turn, no composition requested.
							assert.deepEqual(
								events.filter((event) => event.startsWith("prompt:")),
								["prompt:1"],
							);
							assert.equal(events.filter((event) => event.startsWith("create:")).length, 1);
							const first = readFileSync(join(cwd, "prompt-1.md"), "utf8");
							assert.match(first, /Review the practice\./);
							assert.match(first, /### `evidence\/metadata\.json`/);
							assert.match(first, /### `work\/change\/diff\.patch`/);
							assert.match(
								first,
								/### Practice `test-practice`\n[\s\S]*# Test practice\nCriteria\./,
							);
							assert.match(
								readFileSync(join(cwd, "work/notes/review.md"), "utf8"),
								/test-practice: PRESENT\/BAD — Unsafe authentication call/,
							);
							// The quote was copied with its diff marker; what is recorded is the line's content,
							// which is what admission reads out of the blob.
							const reviewState = readFileSync(join(cwd, "out/review-state.json"), "utf8");
							assert.match(reviewState, /"quote": " insecure\(\);"/);
							assert.match(reviewState, /"side": "NEW"/);
							reached({ "test-practice": "EVALUATED" });
							break;
						}
						case "finish": {
							assert.equal(child.status, 0, child.stderr);
							assert.deepEqual(
								events.filter((event) => event.startsWith("prompt:")),
								["prompt:1", "prompt:2"],
							);
							assert.equal(events.filter((event) => event.startsWith("create:")).length, 1);
							const second = readFileSync(join(cwd, "prompt-2.md"), "utf8");
							assert.match(
								second,
								/## Recorded so far\n(- test-practice: .*\n)*- test-practice: PRESENT\/BAD/,
							);
							assert.match(second, /No observation was recorded for: second-practice/);
							reached({ "test-practice": "EVALUATED", "second-practice": "EVALUATED" });
							break;
						}
						case "repeat": {
							assert.equal(child.status, 0, child.stderr);
							assert.match(
								child.stderr,
								/turn 1\/1 \(code\): the same bash call 3 times — nudging to record/,
							);
							assert.match(
								child.stderr,
								/turn 1\/1 \(code\): the same bash call 6 times — aborting this turn/,
							);
							assert.ok(events.includes("steer") && events.includes("abort"), events.join("\n"));
							// The finishing turn, in the same session, records the practice the loop left unrecorded.
							assert.match(events.find((event) => event.startsWith("finish:")) ?? "", /stored/);
							reached({ "test-practice": "EVALUATED" });
							break;
						}
						case "refusal-cap": {
							assert.equal(child.status, 1, child.stderr);
							const refusals = events.filter((event) => event.startsWith("refusal-"));
							assert.equal(refusals.length, 9);
							assert.match(refusals[7] ?? "", /refused/);
							assert.match(
								refusals[8] ?? "",
								/8 submissions for 'test-practice' were refused; no more are accepted/,
							);
							reached({ "test-practice": "NOT_REACHED" });
							break;
						}
						case "tree-citation": {
							assert.equal(child.status, 0, child.stderr);
							assert.deepEqual(
								events.filter((event) => event.startsWith("citation:")),
								[
									"citation:refused",
									"citation:stored",
									"citation:history:1",
									"citation:history-refused",
									'citation:echo:   src/Auth.java:2-2 recorded "  insecure();"',
								],
								child.stderr,
							);
							assert.ok(!existsSync(join(cwd, "out/stray.txt")));
							const result: unknown = JSON.parse(
								readFileSync(join(cwd, "out/result.json"), "utf8"),
							);
							assert.ok(
								typeof result === "object" && result !== null && "admissionDigest" in result,
							);
							assert.equal(result.admissionDigest, "admitted-digest");
							reached({ "test-practice": "EVALUATED" });
							break;
						}
						case "compose": {
							assert.equal(child.status, 0, child.stderr);
							assert.deepEqual(
								events.filter((event) => event.startsWith("prompt:")),
								["prompt:1", "prompt:2"],
							);
							assert.equal(events.filter((event) => event.startsWith("create:")).length, 1);
							assert.ok(!events.includes("compact"), child.stderr);
							assert.match(
								events.find((event) => event.startsWith("feedback-alone:")) ?? "",
								/IN_APP needs a pattern across at least 2 pieces of work, and test-practice is NEGATIVE on 1/,
								child.stderr,
							);
							const reply = events.find((event) => event.startsWith("feedback:")) ?? "";
							assert.match(reply, /#1: stored a IN_APP unit for test-practice \(NEW\); 1\/1 used/);
							assert.match(reply, /#2: basedOn is required/);
							assert.match(reply, /#3: unknown unit field\(s\): verdict — a unit takes channel/);
							assert.match(reply, /#4: body must be at most 8000 characters; this one is 8001/);
							assert.match(
								reply,
								/#5: withholdReason must be one of NO_MATERIAL_CHANGE, ALREADY_SAID, BELOW_BAR \(received 'NOT_A_REASON'\)/,
							);
							assert.match(
								reply,
								/#6: practiceSlug 'other-practice' is not a practice with an admitted observation in this run \(those are: test-practice\)/,
							);
							assert.match(
								reply,
								/#7: already have a IN_CONTEXT unit for test-practice; skipped\./,
							);
							assert.match(
								reply,
								/#8: each item of units is one unit object .*\(received string\)/,
							);
							assert.match(
								events.find((event) => event.startsWith("feedback-bare:")) ?? "",
								/#1: IN_CHAT is not a lane this run may write for/,
							);
							assert.match(
								events.find((event) => event.startsWith("lead-long:")) ?? "",
								/lead must be at most 240 characters, or end a sentence within them; this one is 305/,
							);
							assert.match(
								events.find((event) => event.startsWith("lead-cut:")) ?? "",
								/Stored the opening line up to its last sentence end within 240 characters: \\"The auth change is the one to read first\.\\"/,
							);
							const second = readFileSync(join(cwd, "prompt-2.md"), "utf8");
							assert.match(second, /Compose from admitted observations\./);
							assert.match(second, /"id": "observation-1"/);
							// The quoted lines and the verification records stay on disk, where the composer
							// can read them if it must; the search it recorded is still shown.
							assert.doesNotMatch(second, /"quote"/);
							assert.doesNotMatch(second, /"verification"/);
							assert.match(second, /"lookedFor": "x"/);
							assert.match(
								readFileSync(join(cwd, "work/composition/observations.json"), "utf8"),
								/"quote": "\+ insecure\(\);"/,
							);
							const feedback: unknown = JSON.parse(
								readFileSync(join(cwd, "out/feedback.json"), "utf8"),
							);
							assert.ok(typeof feedback === "object" && feedback !== null);
							assert.equal(Reflect.get(feedback, "admissionDigest"), "admitted-digest");
							const units: unknown = Reflect.get(feedback, "units");
							assert.ok(Array.isArray(units) && units.length === 2, JSON.stringify(units));
							const [delivered, withheld] = units as unknown[];
							assert.ok(typeof delivered === "object" && delivered !== null);
							assert.ok(typeof withheld === "object" && withheld !== null);
							assert.equal(Reflect.get(delivered, "action"), "NEW");
							assert.equal(Reflect.get(withheld, "action"), "WITHHOLD");
							assert.equal(
								Reflect.get(feedback, "lead"),
								"The auth change is the one to read first.",
							);
							assert.match(child.stderr, /composition: .*stored=3/);
							reached({ "test-practice": "EVALUATED" });
							break;
						}
						case "compose-overflow": {
							// 120k held, a prompt of a few thousand and 16k of output do not fit in 128k: the
							// session is compacted before the prompt, and the prompt itself is sent whole.
							assert.equal(child.status, 0, child.stderr);
							const order = events.filter((event) =>
								["prompt:1", "compact", "prompt:2"].includes(event),
							);
							assert.deepEqual(order, ["prompt:1", "compact", "prompt:2"]);
							assert.match(
								child.stderr,
								/composition: 120000 tokens held and \d+ needed exceed the 128000 window — compacting first/,
							);
							assert.match(
								readFileSync(join(cwd, "prompt-2.md"), "utf8"),
								/Compose from admitted observations\./,
							);
							break;
						}
						case "compose-silent": {
							assert.equal(child.status, 0, child.stderr);
							assert.deepEqual(
								events.filter((event) => event.startsWith("prompt:")),
								["prompt:1", "prompt:2", "prompt:3"],
							);
							assert.match(
								readFileSync(join(cwd, "prompt-3.md"), "utf8"),
								/## Nothing persisted[\s\S]*NEGATIVE observation: test-practice/,
							);
							assert.match(
								child.stderr,
								/composition recorded nothing for 1 NEGATIVE practice\(s\) — asking once more/,
							);
							assert.equal(
								(
									child.stderr.match(
										/composition: 12 calls without a recording call — nudging to persist/g,
									) ?? []
								).length,
								1,
								child.stderr,
							);
							assert.equal(events.filter((event) => event === "steer").length, 1);
							assert.match(
								events.find((event) => event.startsWith("feedback-finish:")) ?? "",
								/#1: stored a IN_APP unit for test-practice \(WITHHOLD\)/,
							);
							const feedback: unknown = JSON.parse(
								readFileSync(join(cwd, "out/feedback.json"), "utf8"),
							);
							assert.ok(typeof feedback === "object" && feedback !== null);
							const units: unknown = Reflect.get(feedback, "units");
							assert.ok(Array.isArray(units) && units.length === 1);
							const unit: unknown = units[0];
							assert.ok(typeof unit === "object" && unit !== null);
							assert.equal(Reflect.get(unit, "withholdReason"), "BELOW_BAR");
							break;
						}
						case "compose-quiet": {
							assert.equal(child.status, 0, child.stderr);
							assert.deepEqual(
								events.filter((event) => event.startsWith("prompt:")),
								["prompt:1", "prompt:2"],
							);
							assert.match(
								events.find((event) => event.startsWith("feedback-quiet:")) ?? "",
								/#1: test-practice has no NEGATIVE observation in this run, so there is nothing to withhold; skipped\./,
							);
							assert.doesNotMatch(child.stderr, /asking once more/);
							break;
						}
						case "compose-loop": {
							assert.equal(child.status, 0, child.stderr);
							assert.ok(events.includes("abort"), child.stderr);
							assert.match(
								child.stderr,
								/composer: 24 recording calls without a record — aborting this turn/,
							);
							// Every refused call is in the transcript with the SDK's reason, and in the trace.
							assert.match(
								child.stderr,
								/composer tool error: report_summary — Validation failed for tool "report_summary": - \/lead: Expected string/,
							);
							const debug: unknown = JSON.parse(
								readFileSync(join(cwd, "out/runner-debug.json"), "utf8"),
							);
							assert.ok(typeof debug === "object" && debug !== null);
							const turns: unknown = Reflect.get(debug, "turns");
							assert.ok(Array.isArray(turns));
							const composition: unknown = turns.find(
								(turn: unknown) =>
									typeof turn === "object" &&
									turn !== null &&
									Reflect.get(turn, "label") === "composition",
							);
							assert.ok(typeof composition === "object" && composition !== null);
							assert.equal(Reflect.get(composition, "toolErrors"), 24);
							assert.equal(Reflect.get(composition, "recordingCalls"), 24);
							break;
						}
						default:
							assert.fail(`unknown stage ${stage}`);
					}
				} finally {
					rmSync(cwd, { recursive: true, force: true });
				}
			},
		);
	}
}
