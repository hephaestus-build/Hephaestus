import assert from "node:assert/strict";
import { test } from "node:test";

import { foundPresent, imageEvents } from "./sandbox-prepull-drill.ts";

const event = (name: string, reason: string, message: string) => ({
	involvedObject: { name },
	reason,
	message,
});
const image = '"ghcr.io/hephaestus-build/agent-pi@sha256:aaaa"';

await test("a sandbox counts as pre-pulled only when the kubelet found its image on the node", () => {
	const events = {
		items: [
			event("holder", "Pulling", `Pulling image ${image}`),
			event(
				"holder",
				"Pulled",
				`Successfully pulled image ${image} in 6.6s (6.6s including waiting)`,
			),
			event("sandbox", "Scheduled", "Successfully assigned the Pod"),
			event(
				"sandbox",
				"Pulled",
				`Container image ${image} already present on machine and can be accessed by the pod`,
			),
			event("sandbox", "Started", "Started container agent"),
		],
	};
	assert.deepEqual(
		imageEvents(events, "sandbox").map((entry) => entry.reason),
		["Pulled"],
	);
	assert.equal(foundPresent(imageEvents(events, "sandbox")), true);
	// The DaemonSet's Pod pulled the image, so a sandbox with those events would have waited for it.
	assert.equal(foundPresent(imageEvents(events, "holder")), false);
	// A Pod without image events proves nothing.
	assert.equal(foundPresent(imageEvents(events, "other")), false);
});
