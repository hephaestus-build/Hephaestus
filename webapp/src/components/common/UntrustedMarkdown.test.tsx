import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { UntrustedMarkdown } from "./UntrustedMarkdown";

/** Links every `#n` it finds, the way the feedback card links the work it can vouch for. */
function linkReferences(value: string) {
	return value.split(/(?<reference>#\d+)/u).map((part, index) =>
		/^#\d+$/u.test(part) ? (
			<a key={index} href={`https://example.com/pull/${part.slice(1)}`}>
				{part}
			</a>
		) : (
			part
		),
	);
}

describe("UntrustedMarkdown", () => {
	it("leaves the words of a link the model wrote alone, so no link lands inside another", () => {
		render(
			<UntrustedMarkdown renderText={linkReferences}>
				See [**#12**](https://example.com/pull/12) and **#13**.
			</UntrustedMarkdown>,
		);

		const links = screen.getAllByRole("link");
		expect(links.map((link) => link.getAttribute("href"))).toStrictEqual([
			"https://example.com/pull/12",
			"https://example.com/pull/13",
		]);
		for (const link of links) {
			expect(link.querySelector("a")).toBeNull();
		}
	});
});

/** A summary comment exactly as Hephaestus posts it on a pull request: marker, body, footer. */
const POSTED_COMMENT = `<!-- hephaestus:practice-review:774b9e9b-2c40-4d6b-9e76-7094dda3a7a2 -->
The description needs its purpose and coverage. Use the description to name the need behind this change and identify which of the linked item's Done-when points it covers or defers.

The title names the app-problem and user-story task, but the authored description only references issue #3 and gives no reason.

---
<sub>Practice review &middot; gpt-6-luna. This feedback is AI-generated and can be inaccurate. Answer or dispute it in [Hephaestus](https://hephaestus.example/w/aet/feedback/scm.pull_request/42).</sub>
<sub>[Why you see this and how to stop it](https://hephaestus.example/settings#practice-feedback)</sub>
`;

describe("UntrustedMarkdown with the HTML of a posted comment", () => {
	it("hides the marker and shows the footer as small print, as GitHub does", () => {
		const { container } = render(<UntrustedMarkdown>{POSTED_COMMENT}</UntrustedMarkdown>);

		expect(container.textContent).not.toContain("<!--");
		expect(container.textContent).not.toContain("hephaestus:practice-review");
		expect(container.textContent).not.toContain("<sub>");
		const smallPrint = [...container.querySelectorAll("small")].map((node) => node.textContent);
		expect(smallPrint).toStrictEqual([
			"Practice review · gpt-6-luna. This feedback is AI-generated and can be inaccurate. Answer or dispute it in Hephaestus.",
			"Why you see this and how to stop it",
		]);
		expect(
			screen
				.getByRole("link", { name: "Why you see this and how to stop it" })
				.getAttribute("href"),
		).toBe("https://hephaestus.example/settings#practice-feedback");
	});

	it("keeps GitHub's block elements and drops what GitHub drops", () => {
		const { container } = render(
			<UntrustedMarkdown>
				{[
					"<details><summary>Lines checked</summary>",
					"",
					"`src/app.ts`",
					"",
					"</details>",
					"",
					'<script>alert(1)</script><img src="https://tracker.example/pixel.gif" onerror="alert(2)">',
					"",
					'<a href="javascript:alert(3)">not a link</a> <b onclick="alert(4)">bold</b>',
				].join("\n")}
			</UntrustedMarkdown>,
		);

		expect(container.querySelector("details summary")?.textContent).toBe("Lines checked");
		expect(container.querySelector("details code")?.textContent).toBe("src/app.ts");
		expect(container.querySelector("script")).toBeNull();
		expect(container.querySelector("img")).toBeNull();
		expect(container.textContent).not.toContain("alert(1)");
		expect(screen.queryByRole("link", { name: "not a link" })).toBeNull();
		expect(container.querySelector("b")?.getAttribute("onclick")).toBeNull();
	});
});
