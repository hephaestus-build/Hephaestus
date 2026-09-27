import { describe, expect, it } from "vitest";

import { untrustedMarkdown } from "./untrusted-markdown";

describe("untrustedMarkdown", () => {
	it("turns an inline image into a link the reader chooses to open", () => {
		const safe = untrustedMarkdown("See ![the diagram](https://example.org/d.png) here.");

		expect(safe).not.toContain("![");
		expect(safe).toContain("[the diagram](https://example.org/d.png)");
	});

	it("turns a reference image into a reference link, keeping its definition", () => {
		const safe = untrustedMarkdown("![chart][c]\n\n[c]: file:///private/var/secret.png");

		expect(safe).not.toContain("![");
		expect(safe).toContain("[chart][c]");
	});

	it("names an image without alternative text", () => {
		expect(untrustedMarkdown("![](https://example.org/t.png)")).toContain(
			"[Image](https://example.org/t.png)",
		);
	});

	it("reduces raw HTML, including an image tag, to text", () => {
		const safe = untrustedMarkdown('Before <img src="https://tracker.example/p.gif"> after');

		expect(safe).toContain("img src");
		expect(safe).not.toMatch(/(?<!\\)<img/u);
	});

	it("finds an image inside a list and a table", () => {
		const safe = untrustedMarkdown(
			"- item ![a](https://example.org/a.png)\n\n| x |\n| - |\n| ![b](https://example.org/b.png) |",
		);

		expect(safe).not.toContain("![");
	});

	it("leaves markdown without images or HTML exactly as it came, half-written syntax included", () => {
		const streaming = "Here is **bold and a [link](https://exa";

		expect(untrustedMarkdown(streaming)).toBe(streaming);
	});

	it("leaves an image inside code alone, because code is never loaded", () => {
		const code = "Use `![x](y)` or\n\n```md\n![x](y)\n```";

		expect(untrustedMarkdown(code)).toBe(code);
	});
});
