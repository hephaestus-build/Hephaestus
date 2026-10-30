import { check, sleep } from "k6";
import http from "k6/http";
import { Counter, Rate, Trend } from "k6/metrics";

import { apiBaseUrl, integer, jsonField, required } from "./lib/config.js";

export { handleSummary } from "./lib/summary.js";

/**
 * Heph alone: simulated developers, each with a main conversation and parallel threads. Every VU is one
 * lane of one developer — lane 0 asks at the top level, lanes 1..THREADS_PER_DEVELOPER each ask in their
 * own thread — so a developer has as many turns in flight as Heph allows one person.
 */
const workspace = required("WORKSPACE_SLUG");
const tokens = (__ENV.AUTH_TOKENS ?? required("AUTH_TOKEN"))
	.split(",")
	.map((value) => value.trim())
	.filter((value) => value !== "");
const lanes = 1 + Number(__ENV.THREADS_PER_DEVELOPER ?? "2");
const pollMs = integer("POLL_MS", 500);
const turnTimeoutSeconds = integer("TURN_TIMEOUT_SECONDS", 600);
const turnP95Ms = integer("TURN_P95_MS", 120_000);
const firstPartialP95Ms = integer("FIRST_PARTIAL_P95_MS", 15_000);
const question =
	__ENV.QUESTION ??
	"In three sentences: what is one habit that makes my pull requests easier to review?";

/** Developers per stage, `<developers>:<duration>` joined by commas, for example `1:2m,5:3m,15:3m`. */
const stages = (__ENV.STAGES ?? "1:2m").split(",").map((stage) => {
	const [developers, duration] = stage.split(":");
	if (!/^\d+$/u.test(developers ?? "") || !/^\d+[smh]$/u.test(duration ?? "")) {
		throw new Error("STAGES must look like 1:2m,5:3m");
	}
	return { target: Number(developers) * lanes, duration };
});
const peakDevelopers = Math.max(...stages.map((stage) => stage.target / lanes));
if (!Number.isInteger(lanes) || lanes < 1 || lanes > 10) {
	throw new Error("THREADS_PER_DEVELOPER must be 0..9");
}
if (tokens.length < peakDevelopers) {
	throw new Error(`AUTH_TOKENS needs one token per developer: ${peakDevelopers} at the peak`);
}

const api = apiBaseUrl();
const turnDuration = new Trend("heph_turn_duration", true);
const firstPartial = new Trend("heph_first_partial", true);
const firstText = new Trend("heph_first_text", true);
const queueWait = new Trend("heph_queue_wait", true);
const completed = new Rate("heph_turn_completed");
const finished = new Counter("heph_turns_finished");
const refused = new Counter("heph_turn_refused");

export const options = {
	discardResponseBodies: false,
	maxRedirects: 0,
	scenarios: {
		heph: {
			executor: "ramping-vus",
			startVUs: 0,
			stages,
			gracefulRampDown: `${turnTimeoutSeconds}s`,
			gracefulStop: `${turnTimeoutSeconds}s`,
		},
	},
	thresholds: {
		"http_req_failed{operation:heph_turn}": ["rate<0.01"],
		"http_req_failed{operation:heph_poll}": ["rate<0.01"],
		"http_req_duration{operation:heph_turn}": ["p(95)<1000"],
		heph_turn_completed: ["rate>0.99"],
		heph_turns_finished: ["count>0"],
		heph_turn_duration: [`p(95)<${turnP95Ms}`],
		heph_first_partial: [`p(95)<${firstPartialP95Ms}`],
	},
};

/** Every API path a VU calls, as `server/openapi.yaml` names it. */
export const hephPaths = {
	conversation: "/workspaces/{workspaceSlug}/mentor/conversation",
	turns: "/workspaces/{workspaceSlug}/mentor/turns",
	entries: "/workspaces/{workspaceSlug}/mentor/entries",
	thread: "/workspaces/{workspaceSlug}/mentor/entries/{entryId}/thread",
};

/**
 * @param {string} path - one of `hephPaths`
 * @param {string} [entryId] - the `{entryId}` of a thread path
 * @returns {string} the URL of the path in this run's workspace
 */
function url(path, entryId = "") {
	return (
		api +
		path
			.replace("{workspaceSlug}", encodeURIComponent(workspace))
			.replace("{entryId}", encodeURIComponent(entryId))
	);
}

const developer = Math.floor((__VU - 1) / lanes);
const lane = (__VU - 1) % lanes;
const headers = {
	Authorization: `Bearer ${tokens[developer % tokens.length] ?? ""}`,
	"Content-Type": "application/json",
};

/**
 * The top-level question this lane's thread hangs off; lane 0 never sets it.
 * @type {string | null}
 */
let threadParent = null;

/**
 * Reads where the turn's question ended from the page that holds it.
 * @param {string} questionId
 * @param {string | null} parentEntryId
 * @returns {string | null} the terminal turn status, or null while it has none
 */
function terminalStatus(questionId, parentEntryId) {
	const page =
		parentEntryId === null
			? `${url(hephPaths.entries)}?limit=20`
			: `${url(hephPaths.thread, parentEntryId)}?limit=20`;
	const listed = http.get(page, { headers, tags: { operation: "heph_poll" } });
	const status =
		listed.status === 200 ? jsonField(listed, `entries.#(id=="${questionId}").turnStatus`) : null;
	return typeof status === "string" && ["DONE", "FAILED", "STOPPED"].includes(status)
		? status
		: null;
}

/**
 * Records the first time an active turn runs, shows a partial answer, and shows text.
 * @param {import("k6").JSONValue} turn - the turn as the conversation lists it
 * @param {{ running: boolean, partial: boolean, text: boolean }} seen - what this turn already recorded
 * @param {number} startedAt - when the question was asked
 */
function observe(turn, seen, startedAt) {
	if (turn === null || typeof turn !== "object" || Array.isArray(turn)) {
		return;
	}
	const elapsed = Date.now() - startedAt;
	if (!seen.running && turn.status === "RUNNING") {
		seen.running = true;
		queueWait.add(elapsed);
	}
	const snapshot = turn.partial;
	if (snapshot === null || typeof snapshot !== "object" || Array.isArray(snapshot)) {
		return;
	}
	if (!seen.partial) {
		seen.partial = true;
		firstPartial.add(elapsed);
	}
	if (!seen.text && typeof snapshot.text === "string" && snapshot.text !== "") {
		seen.text = true;
		firstText.add(elapsed);
	}
}

/**
 * Asks once and follows the turn through the conversation's active turns until it ends.
 * @param {string | null} parentEntryId - null asks at the top level
 * @param {string} [text] - the synthetic question, or the configured question
 * @param {string} [requestId] - an optional synthetic correlation ID
 * @returns {string | null} the question's entry id when Heph answered
 */
export function ask(parentEntryId, text = question, requestId = crypto.randomUUID()) {
	const startedAt = Date.now();
	const deadline = startedAt + turnTimeoutSeconds * 1000;
	const body = JSON.stringify({
		requestId,
		text,
		...(parentEntryId === null ? {} : { parentEntryId }),
	});
	const submitted = http.post(url(hephPaths.turns), body, {
		headers,
		tags: { operation: "heph_turn" },
		responseCallback: http.expectedStatuses(200, 409),
	});
	if (submitted.status === 409) {
		// The conversation already holds the follow-ups Heph allows; that is back-pressure, not an error.
		refused.add(1);
		sleep(5);
		return parentEntryId;
	}
	const turnId = jsonField(submitted, "turn.id");
	const questionId = jsonField(submitted, "question.id");
	check(submitted, { "heph turn submitted": () => typeof turnId === "string" });
	if (typeof turnId !== "string" || typeof questionId !== "string") {
		completed.add(false);
		return null;
	}
	const seen = { running: false, partial: false, text: false };
	while (Date.now() < deadline) {
		sleep(pollMs / 1000);
		const conversation = http.get(url(hephPaths.conversation), {
			headers,
			tags: { operation: "heph_poll" },
		});
		if (conversation.status !== 200) {
			continue;
		}
		const turn = jsonField(conversation, `activeTurns.#(id=="${turnId}")`);
		if (turn !== null) {
			observe(turn, seen, startedAt);
			continue;
		}
		const status = terminalStatus(questionId, parentEntryId);
		if (status === null) {
			continue;
		}
		turnDuration.add(Date.now() - startedAt);
		completed.add(status === "DONE");
		if (status === "DONE") {
			finished.add(1);
		}
		check(status, { "heph answered": (value) => value === "DONE" });
		return questionId;
	}
	completed.add(false);
	check(null, { "heph answered": () => false });
	return null;
}

export default function heph() {
	if (lane === 0) {
		ask(null);
		return;
	}
	if (threadParent === null) {
		threadParent = ask(null);
		return;
	}
	ask(threadParent);
}
