import { fireEvent, type queries, render, screen, within } from "@testing-library/react";
import { useState } from "react";
import { describe, expect, it, vi } from "vitest";
import { renderWithRouter } from "@/test/router-harness";

import type { AgentBinding, AvailableLlmModel, PracticePrecomputeSummary } from "@/api/types.gen";

import { AgentBindingsPage, type AgentBindingsPageProps } from "./AgentBindingsPage";

const inHouseModel: AvailableLlmModel = {
	dataHandlingTier: "IN_HOUSE",
	id: 20,
	scope: "SHARED",
	displayName: "GPT Test",
	connectionDisplayName: "Shared OpenAI",
	pricingMode: "NO_CHARGE",
	purposes: ["PRACTICE_REVIEW", "MENTOR"],
};

const cloudModel: AvailableLlmModel = {
	dataHandlingTier: "CLOUD",
	id: 21,
	scope: "SHARED",
	displayName: "GPT Other",
	connectionDisplayName: "Shared OpenAI",
	pricingMode: "NO_CHARGE",
	purposes: ["PRACTICE_REVIEW", "MENTOR"],
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
	servedTiers: ["IN_HOUSE", "CLOUD"],
};

function Page(overrides: Partial<AgentBindingsPageProps>) {
	return (
		<AgentBindingsPage
			workspaceSlug="demo"
			state={{ status: "ready" }}
			bindings={[inHouseBinding]}
			availableModels={[inHouseModel, cloudModel]}
			practicesEnabled
			aiChoiceRequired={false}
			precomputeNeeds={{ status: "ready", summaries: [] }}
			ownProviderAllowed
			pendingWrites={new Map()}
			onSave={vi.fn()}
			onTurnOff={vi.fn()}
			{...overrides}
		/>
	);
}

function renderAt(overrides: Partial<AgentBindingsPageProps>) {
	return render(<Page {...overrides} />);
}

function renderPage(overrides: Partial<AgentBindingsPageProps> = {}) {
	const onSave = vi.fn();
	const onTurnOff = vi.fn();
	renderAt({ onSave, onTurnOff, ...overrides });
	return { onSave, onTurnOff };
}

type Scope = ReturnType<typeof within<typeof queries>>;

const UNCHOSEN_ROW = "Members who have not chosen";

/** The purpose's row, opened if it is closed: a closed row holds no forms. */
function purposeRegion(purpose: string): Scope {
	const trigger = screen.getByRole("button", { name: purpose });
	if (trigger.getAttribute("aria-expanded") !== "true") {
		fireEvent.click(trigger);
	}
	return within(screen.getByRole("region", { name: purpose }));
}

function row(purpose: string, title: string): Scope {
	return within(purposeRegion(purpose).getByRole("group", { name: title }));
}

const reviewsInHouse = () => row("Practice reviews", "In-house");

const saveButton = (scope: Scope) => scope.getByRole("button", { name: /^Save assignment/u });
const timeoutInput = (scope: Scope) =>
	scope.getByLabelText<HTMLInputElement>(/^Timeout \(seconds\)/u);

describe("AgentBindingsPage", () => {
	it("renders one row per tier under each purpose, with the undeclared row last", () => {
		renderPage();
		const reviews = purposeRegion("Practice reviews");
		const rowNames = reviews
			.getAllByRole("heading", { level: 4 })
			.map((heading) => heading.textContent);
		expect(rowNames).toStrictEqual(["In-house", "Cloud", UNCHOSEN_ROW]);

		const unassignedSwitch = row("Heph", "In-house").getByRole("switch", {
			name: /^Use this model/u,
		});
		expect(unassignedSwitch.getAttribute("aria-checked")).toBe("false");
		expect(unassignedSwitch.getAttribute("aria-disabled")).toBe("true");
	});

	it("shows the binding it saves, so the payload cannot disagree with the controls", () => {
		const { onSave } = renderPage();

		const inHouse = reviewsInHouse();
		inHouse.getByText(/GPT Test/u);
		expect(
			inHouse.getByRole("switch", { name: /^Use this model/u }).getAttribute("aria-checked"),
		).toBe("true");

		fireEvent.click(saveButton(inHouse));

		expect(onSave).toHaveBeenCalledWith(
			{ purpose: "PRACTICE_REVIEW", tier: "IN_HOUSE" },
			expect.objectContaining({ instanceModelId: 20, enabled: true }),
		);
	});

	it("exposes the advanced settings as a disclosure", () => {
		renderPage();

		const inHouse = reviewsInHouse();
		const trigger = inHouse.getByRole("button", { name: /^Advanced/u });
		expect(trigger.getAttribute("aria-expanded")).toBe("false");
		expect(inHouse.queryByLabelText(/^Timeout \(seconds\)/u)).toBeNull();

		fireEvent.click(trigger);

		expect(trigger.getAttribute("aria-expanded")).toBe("true");
		timeoutInput(inHouse);
	});

	it("refuses to save a cleared timeout instead of sending a zero", () => {
		const { onSave } = renderPage();
		const inHouse = reviewsInHouse();

		fireEvent.click(inHouse.getByRole("button", { name: /^Advanced/u }));
		fireEvent.change(timeoutInput(inHouse), { target: { value: "" } });
		fireEvent.click(saveButton(inHouse));

		inHouse.getByText("Enter a number of seconds.");
		expect(timeoutInput(inHouse).getAttribute("aria-invalid")).toBe("true");
		expect(onSave).not.toHaveBeenCalled();
	});

	it("rejects a timeout below the floor and only saves once it is corrected", () => {
		const { onSave } = renderPage();
		const inHouse = reviewsInHouse();

		fireEvent.click(inHouse.getByRole("button", { name: /^Advanced/u }));
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

		fireEvent.click(inHouse.getByRole("button", { name: /^Advanced/u }));
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
			expect.objectContaining({ timeoutSeconds: 10_800 }),
		);
	});

	it("reopens the advanced disclosure when the field that blocked the save is inside it", () => {
		renderPage();
		const inHouse = reviewsInHouse();

		fireEvent.click(inHouse.getByRole("button", { name: /^Advanced/u }));
		fireEvent.change(inHouse.getByLabelText(/^Max concurrent runs/u), { target: { value: "0" } });
		fireEvent.click(inHouse.getByRole("button", { name: /^Advanced/u }));
		expect(inHouse.queryByLabelText(/^Max concurrent runs/u)).toBeNull();

		fireEvent.click(saveButton(inHouse));

		inHouse.getByText("Enter a whole number of runs, 1 or more.");
		expect(document.activeElement).toBe(inHouse.getByLabelText(/^Max concurrent runs/u));
	});

	it("keeps unrelated validation errors visible while another field is corrected", () => {
		renderPage();
		const inHouse = reviewsInHouse();

		fireEvent.click(inHouse.getByRole("button", { name: /^Advanced/u }));
		const timeout = timeoutInput(inHouse);
		const concurrency = inHouse.getByLabelText(/^Max concurrent runs/u);
		fireEvent.change(timeout, { target: { value: "" } });
		fireEvent.change(concurrency, { target: { value: "0" } });
		fireEvent.click(saveButton(inHouse));

		fireEvent.change(timeout, { target: { value: "60" } });

		expect(inHouse.queryByText("Enter a number of seconds.")).toBeNull();
		inHouse.getByText("Enter a whole number of runs, 1 or more.");
	});

	it("offers Clear assignment only for a row that is actually bound", () => {
		const { onTurnOff } = renderPage();

		const clearAssignment = reviewsInHouse().getByRole("button", { name: /^Clear assignment/u });
		expect(screen.getAllByRole("button", { name: /^Clear assignment/u })).toHaveLength(1);

		fireEvent.click(clearAssignment);

		expect(onTurnOff).toHaveBeenCalledWith({ purpose: "PRACTICE_REVIEW", tier: "IN_HOUSE" });
	});
});

const expanded = (name: string) =>
	screen.getByRole("button", { name }).getAttribute("aria-expanded");

describe("AgentBindingsPage rows", () => {
	const decisionModel: AvailableLlmModel = {
		...cloudModel,
		id: 30,
		displayName: "Decider",
		purposes: ["PRACTICE_DECISION"],
	};
	const needs: PracticePrecomputeSummary[] = [
		{
			practiceSlug: "comment-quality",
			practiceName: "Comment quality",
			asOf: { jobId: "job-1", finishedAt: new Date("2026-10-03T09:00:00") },
			scriptChanged: false,
			needs: [
				{
					purpose: "PRACTICE_DECISION",
					need: "REQUIRED",
					unmetTiers: ["IN_HOUSE", "CLOUD", "UNDECLARED"],
				},
			],
		},
	];

	it("opens a precompute row whose required model some members lack, and says who", async () => {
		await renderWithRouter(
			<Page
				availableModels={[inHouseModel, cloudModel, decisionModel]}
				precomputeNeeds={{ status: "ready", summaries: needs }}
			/>,
			"/",
		);
		expect(expanded("Decision model")).toBe("true");
		expect(expanded("Embedding model")).toBe("false");
		expect(screen.getByRole("note").textContent).toBe(
			"1 practice needs a decision model for In-house and Cloud members and members who have not chosen. Until you assign one, its precompute script does not run, and the review checks the practice without its help.",
		);
		expect(
			screen.getByRole("button", { name: "Decision model" }).getAttribute("aria-describedby"),
		).not.toBeNull();
		screen.getByText("Needs attention", { ignore: ".sr-only" });
	});

	it("opens a precompute row once, when needs that arrive after the page ask for it", async () => {
		function NeedsArriveLater() {
			const [precomputeNeeds, setPrecomputeNeeds] = useState<
				AgentBindingsPageProps["precomputeNeeds"]
			>({ status: "loading" });
			return (
				<>
					<button
						type="button"
						onClick={() => setPrecomputeNeeds({ status: "ready", summaries: [...needs] })}
					>
						Needs arrive
					</button>
					<Page
						availableModels={[inHouseModel, cloudModel, decisionModel]}
						precomputeNeeds={precomputeNeeds}
					/>
				</>
			);
		}
		await renderWithRouter(<NeedsArriveLater />, "/");
		expect(expanded("Decision model")).toBe("false");
		fireEvent.click(screen.getByRole("button", { name: "Needs arrive" }));
		expect(expanded("Decision model")).toBe("true");
		// Decided once: the reader's close holds while the same needs come back.
		fireEvent.click(screen.getByRole("button", { name: "Decision model" }));
		fireEvent.click(screen.getByRole("button", { name: "Needs arrive" }));
		expect(expanded("Decision model")).toBe("false");
	});
});
