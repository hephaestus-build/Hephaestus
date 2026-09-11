import { fireEvent, type queries, render, screen, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import type { AgentBinding, AvailableLlmModel } from "@/api/types.gen";

import { AgentBindingsPage, type AgentBindingsPageProps } from "./AgentBindingsPage";

const inHouseModel: AvailableLlmModel = {
	dataHandlingTier: "IN_HOUSE",
	id: 20,
	scope: "SHARED",
	displayName: "GPT Test",
	connectionDisplayName: "Shared OpenAI",
	supportsReasoning: false,
	pricingMode: "NO_CHARGE",
};

const keptModel: AvailableLlmModel = {
	dataHandlingTier: "PROVIDER_KEPT",
	id: 21,
	scope: "SHARED",
	displayName: "GPT Other",
	connectionDisplayName: "Shared OpenAI",
	supportsReasoning: false,
	pricingMode: "NO_CHARGE",
};

const inHouseBinding: AgentBinding = {
	dataHandlingTier: "IN_HOUSE",
	purpose: "PRACTICE_REVIEW",
	instanceModelId: 20,
	enabled: true,
	ready: true,
	timeoutSeconds: 600,
	maxConcurrentJobs: 1,
	allowInternet: false,
};

function renderPage(overrides: Partial<AgentBindingsPageProps> = {}) {
	const onSave = vi.fn();
	const onTurnOff = vi.fn();
	render(
		<AgentBindingsPage
			workspaceSlug="demo"
			bindings={[inHouseBinding]}
			availableModels={[inHouseModel, keptModel]}
			practicesEnabled
			mentorEnabled
			aiChoiceRequired={false}
			isLoading={false}
			isError={false}
			loadError={null}
			pendingTargets={new Set()}
			onRetry={vi.fn()}
			onSave={onSave}
			onTurnOff={onTurnOff}
			{...overrides}
		/>,
	);
	return { onSave, onTurnOff };
}

type Scope = ReturnType<typeof within<typeof queries>>;

const UNCHOSEN_ROW = "Members who haven't chosen";

function row(purpose: string, title: string): Scope {
	const card = screen.getByRole("region", { name: purpose });
	return within(within(card).getByRole("group", { name: title }));
}

const reviewsInHouse = () => row("Practice reviews", "Stays in-house");
const saveButton = (scope: Scope) => scope.getByRole("button", { name: /^Save assignment/ });
const timeoutInput = (scope: Scope) =>
	scope.getByLabelText<HTMLInputElement>(/^Timeout \(seconds\)/);

describe("AgentBindingsPage", () => {
	it("renders one row per tier under each purpose, with the undeclared row last", () => {
		renderPage();
		const reviews = within(screen.getByRole("region", { name: "Practice reviews" }));
		const rowNames = reviews
			.getAllByRole("heading", { level: 3 })
			.map((heading) => heading.textContent)
			.filter((name) => name !== "Preview");
		expect(rowNames).toStrictEqual([
			"Stays in-house",
			"Provider, nothing kept",
			"Provider, kept for safety checks",
			UNCHOSEN_ROW,
		]);

		const unassignedSwitch = row("Heph", "Stays in-house").getByRole("switch", {
			name: /^Use this model/,
		});
		expect(unassignedSwitch.getAttribute("aria-checked")).toBe("false");
		expect(unassignedSwitch.getAttribute("aria-disabled")).toBe("true");
	});

	it("previews who gets which model by the ceiling rule, including members who haven't chosen", () => {
		renderPage({
			bindings: [
				inHouseBinding,
				{ ...inHouseBinding, dataHandlingTier: "PROVIDER_KEPT", instanceModelId: 21, ready: false },
			],
		});
		const reviews = within(screen.getByRole("region", { name: "Practice reviews" }));
		const definitionAfter = (term: string) =>
			reviews.getByText(term, { selector: "dt" }).nextElementSibling?.textContent;

		expect(definitionAfter("Members who chose Only in-house")).toBe("→ Stays in-house: GPT Test");
		expect(definitionAfter("Members who chose Any AI this workspace set up")).toBe(
			"→ Stays in-house: GPT Test",
		);
		expect(definitionAfter("Members who haven't chosen")).toBe("→ nothing runs for them");
	});

	it("drops the unchosen preview row once the choice is required", () => {
		renderPage({ aiChoiceRequired: true });
		const reviews = within(screen.getByRole("region", { name: "Practice reviews" }));
		expect(reviews.queryByText("Members who haven't chosen", { selector: "dt" })).toBeNull();
		row("Practice reviews", UNCHOSEN_ROW).getByText(/serves no one now/);
	});

	it("names the readiness of a bound row and says nothing for an empty one", () => {
		renderPage({
			bindings: [
				inHouseBinding,
				{ ...inHouseBinding, dataHandlingTier: "PROVIDER_KEPT", instanceModelId: 21, ready: false },
			],
		});
		reviewsInHouse().getByText("Ready");
		row("Practice reviews", "Provider, kept for safety checks").getByText("Not ready");
		expect(row("Practice reviews", "Provider, nothing kept").queryByText(/ready/i)).toBeNull();
	});

	it("tells an admin to clear a moved model when no other model of the row's tier exists", () => {
		renderPage({
			bindings: [{ ...inHouseBinding, instanceModelId: 21 }],
			availableModels: [keptModel],
		});
		const inHouse = reviewsInHouse();
		expect(inHouse.getByRole("combobox", { name: /Stays in-house/ }).hasAttribute("disabled")).toBe(
			true,
		);
		expect(inHouse.getByText(/is now declared as/).textContent).toBe(
			"GPT Other is now declared as Provider, kept for safety checks and no longer serves this row. Clear the assignment, or ask your host for a model declared as Stays in-house.",
		);
	});

	it("shows the binding it saves, so the payload cannot disagree with the controls", () => {
		const { onSave } = renderPage();

		const inHouse = reviewsInHouse();
		inHouse.getByText(/GPT Test/);
		expect(
			inHouse.getByRole("switch", { name: /^Use this model/ }).getAttribute("aria-checked"),
		).toBe("true");

		fireEvent.click(saveButton(inHouse));

		expect(onSave).toHaveBeenCalledWith(
			{ purpose: "PRACTICE_REVIEW", tier: "IN_HOUSE" },
			expect.objectContaining({ instanceModelId: 20, enabled: true }),
		);
	});

	it("shows the server's refusal on the row it refused", () => {
		renderPage({
			saveErrors: {
				"PRACTICE_REVIEW:IN_HOUSE":
					"This model is declared as Provider, kept for safety checks; assign it to that row.",
			},
		});
		const inHouse = reviewsInHouse();
		expect(inHouse.getByRole("alert").textContent).toBe(
			"This model is declared as Provider, kept for safety checks; assign it to that row.",
		);
		expect(
			inHouse.getByRole("combobox", { name: /Stays in-house/ }).getAttribute("aria-invalid"),
		).toBe("true");
		expect(
			row("Practice reviews", "Provider, kept for safety checks").queryByRole("alert"),
		).toBeNull();
	});

	it("exposes the advanced settings as a disclosure", () => {
		renderPage();

		const inHouse = reviewsInHouse();
		const trigger = inHouse.getByRole("button", { name: /^Advanced/ });
		expect(trigger.getAttribute("aria-expanded")).toBe("false");
		expect(inHouse.queryByLabelText(/^Timeout \(seconds\)/)).toBeNull();

		fireEvent.click(trigger);

		expect(trigger.getAttribute("aria-expanded")).toBe("true");
		timeoutInput(inHouse);
	});

	it("refuses to save a cleared timeout instead of sending a zero", () => {
		const { onSave } = renderPage();
		const inHouse = reviewsInHouse();

		fireEvent.click(inHouse.getByRole("button", { name: /^Advanced/ }));
		fireEvent.change(timeoutInput(inHouse), { target: { value: "" } });
		fireEvent.click(saveButton(inHouse));

		inHouse.getByText("Enter a number of seconds.");
		expect(timeoutInput(inHouse).getAttribute("aria-invalid")).toBe("true");
		expect(onSave).not.toHaveBeenCalled();
	});

	it("rejects a timeout below the floor and only saves once it is corrected", () => {
		const { onSave } = renderPage();
		const inHouse = reviewsInHouse();

		fireEvent.click(inHouse.getByRole("button", { name: /^Advanced/ }));
		const timeout = timeoutInput(inHouse);
		fireEvent.change(timeout, { target: { value: "5" } });
		fireEvent.click(saveButton(inHouse));

		inHouse.getByText("Enter a whole number of seconds, 30 or more.");
		expect(onSave).not.toHaveBeenCalled();

		fireEvent.change(timeout, { target: { value: "45" } });
		fireEvent.click(saveButton(inHouse));

		expect(onSave).toHaveBeenCalledWith(
			{ purpose: "PRACTICE_REVIEW", tier: "IN_HOUSE" },
			expect.objectContaining({ timeoutSeconds: 45 }),
		);
	});

	it("rejects a timeout above the ceiling, says why, and accepts the ceiling itself", () => {
		const { onSave } = renderPage();
		const inHouse = reviewsInHouse();

		fireEvent.click(inHouse.getByRole("button", { name: /^Advanced/ }));
		const timeout = timeoutInput(inHouse);
		fireEvent.change(timeout, { target: { value: "14400" } });
		fireEvent.click(saveButton(inHouse));

		inHouse.getByText("Runs stop after three hours, so enter 10800 seconds or less.");
		expect(timeout.getAttribute("aria-invalid")).toBe("true");
		expect(onSave).not.toHaveBeenCalled();

		fireEvent.change(timeout, { target: { value: "10800" } });
		fireEvent.click(saveButton(inHouse));

		expect(onSave).toHaveBeenCalledWith(
			{ purpose: "PRACTICE_REVIEW", tier: "IN_HOUSE" },
			expect.objectContaining({ timeoutSeconds: 10800 }),
		);
	});

	it("reopens the advanced disclosure when the field that blocked the save is inside it", () => {
		renderPage();
		const inHouse = reviewsInHouse();

		fireEvent.click(inHouse.getByRole("button", { name: /^Advanced/ }));
		fireEvent.change(inHouse.getByLabelText(/^Max concurrent runs/), { target: { value: "0" } });
		fireEvent.click(inHouse.getByRole("button", { name: /^Advanced/ }));
		expect(inHouse.queryByLabelText(/^Max concurrent runs/)).toBeNull();

		fireEvent.click(saveButton(inHouse));

		inHouse.getByText("Enter a whole number of runs, 1 or more.");
		expect(document.activeElement).toBe(inHouse.getByLabelText(/^Max concurrent runs/));
	});

	it("keeps unrelated validation errors visible while another field is corrected", () => {
		renderPage();
		const inHouse = reviewsInHouse();

		fireEvent.click(inHouse.getByRole("button", { name: /^Advanced/ }));
		const timeout = timeoutInput(inHouse);
		const concurrency = inHouse.getByLabelText(/^Max concurrent runs/);
		fireEvent.change(timeout, { target: { value: "" } });
		fireEvent.change(concurrency, { target: { value: "0" } });
		fireEvent.click(saveButton(inHouse));

		fireEvent.change(timeout, { target: { value: "60" } });

		expect(inHouse.queryByText("Enter a number of seconds.")).toBeNull();
		inHouse.getByText("Enter a whole number of runs, 1 or more.");
	});

	it("offers Clear assignment only for a row that is actually bound", () => {
		const { onTurnOff } = renderPage();

		const clearAssignment = reviewsInHouse().getByRole("button", { name: /^Clear assignment/ });
		expect(screen.getAllByRole("button", { name: /^Clear assignment/ })).toHaveLength(1);

		fireEvent.click(clearAssignment);

		expect(onTurnOff).toHaveBeenCalledWith({ purpose: "PRACTICE_REVIEW", tier: "IN_HOUSE" });
	});
});
