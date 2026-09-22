import { Link } from "@tanstack/react-router";
import { act, fireEvent, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { mockPracticeDefinitionOptions } from "@/mocks/fixtures/practice";
import { deferred } from "@/test/async";
import { renderWithRouter } from "@/test/router-harness";

import { PracticeDefinitionForm, type PracticeDefinitionValue } from "./PracticeDefinitionForm";

vi.mock("@/components/common/CodeEditor", () => ({
	CodeEditor: () => <div />,
}));

async function renderCreateForm(
	onSubmit: (value: PracticeDefinitionValue) => void | Promise<void>,
) {
	return renderWithRouter(
		<PracticeDefinitionForm
			mode="create"
			groups={[]}
			definitionOptions={mockPracticeDefinitionOptions}
			isPending={false}
			cancelAction={<Link to="/">Cancel</Link>}
			onSubmit={onSubmit}
		/>,
		"/admin/practices/new",
	);
}

const nameField = () => screen.getByRole<HTMLInputElement>("textbox", { name: /Name/u });
const slugField = () => screen.getByRole<HTMLInputElement>("textbox", { name: "Identifier" });

async function openTechnicalSettings() {
	fireEvent.click(screen.getByRole("button", { name: /Technical settings/u }));
	return screen.findByRole("textbox", { name: "Identifier" });
}

function fillValidDraft() {
	fireEvent.change(nameField(), { target: { value: "Explain what changed and why" } });
	fireEvent.change(screen.getByRole("textbox", { name: /What to look for/u }), {
		target: { value: "Look for a description that explains the behaviour change." },
	});
}

describe("the identifier a practice is created under", () => {
	it("follows the name until an author writes one of their own", async () => {
		await renderCreateForm(vi.fn());
		await openTechnicalSettings();

		fireEvent.change(nameField(), { target: { value: "Small changes" } });
		expect(slugField().value).toBe("small-changes");

		// The identifier cannot be changed after the practice exists, so an author who takes it over
		// has made a decision the name is not allowed to overwrite behind them.
		fireEvent.change(slugField(), { target: { value: "reviewable-diffs" } });
		fireEvent.change(nameField(), { target: { value: "Small, reviewable changes" } });

		expect(slugField().value).toBe("reviewable-diffs");
		expect(nameField().value).toBe("Small, reviewable changes");
	});

	it("can be handed back to the name", async () => {
		await renderCreateForm(vi.fn());
		await openTechnicalSettings();

		fireEvent.change(nameField(), { target: { value: "Small changes" } });
		fireEvent.change(slugField(), { target: { value: "reviewable-diffs" } });
		fireEvent.click(screen.getByRole("button", { name: "Reset to generated identifier" }));

		expect(slugField().value).toBe("small-changes");

		fireEvent.change(nameField(), { target: { value: "Small, reviewable changes" } });
		expect(slugField().value).toBe("small-reviewable-changes");
	});
});

/**
 * `isPending` drops the instant the mutation resolves and the caller navigates on the next line, so
 * a guard released on it races that navigation and asks to discard a save that just succeeded.
 */
describe("the unsaved-changes guard around a save", () => {
	it("stays out of the way of a caller navigating after a successful save", async () => {
		const saved = deferred<undefined>();
		const { router } = await renderCreateForm(async () => saved.promise);
		fillValidDraft();

		fireEvent.click(screen.getByRole("button", { name: "Create practice" }));
		saved.resolve(undefined);
		await saved.promise;
		fireEvent.click(screen.getByRole("link", { name: "Cancel" }));

		// The navigation went through rather than merely not having been interrupted yet.
		await waitFor(() => expect(router.state.location.pathname).toBe("/"));
		expect(screen.queryByRole("alertdialog")).toBeNull();
	});

	it("comes back down when the save is refused", async () => {
		const failed = deferred<undefined>();
		await renderCreateForm(async () => failed.promise);
		fillValidDraft();

		fireEvent.click(screen.getByRole("button", { name: "Create practice" }));
		// The guard re-arms from `track`'s rejection handler, so the click below has to wait for
		// React to have processed it.
		await act(async () => {
			failed.reject(new Error("Conflict"));
			await Promise.allSettled([failed.promise]);
		});
		fireEvent.click(screen.getByRole("link", { name: "Cancel" }));

		// The draft is still the only copy of this practice, so leaving has to be a decision again.
		await screen.findByRole("alertdialog", { name: "Discard unsaved changes?" });
	});

	it("is left exactly as it was by a caller that says nothing", async () => {
		// A caller returning `void` is not reporting success — holding the guard down on that would
		// leave a failed save unprotected for good.
		await renderCreateForm(vi.fn());
		fillValidDraft();

		fireEvent.click(screen.getByRole("button", { name: "Create practice" }));
		fireEvent.click(screen.getByRole("link", { name: "Cancel" }));

		await screen.findByRole("alertdialog", { name: "Discard unsaved changes?" });
	});
});
