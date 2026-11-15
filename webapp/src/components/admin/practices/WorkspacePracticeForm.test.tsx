import { Link } from "@tanstack/react-router";
import { fireEvent, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { currentDefinition } from "@/components/admin/practices/practice-definition-fields";

import type { UpdatePracticeRequest } from "@/api/types.gen";
import { mockDefinitionOptions } from "@/components/admin/practice-editor/fixtures";
import { renderWithRouter } from "@/test/router-harness";

import { mockGroups, mockPractice } from "./fixtures";
import { WorkspacePracticeForm } from "./WorkspacePracticeForm";

type EditSubmit = (request: UpdatePracticeRequest, groupSlug: string | null) => void;

async function renderEdit(onSubmit: EditSubmit) {
	return renderWithRouter(
		<WorkspacePracticeForm
			mode="edit"
			workspaceSlug="team"
			groups={mockGroups}
			definitionOptions={mockDefinitionOptions}
			initialData={mockPractice}
			isPending={false}
			cancel={<Link to="/">Cancel</Link>}
			onSubmit={onSubmit}
		/>,
		`/w/team/admin/practices?detail=practice-edit:${mockPractice.slug}`,
	);
}

describe("editing a workspace practice", () => {
	it("sends one complete definition pinned to the version it was edited from", async () => {
		const onSubmit = vi.fn<EditSubmit>();
		await renderEdit(onSubmit);

		fireEvent.change(await screen.findByRole("textbox", { name: /Name/u }), {
			target: { value: "Describe the change" },
		});
		fireEvent.click(screen.getByRole("button", { name: "Save changes" }));

		await waitFor(() =>
			expect(onSubmit).toHaveBeenCalledExactlyOnceWith(
				{
					expectedVersionId: mockPractice.currentVersion.id,
					definition: { ...currentDefinition(mockPractice), name: "Describe the change" },
				},
				mockPractice.groupSlug,
			),
		);
	});

	it("says which version a save creates, and that results stay", async () => {
		await renderEdit(vi.fn());

		await screen.findByText(/the change becomes version 2/u);
	});
});
