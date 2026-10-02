import { Link } from "@tanstack/react-router";
import { fireEvent, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";

import type { Practice, UpdatePracticeRequest } from "@/api/types.gen";
import {
	mockAuthorDeclaredEvidenceValidation,
	mockPracticeDefinitionOptions,
	mockPullRequestReviewFields,
	mockPullRequestPolicy,
} from "@/mocks/fixtures/practice";
import { renderWithRouter } from "@/test/router-harness";

import type { PracticeReviewFields } from "@/components/admin/practice-editor/review-settings";
import { PracticeForm } from "./PracticeForm";

vi.mock("@/components/common/CodeEditor", () => ({ CodeEditor: () => <div /> }));

const gate = {
	skipReason: "the change has no Swift code",
	anyOf: [{ changedPathMatches: ["**/*.swift"] }],
};

function practice(reviewFields: PracticeReviewFields): Practice {
	return {
		id: 1,
		slug: "review-swift",
		name: "Review Swift",
		...reviewFields,
		criteria: "Check the work carefully.",
		deliveryBehavior: { summaryOnly: false },
		automatedReviewPolicy: mockPullRequestPolicy,
		automatedReviewValidation: mockAuthorDeclaredEvidenceValidation,
		autonomy: { effective: "AUTOMATIC", inherited: false, source: "PRACTICE" },
		artifactKind: "scm.pull_request",
		createdAt: new Date("2026-01-01"),
		updatedAt: new Date("2026-01-01"),
		displayOrder: 0,
	};
}

async function renderPractice(
	reviewFields: PracticeReviewFields,
	onSubmit: (slug: string, request: UpdatePracticeRequest, group: string | null) => void,
) {
	return renderWithRouter(
		<PracticeForm
			mode="edit"
			workspaceSlug="team"
			groups={[]}
			definitionOptions={mockPracticeDefinitionOptions}
			initialData={practice(reviewFields)}
			isPending={false}
			cancel={<Link to="/">Cancel</Link>}
			onSubmit={onSubmit}
		/>,
		"/w/team/admin/practices/review-swift",
	);
}

describe("workspace practice scope", () => {
	it("sends declared feedback delivery choices from the shared editor", async () => {
		const onSubmit =
			vi.fn<(slug: string, request: UpdatePracticeRequest, group: string | null) => void>();
		await renderPractice(mockPullRequestReviewFields, onSubmit);
		const user = userEvent.setup();
		await user.click(screen.getByRole("button", { name: /Technical settings/u }));
		await user.click(screen.getByRole("switch", { name: /Show this practice in the summary/u }));
		await user.type(
			screen.getByRole("textbox", { name: "Preferred practice slug" }),
			"preferred-practice",
		);
		await user.click(screen.getByRole("button", { name: "Save changes" }));

		expect(onSubmit).toHaveBeenCalledWith(
			"review-swift",
			expect.objectContaining({
				deliveryBehavior: { summaryOnly: true, redundantToSlug: "preferred-practice" },
			}),
			null,
		);
	});

	it("keeps an invalid preferred slug in the editor with a field error", async () => {
		const onSubmit = vi.fn();
		await renderPractice(mockPullRequestReviewFields, onSubmit);
		const user = userEvent.setup();
		await user.click(screen.getByRole("button", { name: /Technical settings/u }));
		await user.type(
			screen.getByRole("textbox", { name: "Preferred practice slug" }),
			"Invalid Slug",
		);
		await user.click(screen.getByRole("button", { name: "Save changes" }));

		expect(onSubmit).not.toHaveBeenCalled();
		expect(
			screen.getByRole("textbox", { name: "Preferred practice slug" }).getAttribute("aria-invalid"),
		).toBe("true");
		expect(screen.getAllByText("Use lowercase letters, numbers, and single hyphens.")).toHaveLength(
			2,
		);
	});

	it.each([
		["gate", { ...mockPullRequestReviewFields, precondition: gate }],
		["reviewer", { ...mockPullRequestReviewFields, subject: "REVIEWER" as const }],
	])("keeps the %s on a name-only save", async (_label, binding) => {
		const onSubmit =
			vi.fn<(slug: string, request: UpdatePracticeRequest, group: string | null) => void>();
		await renderPractice(binding, onSubmit);
		fireEvent.change(screen.getByRole("textbox", { name: /Name/u }), {
			target: { value: "Review Swift code" },
		});
		fireEvent.click(screen.getByRole("button", { name: "Save changes" }));
		expect(onSubmit).toHaveBeenCalledWith(
			"review-swift",
			expect.objectContaining({
				...binding,
				definitionChanges: undefined,
			}),
			null,
		);
	});

	it("shows the gate and reviewer when both are set", async () => {
		await renderPractice(
			{ ...mockPullRequestReviewFields, precondition: gate, subject: "REVIEWER" },
			vi.fn(),
		);
		expect(screen.getByRole("textbox", { name: "Only review when" })).toHaveProperty(
			"value",
			JSON.stringify(gate, null, 2),
		);
		expect(
			screen.getByRole("combobox", { name: "Person this practice judges" }).textContent,
		).toContain("Reviewer");
	});

	it("marks a reviewer-to-author change as deliberate", async () => {
		const onSubmit =
			vi.fn<(slug: string, request: UpdatePracticeRequest, group: string | null) => void>();
		await renderPractice({ ...mockPullRequestReviewFields, subject: "REVIEWER" }, onSubmit);
		const user = userEvent.setup();
		await user.click(screen.getByRole("combobox", { name: "Person this practice judges" }));
		await user.click(await screen.findByRole("option", { name: "Author" }));
		await waitFor(() =>
			expect(
				screen.getByRole("combobox", { name: "Person this practice judges" }).textContent,
			).toContain("Author"),
		);
		fireEvent.click(screen.getByRole("button", { name: "Save changes" }));
		expect(onSubmit).toHaveBeenCalledWith(
			"review-swift",
			expect.objectContaining({
				definitionChanges: ["SUBJECT"],
				subject: "AUTHOR",
			}),
			null,
		);
	});

	it("marks a gate removal as deliberate", async () => {
		const onSubmit =
			vi.fn<(slug: string, request: UpdatePracticeRequest, group: string | null) => void>();
		await renderPractice({ ...mockPullRequestReviewFields, precondition: gate }, onSubmit);
		fireEvent.change(screen.getByRole("textbox", { name: "Only review when" }), {
			target: { value: "" },
		});
		fireEvent.click(screen.getByRole("button", { name: "Save changes" }));
		expect(onSubmit).toHaveBeenCalledWith(
			"review-swift",
			expect.objectContaining({
				definitionChanges: ["PRECONDITION"],
				precondition: undefined,
				clear: ["PRECONDITION", "PRECOMPUTE_SCRIPT", "WHY_IT_MATTERS", "WHAT_GOOD_LOOKS_LIKE"],
			}),
			null,
		);
	});
});
