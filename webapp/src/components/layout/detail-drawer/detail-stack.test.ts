import { describe, expect, it } from "vitest";

import { detailStackSchema, encodeDetailStack, parseDetailStack } from "./detail-stack";

const KINDS = ["group", "practice"] as const;
const parse = (detail: unknown) => detailStackSchema(KINDS).parse({ detail }).detail;

describe("detailStackSchema", () => {
	it("coerces the single-value form a hand-written URL produces", () => {
		expect(parse("group:code-review")).toStrictEqual(["group:code-review"]);
	});

	it("keeps an id containing a colon, splitting only at the first one", () => {
		expect(parseDetailStack(parse("practice:scm:pull-request"), KINDS)).toStrictEqual([
			{ kind: "practice", id: "scm:pull-request" },
		]);
	});

	it("drops kinds the surface cannot render, rather than passing them on", () => {
		expect(parse(["practice:a", "sabotage:b", "group:c"])).toStrictEqual(["practice:a", "group:c"]);
	});

	it.each([[":x"], ["x:"], ["nocolon"], [""]])("drops the malformed entry %j", (value) => {
		expect(parse([value])).toStrictEqual([]);
	});

	it("drops a repeat, because the same thing twice is never a stack", () => {
		expect(parse(["practice:a", "practice:a"])).toStrictEqual(["practice:a"]);
	});

	it("caps depth so a hand-written URL cannot mount an unbounded number of drawers", () => {
		const long = Array.from({ length: 50 }, (_, index) => `practice:p${index}`);
		expect(parse(long)).toHaveLength(4);
	});

	it("reads an absent param as a closed stack", () => {
		expect(parseDetailStack(parse(undefined), KINDS)).toStrictEqual([]);
	});
});

describe("encodeDetailStack", () => {
	it("writes the wire form the schema reads back", () => {
		const stack = [
			{ kind: "group", id: "code-review" },
			{ kind: "practice", id: "describe-what-and-why" },
		];
		expect(encodeDetailStack(stack)).toStrictEqual([
			"group:code-review",
			"practice:describe-what-and-why",
		]);
		expect(parseDetailStack(parse(encodeDetailStack(stack)), KINDS)).toStrictEqual(stack);
	});

	it("omits the param entirely when nothing is open, so a closed stack leaves no URL trace", () => {
		expect(encodeDetailStack([])).toBeUndefined();
	});
});
