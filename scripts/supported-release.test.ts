import assert from "node:assert/strict";
import { test } from "node:test";

import { supportedPublishedRelease } from "./supported-release.ts";

const release = (tag: string, isDraft = false) => ({ tag, isDraft });

void test("the rescan's release follows the support statement, by version and not by date", () => {
	assert.equal(
		supportedPublishedRelease([release("v0.95.1"), release("v1.0.0-rc.12"), release("v0.95.2")]),
		"v1.0.0-rc.12",
	);
	assert.equal(
		supportedPublishedRelease([
			release("v1.0.0"),
			release("v1.10.0"),
			release("v1.2.0"),
			release("v2.0.0-rc.1"),
		]),
		"v1.10.0",
	);
});

void test("a draft is never the supported release, and no published release fails", () => {
	assert.equal(
		supportedPublishedRelease([release("v1.0.0-rc.12"), release("v1.0.0-rc.13", true)]),
		"v1.0.0-rc.12",
	);
	assert.throws(
		() => supportedPublishedRelease([release("v1.0.0", true)]),
		/no published release/u,
	);
	assert.throws(() => supportedPublishedRelease([]), /no published release/u);
});
