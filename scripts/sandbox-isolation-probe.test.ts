import assert from "node:assert/strict";
import { test } from "node:test";

import { tcpPort } from "./sandbox-isolation-probe.ts";

await test("the probe tries the gateway on whatever port the worker was given", () => {
	assert.equal(tcpPort("9081"), "9081");
	assert.equal(tcpPort("65535"), "65535");
});

await test("a worker without a usable gateway port stops the probe before it proves anything", () => {
	for (const value of ["", "0", "08081", "65536", "8081/tcp", " 8081", "-1"]) {
		assert.throws(() => tcpPort(value), /not a TCP port/u, JSON.stringify(value));
	}
});
