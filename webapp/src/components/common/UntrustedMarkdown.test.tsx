import { render, screen } from "@testing-library/react";
import { lazy } from "react";
import { expect, it, vi } from "vitest";

import { LazyMarkdown } from "./UntrustedMarkdown";

it("shows the source as plain text when the renderer cannot load, and the rest of the page stays", async () => {
	const Renderer = lazy(async () => {
		throw new TypeError("Failed to fetch dynamically imported module: /assets/renderer-1a2b3c.js");
	});
	render(
		<main>
			<h1>Your practices</h1>
			<LazyMarkdown source="**Name** the branch after the issue.">
				<Renderer />
			</LazyMarkdown>
		</main>,
		{ onCaughtError: vi.fn() },
	);

	const text = await screen.findByText("**Name** the branch after the issue.");
	expect(text.tagName).toBe("P");
	expect(screen.getByRole("heading", { name: "Your practices" }).textContent).toBe(
		"Your practices",
	);
	expect(document.body.textContent).not.toContain("renderer-1a2b3c");
});
