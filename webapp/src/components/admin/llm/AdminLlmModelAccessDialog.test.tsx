import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import type { LlmModel } from "@/api/types.gen";

import { AdminLlmModelAccessDialog } from "./AdminLlmModelAccessDialog";

const model: LlmModel = {
	processingLocation: "UNCLASSIFIED",
	id: 7,
	slug: "gpt-5",
	displayName: "GPT-5",
	upstreamModelId: "gpt-5",
	connectionId: 1,
	connectionDisplayName: "OpenAI",
	enabled: true,
	supportsReasoning: true,
	visibility: "PUBLIC",
	grantedWorkspaceIds: [],
	createdAt: new Date("2026-07-01T00:00:00Z"),
};

const workspaces = [
	{ id: 10, displayName: "Alpha", workspaceSlug: "alpha" },
	{ id: 11, displayName: "Beta", workspaceSlug: "beta" },
];

describe("AdminLlmModelAccessDialog", () => {
	it("makes an access reduction and its immediate effect explicit", () => {
		render(
			<AdminLlmModelAccessDialog
				open
				onOpenChange={vi.fn()}
				model={model}
				workspaceOptions={workspaces}
				isSubmitting={false}
				onSave={vi.fn()}
			/>,
		);

		fireEvent.click(screen.getByRole("radio", { name: /^Selected workspaces/i }));
		screen.getByText(/stop in the workspaces you removed/i);
		screen.getByText(/no workspace will be able to use this model/i);
	});

	it("saves the selected workspace allowlist", async () => {
		const onSave = vi.fn();
		render(
			<AdminLlmModelAccessDialog
				open
				onOpenChange={vi.fn()}
				model={{ ...model, visibility: "GRANTED", grantedWorkspaceIds: [10] }}
				workspaceOptions={workspaces}
				isSubmitting={false}
				onSave={onSave}
			/>,
		);

		fireEvent.click(screen.getByRole("combobox", { name: "Workspaces" }));
		fireEvent.click(await screen.findByRole("option", { name: /beta/i }));

		expect(screen.getByRole("option", { name: /alpha/i }).getAttribute("aria-selected")).toBe(
			"true",
		);
		expect(screen.getByRole("option", { name: /beta/i }).getAttribute("aria-selected")).toBe(
			"true",
		);
		expect(screen.getByRole("combobox", { name: "Workspaces" }).textContent).toContain(
			"2 selected",
		);

		fireEvent.click(screen.getByRole("button", { name: "Save access" }));

		expect(onSave).toHaveBeenCalledWith({ visibility: "GRANTED", workspaceIds: [10, 11] });
	});

	it("keeps an unsaved selection when the model is refetched underneath it", async () => {
		const granted = { ...model, visibility: "GRANTED" as const, grantedWorkspaceIds: [10, 11] };
		const props = {
			open: true as const,
			onOpenChange: vi.fn(),
			workspaceOptions: workspaces,
			isSubmitting: false,
			onSave: vi.fn(),
		};
		const { rerender } = render(<AdminLlmModelAccessDialog {...props} model={granted} />);

		fireEvent.click(screen.getByRole("combobox", { name: "Workspaces" }));
		fireEvent.click(await screen.findByRole("option", { name: /beta/i }));
		expect(screen.getByRole("option", { name: /beta/i }).getAttribute("aria-selected")).toBe(
			"false",
		);

		rerender(
			<AdminLlmModelAccessDialog
				{...props}
				model={{ ...granted, grantedWorkspaceIds: [10, 11] }}
			/>,
		);

		expect(screen.getByRole("option", { name: /beta/i }).getAttribute("aria-selected")).toBe(
			"false",
		);
		expect(screen.getByRole("combobox", { name: "Workspaces" }).textContent).toContain("Alpha");
	});

	it("distinguishes future restrictions from removing current workspace access", async () => {
		render(
			<AdminLlmModelAccessDialog
				open
				onOpenChange={vi.fn()}
				model={model}
				workspaceOptions={workspaces}
				isSubmitting={false}
				onSave={vi.fn()}
			/>,
		);

		fireEvent.click(screen.getByRole("radio", { name: /^Selected workspaces/i }));
		fireEvent.click(screen.getByRole("combobox", { name: "Workspaces" }));
		fireEvent.click(await screen.findByRole("option", { name: /alpha/i }));
		fireEvent.click(await screen.findByRole("option", { name: /beta/i }));

		screen.getByText("Future workspaces will need an explicit grant");
		expect(screen.queryByText(/stop in the workspaces you removed/i)).toBeNull();
	});

	it("can grant public access even when the workspace directory is unavailable", () => {
		const onSave = vi.fn();
		render(
			<AdminLlmModelAccessDialog
				open
				onOpenChange={vi.fn()}
				model={{ ...model, visibility: "GRANTED", grantedWorkspaceIds: [10] }}
				workspaceOptions={[]}
				workspacesError={{ status: 503, detail: "Directory unavailable." }}
				isSubmitting={false}
				onSave={onSave}
			/>,
		);

		screen.getByText("Could not load workspaces");
		expect(screen.getByRole<HTMLButtonElement>("button", { name: "Save access" }).disabled).toBe(
			true,
		);

		fireEvent.click(screen.getByRole("radio", { name: /^All workspaces/i }));
		const save = screen.getByRole<HTMLButtonElement>("button", { name: "Save access" });
		expect(save.disabled).toBe(false);

		fireEvent.click(save);
		expect(onSave).toHaveBeenCalledWith({ visibility: "PUBLIC" });
	});
});
