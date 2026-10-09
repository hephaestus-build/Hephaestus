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

	it("draws no image unless the caller draws it, so no image loads from anywhere", () => {
		const { container } = render(
			<UntrustedMarkdown>![A tracking pixel](https://example.com/pixel.png)</UntrustedMarkdown>,
		);

		expect(container.querySelector("img")).toBeNull();
	});
});
