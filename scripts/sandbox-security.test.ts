import assert from "node:assert/strict";
import test from "node:test";

import { containsCanary } from "./sandbox-security.ts";

void test("the canary oracle fails on output, request and log bytes, including common encodings", () => {
	const marker = "workspace-a-private-marker";
	for (const text of [
		marker,
		Buffer.from(marker).toString("base64"),
		Buffer.from(marker).toString("hex"),
		Buffer.from(`x${marker}`).toString("base64"),
		Buffer.from(`xx${marker}`).toString("base64"),
	]) {
		assert.equal(containsCanary(Buffer.from(`prefix ${text} suffix`), marker), true);
	}
	assert.equal(containsCanary(Buffer.from("workspace-b reviewed work"), marker), false);
});
