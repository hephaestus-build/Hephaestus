import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";

import { LandingFaqSection } from "./LandingFaqSection";

describe("LandingFaqSection", () => {
	it("explains private feedback and approval before feedback on the work", async () => {
		const user = userEvent.setup();
		render(<LandingFaqSection />);
		await user.click(screen.getByRole("button", { name: "Where does feedback appear?" }));
		const answer = await screen.findByText(/on your private Practice profile/u);
		expect(answer.textContent).toContain("waits for a workspace admin's approval by default");
	});

	it("names the member AI choice and Slack permission", async () => {
		const user = userEvent.setup();
		render(<LandingFaqSection />);
		await user.click(screen.getByRole("button", { name: "What project data can Hephaestus use?" }));
		const answer = await screen.findByText(/Each member chooses In-house, Cloud or No AI/u);
		expect(answer.textContent).toContain(
			"Slack channel messages are used only with the member's permission",
		);
		expect(answer.textContent).not.toContain("set per deployment");
	});

	it("separates shared-model caps from workspace-provider caps", async () => {
		const user = userEvent.setup();
		render(<LandingFaqSection />);
		await user.click(screen.getByRole("button", { name: "What does it cost?" }));
		const answer = await screen.findByText(/The instance admin sets each workspace's monthly cap/u);
		expect(answer.textContent).toContain("for shared models");
		expect(answer.textContent).toContain(
			"The workspace admin sets the cap for the workspace's own provider",
		);
	});
});
