import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { ModelLinkHostsContext } from "./model-links";
import { UntrustedMarkdown } from "./UntrustedMarkdown";
import { UntrustedMarkdownRenderer } from "./UntrustedMarkdownRenderer";

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

describe("UntrustedMarkdownRenderer", () => {
	it("leaves the words of a link the model wrote alone, so no link lands inside another", () => {
		render(
			<ModelLinkHostsContext value={["example.com"]}>
				<UntrustedMarkdownRenderer renderText={linkReferences}>
					See [**#12**](https://example.com/pull/12) and **#13**.
				</UntrustedMarkdownRenderer>
			</ModelLinkHostsContext>,
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

	it("shows a link to a host outside the workspace as its words and its address, never as a link", () => {
		render(
			<ModelLinkHostsContext value={["github.com", ".hephaestus.example"]}>
				<UntrustedMarkdownRenderer>
					Read [this guide](https://evil.example/?q=secret), https://other.example/a, [the
					PR](https://github.com/a/b/pull/1) and [your page](https://team.hephaestus.example/p).
				</UntrustedMarkdownRenderer>
			</ModelLinkHostsContext>,
		);

		expect(screen.getAllByRole("link").map((link) => link.getAttribute("href"))).toStrictEqual([
			"https://github.com/a/b/pull/1",
			"https://team.hephaestus.example/p",
		]);
		expect(screen.getByText("https://evil.example/?q=secret").tagName).toBe("CODE");
		expect(screen.getByText("https://other.example/a").tagName).toBe("CODE");
	});

	it("keeps the links of text a person wrote, such as a practice guide", () => {
		render(
			<ModelLinkHostsContext value={["github.com"]}>
				<UntrustedMarkdownRenderer authored>
					See [the guide](https://docs.example/guide).
				</UntrustedMarkdownRenderer>
			</ModelLinkHostsContext>,
		);

		expect(screen.getByRole("link").getAttribute("href")).toBe("https://docs.example/guide");
	});

	it("draws no image unless the caller draws it, so no image loads from anywhere", () => {
		const { container } = render(
			<UntrustedMarkdownRenderer>
				![A tracking pixel](https://example.com/pixel.png)
			</UntrustedMarkdownRenderer>,
		);

		expect(container.querySelector("img")).toBeNull();
	});

	it("fades text in only when it is new, while the answer grows or is read again", () => {
		const shown = "First.\n\nSecond.\n\nThird";
		const { container, rerender } = render(
			<UntrustedMarkdownRenderer streaming>{shown}</UntrustedMarkdownRenderer>,
		);
		const before = fading(container);

		rerender(<UntrustedMarkdownRenderer streaming>{`${shown} grows`}</UntrustedMarkdownRenderer>);
		expect(fading(container)).toStrictEqual(before);

		// A footnote makes Streamdown read the whole answer as one block: nothing already shown fades again.
		rerender(
			<UntrustedMarkdownRenderer streaming>{`${shown} grows[^1]`}</UntrustedMarkdownRenderer>,
		);
		for (const element of fading(container)) {
			expect(before).toContain(element);
		}

		rerender(
			<UntrustedMarkdownRenderer
				streaming
			>{`${shown} grows.\n\n- A list appended`}</UntrustedMarkdownRenderer>,
		);
		const appended = fading(container).at(-1);
		expect(appended?.textContent.trim()).toBe("A list appended");
		expect(before).not.toContain(appended);
		expect(container.querySelector("[data-sd-animate]")).toBeNull();
	});
});

describe("UntrustedMarkdown", () => {
	it("loads the renderer on first use and then shows the Markdown as the renderer draws it", async () => {
		render(<UntrustedMarkdown>{"## Next step\n\nRun `pnpm test`."}</UntrustedMarkdown>);

		await screen.findByRole("heading", { level: 4, name: "Next step" });
		expect(screen.getByText("pnpm test").tagName).toBe("CODE");
	});
});
