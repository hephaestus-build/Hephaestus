import { Link } from "@tanstack/react-router";
import { act, fireEvent, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, type Mock, vi } from "vitest";

import { deferred } from "@/test/async";
import { renderWithRouter } from "@/test/router-harness";

import { mockDefinitionOptions, mockScan, practiceValueOf } from "./fixtures";
import {
	PracticeDefinitionForm,
	type PracticeDefinitionSubmission,
} from "./PracticeDefinitionForm";

vi.mock("@/components/common/CodeEditor", () => ({
	CodeEditor: () => <div />,
}));

type Submit = (value: PracticeDefinitionSubmission) => void | Promise<void>;

async function renderCreateForm(onSubmit: Submit) {
	return renderWithRouter(
		<PracticeDefinitionForm
			mode="create"
			groups={[]}
			definitionOptions={mockDefinitionOptions}
			isPending={false}
			cancelAction={<Link to="/">Cancel</Link>}
			onSubmit={onSubmit}
		/>,
		"/admin/practices/new",
	);
}

async function renderEditForm(onSubmit: Submit, initialData = practiceValueOf()) {
	return renderWithRouter(
		<PracticeDefinitionForm
			mode="edit"
			groups={[{ slug: "review-ready-work", name: "Review-ready work" }]}
			definitionOptions={mockDefinitionOptions}
			initialData={initialData}
			isPending={false}
			cancelAction={<Link to="/">Cancel</Link>}
			onSubmit={onSubmit}
		/>,
		"/admin/practices/new",
	);
}

function submitted(onSubmit: Mock<Submit>): PracticeDefinitionSubmission {
	const [value] = onSubmit.mock.lastCall ?? [];
	if (value === undefined) {
		throw new Error("The form submitted nothing");
	}
	return value;
}

const nameField = () => screen.getByRole<HTMLInputElement>("textbox", { name: /^Name/u });
const slugField = () => screen.getByRole<HTMLInputElement>("textbox", { name: "Identifier" });
const appliesWhenField = () =>
	screen.getByRole<HTMLTextAreaElement>("textbox", { name: "File paths 1" });

async function openTechnicalSettings() {
	fireEvent.click(screen.getByRole("button", { name: /Technical settings/u }));
	return screen.findByRole("textbox", { name: "Identifier" });
}

async function fillValidDraft() {
	fireEvent.change(nameField(), { target: { value: "Explain what changed and why" } });
	fireEvent.change(screen.getByRole("textbox", { name: /What to look for/u }), {
		target: { value: "Look for a description that explains the behaviour change." },
	});
	fireEvent.click(screen.getByRole("button", { name: "Choose sources" }));
	const core = await screen.findByRole("radiogroup", { name: "How Pull request details is read" });
	fireEvent.click(within(core).getByRole("radio", { name: "Required" }));
}

describe("the definition an edit submits", () => {
	it("gives back an untouched practice as the one closed definition it loaded", async () => {
		const onSubmit = vi.fn<Submit>();
		await renderEditForm(onSubmit, practiceValueOf({ scan: mockScan }));
		fireEvent.click(screen.getByRole("button", { name: "Save changes" }));
		await waitFor(() => expect(onSubmit).toHaveBeenCalledOnce());
		expect(submitted(onSubmit)).toStrictEqual(practiceValueOf({ scan: mockScan }));
	});

	it("preserves saved scan settings after a name change without showing scan controls", async () => {
		const scan = { ...mockScan, reads: { ...mockScan.reads, contract: "1.0.0" } };
		const onSubmit = vi.fn<Submit>();
		await renderEditForm(onSubmit, practiceValueOf({ scan }));
		expect(screen.queryByRole("switch", { name: "Include in scans" })).toBeNull();
		fireEvent.change(nameField(), { target: { value: "Explain every change" } });
		fireEvent.click(screen.getByRole("button", { name: "Save changes" }));
		await waitFor(() => expect(onSubmit).toHaveBeenCalledOnce());
		expect(submitted(onSubmit).definition.scan).toStrictEqual(scan);
	});

	it("shows the kind of an existing practice without offering to change it", async () => {
		await renderEditForm(vi.fn());
		expect(screen.queryByRole("radio", { name: /^Issue/u })).toBeNull();
		const note = screen.getByText("You cannot change this after the practice exists.");
		expect(note.previousElementSibling?.textContent).toBe("Pull request");
	});

	it("saves Going well, which every practice can say", async () => {
		const onSubmit = vi.fn<Submit>();
		await renderEditForm(onSubmit);
		fireEvent.change(screen.getByRole("textbox", { name: "Going well" }), {
			target: { value: "You keep every change to one purpose." },
		});
		fireEvent.click(screen.getByRole("button", { name: "Save changes" }));
		await waitFor(() => expect(onSubmit).toHaveBeenCalledOnce());
		expect(submitted(onSubmit).definition.guidance.goingWell).toBe(
			"You keep every change to one purpose.",
		);
	});

	it("keeps the identifier when the practice is renamed", async () => {
		const onSubmit = vi.fn<Submit>();
		await renderEditForm(onSubmit);
		await openTechnicalSettings();
		fireEvent.change(nameField(), { target: { value: "Small, reviewable changes" } });
		expect(slugField().value).toBe("keep-the-change-reviewable");
		fireEvent.click(screen.getByRole("button", { name: "Save changes" }));
		await waitFor(() => expect(onSubmit).toHaveBeenCalledOnce());
		expect(submitted(onSubmit)).toMatchObject({
			slug: "keep-the-change-reviewable",
			definition: { name: "Small, reviewable changes" },
		});
	});
});

describe("Reviewed by", () => {
	it("says what a save with Nobody clears, and clears it", async () => {
		const onSubmit = vi.fn<Submit>();
		await renderEditForm(
			onSubmit,
			practiceValueOf({ script: "export default 1;", scan: mockScan }),
		);
		fireEvent.click(screen.getByRole("radio", { name: /^Nobody/u }));
		screen.getByText(
			"Saving with Nobody clears the read sources, the practice script and the scan settings. Choose Hephaestus again before you save to keep them.",
		);
		fireEvent.click(screen.getByRole("button", { name: "Save changes" }));
		await waitFor(() => expect(onSubmit).toHaveBeenCalledOnce());
		expect(submitted(onSubmit).definition).toMatchObject({
			reviewedBy: { by: "NOBODY", humanReason: null, limits: [] },
			reads: { sources: [] },
			script: null,
			scan: null,
		});
	});

	it("refuses People without a reason, and saves the reason it is given", async () => {
		const onSubmit = vi.fn<Submit>();
		await renderEditForm(onSubmit);
		fireEvent.click(screen.getByRole("radio", { name: /^People/u }));
		fireEvent.click(screen.getByRole("button", { name: "Save changes" }));
		expect(onSubmit).not.toHaveBeenCalled();
		expect(
			screen.getAllByText("Say why people review this practice, in at most 500 characters.").length,
		).toBeGreaterThan(0);

		fireEvent.change(screen.getByRole("textbox", { name: /Why do people review it/u }), {
			target: { value: "The decision was made in a meeting." },
		});
		fireEvent.click(screen.getByRole("button", { name: "Save changes" }));
		await waitFor(() => expect(onSubmit).toHaveBeenCalledOnce());
		expect(submitted(onSubmit).definition.reviewedBy).toStrictEqual({
			by: "PEOPLE",
			humanReason: {
				description: "The decision was made in a meeting.",
			},
			limits: [],
		});
	});
});

describe("About", () => {
	it("offers the work itself and saves it as no role", async () => {
		const onSubmit = vi.fn<Submit>();
		await renderEditForm(onSubmit, practiceValueOf({ about: null }));
		expect(screen.getByRole("combobox", { name: /^About/u }).textContent).toContain("The work");
		fireEvent.click(screen.getByRole("button", { name: "Save changes" }));
		await waitFor(() => expect(onSubmit).toHaveBeenCalledOnce());
		expect(submitted(onSubmit).definition.about).toBeNull();
	});
});

describe("Applies when", () => {
	it("does not save an empty condition while keeping it visible", async () => {
		const onSubmit = vi.fn<Submit>();
		await renderEditForm(onSubmit);
		fireEvent.click(screen.getByRole("button", { name: "Add condition" }));
		fireEvent.click(screen.getByRole("button", { name: "Save changes" }));
		expect(onSubmit).not.toHaveBeenCalled();
		expect(appliesWhenField().value).toBe("");
		expect(screen.getAllByText("Enter a value for each condition.").length).toBeGreaterThan(0);
	});

	it("asks for Otherwise once there are conditions, and saves both", async () => {
		const onSubmit = vi.fn<Submit>();
		await renderEditForm(onSubmit);
		fireEvent.click(screen.getByRole("button", { name: "Add condition" }));
		fireEvent.change(appliesWhenField(), { target: { value: "**/*.swift" } });
		fireEvent.change(screen.getByRole("textbox", { name: /^Otherwise/u }), {
			target: { value: "the change touches no Swift source file" },
		});
		fireEvent.click(screen.getByRole("button", { name: "Save changes" }));
		await waitFor(() => expect(onSubmit).toHaveBeenCalledOnce());
		expect(submitted(onSubmit).definition.appliesWhen).toStrictEqual({
			anyOf: [{ changedPath: ["**/*.swift"] }],
			otherwise: "the change touches no Swift source file",
		});
	});
});

describe("the kind a practice is created for", () => {
	it("offers no applicability conditions for the issue options returned by the server", async () => {
		await renderCreateForm(vi.fn());
		fireEvent.click(screen.getByRole("radio", { name: /^Issue/u }));
		expect(screen.getByRole<HTMLButtonElement>("button", { name: "Add condition" }).disabled).toBe(
			true,
		);
		expect(screen.queryByRole("combobox", { name: "Condition 1" })).toBeNull();
		expect(screen.queryByRole("textbox", { name: "File paths 1" })).toBeNull();
	});

	it("restores each kind's conditions when the item kind changes", async () => {
		await renderCreateForm(vi.fn());
		fireEvent.click(screen.getByRole("radio", { name: /^Pull request review/u }));
		fireEvent.click(screen.getByRole("button", { name: "Add condition" }));
		const user = userEvent.setup();
		await user.click(screen.getByRole("combobox", { name: "Source 1" }));
		await user.click(await screen.findByRole("option", { name: "Review threads and decisions" }));
		fireEvent.click(screen.getByRole("radio", { name: /^Pull request\b(?! review)/u }));
		expect(screen.queryByRole("combobox", { name: "Source 1" })).toBeNull();
		fireEvent.click(screen.getByRole("radio", { name: /^Pull request review/u }));
		expect(screen.getByRole("combobox", { name: "Source 1" }).textContent).toContain(
			"Review threads and decisions",
		);
	});
});

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
		await fillValidDraft();

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
		await fillValidDraft();

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
		await fillValidDraft();

		fireEvent.click(screen.getByRole("button", { name: "Create practice" }));
		fireEvent.click(screen.getByRole("link", { name: "Cancel" }));

		await screen.findByRole("alertdialog", { name: "Discard unsaved changes?" });
	});
});

describe("the practice script section", () => {
	it("waits closed as the built-in review, and links its guide", async () => {
		await renderCreateForm(vi.fn());
		const section = screen.getByRole("button", { name: /^Script/u });
		expect(section.getAttribute("aria-expanded")).toBe("false");
		screen.getByText(/^Built-in\./u);
		fireEvent.click(section);

		const guide = await screen.findByRole("link", { name: /^Write a practice script/u });
		// A test build is not a release, so it reads the docs of the next release.
		expect(guide.getAttribute("href")).toBe(
			"https://docs.hephaestus.build/next/admin/practice-scripts",
		);
	});

	it("opens with the script a practice already has", async () => {
		await renderEditForm(vi.fn(), practiceValueOf({ script: "export default 1;" }));
		const section = screen.getByRole("button", { name: /^Script/u });
		expect(section.getAttribute("aria-expanded")).toBe("true");
	});

	it("is not offered while people review the practice", async () => {
		await renderEditForm(vi.fn());
		fireEvent.click(screen.getByRole("radio", { name: /^People/u }));
		expect(screen.queryByRole("button", { name: /^Script/u })).toBeNull();
	});
});
