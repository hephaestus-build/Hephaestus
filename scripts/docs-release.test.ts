import assert from "node:assert/strict";
import { test } from "node:test";

import { docsRelease } from "./lib/previous-release.ts";

await test("before the first stable 1.0.0, the site root serves the newest release candidate", () => {
	assert.equal(
		docsRelease(["v0.95.0", "v0.95.1", "v1.0.0-rc.1", "v1.0.0-rc.5", "v1.0.0-rc.4", "heph-wip"]),
		"v1.0.0-rc.5",
	);
});

await test("release candidates compare by number, so rc.10 is newer than rc.9", () => {
	assert.equal(docsRelease(["v1.0.0-rc.9", "v1.0.0-rc.10"]), "v1.0.0-rc.10");
});

await test("from 1.0.0 on, the site root serves the newest stable release, not a newer candidate", () => {
	assert.equal(docsRelease(["v1.0.0-rc.5", "v1.0.0", "v1.0.1", "v1.1.0-rc.1"]), "v1.0.1");
});

await test("a 0.x stable release never outranks a newer candidate", () => {
	assert.equal(docsRelease(["v0.95.1", "v1.0.0-rc.1"]), "v1.0.0-rc.1");
});

await test("no release tag gives no release", () => {
	assert.equal(docsRelease(["heph-wip-phase16"]), undefined);
});
