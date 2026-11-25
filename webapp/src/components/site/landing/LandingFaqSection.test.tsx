import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";

import { LandingFaqSection } from "./LandingFaqSection";

describe("LandingFaqSection", () => {
	it("explains private feedback and approval before feedback on the work", async () => {
		const user = userEvent.setup();
		render(<LandingFaqSection />);
		await user.click(screen.getByRole("button", { name: "Where does feedback appear?" }));
		const answer = await screen.findByText(/on your Practice profile/u);
		expect(answer.textContent).toContain("waits for a workspace admin’s approval by default");
	});

	it("says what workspace admins see and that feedback has no grade", async () => {
		const user = userEvent.setup();
		render(<LandingFaqSection />);
		await user.click(screen.getByRole("button", { name: "Who sees my feedback?" }));
		const answer = await screen.findByText(/Your feedback has no score, grade or rank/u);
		expect(answer.textContent).toContain("No workspace admin page shows your conversation");
		expect(answer.textContent).toContain("Feedback on the work is visible to everyone");
		expect(answer.textContent).toContain("An instance admin can include it in an audited export");
	});

	it("names the member AI choice and Slack opt-out", async () => {
		const user = userEvent.setup();
		render(<LandingFaqSection />);
		await user.click(screen.getByRole("button", { name: "What project data can Hephaestus use?" }));
		const answer = await screen.findByText(/Each member chooses In-house, Cloud or No AI/u);
		expect(answer.textContent).toContain("You can turn off use of your Slack channel messages.");
		expect(answer.textContent).not.toContain("set per deployment");
	});

	it("separates shared-model caps from workspace-provider caps", async () => {
		const user = userEvent.setup();
		render(<LandingFaqSection />);
		await user.click(screen.getByRole("button", { name: "What does it cost?" }));
		const answer = await screen.findByText(/The instance admin can set a monthly budget/u);
		expect(answer.textContent).toContain("for shared models");
		expect(answer.textContent).toContain(
			"The workspace admin can set a cap for the workspace’s own provider",
		);
	});
});
