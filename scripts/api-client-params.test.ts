import assert from "node:assert/strict";
import { test } from "node:test";

// Both generated runtime copies must pass the same security test.
/* eslint-disable import/no-relative-parent-imports */
import { buildClientParams as extensionParams } from "../extension/src/api/core/params.gen.ts";
import { buildClientParams as webappParams } from "../webapp/src/api/core/params.gen.ts";
/* eslint-enable import/no-relative-parent-imports */

for (const [name, buildClientParams] of [
	["webapp", webappParams],
	["extension", extensionParams],
] as const) {
	for (const slot of ["body", "headers", "path", "query"] as const) {
		void test(`${name}: extra ${slot} keys cannot replace the parameter prototype`, () => {
			const result = buildClientParams(
				[{ q: "hello", [`$${slot}___proto__`]: { isAdmin: true } }],
				[{ args: [{ in: slot, key: "q" }] }],
			);
			const params = result[slot];
			assert.ok(params !== null && typeof params === "object");
			assert.equal(Object.getPrototypeOf(params), null);
			assert.equal(Reflect.get(params, "q"), "hello");
			assert.equal(Reflect.get(params, "isAdmin"), undefined);
		});
	}
}
