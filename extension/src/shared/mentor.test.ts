import { describe, expect, it } from "vitest";

import {
	firstMessage,
	mentorPanelPath,
	panelTabOf,
	turnRequestSchema,
	workReference,
} from "~/shared/mentor";
import { parseWorkPage } from "~/shared/work-url";

const THREAD = "5b0f7c2e-3a1d-4e8b-9c6f-2d4e6a8b0c1e";
const MESSAGE = "0d9a3f1e-1b2c-4d5e-8f60-718293a4b5c6";

function page(url: string) {
	const parsed = parseWorkPage(url);
	if (parsed === undefined) {
		throw new Error(`not a work page: ${url}`);
	}
	return parsed;
}

describe("workReference", () => {
	it("names the work from its address alone, canonical and without the page's sub-view", () => {
		expect(workReference(page("https://github.com/octo/app/pull/12/files?diff=split#x"))).toBe(
			"About pull request #12 in octo/app: https://github.com/octo/app/pull/12",
		);
		expect(
			workReference(page("https://gitlab.example.test/group/sub/project/-/merge_requests/4/diffs")),
		).toBe(
			"About merge request !4 in group/sub/project: https://gitlab.example.test/group/sub/project/-/merge_requests/4",
		);
		expect(workReference(page("https://github.com/octo/app/issues/3"))).toBe(
			"About issue #3 in octo/app: https://github.com/octo/app/issues/3",
		);
	});

	it("opens the first message, which is otherwise the reader's own words", () => {
		expect(
			firstMessage("About issue #3 in octo/app: https://github.com/octo/app/issues/3", "Why?"),
		).toBe("About issue #3 in octo/app: https://github.com/octo/app/issues/3\n\nWhy?");
	});
});

describe("the panel address", () => {
	it("round-trips one tab id and nothing else", () => {
		expect(mentorPanelPath(42)).toBe("mentor.html?tab=42");
		expect(panelTabOf("?tab=42")).toBe(42);
		for (const search of [
			"",
			"?tab=",
			"?tab=0",
			"?tab=01",
			"?tab=1&x=2",
			"?x=1&tab=1",
			"?tab=1.5",
		]) {
			expect(panelTabOf(search)).toBeUndefined();
		}
	});
});

describe("turnRequestSchema", () => {
	const sdkBody = {
		id: THREAD,
		message: { id: MESSAGE, role: "user", parts: [{ type: "text", text: "Why?" }], metadata: {} },
		trigger: "submit-message",
		messageId: undefined,
	};

	it("accepts the AI SDK's own body for a mentor turn", () => {
		expect(turnRequestSchema.safeParse({ type: "start", body: sdkBody }).success).toBe(true);
		expect(
			turnRequestSchema.safeParse({
				type: "start",
				body: { ...sdkBody, trigger: "regenerate-message", messageId: MESSAGE },
			}).success,
		).toBe(true);
	});

	it.each([
		{ type: "start", body: sdkBody, url: "https://evil.example" },
		{ type: "start", body: { ...sdkBody, id: "not-a-thread" } },
		{ type: "start", body: { ...sdkBody, message: { ...sdkBody.message, role: "assistant" } } },
		{ type: "start", body: { ...sdkBody, message: { ...sdkBody.message, parts: [] } } },
		{ type: "start", body: { ...sdkBody, trigger: "resume" } },
		{ type: "fetch", body: sdkBody },
	])("refuses %o", (value) => {
		expect(turnRequestSchema.safeParse(value).success).toBe(false);
	});
});
