import { sleep } from "k6";
import exec from "k6/execution";
import { Counter } from "k6/metrics";

import { detection, setup as reviewSetup } from "./detection-mentor.js";
import { ask, options as conversationOptions } from "./heph.js";
import { integer, required } from "./lib/config.js";
import webhookBurst from "./webhook-burst.js";

export { handleSummary } from "./lib/summary.js";

const scenario = __ENV.COURSE_SCENARIO ?? "S3";
if (!["S1", "S2", "S3", "S4", "S5"].includes(scenario)) {
	throw new Error("COURSE_SCENARIO must be S1..S5");
}
if (__ENV.THREADS_PER_DEVELOPER !== "0") {
	throw new Error("Course arrivals require THREADS_PER_DEVELOPER=0");
}
const duration = __ENV.DURATION ?? (scenario === "S1" || scenario === "S4" ? "2h" : "30m");
const reviews = scenario !== "S3";
const conversations = scenario !== "S2";
const storm = scenario === "S4" || scenario === "S5";
const reviewRate = integer("COURSE_REVIEW_RATE", scenario === "S1" ? 1 : 8);
const chatRate = integer("COURSE_CHAT_RATE", scenario === "S1" ? 1 : 8);
const initialCohort = scenario === "S3" && __ENV.COURSE_CHAT_RATE === undefined;
const cohort = integer("COURSE_USERS", 40);
const unit = duration.slice(-1);
const durationCount = duration.slice(0, -1);
if (!/^\d+$/u.test(durationCount) || !["s", "m", "h"].includes(unit)) {
	throw new Error("DURATION must be an integer followed by s, m or h");
}
const secondsPerUnit = { s: 1, m: 60, h: 3600 };
const scale = Object.entries(secondsPerUnit).find(([candidate]) => candidate === unit)?.[1];
const expectedSessions = initialCohort
	? cohort
	: Math.ceil((Number(durationCount) * (scale ?? 0) * chatRate) / 120);
const timeUnit = "120s";
const completed = new Counter("course_sessions_finished");

/** @type {import("k6/options").Options} */
export const options = {
	discardResponseBodies: false,
	maxRedirects: 0,
	scenarios: {
		...(reviews
			? {
					reviews: {
						exec: "review",
						executor: "constant-arrival-rate",
						rate: reviewRate,
						timeUnit,
						duration,
						preAllocatedVUs: 16,
						maxVUs: 64,
						gracefulStop: "20m",
					},
				}
			: {}),
		...(conversations
			? {
					conversations: {
						exec: "conversation",
						executor: "constant-arrival-rate",
						rate: initialCohort ? cohort : chatRate,
						// Keep the next arrival tick beyond the one-second cohort boundary.
						timeUnit: initialCohort ? "1001ms" : timeUnit,
						duration: initialCohort ? "1s" : duration,
						preAllocatedVUs: cohort,
						maxVUs: cohort,
						gracefulStop: "20m",
					},
				}
			: {}),
		...(storm
			? {
					redeliveries: {
						exec: "redelivery",
						executor: "constant-arrival-rate",
						rate: 100,
						timeUnit: "1s",
						duration: "5m",
						preAllocatedVUs: 8,
						maxVUs: 16,
					},
				}
			: {}),
	},
	thresholds: {
		dropped_iterations: ["count==0"],
		...(conversations ? conversationOptions.thresholds : {}),
		...(conversations
			? {
					course_sessions_finished: [`count==${expectedSessions}`],
					heph_turn_refused: ["count==0"],
				}
			: {}),
		...(reviews
			? {
					review_jobs_completed: ["rate==1"],
					review_jobs_finished: [`count==${integer("REVIEW_REQUESTS", 120)}`],
					review_job_duration: ["p(95)<900000"],
				}
			: {}),
	},
};

export function setup() {
	// One token per possible VU prevents identities from colliding when k6 adds VUs.
	const tokens = required("AUTH_TOKENS").split(",").filter(Boolean);
	const identityCount = (reviews ? 64 : 0) + (conversations ? cohort : 0) + (storm ? 16 : 0);
	if (tokens.length < identityCount || new Set(tokens).size !== tokens.length) {
		throw new Error(`AUTH_TOKENS requires ${identityCount} distinct synthetic identities`);
	}
	return reviews ? reviewSetup() : { artifactIds: [] };
}

/** @param {ReturnType<typeof setup>} data */
export function review(data) {
	detection(data);
}

export function conversation() {
	const correlation = crypto.randomUUID();
	const started = Date.now();
	console.log(JSON.stringify({ phase: "session_sent", correlation, at: started, scenario }));
	let parent = null;
	for (let index = 0; index < integer("COURSE_TURNS", 3); index += 1) {
		const requestId = crypto.randomUUID();
		console.log(JSON.stringify({ phase: "turn_sent", requestId, correlation, at: Date.now() }));
		const questionId = ask(
			parent,
			`capacity-bench:${requestId} capacity-replay:${__ENV.CAPACITY_RUN_LABEL ?? "test"}:${__VU - 1}:${index} Read INDEX.md if tools are available, then describe one practice in this synthetic course.`,
			requestId,
		);
		console.log(
			JSON.stringify({
				phase: "turn_end",
				requestId,
				correlation,
				at: Date.now(),
				answered: questionId !== null,
			}),
		);
		if (questionId === null) {
			return;
		}
		if (parent === null) {
			parent = questionId;
		}
		if (index + 1 < integer("COURSE_TURNS", 3)) {
			sleep(integer("COURSE_THINK_SECONDS", 30));
		}
	}
	completed.add(1);
	console.log(
		JSON.stringify({
			phase: "session_end",
			correlation,
			at: Date.now(),
			scenario,
			iteration: exec.scenario.iterationInTest,
		}),
	);
}

export function redelivery() {
	// This existing adapter measures authenticated publication, not downstream provider sync.
	webhookBurst();
}
