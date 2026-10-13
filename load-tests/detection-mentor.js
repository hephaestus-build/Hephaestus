import { check, sleep } from "k6";
import exec from "k6/execution";
import http from "k6/http";
import { Counter, Rate, Trend } from "k6/metrics";

import {
	apiBaseUrl,
	authHeaders,
	integer,
	jsonField,
	required,
	sharedThresholds,
} from "./lib/config.js";

export { handleSummary } from "./lib/summary.js";

const workspace = required("WORKSPACE_SLUG");
const mentorVUs = integer("MENTOR_VUS", 5);
const reviewVUs = integer("REVIEW_VUS", 2);
const reviewRequests = integer("REVIEW_REQUESTS", reviewVUs);
const reviewTimeoutSeconds = integer("REVIEW_TIMEOUT_SECONDS", 1200);
const mentorTimeoutSeconds = integer("MENTOR_TIMEOUT_SECONDS", 600);
const reviewJobDuration = new Trend("review_job_duration", true);
const reviewJobsFinished = new Counter("review_jobs_finished");
const mentorCompleted = new Rate("mentor_completed");
const mentorTurnDuration = new Trend("mentor_turn_duration", true);
const reviewJobsCompleted = new Rate("review_jobs_completed");
const terminalStatuses = new Set(["COMPLETED", "FAILED", "TIMED_OUT", "CANCELLED"]);
/** The statuses a Heph turn ends in. A `STOPPED` turn keeps what Heph wrote before the stop. */
const terminalTurnStatuses = new Set(["DONE", "FAILED", "STOPPED"]);
const api = apiBaseUrl();
const headers = authHeaders();

export const options = {
	discardResponseBodies: false,
	maxRedirects: 0,
	scenarios: {
		mentor_sessions: {
			exec: "mentor",
			executor: "constant-vus",
			vus: mentorVUs,
			duration: __ENV.DURATION ?? "10m",
			gracefulStop: "10m",
		},
		practice_detection: {
			exec: "detection",
			executor: "shared-iterations",
			vus: reviewVUs,
			iterations: reviewRequests,
			maxDuration: __ENV.REVIEW_MAX_DURATION ?? "20m",
			startTime: __ENV.REVIEW_START_TIME ?? "15s",
		},
	},
	thresholds: {
		...sharedThresholds,
		dropped_iterations: ["count==0"],
		review_jobs_finished: [`count==${reviewRequests}`],
		mentor_completed: ["rate>0.99"],
		"http_req_failed{operation:mentor_turn}": ["rate<0.01"],
		"http_req_failed{operation:mentor_status}": ["rate<0.01"],
		"http_req_failed{operation:review_request}": ["rate==0"],
		"checks{scenario:mentor_sessions}": ["rate>0.99"],
		"checks{scenario:practice_detection}": ["rate==1"],
		"http_req_duration{operation:mentor_turn}": ["p(95)<1000"],
		mentor_turn_duration: ["p(95)<120000"],
		"http_req_duration{operation:review_request}": ["p(95)<1000"],
		review_job_duration: ["p(95)<900000"],
		review_jobs_completed: ["rate==1"],
	},
};

export function setup() {
	const artifactIds = required("ARTIFACT_IDS")
		.split(",")
		.map((value) => Number(value.trim()));
	if (artifactIds.some((value) => !Number.isSafeInteger(value) || value < 1)) {
		throw new Error("ARTIFACT_IDS must be comma-separated positive integers");
	}
	if (new Set(artifactIds).size !== artifactIds.length) {
		throw new Error("ARTIFACT_IDS must be distinct");
	}
	if (reviewRequests > artifactIds.length) {
		throw new Error("ARTIFACT_IDS must contain at least REVIEW_REQUESTS distinct ids");
	}
	return { artifactIds };
}

/** Every API path that a mentor VU calls, as `server/openapi.yaml` names it. */
export const mentorPaths = {
	turns: "/workspaces/{workspaceSlug}/mentor/turns",
	entries: "/workspaces/{workspaceSlug}/mentor/entries",
	thread: "/workspaces/{workspaceSlug}/mentor/entries/{entryId}/thread",
};

/**
 * @param {string} path - one of `mentorPaths`
 * @param {string | null} [entryId] - the `{entryId}` of a thread path
 * @returns {string} the URL of the path in this run's workspace
 */
function mentorUrl(path, entryId = null) {
	return (
		api +
		path
			.replace("{workspaceSlug}", encodeURIComponent(workspace))
			.replace("{entryId}", encodeURIComponent(entryId ?? ""))
	);
}

/**
 * @param {string | null} parentEntryId - the top-level message whose thread this turn replies in
 * @returns {string} the submission body; a fresh `requestId` makes every iteration a new question
 */
function mentorTurn(parentEntryId) {
	return JSON.stringify({
		requestId: crypto.randomUUID(),
		text: "Summarize my current practice priorities.",
		...(parentEntryId === null ? {} : { parentEntryId }),
	});
}

/**
 * The top-level message this VU replies to. Each Heph thread runs at most one turn at a time, so one
 * Heph thread per VU keeps the VUs concurrent instead of queued behind each other.
 * @type {string | null}
 */
let threadParent = null;

/**
 * Asks once and waits for the answer.
 * @param {string | null} parentEntryId - null asks at the top level
 * @returns {string | null} the question's entry id when Heph answered, else null
 */
function ask(parentEntryId) {
	const turns = mentorUrl(mentorPaths.turns);
	// The newest page of the conversation the question is in; it holds the question while Heph answers it.
	const page = `${
		parentEntryId === null
			? mentorUrl(mentorPaths.entries)
			: mentorUrl(mentorPaths.thread, parentEntryId)
	}?limit=100`;
	const startedAt = Date.now();
	const deadline = startedAt + mentorTimeoutSeconds * 1000;
	// A full queue answers 409 and is waited out below, so it is not a failed request.
	const submit = {
		headers,
		tags: { operation: "mentor_turn" },
		responseCallback: http.expectedStatuses(200, 409),
	};
	let submitted = http.post(turns, mentorTurn(parentEntryId), submit);
	// The top level is shared by every VU and holds only a few queued questions; wait for room.
	while (submitted.status === 409 && jsonField(submitted, "reason") === "FOLLOW_UPS_FULL") {
		if (Date.now() >= deadline) {
			break;
		}
		sleep(5);
		submitted = http.post(turns, mentorTurn(parentEntryId), submit);
	}
	const turnId = submitted.status === 200 ? jsonField(submitted, "turn.id") : null;
	const questionId = submitted.status === 200 ? jsonField(submitted, "question.id") : null;
	check(submitted, { "mentor turn submitted": () => typeof turnId === "string" });
	if (typeof turnId !== "string" || typeof questionId !== "string") {
		mentorCompleted.add(false);
		return null;
	}

	while (Date.now() < deadline) {
		sleep(2);
		const listed = http.get(page, { headers, tags: { operation: "mentor_status" } });
		const status =
			listed.status === 200 ? jsonField(listed, `entries.#(id=="${questionId}").turnStatus`) : null;
		if (typeof status === "string" && terminalTurnStatuses.has(status)) {
			mentorTurnDuration.add(Date.now() - startedAt);
			const done = status === "DONE";
			mentorCompleted.add(done);
			check(listed, { "mentor turn answered": () => done });
			return done ? questionId : null;
		}
	}
	mentorCompleted.add(false);
	check(turnId, { "mentor turn answered": () => false });
	return null;
}

export function mentor() {
	if (threadParent === null) {
		threadParent = ask(null);
		return;
	}
	ask(threadParent);
}

/**
 * @param {ReturnType<typeof setup>} data - the artifacts `setup` validated, one per iteration
 */
export function detection(data) {
	const artifactId = data.artifactIds[exec.scenario.iterationInTest];
	const startedAt = Date.now();
	const response = http.post(
		`${api}/workspaces/${encodeURIComponent(workspace)}/practices/review-requests`,
		JSON.stringify({ artifactKind: "scm.pull-request", artifactId }),
		{ headers, tags: { operation: "review_request" } },
	);
	const submitted = response.status === 200 && jsonField(response, "status") === "SUBMITTED";
	const jobId = submitted ? jsonField(response, "jobId") : null;
	check(response, { "review request submitted": () => typeof jobId === "string" });
	if (typeof jobId !== "string") {
		reviewJobsCompleted.add(false);
		return;
	}

	const deadline = startedAt + reviewTimeoutSeconds * 1000;
	while (Date.now() < deadline) {
		sleep(2);
		const job = http.get(
			`${api}/workspaces/${encodeURIComponent(workspace)}/agents/jobs/${jobId}`,
			{ headers, tags: { operation: "review_status" } },
		);
		if (job.status !== 200) {
			continue;
		}
		const status = jsonField(job, "status");
		if (typeof status === "string" && terminalStatuses.has(status)) {
			reviewJobDuration.add(Date.now() - startedAt);
			reviewJobsCompleted.add(status === "COMPLETED");
			if (status === "COMPLETED") {
				reviewJobsFinished.add(1);
			}
			check(job, { "review job completed": () => status === "COMPLETED" });
			return;
		}
	}
	reviewJobsCompleted.add(false);
}
