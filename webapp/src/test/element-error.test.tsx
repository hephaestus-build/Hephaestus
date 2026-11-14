import { render, screen } from "@testing-library/react";
import { assert, describe, expect, it } from "vitest";

// `setup-msw.ts` leaves the DOM out of the errors that a wait discards. The error a test reports
// still shows it.
describe("a failed query's error", () => {
	it("shows the page when a wait gives up", async () => {
		render(<p>Nothing to press here</p>);

		const error: unknown = await screen
			.findByRole("button", { name: "Save" }, { timeout: 100 })
			.catch((failure: unknown) => failure);

		assert(error instanceof Error);
		expect(error.message).toContain('Unable to find role="button" and name "Save"');
		expect(error.message).toContain("Nothing to press here");
	});

	it("shows the page when a query fails outside a wait", () => {
		render(<p>Nothing to press here</p>);

		expect(() => screen.getByRole("button", { name: "Save" })).toThrow(/Nothing to press here/u);
	});
});
