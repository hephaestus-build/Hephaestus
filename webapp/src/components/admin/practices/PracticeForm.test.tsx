import { Link } from "@tanstack/react-router";
import { fireEvent, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";

import type { Practice, UpdatePracticeRequest } from "@/api/types.gen";
import {
	mockAuthorDeclaredEvidenceValidation,
	mockPracticeDefinitionOptions,
	mockPullRequestBinding,
	mockMergeBinding,
	mockPullRequestPolicy,
} from "@/mocks/fixtures/practice";
import { renderWithRouter } from "@/test/router-harness";

import { PracticeForm } from "./PracticeForm";

vi.mock("@/components/common/CodeEditor", () => ({ CodeEditor: () => <div /> }));

const gate = {
	absentSays: "the change has no Swift code",
	anyOf: [{ changedPathMatches: ["**/*.swift"] }],
};

function practice(
	binding: Practice["bindings"][number],
	extraBindings: Practice["bindings"] = [],
): Practice {
	return {
		id: 1,
		slug: "review-swift",
		name: "Review Swift",
		bindings: [binding, ...extraBindings],
		criteria: "Check the work carefully.",
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
	binding: Practice["bindings"][number],
	onSubmit: (slug: string, request: UpdatePracticeRequest, group: string | null) => void,
	extraBindings: Practice["bindings"] = [],
) {
	return renderWithRouter(
		<PracticeForm
			mode="edit"
			workspaceSlug="team"
			groups={[]}
			definitionOptions={mockPracticeDefinitionOptions}
			initialData={practice(binding, extraBindings)}
			isPending={false}
			cancel={<Link to="/">Cancel</Link>}
			onSubmit={onSubmit}
		/>,
		"/w/team/admin/practices/review-swift",
	);
}

describe("workspace practice scope", () => {
	it.each([
		["gate", { ...mockPullRequestBinding, appliesWhen: gate }],
		["reviewer", { ...mockPullRequestBinding, subject: "REVIEWER" as const }],
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
				bindings: undefined,
				bindingChanges: undefined,
			}),
			null,
		);
	});

	it("shows the gate and reviewer when both are set", async () => {
		await renderPractice(
			{ ...mockPullRequestBinding, appliesWhen: gate, subject: "REVIEWER" },
			vi.fn(),
		);
		expect(screen.getByRole("textbox", { name: "Only review when" })).toHaveProperty(
			"value",
			JSON.stringify(gate, null, 2),
		);
		expect(
			screen.getByRole("combobox", { name: "Person this practice judges" }).textContent,
		).toContain("reviewer");
	});

	it("does not collapse an older two-occasion practice on a name-only save", async () => {
		const onSubmit =
			vi.fn<(slug: string, request: UpdatePracticeRequest, group: string | null) => void>();
		await renderPractice(mockPullRequestBinding, onSubmit, [mockMergeBinding]);
		fireEvent.change(screen.getByRole("textbox", { name: /Name/u }), {
			target: { value: "Review Swift code" },
		});
		fireEvent.click(screen.getByRole("button", { name: "Save changes" }));
		expect(onSubmit).toHaveBeenCalledWith(
			"review-swift",
			expect.objectContaining({ bindings: undefined }),
			null,
		);
	});

	it("marks a reviewer-to-author change as deliberate", async () => {
		const onSubmit =
			vi.fn<(slug: string, request: UpdatePracticeRequest, group: string | null) => void>();
		await renderPractice({ ...mockPullRequestBinding, subject: "REVIEWER" }, onSubmit);
		const user = userEvent.setup();
		await user.click(screen.getByRole("combobox", { name: "Person this practice judges" }));
		await user.click(screen.getByRole("option", { name: "author" }));
		await waitFor(() =>
			expect(
				screen.getByRole("combobox", { name: "Person this practice judges" }).textContent,
			).toContain("author"),
		);
		fireEvent.click(screen.getByRole("button", { name: "Save changes" }));
		expect(onSubmit).toHaveBeenCalledWith(
			"review-swift",
			expect.objectContaining({
				bindingChanges: ["SUBJECT"],
				bindings: [expect.objectContaining({ subject: "AUTHOR" })],
			}),
			null,
		);
	});

	it("marks a gate removal as deliberate", async () => {
		const onSubmit =
			vi.fn<(slug: string, request: UpdatePracticeRequest, group: string | null) => void>();
		await renderPractice({ ...mockPullRequestBinding, appliesWhen: gate }, onSubmit);
		fireEvent.change(screen.getByRole("textbox", { name: "Only review when" }), {
			target: { value: "" },
		});
		fireEvent.click(screen.getByRole("button", { name: "Save changes" }));
		expect(onSubmit).toHaveBeenCalledWith(
			"review-swift",
			expect.objectContaining({
				bindingChanges: ["APPLIES_WHEN"],
				bindings: [expect.not.objectContaining({ appliesWhen: gate })],
			}),
			null,
		);
	});
});
