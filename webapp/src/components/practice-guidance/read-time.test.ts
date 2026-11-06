import { describe, expect, it } from "vitest";

import { countWords, guideWords, readTime } from "./read-time";

describe("read time", () => {
	it("counts words, keeping contractions whole", () => {
		expect(countWords("A reviewer can’t hold it all, so split it.")).toBe(9);
		expect(countWords("")).toBe(0);
	});

	it("counts a guide's text without its figures or link addresses", () => {
		expect(
			guideWords(
				"## How to do it\n\n- Split [the change](https://x.test/a/b) first.\n\n![Steps](figure:steps)",
			),
		).toBe(8);
	});

	it("counts reference-style links by their words, without definitions or bare addresses", () => {
		expect(
			guideWords(
				[
					"Read [the guide][guide] and [the checklist][] first.",
					"![Steps][steps]",
					"See https://x.test/very/long/path or <https://x.test/b>.",
					"",
					'[guide]: https://x.test/guide "The guide"',
					"  [the checklist]: <https://x.test/checklist>",
					"[steps]: figure:steps",
				].join("\n"),
			),
		).toBe(9);
	});

	it("rounds up to whole minutes, at least one", () => {
		expect(readTime(0)).toBe("1 min read");
		expect(readTime(200)).toBe("1 min read");
		expect(readTime(201)).toBe("2 min read");
		expect(readTime(799)).toBe("4 min read");
	});
});
