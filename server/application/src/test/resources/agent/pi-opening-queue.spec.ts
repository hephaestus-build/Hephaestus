import assert from "node:assert/strict";
import test from "node:test";

import { OpeningQueue } from "../../../main/resources/agent/pi-opening-queue.ts";

void test("a cold opening does not hold another leader, and followers keep server order", async () => {
	const queue = new OpeningQueue(["a-1", "a-2", "b-1", "a-3", "b-2"], (work) => work[0] ?? "");
	try {
		assert.equal(await queue.take(), "a-1");
		assert.equal(await queue.take(), "b-1");
		let started = false;
		const follower = (async () => {
			const work = await queue.take();
			started = true;
			return work;
		})();
		await Promise.resolve();
		assert.equal(started, false);
		queue.firstStream("a");
		assert.equal(await follower, "a-2");
		assert.equal(await queue.take(), "a-3");
		queue.firstStream("b");
		assert.equal(await queue.take(), "b-2");
		assert.equal(await queue.take(), undefined);
	} finally {
		queue.dispose();
	}
});

void test("a leader that never streams releases its followers at the warm-up limit", async () => {
	const queue = new OpeningQueue(["first", "second"], () => "same", 5);
	try {
		assert.equal(await queue.take(), "first");
		assert.equal(await queue.take(), "second");
	} finally {
		queue.dispose();
	}
});

void test("cancellation releases waiters without starting pending practices", async () => {
	const queue = new OpeningQueue([1, 2], () => "same");
	assert.equal(await queue.take(), 1);
	const pending = queue.take();
	queue.dispose();
	assert.equal(await pending, undefined);
});
