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

/** The elements directly under Streamdown's root: each one fades in when it is inserted. */
function fading(container: HTMLElement): Element[] {
	return [...container.querySelectorAll(":scope > div > *")];
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

	it("fades text in only when it is new, while the answer grows or is read again", () => {
		const shown = "First.\n\nSecond.\n\nThird";
		const { container, rerender } = render(
			<UntrustedMarkdown streaming>{shown}</UntrustedMarkdown>,
		);
		const before = fading(container);

		rerender(<UntrustedMarkdown streaming>{`${shown} grows`}</UntrustedMarkdown>);
		expect(fading(container)).toStrictEqual(before);

		// A footnote makes Streamdown read the whole answer as one block: nothing already shown fades again.
		rerender(<UntrustedMarkdown streaming>{`${shown} grows[^1]`}</UntrustedMarkdown>);
		for (const element of fading(container)) {
			expect(before).toContain(element);
		}

		rerender(
			<UntrustedMarkdown streaming>{`${shown} grows.\n\n- A list appended`}</UntrustedMarkdown>,
		);
		const appended = fading(container).at(-1);
		expect(appended?.textContent.trim()).toBe("A list appended");
		expect(before).not.toContain(appended);
		expect(container.querySelector("[data-sd-animate]")).toBeNull();
	});
});
