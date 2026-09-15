import assert from "node:assert/strict";
import test from "node:test";

import {
	isRetryableStatus,
	isTimeoutAbort,
	retryDelayMs,
	retrying,
} from "../../../main/resources/agent/pi-runner-retry.ts";

const noSleep = () => Promise.resolve();

void test("a call that arrives is not repeated", async () => {
	let calls = 0;
	const result = await retrying(
		() => {
			calls += 1;
			return Promise.resolve("admitted");
		},
		() => true,
		{ attempts: 5, sleep: noSleep },
	);
	assert.equal(result, "admitted");
	assert.equal(calls, 1);
});

void test("a call that did not arrive is repeated until it does", async () => {
	let calls = 0;
	const result = await retrying(
		() => {
			calls += 1;
			return calls < 3 ? Promise.reject(new Error("did not arrive")) : Promise.resolve("admitted");
		},
		() => true,
		{ attempts: 5, sleep: noSleep },
	);
	assert.equal(result, "admitted");
	assert.equal(calls, 3);
});

void test("a decision the server already took is not repeated", async () => {
	let calls = 0;
	await assert.rejects(
		retrying(
			() => {
				calls += 1;
				return Promise.reject(new Error("refused"));
			},
			() => false,
			{ attempts: 5, sleep: noSleep },
		),
		/refused/,
	);
	assert.equal(calls, 1);
});

void test("the last failure is what the caller sees when the attempts are spent", async () => {
	let calls = 0;
	await assert.rejects(
		retrying(
			() => {
				calls += 1;
				return Promise.reject(new Error(`attempt ${calls}`));
			},
			() => true,
			{ attempts: 3, sleep: noSleep },
		),
		/attempt 3/,
	);
	assert.equal(calls, 3);
});

void test("each wait is reported and doubles", async () => {
	const waits: number[] = [];
	await assert.rejects(
		retrying(
			() => Promise.reject(new Error("did not arrive")),
			() => true,
			{ attempts: 4, sleep: noSleep },
			(_attempt, _error, delayMs) => waits.push(delayMs),
		),
		/did not arrive/,
	);
	assert.deepEqual(waits, [1_000, 2_000, 4_000]);
});

void test("a wait has to be a real attempt on a real base", () => {
	assert.equal(retryDelayMs(1, 250), 250);
	assert.equal(retryDelayMs(3, 250), 1_000);
	for (const invalid of [0, -1, 1.5, Number.NaN]) {
		assert.throws(() => retryDelayMs(invalid), /positive integer/);
	}
	assert.throws(() => retryDelayMs(1, 0), /positive number/);
});

void test("attempts that cannot be spent are refused before the call is made", async () => {
	let calls = 0;
	await assert.rejects(
		retrying(
			() => {
				calls += 1;
				return Promise.resolve("x");
			},
			() => true,
			{ attempts: 0 },
		),
		/positive integer/,
	);
	assert.equal(calls, 0);
});

void test("a server saying not now is worth asking again; a server saying no is not", () => {
	for (const status of [500, 502, 503, 504, 429]) {
		assert.equal(isRetryableStatus(status), true, `${status} should be retried`);
	}
	for (const status of [200, 201, 400, 401, 403, 404, 409, 422]) {
		assert.equal(isRetryableStatus(status), false, `${status} should not be retried`);
	}
});

void test("an attempt that ran out its own clock is a slow server, not a transport failure", () => {
	assert.equal(isTimeoutAbort(new DOMException("The operation was aborted", "TimeoutError")), true);
	assert.equal(
		isTimeoutAbort(new TypeError("fetch failed", { cause: new Error("ECONNRESET") })),
		false,
	);
	assert.equal(isTimeoutAbort(new DOMException("The operation was aborted", "AbortError")), false);
	assert.equal(isTimeoutAbort(null), false);
});

void test("a slow attempt is not repeated while a transport failure is", async () => {
	let calls = 0;
	await assert.rejects(
		retrying(
			() => {
				calls += 1;
				return Promise.reject(new DOMException("The operation was aborted", "TimeoutError"));
			},
			(error) => !isTimeoutAbort(error),
			{ attempts: 4, sleep: noSleep },
		),
		/aborted/,
	);
	assert.equal(calls, 1);
});
