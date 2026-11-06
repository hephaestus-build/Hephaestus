import assert from "node:assert/strict";
import { test } from "node:test";

import { evictionOrder } from "./kubernetes-memory-drill.ts";

const event = (name: string, time: string, reason = "Evicted") => ({
	reason,
	involvedObject: { name },
	firstTimestamp: time,
});

await test("eviction evidence uses event time, excludes other Pods and does not confuse OOM with eviction", () => {
	assert.deepEqual(
		evictionOrder({
			items: [
				event("roles", "2026-11-05T10:00:30Z"),
				event("unrelated", "2026-11-05T10:00:00Z"),
				event("reviews", "2026-11-05T10:00:10Z"),
				event("roles", "2026-11-05T10:00:00Z", "OOMKilling"),
				event("conversations", "2026-11-05T10:00:20Z"),
			],
		}),
		["reviews", "conversations", "roles"],
	);
	assert.throws(
		() =>
			evictionOrder({
				items: [event("reviews", "2026-11-05T10:00:30Z"), event("roles", "2026-11-05T10:00:30Z")],
			}),
		/establish an order/u,
	);
	assert.throws(() => evictionOrder({ items: [event("reviews", "invalid")] }), /ISO timestamp/u);
});
