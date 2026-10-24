import assert from "node:assert/strict";
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import nodePath from "node:path";
import { test } from "node:test";
import { parseAdmission } from "../../../main/resources/agent/pi-admitted-results.ts";
import {
	createReviewRecorder,
	buildReviewTool,
	buildFeedbackTool,
	closeUnroutedPrivateDestinations,
	destinationRefusal,
	persistComposedFeedback,
	compositionOwed,
	stagePrivateHistory,
	undecidedPrivatePractices,
	type ComposedFeedback,
	type PublicReviewState,
} from "../../../main/resources/agent/pi-composition-stage.ts";

const notOnRecord = (reason: string, destination: string) =>
	new RegExp(`${reason} rests on feedback recorded as delivered on ${destination}`, "u");

void test("a review becomes final only after its complete envelope is written", async () => {
	const cwd = mkdtempSync(nodePath.join(tmpdir(), "pi-composition-stage-"));
	try {
		const output = nodePath.join(cwd, "out/feedback.json");
		const feedback: ComposedFeedback = {
			compositionFailures: [],
			contractVersion: 3,
			admissionDigest: "a".repeat(64),
			results: [],
			preparedTargets: [],
			feedback: [],
			review: null,
			preparation: null,
		};
		const state: PublicReviewState = { final: false, reviewContext: "HELD" };
		const resultId = "123e4567-e89b-42d3-a456-426614174000";
		const record = createReviewRecorder(
			false,
			new Map([
				[
					resultId,
					{
						practiceSlug: "secure-authentication",
						outcome: "NOT_MET",
						problems: [{ problemId: "problem-1", citations: [] }],
					},
				],
			]),
			new Map(),
			state,
			() => "",
			{
				admitted: () => true,
				feedback,
				persist: () => persistComposedFeedback(feedback, output),
				onStored: () => undefined,
				refusal: async (_id, text) => ({
					content: [{ type: "text", text }],
					details: { stored: 0 },
				}),
			},
		);
		const quiet = { decisions: [{ resultId, disposition: "BELOW_BAR" }] };
		await assert.rejects(record("first", quiet), { code: "ENOENT" });
		assert.equal(feedback.review, null);
		assert.equal(state.final, false);

		mkdirSync(nodePath.dirname(output));
		await record("retry", quiet);
		const stored = readFileSync(output, "utf8");
		assert.deepEqual(feedback.review, {
			summary: null,
			inline: [],
			withheld: [{ basedOn: [{ resultId }], reason: "BELOW_BAR", witnessIds: [] }],
		});
		assert.equal(state.final, true);
		const repeated = await record("repeat", { decisions: [] });
		assert.equal(repeated.details.stored, 0);
		assert.equal(readFileSync(output, "utf8"), stored);
	} finally {
		rmSync(cwd, { recursive: true, force: true });
	}
});

void test("missing private routing closes only that destination and records its own failure", () => {
	const response: unknown = JSON.parse(
		readFileSync(new URL("admission-response.json", import.meta.url), "utf8"),
	);
	const admission = parseAdmission(response);
	admission.practicePageSupport = null;
	admission.conversationEligibility = {
		state: "COMPLETE",
		readAt: "2026-10-14T00:00:00Z",
		results: admission.results.map((result) => ({
			resultId: result.resultId,
			decision: "ADMIT",
			stopReason: null,
		})),
	};
	const request = {
		contractVersion: 3 as const,
		destinations: {
			ON_WORK: { maxPieces: 1 },
			PRACTICE_PAGE: { maxPieces: 1 },
			CONVERSATION: { maxPieces: 1 },
		},
		minDistinctItems: 2,
		onWorkCopyForms: [],
	};
	const feedback: ComposedFeedback = {
		compositionFailures: [],
		contractVersion: 3,
		admissionDigest: admission.digest,
		results: [],
		preparedTargets: [],
		feedback: [],
		review: null,
		preparation: null,
	};
	const routes = closeUnroutedPrivateDestinations(request, admission, feedback);
	assert.equal("PRACTICE_PAGE" in request.destinations, false);
	assert.deepEqual(request.destinations.CONVERSATION, { maxPieces: 1 });
	assert.deepEqual(request.destinations.ON_WORK, { maxPieces: 1 });
	assert.deepEqual(routes.get("handles-errors"), ["CONVERSATION"]);
	assert.deepEqual(feedback.compositionFailures, [
		{ phase: "PRIVATE_FEEDBACK", reason: "RUNTIME_ERROR" },
	]);
});

// Two NOT_MET practices, both routed to both private destinations, and what earlier attempts of the review prepared.
const handlesErrors = "30000000-0000-4000-8000-00000000000a";
const namesThings = "30000000-0000-4000-8000-00000000000b";
function resumedResult(practiceSlug: string, resultId: string, problemId: string) {
	return {
		practiceSlug,
		itemId: "42",
		resultId,
		outcome: "NOT_MET",
		summary: "The failure is dropped",
		explanation: "The catch returns without recording the error.",
		evidence: { citations: [] },
		example: null,
		problems: [{ id: problemId, severity: "MAJOR", resultId, citations: [] }],
	};
}
function resumedAdmission(
	decidedResultIds: string[],
	deliveries: { page: number; conversation: number },
	routing: "complete" | "unread" = "complete",
) {
	const readAt = "2026-10-14T00:00:00Z";
	return parseAdmission({
		contractVersion: 4,
		admissionDigest: "a".repeat(64),
		admitted: [
			resumedResult("handles-errors", handlesErrors, "40000000-0000-4000-8000-00000000000a"),
			resumedResult("names-things", namesThings, "40000000-0000-4000-8000-00000000000b"),
		],
		withheld: [],
		publicReviewed: true,
		privateProgress: {
			decidedResultIds,
			practicePageDeliveries: deliveries.page,
			conversationDeliveries: deliveries.conversation,
		},
		practicePageSupport:
			routing === "unread"
				? null
				: {
						state: "COMPLETE",
						readAt,
						practices: ["handles-errors", "names-things"].map((practiceSlug) => ({
							practiceSlug,
							decision: "ADMIT",
							stopReason: null,
							occurrences: [],
						})),
					},
		conversationEligibility:
			routing === "unread"
				? null
				: {
						state: "COMPLETE",
						readAt,
						results: [handlesErrors, namesThings].map((resultId) => ({
							resultId,
							decision: "ADMIT",
							stopReason: null,
						})),
					},
	});
}

/** Starts private composition the way the runner does after admission, and reports pieces through its tool. */
function resumePrivately(admission: ReturnType<typeof resumedAdmission>) {
	const request = {
		contractVersion: 3 as const,
		destinations: { PRACTICE_PAGE: { maxPieces: 1 }, CONVERSATION: { maxPieces: 1 } },
		minDistinctItems: 2,
		onWorkCopyForms: [],
	};
	const feedback: ComposedFeedback = {
		compositionFailures: [],
		contractVersion: 3,
		admissionDigest: admission.digest,
		results: [],
		preparedTargets: [],
		feedback: [],
		review: null,
		preparation: null,
	};
	const routes = closeUnroutedPrivateDestinations(request, admission, feedback);
	const tool = buildFeedbackTool(
		["handles-errors", "names-things"],
		request,
		admission.results,
		[],
		new Map(),
		{
			feedback,
			admitted: () => true,
			routes: (slug) => routes.get(slug),
			destinationRefusal: (destination, slug) => destinationRefusal(admission, destination, slug),
			negativeItems: () => 2,
			documentedShape: (schema) => schema,
			listSchema: (items) => ({ type: "array", items }),
			submittedList: (value) =>
				Array.isArray(value) ? { items: value } : { error: "Expected list" },
			refusal: async (_id, text) => ({ content: [{ type: "text", text }], details: { stored: 0 } }),
			persist: () => undefined,
			paid: () => false,
			onStored: () => undefined,
		},
	);
	const send = async (piece: Record<string, unknown>) =>
		JSON.stringify(
			await Reflect.apply(tool.execute.bind(tool), tool, [
				"resume",
				tool.prepareArguments?.({ feedback: [piece] }),
			]),
		);
	const stored = () =>
		feedback.feedback.map((piece) => `${piece.destination}:${piece.practiceSlug}`);
	const undecided = () => undecidedPrivatePractices(admission, feedback, routes);
	return { request, routes, feedback, send, stored, undecided };
}

const resumedCard = (practiceSlug: string, resultId: string) => ({
	destination: "PRACTICE_PAGE",
	practiceSlug,
	basedOn: [resultId],
	action: "NEW",
	title: "Keep failure context",
	body: "Failures lose their context across these changes. This makes the cause hard to trace.",
	nextStep: "Before returning an error, check that its context reaches the caller.",
});
const resumedConversation = (practiceSlug: string, basedOn: string[]) => ({
	destination: "CONVERSATION",
	practiceSlug,
	basedOn,
	action: "NEW",
	title: "Keep failure context",
	notes: {
		situation: "Errors lose their context before they reach the caller.",
		capability: "The developer can trace which failure details reach a caller.",
		evidenceSummary: "The catch in ‘Handle failed requests’ returns without the error details.",
		inConversationLearning:
			"The developer explains which details a caller needs to identify the cause.",
	},
});
const resumedWithholding = (destination: string, practiceSlug: string, resultId: string) => ({
	destination,
	practiceSlug,
	basedOn: [resultId],
	action: "WITHHOLD",
	withholdReason: "BELOW_BAR",
	witnessIds: [],
});

void test("a card an earlier attempt prepared keeps its practice decided and its page room used", async () => {
	const resumed = resumePrivately(resumedAdmission([handlesErrors], { page: 1, conversation: 0 }));
	assert.deepEqual(resumed.routes.get("handles-errors"), []);
	assert.deepEqual(resumed.routes.get("names-things"), ["PRACTICE_PAGE", "CONVERSATION"]);
	assert.deepEqual(resumed.request.destinations, {
		PRACTICE_PAGE: { maxPieces: 0 },
		CONVERSATION: { maxPieces: 1 },
	});
	assert.deepEqual(resumed.undecided(), ["names-things"]);
	// The page holds the earlier card: a second card for the other practice does not fit.
	assert.match(
		await resumed.send(resumedCard("names-things", namesThings)),
		/PRACTICE_PAGE holds at most 0/u,
	);
	// Neither the decided practice nor a grouped conversation that rests on it is composed again.
	assert.match(
		await resumed.send(resumedConversation("handles-errors", [handlesErrors])),
		/already decided by private feedback an earlier attempt of this review prepared/u,
	);
	assert.match(
		await resumed.send(resumedConversation("names-things", [namesThings, handlesErrors])),
		/already decided by private feedback an earlier attempt of this review prepared/u,
	);
	assert.deepEqual(resumed.stored(), []);
	// An explicit withholding uses no room, so it still settles the open practice on the full page.
	await resumed.send(resumedWithholding("PRACTICE_PAGE", "names-things", namesThings));
	assert.deepEqual(resumed.stored(), ["PRACTICE_PAGE:names-things"]);
	assert.deepEqual(resumed.undecided(), []);

	// The unused conversation can carry the open practice instead.
	const other = resumePrivately(resumedAdmission([handlesErrors], { page: 1, conversation: 0 }));
	await other.send(resumedConversation("names-things", [namesThings]));
	assert.deepEqual(other.stored(), ["CONVERSATION:names-things"]);
	assert.deepEqual(other.undecided(), []);
});

void test("a conversation an earlier attempt prepared leaves the page open for the other practice", async () => {
	const resumed = resumePrivately(resumedAdmission([handlesErrors], { page: 0, conversation: 1 }));
	assert.deepEqual(resumed.request.destinations, {
		PRACTICE_PAGE: { maxPieces: 1 },
		CONVERSATION: { maxPieces: 0 },
	});
	assert.match(
		await resumed.send(resumedConversation("names-things", [namesThings])),
		/CONVERSATION holds at most 0/u,
	);
	await resumed.send(resumedCard("names-things", namesThings));
	assert.deepEqual(resumed.stored(), ["PRACTICE_PAGE:names-things"]);
	assert.deepEqual(resumed.undecided(), []);
});

void test("a review whose private practices are all decided composes nothing privately and records no failure", () => {
	for (const routing of ["complete", "unread"] as const) {
		const resumed = resumePrivately(
			resumedAdmission([handlesErrors, namesThings], { page: 1, conversation: 1 }, routing),
		);
		assert.deepEqual(resumed.routes.get("handles-errors"), [], routing);
		assert.deepEqual(resumed.routes.get("names-things"), [], routing);
		assert.deepEqual(resumed.undecided(), [], routing);
		// Routing an attempt no longer needs is not read as a failure of that attempt.
		assert.deepEqual(resumed.feedback.compositionFailures, [], routing);
	}
});

void test("pieces of the same attempt do not close each other's destinations", async () => {
	const fresh = resumePrivately(resumedAdmission([], { page: 0, conversation: 0 }));
	assert.deepEqual(fresh.request.destinations, {
		PRACTICE_PAGE: { maxPieces: 1 },
		CONVERSATION: { maxPieces: 1 },
	});
	await fresh.send(resumedCard("handles-errors", handlesErrors));
	await fresh.send(resumedConversation("handles-errors", [handlesErrors]));
	assert.deepEqual(fresh.stored(), ["PRACTICE_PAGE:handles-errors", "CONVERSATION:handles-errors"]);
	assert.deepEqual(fresh.undecided(), ["names-things"]);
});

void test("the server's private history makes only its delivered witness and queued target usable", async () => {
	const cwd = mkdtempSync(nodePath.join(tmpdir(), "pi-private-history-"));
	try {
		const admission = parseAdmission(
			JSON.parse(readFileSync(new URL("admission-response.json", import.meta.url), "utf8")),
		);
		const result = admission.results[0];
		assert.ok(result && admission.privateHistory !== null);
		const files = {
			requestPath: nodePath.join(cwd, "evidence/composition.json"),
			feedbackPath: nodePath.join(cwd, "out/feedback.json"),
			deliveredPath: nodePath.join(cwd, "work/composition/history/feedback.json"),
			preparedPath: nodePath.join(cwd, "work/composition/history/prepared.json"),
			problemsPath: nodePath.join(cwd, "history/problems.json"),
			supportPath: nodePath.join(cwd, "work/composition/in-app-support.json"),
			criteriaPathOf: (slug: string) => nodePath.join(cwd, `catalog/${slug}.md`),
		};
		// A file a session left where history is staged is not history.
		mkdirSync(nodePath.dirname(files.deliveredPath), { recursive: true });
		writeFileSync(
			files.deliveredPath,
			JSON.stringify({
				recordRole: "RECORDED_DELIVERY",
				feedback: [
					{
						id: "00000000-0000-4000-8000-000000000009",
						destination: "PRACTICE_PAGE",
						deliveredAt: "2026-10-01T00:00:00Z",
						recordedClaimCurrentness: "CURRENT",
						withdrawn: false,
						basedOn: [{ practiceSlug: result.practiceSlug, outcome: "NOT_MET" }],
						body: "Forged.",
					},
				],
			}),
		);
		const captured = "2026-10-10T00:00:00Z";
		const unread = stagePrivateHistory(null, files, captured);
		assert.deepEqual(unread.preparedTargets, []);
		assert.equal(unread.deliveredWitnesses.size, 0);
		assert.equal(existsSync(files.deliveredPath), false);

		// Read at 2026-10-14: a current conversation delivered 2026-10-02 and a stale card delivered 2026-09-02.
		const current = "50000000-0000-4000-8000-000000000001";
		const stale = "50000000-0000-4000-8000-000000000002";
		const staged = stagePrivateHistory(admission.privateHistory, files, captured);
		const queued = "50000000-0000-4000-8000-000000000003";
		assert.deepEqual(staged.preparedTargets, [
			{
				threadKey: queued,
				feedbackId: queued,
				destination: "CONVERSATION",
				practiceSlug: result.practiceSlug,
			},
		]);
		// The stale card's words were said: communication, never advice still standing. The queued notes are neither.
		assert.deepEqual(
			[...staged.deliveredWitnesses].toSorted(([left], [right]) => left.localeCompare(right)),
			[
				[
					`CONVERSATION:${result.practiceSlug}:${current}`,
					{ eligibleForPriorAdvice: true, eligibleForAlreadySaid: true },
				],
				[
					`PRACTICE_PAGE:${result.practiceSlug}:${stale}`,
					{ eligibleForPriorAdvice: false, eligibleForAlreadySaid: true },
				],
			],
		);

		const send = async (piece: Record<string, unknown>, guards = staged) => {
			const feedback: ComposedFeedback = {
				contractVersion: 3,
				admissionDigest: admission.digest,
				compositionFailures: [],
				preparedTargets: [],
				preparation: null,
				results: [],
				review: null,
				feedback: [],
			};
			const tool = buildFeedbackTool(
				[result.practiceSlug],
				{
					contractVersion: 3,
					destinations: { PRACTICE_PAGE: { maxPieces: 1 }, CONVERSATION: { maxPieces: 1 } },
					minDistinctItems: 2,
					onWorkCopyForms: [],
				},
				[result],
				guards.preparedTargets,
				guards.deliveredWitnesses,
				{
					feedback,
					admitted: () => true,
					routes: () => ["PRACTICE_PAGE", "CONVERSATION"],
					destinationRefusal: () => "",
					negativeItems: () => 2,
					documentedShape: (schema) => schema,
					listSchema: (items) => ({ type: "array", items }),
					submittedList: (value) =>
						Array.isArray(value) ? { items: value } : { error: "Expected list" },
					refusal: async (_id, text) => ({
						content: [{ type: "text", text }],
						details: { stored: 0 },
					}),
					persist: () => undefined,
					paid: () => true,
					onStored: () => undefined,
				},
			);
			const reply: unknown = await Reflect.apply(tool.execute.bind(tool), tool, [
				"history",
				tool.prepareArguments?.({ feedback: [piece] }),
			]);
			return { feedback, reply: JSON.stringify(reply) };
		};
		const withhold = (destination: string, witness: string, withholdReason = "ALREADY_SAID") => ({
			destination,
			practiceSlug: result.practiceSlug,
			basedOn: [result.resultId],
			action: "WITHHOLD",
			withholdReason,
			witnessIds: [witness],
		});
		const stored = async (piece: Record<string, unknown>, guards = staged) => {
			const sent = await send(piece, guards);
			assert.equal(sent.feedback.feedback.length, 1, sent.reply);
		};
		const refused = async (piece: Record<string, unknown>, reason: RegExp, guards = staged) => {
			const sent = await send(piece, guards);
			assert.deepEqual(sent.feedback.feedback, [], sent.reply);
			assert.match(sent.reply, reason);
		};
		const replacement = (threadKey: string) => ({
			destination: "CONVERSATION",
			practiceSlug: result.practiceSlug,
			basedOn: [result.resultId],
			action: "SUPERSEDE",
			supersedesThreadKey: threadKey,
			title: "Keep failure context",
			notes: {
				situation: "Errors lose their context before they reach the caller.",
				capability: "The developer can trace which failure details reach a caller.",
				evidenceSummary: "The catch in ‘Handle failed requests’ returns without the error details.",
				inConversationLearning:
					"The developer explains which details a caller needs to identify the cause.",
				alreadySaid: "Heph said earlier that the catch drops the error.",
			},
		});

		// A current claim delivered before capture stands for both reasons.
		await stored(withhold("CONVERSATION", current));
		await stored(withhold("CONVERSATION", current, "NO_MATERIAL_CHANGE"));
		// A stale claim's words were said, but are no standing advice.
		await stored(withhold("PRACTICE_PAGE", stale));
		await refused(
			withhold("PRACTICE_PAGE", stale, "NO_MATERIAL_CHANGE"),
			notOnRecord("NO_MATERIAL_CHANGE", "PRACTICE_PAGE"),
		);
		// A witness on another destination is none here.
		await refused(withhold("PRACTICE_PAGE", current), /Witnesses must name delivered feedback/u);

		// Delivered after this work was captured, or with no known capture time: said, but nothing the work could answer.
		for (const capturedAt of ["2026-10-01T00:00:00Z", null]) {
			const late = stagePrivateHistory(admission.privateHistory, files, capturedAt);
			await stored(withhold("CONVERSATION", current), late);
			await refused(
				withhold("CONVERSATION", current, "NO_MATERIAL_CHANGE"),
				notOnRecord("NO_MATERIAL_CHANGE", "CONVERSATION"),
				late,
			);
		}
		// Read before either delivery: ALREADY_SAID has no communication witness.
		const early = stagePrivateHistory(
			{ ...admission.privateHistory, readAt: "2026-09-01T00:00:00Z" },
			files,
			captured,
		);
		await refused(
			withhold("PRACTICE_PAGE", stale),
			notOnRecord("ALREADY_SAID", "PRACTICE_PAGE"),
			early,
		);
		await refused(
			withhold("CONVERSATION", current),
			notOnRecord("ALREADY_SAID", "CONVERSATION"),
			early,
		);
		const restaged = stagePrivateHistory(admission.privateHistory, files, captured);

		const replaced = await send(replacement(queued), restaged);
		assert.equal(replaced.feedback.feedback.length, 1, replaced.reply);
		assert.equal(replaced.feedback.feedback[0]?.supersedesThreadKey, queued);
		assert.deepEqual(
			replaced.feedback.preparedTargets.map((target) => target.feedbackId),
			[queued],
		);
		const forged = await send(replacement("00000000-0000-4000-8000-000000000009"));
		assert.deepEqual(forged.feedback.feedback, []);
		assert.match(forged.reply, /No queued message has threadKey/u);
	} finally {
		rmSync(cwd, { recursive: true, force: true });
	}
});

void test("private copy has soft targets, refuses excess with an example, and accepts a corrected piece", async () => {
	const admission = parseAdmission(
		JSON.parse(readFileSync(new URL("admission-response.json", import.meta.url), "utf8")),
	);
	const result = admission.results[0];
	assert.ok(result);
	const card = {
		destination: "PRACTICE_PAGE",
		practiceSlug: result.practiceSlug,
		basedOn: [result.resultId],
		action: "NEW",
		title: "Keep failure context",
		body: "Failures lose their context across these changes. This makes the cause hard to trace.",
		nextStep: "Before returning an error, check that its context reaches the caller.",
	};
	const conversation = {
		destination: "CONVERSATION",
		practiceSlug: result.practiceSlug,
		basedOn: [result.resultId],
		action: "NEW",
		title: "Keep failure context",
		notes: {
			situation: "Errors lose their context before they reach the caller.",
			capability: "The developer can trace which failure details reach a caller.",
			evidenceSummary: "The catch in ‘Handle failed requests’ returns without the error details.",
			inConversationLearning:
				"The developer explains which details a caller needs to identify the cause.",
			alreadySaid: "Earlier practice-page feedback asked for failure context.",
		},
	};
	for (const [field, target] of [
		["body", 600],
		["nextStep", 300],
		["situation", 300],
		["capability", 300],
		["evidenceSummary", 600],
		["inConversationLearning", 300],
		["alreadySaid", 300],
	] as const) {
		for (const length of [target, target + 1, target * 2, target * 2 + 1]) {
			const feedback: ComposedFeedback = {
				contractVersion: 3,
				admissionDigest: admission.digest,
				compositionFailures: [],
				preparedTargets: [],
				preparation: null,
				results: [],
				review: null,
				feedback: [],
			};
			const tool = buildFeedbackTool(
				[result.practiceSlug],
				{
					contractVersion: 3,
					destinations: { PRACTICE_PAGE: { maxPieces: 1 }, CONVERSATION: { maxPieces: 1 } },
					minDistinctItems: 2,
					onWorkCopyForms: [],
				},
				[result],
				[],
				new Map(),
				{
					feedback,
					admitted: () => true,
					routes: () => ["PRACTICE_PAGE", "CONVERSATION"],
					destinationRefusal: () => "",
					negativeItems: () => 2,
					documentedShape: (schema) => schema,
					listSchema: (items) => ({ type: "array", items }),
					submittedList: (value) =>
						Array.isArray(value) ? { items: value } : { error: "Expected list" },
					refusal: async (_id, text) => ({
						content: [{ type: "text", text }],
						details: { stored: 0 },
					}),
					persist: () => undefined,
					paid: () => true,
					onStored: () => undefined,
				},
			);
			const text = "x".repeat(length);
			const isCard = field === "body" || field === "nextStep";
			const piece = isCard
				? { ...card, [field]: text }
				: { ...conversation, notes: { ...conversation.notes, [field]: text } };
			const send = async (value: unknown) => {
				const reply: unknown = await Reflect.apply(tool.execute.bind(tool), tool, [
					"copy",
					tool.prepareArguments?.({ feedback: [value] }),
				]);
				return reply;
			};
			const reply: unknown = await send(piece);
			if (length > target * 2) {
				assert.deepEqual(feedback.feedback, []);
				assert.match(JSON.stringify(reply), /Corrected text example \(synthetic/u);
				assert.ok(!JSON.stringify(reply).includes(text));
				await send(isCard ? card : conversation);
			}
			const stored = feedback.feedback[0];
			assert.ok(stored, `${field} at ${length}: ${JSON.stringify(reply)}`);
			assert.deepEqual(stored.basedOn, [{ resultId: result.resultId }]);
			if (length <= target * 2) {
				assert.equal(isCard ? stored[field] : stored.notes?.[field], text);
			} else if (isCard) {
				assert.equal(stored.title, card.title);
				assert.equal(stored.body, card.body);
				assert.equal(stored.nextStep, card.nextStep);
			}
		}
	}
});

void test("preparation retains exact copy support, the second citation, quiet witnesses and page fields", async (t) => {
	const { prepareComposedFeedback } =
		await import("../../../main/resources/agent/pi-composition-stage.ts");
	const resultId = "00000000-0000-4000-8000-000000000001";
	const quietId = "00000000-0000-4000-8000-000000000002";
	const problemId = "00000000-0000-4000-8000-000000000003";
	const previousId = "00000000-0000-4000-8000-000000000004";
	const support = [{ resultId, problemId }];
	const feedback: ComposedFeedback = {
		contractVersion: 3,
		admissionDigest: "a".repeat(64),
		compositionFailures: [],
		preparedTargets: [
			{
				threadKey: "queued-token",
				feedbackId: previousId,
				destination: "CONVERSATION",
				practiceSlug: "tokens",
			},
		],
		preparation: null,
		results: [
			{ resultId, practiceSlug: "tokens", outcome: "NOT_MET", problems: [], citations: [] },
		],
		review: {
			summary: null,
			inline: [
				{
					body: "Check the token here.",
					basedOn: support,
					anchor: { problemId, citationIndex: 1 },
				},
			],
			withheld: [
				{
					basedOn: [{ resultId: quietId }],
					reason: "ALREADY_SAID",
					witnessIds: ["github:review-comment:7"],
				},
			],
		},
		feedback: [
			{
				destination: "PRACTICE_PAGE",
				practiceSlug: "tokens",
				action: "NEW",
				basedOn: support,
				title: "Guard the token boundary",
				body: "Unchecked tokens reach the handler across these changes.",
				nextStep: "Check invalid tokens before forwarding.",
			},
			{
				destination: "CONVERSATION",
				practiceSlug: "tokens",
				action: "SUPERSEDE",
				basedOn: support,
				title: "Check token boundaries",
				supersedesThreadKey: "queued-token",
				notes: {
					situation: "Unchecked tokens reach the handler.",
					capability: "Recognize the trust boundary.",
					evidenceSummary: "The handler forwards an unchecked token.",
					inConversationLearning: "Explain where token checks belong.",
				},
			},
		],
	};
	const requests: unknown[] = [];
	t.mock.method(globalThis, "fetch", async (_input: unknown, init: RequestInit) => {
		assert.ok(typeof init.body === "string");
		requests.push(JSON.parse(init.body));
		return Response.json({
			feedbackIds: [],
			contentDigest: "a".repeat(64),
			preparedAt: "2026-10-15T00:00:00Z",
		});
	});
	await prepareComposedFeedback({
		feedback,
		admitted: true,
		expired: false,
		url: "https://example.test/prepare",
		token: "synthetic",
		task: { reviewId: resultId, jobId: quietId, attempt: 0 },
		timeoutMs: 1000,
		persist: () => undefined,
		recordFailure: () => assert.fail("valid packages must be prepared"),
	});
	assert.equal(requests.length, 3);
	const text = JSON.stringify(requests);
	assert.match(text, /"citationIndex":1/u);
	assert.match(text, /"witnessIds":\["github:review-comment:7"\]/u);
	assert.match(
		text,
		/"page":\{"title":"Guard the token boundary","body":"Unchecked tokens reach the handler across these changes\.","nextStep":"Check invalid tokens before forwarding\."\}/u,
	);
	assert.match(text, new RegExp(`"replacesId":"${previousId}"`, "u"));
});

void test("a refused private package preserves accepted public feedback", async (t) => {
	const { prepareComposedFeedback } =
		await import("../../../main/resources/agent/pi-composition-stage.ts");
	const resultId = "00000000-0000-4000-8000-000000000001";
	const feedback: ComposedFeedback = {
		contractVersion: 3,
		admissionDigest: "a".repeat(64),
		compositionFailures: [],
		preparedTargets: [],
		preparation: null,
		results: [
			{ resultId, practiceSlug: "tokens", outcome: "NOT_MET", problems: [], citations: [] },
		],
		review: {
			summary: { body: "Check the token before forwarding.", basedOn: [{ resultId }] },
			inline: [],
			withheld: [],
		},
		feedback: [
			{
				destination: "CONVERSATION",
				practiceSlug: "tokens",
				action: "NEW",
				basedOn: [{ resultId }],
				title: "Token boundaries",
				notes: {
					situation: "Unchecked tokens.",
					capability: "Locate the boundary.",
					evidenceSummary: "The handler forwards unchecked tokens.",
					inConversationLearning: "Explain the boundary.",
				},
			},
		],
	};
	let calls = 0;
	t.mock.method(globalThis, "fetch", async () => {
		calls += 1;
		return calls === 1
			? Response.json({
					feedbackIds: [],
					contentDigest: "a".repeat(64),
					preparedAt: "2026-10-15T00:00:00Z",
				})
			: new Response(null, { status: 409 });
	});
	const failures: string[] = [];
	await prepareComposedFeedback({
		feedback,
		admitted: true,
		expired: false,
		url: "https://example.test/prepare",
		token: "synthetic",
		task: { reviewId: resultId, jobId: resultId, attempt: 0 },
		timeoutMs: 1000,
		persist: () => undefined,
		recordFailure: (phase, reason) => {
			failures.push(`${phase}:${reason}`);
		},
	});
	assert.ok(feedback.review?.summary);
	assert.equal(feedback.preparation?.contentDigest, "a".repeat(64));
	assert.deepEqual(feedback.feedback, []);
	assert.deepEqual(failures, ["PRIVATE_FEEDBACK:SERVER_REFUSED"]);
});

void test("the card tool and preparation share the server's title/body/nextStep fixture", async (t) => {
	const { prepareComposedFeedback } =
		await import("../../../main/resources/agent/pi-composition-stage.ts");
	const fixture: unknown = JSON.parse(
		readFileSync(new URL("composer-card.json", import.meta.url), "utf8"),
	);
	const { z } =
		await import("../../../main/resources/agent/work/practice-scripts/lib/core/practice-script.ts");
	const card = z
		.object({
			reported: z.record(z.string(), z.unknown()),
			prepared: z.record(z.string(), z.unknown()),
		})
		.parse(fixture);
	const admission = parseAdmission(
		JSON.parse(readFileSync(new URL("admission-response.json", import.meta.url), "utf8")),
	);
	const result = admission.results[0];
	assert.ok(result);
	const prepared = structuredClone(card.prepared);
	// This admission fixture supplies the result identity used by the shared card fixture.
	assert.equal(result.resultId, "30000000-0000-4000-8000-000000000001");
	result.practiceSlug = "tokens";
	const feedback: ComposedFeedback = {
		contractVersion: 3,
		admissionDigest: admission.digest,
		compositionFailures: [],
		preparedTargets: [],
		preparation: null,
		results: [
			{
				resultId: result.resultId,
				practiceSlug: "tokens",
				outcome: "NOT_MET",
				problems: [],
				citations: [],
			},
		],
		review: null,
		feedback: [],
	};
	const tool = buildFeedbackTool(
		["tokens"],
		{
			contractVersion: 3,
			destinations: { PRACTICE_PAGE: { maxPieces: 1 } },
			minDistinctItems: 2,
			onWorkCopyForms: [],
		},
		[result],
		[],
		new Map(),
		{
			feedback,
			admitted: () => true,
			routes: () => ["PRACTICE_PAGE"],
			destinationRefusal: () => "",
			negativeItems: () => 2,
			documentedShape: (schema) => schema,
			listSchema: (items) => ({ type: "array", items }),
			submittedList: (value) =>
				Array.isArray(value) ? { items: value } : { error: "Expected list" },
			refusal: async (_id, text) => ({ content: [{ type: "text", text }], details: { stored: 0 } }),
			persist: () => undefined,
			paid: () => true,
			onStored: () => undefined,
		},
	);
	assert.ok(tool.prepareArguments);
	for (const wrapper of ["args", "arguments", "input"]) {
		assert.deepEqual(tool.prepareArguments({ [wrapper]: { feedback: [card.reported] } }), {
			feedback: [card.reported],
		});
	}
	assert.throws(
		() => tool.prepareArguments?.({ feedback: "card" }),
		/feedback.*Expected array.*Corrected field example/u,
	);
	const quietPiece = {
		destination: "PRACTICE_PAGE",
		practiceSlug: "tokens",
		basedOn: [result.resultId],
		action: "WITHHOLD",
		withholdReason: "BELOW_BAR",
		witnessIds: null,
		title: null,
		body: null,
		nextStep: null,
		notes: null,
	};
	assert.deepEqual(tool.prepareArguments({ feedback: [quietPiece] }), {
		feedback: [
			{
				destination: "PRACTICE_PAGE",
				practiceSlug: "tokens",
				basedOn: [result.resultId],
				action: "WITHHOLD",
				withholdReason: "BELOW_BAR",
				witnessIds: [],
			},
		],
	});
	const reply = z
		.object({ details: z.object({ stored: z.number() }) })
		.parse(
			await Reflect.apply(tool.execute.bind(tool), tool, ["card", { feedback: [card.reported] }]),
		);
	assert.equal(reply.details.stored, 1, JSON.stringify(reply));
	t.mock.method(globalThis, "fetch", async (_input: unknown, init: RequestInit) => {
		assert.ok(typeof init.body === "string");
		const posted = z
			.object({ composition: z.array(z.record(z.string(), z.unknown())) })
			.parse(JSON.parse(init.body));
		assert.deepEqual(posted.composition, [prepared]);
		return Response.json({
			feedbackIds: [],
			contentDigest: "a".repeat(64),
			preparedAt: "2026-10-15T00:00:00Z",
		});
	});
	await prepareComposedFeedback({
		feedback,
		admitted: true,
		expired: false,
		url: "https://example.test/prepare",
		token: "synthetic",
		task: { reviewId: result.resultId, jobId: result.resultId, attempt: 0 },
		timeoutMs: 1000,
		persist: () => undefined,
		recordFailure: () => assert.fail("the card should prepare"),
	});
});

void test("report_review accepts one wrapper and null unused lists, and explains wrong text before storing a correction", async () => {
	for (const wrapper of ["args", "arguments", "input"]) {
		const resultId = "123e4567-e89b-42d3-a456-426614174000";
		const feedback: ComposedFeedback = {
			contractVersion: 3,
			admissionDigest: "a".repeat(64),
			compositionFailures: [],
			results: [],
			preparedTargets: [],
			feedback: [],
			review: null,
			preparation: null,
		};
		const state: PublicReviewState = { final: false, reviewContext: "HELD" };
		const tool = buildReviewTool(
			false,
			new Map([[resultId, { practiceSlug: "authentication", outcome: "NOT_MET", problems: [] }]]),
			new Map(),
			state,
			() => "",
			{
				admitted: () => true,
				feedback,
				persist: () => undefined,
				onStored: () => undefined,
				refusal: async (_id, text) => {
					throw new Error(text);
				},
			},
		);
		assert.ok(tool.prepareArguments);
		const quiet = {
			decisions: [{ resultId, disposition: "BELOW_BAR", witnessIds: null }],
			inline: null,
		};
		assert.throws(
			() =>
				tool.prepareArguments?.({
					...quiet,
					decisions: [{ resultId, disposition: "BELOW_BAR", witnessIds: [] }],
					inline: [],
					summary: "No concern.",
				}),
			/summary.*Expected.*Corrected field example/u,
		);
		assert.throws(
			() =>
				tool.prepareArguments?.({
					...quiet,
					decisions: [{ resultId, disposition: "BELOW_BAR", witnessIds: [] }],
					inline: [],
					summary: null,
					unknown: true,
				}),
			/unknown.*only.*decisions.*Corrected field example/u,
		);
		const prepared = tool.prepareArguments({ [wrapper]: quiet });
		const reply: unknown = await Reflect.apply(tool.execute.bind(tool), tool, ["quiet", prepared]);
		assert.ok(typeof reply === "object" && reply !== null);
		const details: unknown = Reflect.get(reply, "details");
		assert.ok(typeof details === "object" && details !== null);
		assert.equal(Reflect.get(details, "stored"), 1);
		assert.equal(state.final, true);
		assert.deepEqual(feedback.review, {
			summary: null,
			inline: [],
			withheld: [{ basedOn: [{ resultId }], reason: "BELOW_BAR", witnessIds: [] }],
		});
	}
});

for (const failure of ["setup", "deadline"] as const) {
	void test(`public preparation finishes before a private ${failure} failure`, async (t) => {
		const { composeAdmittedFeedback, prepareComposedFeedback } =
			await import("../../../main/resources/agent/pi-composition-stage.ts");
		const admission = parseAdmission(
			JSON.parse(readFileSync(new URL("admission-response.json", import.meta.url), "utf8")),
		);
		const feedback: ComposedFeedback = {
			compositionFailures: [],
			contractVersion: 3,
			admissionDigest: admission.digest,
			results: [],
			preparedTargets: [],
			feedback: [],
			review: null,
			preparation: null,
		};
		const requests: unknown[] = [];
		t.mock.method(globalThis, "fetch", async (_input: unknown, init: RequestInit) => {
			assert.ok(typeof init.body === "string");
			requests.push(JSON.parse(init.body));
			return Response.json({
				feedbackIds: [],
				contentDigest: "a".repeat(64),
				preparedAt: "2026-10-15T00:00:00Z",
			});
		});
		const run = composeAdmittedFeedback({
			phases: new Set(["PUBLIC_REVIEW", "PRIVATE_FEEDBACK"]),
			request: {
				contractVersion: 3,
				destinations: { ON_WORK: { maxPieces: 1 }, CONVERSATION: { maxPieces: 1 } },
				minDistinctItems: 2,
				onWorkCopyForms: [],
			},
			admission,
			feedback,
			routes: null,
			notReached: [],
			remainingMs: 500,
			onDeadline: () => undefined,
			stage: {
				composeReview: async () => {
					feedback.review = {
						summary: {
							body: "Check the token boundary.",
							basedOn: [
								{ resultId: admission.results[0]?.resultId ?? assert.fail("fixture has results") },
							],
						},
						inline: [],
						withheld: [],
					};
					return "DONE";
				},
				composePrivately: async (_request, safety) => {
					assert.equal(
						requests.length,
						1,
						"the public package must be durable before private setup",
					);
					assert.ok(feedback.preparation !== null);
					if (failure === "setup") {
						throw new Error("private session setup failed");
					}
					await safety.elapsed;
				},
			},
			prepare: async (safety, phase) =>
				prepareComposedFeedback({
					feedback,
					phase,
					admitted: true,
					expired: safety.expired(),
					url: "https://example.test/prepare",
					token: "synthetic",
					task: { reviewId: "review", jobId: "job", attempt: 1 },
					timeoutMs: 100,
					persist: () => undefined,
					recordFailure: () => assert.fail("public preparation must succeed"),
				}),
			persist: () => undefined,
			after: () => undefined,
		});
		await run;
		assert.equal(requests.length, 1);
		assert.partialDeepStrictEqual(requests[0], { publicComposition: true });
		assert.ok(feedback.review !== null);
		assert.ok(feedback.preparation !== null);
		assert.deepEqual(feedback.compositionFailures, [
			{ phase: "PRIVATE_FEEDBACK", reason: failure === "setup" ? "RUNTIME_ERROR" : "SAFETY" },
		]);
	});
}

void test("only changed public history composes again in fresh sessions, within the bound; an unknown delivery never does", async () => {
	const { composeAdmittedFeedback, MAX_PUBLIC_RECOMPOSITIONS } =
		await import("../../../main/resources/agent/pi-composition-stage.ts");
	const admission = parseAdmission(
		JSON.parse(readFileSync(new URL("admission-response.json", import.meta.url), "utf8")),
	);
	for (const [refusal, settles, expectedSessions] of [
		["PUBLIC_HISTORY_CHANGED", true, 2],
		["PUBLIC_HISTORY_CHANGED", false, 1 + MAX_PUBLIC_RECOMPOSITIONS],
		["PUBLIC_DELIVERY_UNRESOLVED", false, 1],
	] as const) {
		const feedback: ComposedFeedback = {
			compositionFailures: [],
			contractVersion: 3,
			admissionDigest: admission.digest,
			results: [],
			preparedTargets: [],
			feedback: [],
			review: null,
			preparation: null,
		};
		let sessions = 0;
		const phases: string[] = [];
		await composeAdmittedFeedback({
			phases: new Set(["PUBLIC_REVIEW", "PRIVATE_FEEDBACK"]),
			request: {
				contractVersion: 3,
				destinations: { ON_WORK: { maxPieces: 1 } },
				minDistinctItems: 2,
				onWorkCopyForms: [],
			},
			admission,
			feedback,
			routes: null,
			notReached: [],
			remainingMs: 5000,
			onDeadline: () => undefined,
			stage: {
				composeReview: async () => {
					sessions += 1;
					return settles && sessions === 2 ? "DONE" : { refused: refusal };
				},
				composePrivately: async () => undefined,
			},
			prepare: async (_safety, phase) => {
				phases.push(phase);
			},
			persist: () => undefined,
			after: () => undefined,
		});
		assert.equal(sessions, expectedSessions, refusal);
		assert.deepEqual(
			feedback.compositionFailures,
			settles ? [] : [{ phase: "PUBLIC_REVIEW", reason: refusal }],
		);
		assert.deepEqual(phases, ["PUBLIC_REVIEW", "PRIVATE_FEEDBACK"]);
	}
});

for (const launch of ["PRIVATE_FEEDBACK", "PUBLIC_REVIEW"] as const) {
	void test(`a launch for ${launch} composes and prepares that phase only`, async () => {
		const { composeAdmittedFeedback } =
			await import("../../../main/resources/agent/pi-composition-stage.ts");
		const admission = parseAdmission(
			JSON.parse(readFileSync(new URL("admission-response.json", import.meta.url), "utf8")),
		);
		const feedback: ComposedFeedback = {
			compositionFailures: [],
			contractVersion: 3,
			admissionDigest: admission.digest,
			results: [],
			preparedTargets: [],
			feedback: [],
			review: null,
			preparation: null,
		};
		const composed: string[] = [];
		const prepared: string[] = [];
		await composeAdmittedFeedback({
			phases: new Set([launch]),
			request: {
				contractVersion: 3,
				destinations: { ON_WORK: { maxPieces: 1 }, CONVERSATION: { maxPieces: 1 } },
				minDistinctItems: 2,
				onWorkCopyForms: [],
			},
			admission,
			feedback,
			routes: null,
			notReached: [],
			remainingMs: 5000,
			onDeadline: () => undefined,
			stage: {
				composeReview: async () => {
					composed.push("PUBLIC_REVIEW");
					return "DONE";
				},
				composePrivately: async () => {
					composed.push("PRIVATE_FEEDBACK");
				},
			},
			prepare: async (_safety, phase) => {
				prepared.push(phase);
			},
			persist: () => undefined,
			after: () => undefined,
		});
		assert.deepEqual(composed, [launch]);
		assert.deepEqual(prepared, [launch]);
	});

	void test(`a launch for ${launch} at its ceiling owes the review on the work and only its own private feedback`, async () => {
		const { composeAdmittedFeedback } =
			await import("../../../main/resources/agent/pi-composition-stage.ts");
		const admission = parseAdmission(
			JSON.parse(readFileSync(new URL("admission-response.json", import.meta.url), "utf8")),
		);
		const feedback: ComposedFeedback = {
			compositionFailures: [],
			contractVersion: 3,
			admissionDigest: admission.digest,
			results: [],
			preparedTargets: [],
			feedback: [],
			review: null,
			preparation: null,
		};
		await composeAdmittedFeedback({
			phases: new Set([launch]),
			request: {
				contractVersion: 3,
				destinations: { ON_WORK: { maxPieces: 1 }, CONVERSATION: { maxPieces: 1 } },
				minDistinctItems: 2,
				onWorkCopyForms: [],
			},
			admission,
			feedback,
			routes: null,
			notReached: [],
			remainingMs: 0,
			onDeadline: () => undefined,
			stage: {
				composeReview: async () => assert.fail("no time is left to compose"),
				composePrivately: async () => assert.fail("no time is left to compose"),
			},
			prepare: async () => assert.fail("nothing was composed to prepare"),
			persist: () => undefined,
			after: () => undefined,
		});
		// The review on the work stays owed from either launch, so a later attempt can compose it in its own.
		assert.deepEqual(
			feedback.compositionFailures,
			launch === "PRIVATE_FEEDBACK"
				? [
						{ phase: "PUBLIC_REVIEW", reason: "SAFETY" },
						{ phase: "PRIVATE_FEEDBACK", reason: "SAFETY" },
					]
				: [{ phase: "PUBLIC_REVIEW", reason: "SAFETY" }],
		);
	});
}

const composedWithFailures = (
	failures: ComposedFeedback["compositionFailures"],
): ComposedFeedback => ({
	compositionFailures: failures,
	contractVersion: 3,
	admissionDigest: "a".repeat(64),
	results: [],
	preparedTargets: [],
	feedback: [],
	review: null,
	preparation: null,
});

void test("only composition the run stopped is owed: the review on the work from the admitting attempt, private feedback once a review exists", () => {
	for (const reason of ["SAFETY", "STALL"] as const) {
		for (const admitting of [true, false]) {
			for (const reviewOnWork of [true, false]) {
				// Private feedback is owed from any attempt, but only once a review on the work exists.
				assert.equal(
					compositionOwed(composedWithFailures([{ phase: "PRIVATE_FEEDBACK", reason }]), {
						admitting,
						reviewOnWork,
					}),
					reviewOnWork,
					reason,
				);
				// The review on the work the run stopped: one more attempt from the admitting attempt, never from its
				// continuation, whatever happened to private feedback.
				for (const failures of [
					[{ phase: "PUBLIC_REVIEW", reason }],
					[
						{ phase: "PUBLIC_REVIEW", reason },
						{ phase: "PRIVATE_FEEDBACK", reason: "SAFETY" },
					],
				] as const) {
					assert.equal(
						compositionOwed(composedWithFailures([...failures]), { admitting, reviewOnWork }),
						admitting,
						reason,
					);
				}
			}
		}
	}
	for (const reason of [
		"MODEL_ERROR",
		"RUNTIME_ERROR",
		"BUDGET",
		"LOOP",
		"NO_DECISION",
		"SERVER_REFUSED",
		"PUBLIC_DELIVERY_UNRESOLVED",
		"PUBLIC_HISTORY_CHANGED",
	] as const) {
		const owed = { admitting: true, reviewOnWork: true };
		assert.equal(
			compositionOwed(composedWithFailures([{ phase: "PRIVATE_FEEDBACK", reason }]), owed),
			false,
			reason,
		);
		// A typed or work-bound outcome of the review on the work is final, even if private feedback ran out of time.
		assert.equal(
			compositionOwed(
				composedWithFailures([
					{ phase: "PUBLIC_REVIEW", reason },
					{ phase: "PRIVATE_FEEDBACK", reason: "SAFETY" },
				]),
				owed,
			),
			false,
			reason,
		);
	}
	assert.equal(
		compositionOwed(composedWithFailures([]), { admitting: true, reviewOnWork: true }),
		false,
	);
});
