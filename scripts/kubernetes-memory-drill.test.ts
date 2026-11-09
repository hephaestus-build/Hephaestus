import assert from "node:assert/strict";
import { test } from "node:test";

import { evictionOrder, unsampled, workingSets } from "./kubernetes-memory-drill.ts";

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

const mib = (value: number) => value * 1024 * 1024;
const pod = (name: string, workingSetBytes?: number) => ({
	podRef: { name },
	memory: workingSetBytes === undefined ? {} : { workingSetBytes },
});

await test("the drill waits until each fixture's sample shows touched memory below its request, and names what it read", () => {
	const settled = workingSets({
		pods: [
			pod("reviews", mib(43)),
			pod("conversations", mib(75)),
			pod("roles", mib(107)),
			pod("other"),
		],
	});
	assert.deepEqual(unsampled(settled), []);
	// A container that the kubelet has not sampled yet has no working set, or one from before its allocation.
	assert.deepEqual(
		unsampled(
			workingSets({ pods: [pod("reviews", mib(43)), pod("conversations"), pod("roles", mib(20))] }),
		),
		[
			"conversations: no sample; it needs more than 64 MiB and less than 192 MiB",
			"roles: 20.0 MiB; it needs more than 96 MiB and less than 192 MiB",
		],
	);
	assert.deepEqual(
		unsampled(
			workingSets({
				pods: [pod("reviews", mib(43)), pod("conversations", mib(75)), pod("roles", mib(192))],
			}),
		),
		["roles: 192.0 MiB; it needs more than 96 MiB and less than 192 MiB"],
	);
});
