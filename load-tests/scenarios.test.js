import { check } from "k6";
import { Counter } from "k6/metrics";
import { mentorTurn, options as mixed, setup } from "./detection-mentor.js";
import { integer } from "./lib/config.js";
import { mentorStreamCompleted } from "./lib/mentor.js";
import { signedDelivery, webhookAccepted } from "./webhook-burst.js";

export { handleSummary } from "./lib/summary.js";

const completed = new Counter("contract_tests_completed");
const reviewJobsFinished = new Counter("review_jobs_finished");
const unfinished = __ENV.TEST_CASE === "unfinished";
export const options = {
	vus: 1,
	iterations: 1,
	thresholds: unfinished
		? { review_jobs_finished: mixed.thresholds.review_jobs_finished }
		: { checks: ["rate==1"], contract_tests_completed: ["count==1"] },
};

/**
 * @param {boolean} condition - the verdict, recorded as a k6 check under `message`
 * @param {string} message - what held, or what the thrown error reports
 */
function assert(condition, message) {
	if (!check(condition, { [message]: (value) => value })) {
		throw new Error(message);
	}
}

/**
 * @param {() => unknown} fn
 * @param {string} message
 */
function rejects(fn, message) {
	try {
		fn();
	} catch {
		return;
	}
	throw new Error(message);
}

export default function contracts() {
	if (unfinished) {
		reviewJobsFinished.add(integer("REVIEW_REQUESTS", 2) - 1);
		return;
	}
	/** @type {[number, string, boolean][]} */
	const verdicts = [
		[202, "ok", true],
		[202, "dropped", false],
		[200, "ok", false],
		[302, "ok", false],
		[503, "ok", false],
	];
	for (const [status, body, expected] of verdicts) {
		assert(
			webhookAccepted({ status, json: () => body }) === expected,
			`Webhook ${status}/${body} publication verdict`,
		);
	}
	assert(
		!webhookAccepted({
			status: 202,
			json: () => {
				throw new Error("Invalid JSON");
			},
		}),
		"Malformed webhook response fails",
	);
	const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/u;
	const delivery = signedDelivery();
	assert(uuid.test(delivery.id), "Webhook delivery carries a unique id");
	assert(/^[0-9a-f]{64}$/u.test(delivery.signature), "Webhook delivery carries an HMAC signature");
	/** @type {unknown} */
	const turn = JSON.parse(mentorTurn());
	const message =
		typeof turn === "object" && turn !== null && "message" in turn ? turn.message : null;
	const messageId =
		typeof message === "object" && message !== null && "id" in message ? message.id : null;
	assert(
		typeof messageId === "string" && uuid.test(messageId),
		"Mentor turn carries a unique message id",
	);
	__ENV.ARTIFACT_IDS = "1,2";
	assert(setup().artifactIds.length === 2, "Distinct artifacts must be accepted");
	for (const ids of ["1,1", "1", "1,nope", "1,0", "1,9007199254740992"]) {
		__ENV.ARTIFACT_IDS = ids;
		rejects(setup, `Invalid artifacts accepted: ${ids}`);
	}
	assert(
		mentorStreamCompleted('data: {"type":"finish","finishReason":"stop"}\r\n\r\ndata: [DONE]\r\n'),
		"Completed mentor turn passes",
	);
	for (const body of [
		null,
		"[DONE]",
		"data: [DONE]\n",
		'data: {"type":"finish"}\n',
		"data: invalid\ndata: [DONE]\n",
		'data: {"type":"error","errorText":"provider failed"}\ndata: [DONE]\n',
		'data: {"type":"finish","finishReason":"error"}\ndata: [DONE]\n',
		'data: {"type":"tool-output-error"}\ndata: {"type":"finish"}\ndata: [DONE]\n',
	]) {
		assert(!mentorStreamCompleted(body), "Failed or incomplete mentor turn fails");
	}
	completed.add(1);
}
