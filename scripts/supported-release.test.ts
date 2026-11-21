import assert from "node:assert/strict";
import { test } from "node:test";

import { supportedRelease } from "./supported-release.ts";

const release = (tag: string, isDraft = false) => ({ tag, isDraft });

void test("before 1.0.0 the latest release is supported, a release candidate included", () => {
	assert.equal(
		supportedRelease([
			release("v0.95.1"),
			release("v1.0.0-rc.10"),
			release("v1.0.0-rc.12"),
			release("v1.0.0-rc.9"),
		]),
		"v1.0.0-rc.12",
	);
	assert.equal(supportedRelease([release("v0.95.0"), release("v0.95.1")]), "v0.95.1");
});

void test("from 1.0.0 the latest stable release is supported, not a later candidate", () => {
	assert.equal(
		supportedRelease([release("v1.0.0-rc.12"), release("v1.0.0"), release("v1.1.0-rc.1")]),
		"v1.0.0",
	);
	assert.equal(
		supportedRelease([
			release("v1.0.0"),
			release("v1.2.0"),
			release("v1.10.0"),
			release("v1.3.0-rc.2"),
		]),
		"v1.10.0",
	);
});

void test("a draft or a tag that is not a release version is never the supported release", () => {
	assert.equal(
		supportedRelease([release("v1.0.0-rc.12"), release("v1.0.0-rc.13", true), release("nightly")]),
		"v1.0.0-rc.12",
	);
	assert.throws(() => supportedRelease([release("v2.0.0", true)]), /no published release/u);
});
