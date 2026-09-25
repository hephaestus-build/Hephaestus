import { describe, expect, it } from "vitest";

import { capitalise, firstNonBlank, hasText } from "./text";

describe("hasText", () => {
	it("rejects absence and the blank string alike", () => {
		expect(hasText(undefined)).toBe(false);
		expect(hasText(null)).toBe(false);
		expect(hasText("")).toBe(false);
	});

	it("accepts any string with a character in it, including whitespace", () => {
		expect(hasText("a")).toBe(true);
		expect(hasText(" ")).toBe(true);
	});
});

describe("firstNonBlank", () => {
	it("skips past blank and absent sources to the first that carries text", () => {
		expect(firstNonBlank(undefined, "", null, "Ada")).toBe("Ada");
	});

	it("reports undefined when every source is blank, so the caller supplies the fallback", () => {
		expect(firstNonBlank(undefined, "", null)).toBeUndefined();
		expect(firstNonBlank()).toBeUndefined();
	});
});

describe("capitalise", () => {
	it("opens a sentence with a capital and leaves the rest of it alone", () => {
		expect(capitalise("before writing a description, find the work item")).toBe(
			"Before writing a description, find the work item",
		);
		expect(capitalise("split the commit so the JAR is built once")).toBe(
			"Split the commit so the JAR is built once",
		);
	});

	it("leaves a sentence that already opens with a capital as it was written", () => {
		expect(capitalise("Split the commit.")).toBe("Split the commit.");
	});

	it("leaves a sentence that opens with a digit or a symbol as it was written", () => {
		expect(capitalise("3 of the checks have no test")).toBe("3 of the checks have no test");
		expect(capitalise("`loader` is renamed twice")).toBe("`loader` is renamed twice");
	});

	it("has nothing to capitalise in the blank string", () => {
		expect(capitalise("")).toBe("");
		expect(capitalise("   ")).toBe("   ");
	});
});
