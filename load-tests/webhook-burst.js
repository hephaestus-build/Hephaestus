import { check } from "k6";
// A default import of `k6/crypto` shadows the global WebCrypto that owns randomUUID.
import { hmac } from "k6/crypto";
import http from "k6/http";

import { baseUrl, integer, jsonField, required, sharedThresholds } from "./lib/config.js";

export { handleSummary } from "./lib/summary.js";

const rate = integer("WEBHOOK_RATE", 100);
const duration = __ENV.DURATION ?? "2m";
const preAllocatedVUs = integer("PRE_ALLOCATED_VUS", 40);
const event = "capacity_test";
const target = `${baseUrl()}/webhooks/github`;
const webhookSecret = required("WEBHOOK_SECRET");

export const options = {
	discardResponseBodies: true,
	maxRedirects: 0,
	scenarios: {
		webhook_burst: {
			executor: "constant-arrival-rate",
			rate,
			timeUnit: "1s",
			duration,
			preAllocatedVUs,
			maxVUs: integer("MAX_VUS", preAllocatedVUs * 2),
		},
	},
	thresholds: {
		...sharedThresholds,
		"checks{scenario:webhook_burst}": ["rate>0.99"],
		"http_req_duration{scenario:webhook_burst}": ["p(95)<250", "p(99)<750"],
		dropped_iterations: ["count==0"],
	},
};

const payload = JSON.stringify({
	repository: { name: "capacity-test", owner: { login: "hephaestus-load" } },
	padding: "x".repeat(integer("WEBHOOK_PADDING_BYTES", 4096)),
});

/**
 * @param {Pick<import("k6/http").Response, "status" | "json">} response
 * @returns {boolean}
 */
export function webhookAccepted(response) {
	return response.status === 202 && jsonField(response, "status") === "ok";
}

export function signedDelivery() {
	return {
		id: crypto.randomUUID(),
		signature: hmac("sha256", webhookSecret, payload, "hex"),
	};
}

export default function webhookBurst() {
	const delivery = signedDelivery();
	const response = http.post(target, payload, {
		headers: {
			"Content-Type": "application/json",
			"X-GitHub-Delivery": delivery.id,
			"X-GitHub-Event": event,
			"X-Hub-Signature-256": `sha256=${delivery.signature}`,
		},
		tags: { operation: "webhook_ingest" },
		responseType: "text",
	});
	check(response, { "webhook published": webhookAccepted });
}
