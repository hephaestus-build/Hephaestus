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

import { mentorStreamCompleted } from "./lib/mentor.js";

export { handleSummary } from "./lib/summary.js";

const workspace = required("WORKSPACE_SLUG");
const mentorVUs = integer("MENTOR_VUS", 5);
const reviewVUs = integer("REVIEW_VUS", 2);
const reviewRequests = integer("REVIEW_REQUESTS", reviewVUs);
const reviewTimeoutSeconds = integer("REVIEW_TIMEOUT_SECONDS", 1200);
const reviewJobDuration = new Trend("review_job_duration", true);
const reviewJobsFinished = new Counter("review_jobs_finished");
const mentorCompleted = new Rate("mentor_completed");
const reviewJobsCompleted = new Rate("review_jobs_completed");
const terminalStatuses = new Set(["COMPLETED", "FAILED", "TIMED_OUT", "CANCELLED"]);
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
		"http_req_failed{operation:review_request}": ["rate==0"],
		"checks{scenario:mentor_sessions}": ["rate>0.99"],
		"checks{scenario:practice_detection}": ["rate==1"],
		"http_req_duration{operation:mentor_turn}": ["p(95)<120000"],
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

export function mentorTurn() {
	return JSON.stringify({
		message: {
			id: crypto.randomUUID(),
			role: "user",
			parts: [{ type: "text", text: "Summarize my current practice priorities." }],
		},
	});
}

export function mentor() {
	const response = http.post(
		`${api}/workspaces/${encodeURIComponent(workspace)}/mentor/chat`,
		mentorTurn(),
		{ headers, tags: { operation: "mentor_turn" }, timeout: "10m" },
	);
	mentorCompleted.add(
		check(response, {
			"mentor returned HTTP 200": (result) => result.status === 200,
			"mentor stream completed without errors": (result) => mentorStreamCompleted(result.body),
		}),
	);
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
