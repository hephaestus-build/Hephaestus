import { Link } from "@tanstack/react-router";
import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { assert, describe, expect, it, vi } from "vitest";

import { bindingsProblem } from "@/components/admin/practice-editor/bindings";
import { buttonVariants } from "@/components/ui/button";
import {
	mockAuthorDeclaredEvidenceValidation,
	mockPracticeDefinitionOptions,
	mockPullRequestBinding,
	mockPullRequestPolicy,
	mockPullRequestWorkType,
} from "@/mocks/fixtures/practice";
import { renderWithRouter } from "@/test/router-harness";

import {
	CuratedPracticeForm,
	type CuratedPracticeFormInitialValue,
	type CuratedPracticeFormValue,
} from "./CuratedPracticeForm";

/**
 * A cancel that navigates, which is what `useUnsavedChanges` guards. In the app it is a
 * `DrawerClose` whose close ends in the same place — a router navigation off the level.
 */
const cancel = (
	<Link to="/admin/catalog" className={buttonVariants({ variant: "outline" })}>
		Cancel
	</Link>
);

vi.mock("@/components/common/CodeEditor", () => ({
	CodeEditor: () => <div />,
}));

const initialData: CuratedPracticeFormInitialValue = {
	slug: "clear-pr-description",
	name: "Write a clear pull request description",
	bindings: [mockPullRequestBinding],
	criteria: "Review whether the description explains the change.",
	automatedReviewPolicy: mockPullRequestPolicy,
	automatedReviewValidation: mockAuthorDeclaredEvidenceValidation,
	status: {
		etag: "tag",
		state: "FROM_HEPHAESTUS",
		changeKind: "NONE",
		offered: true,
	},
};

function submitSpy() {
	return vi.fn<(value: CuratedPracticeFormValue) => void>();
}

async function renderForm(
	overrides: Partial<CuratedPracticeFormInitialValue> = {},
	onSubmit = submitSpy(),
) {
	return renderWithRouter(
		<CuratedPracticeForm
			mode="edit"
			cancel={cancel}
			groups={[]}
			definitionOptions={mockPracticeDefinitionOptions}
			initialData={{ ...initialData, ...overrides }}
			isPending={false}
			onSubmit={onSubmit}
		/>,
		"/admin/catalog/practices/clear-pr-description",
	);
}

function occasion() {
	return within(screen.getByRole("group", { name: "Reviews when" }));
}

/**
 * A moment that recurs carries "every time" under its label, which is part of the node's accessible
 * name, so the name is matched by prefix rather than in full.
 */
function moment(signal: string) {
	const option = mockPullRequestWorkType.signals.find((candidate) => candidate.signal === signal);
	if (!option) {
		throw new Error(`No signal option for ${signal}`);
	}
	return new RegExp(`^${option.displayName}`, "u");
}

describe("CuratedPracticeForm", () => {
	it("associates validation messages with invalid fields", async () => {
		await renderWithRouter(
			<CuratedPracticeForm
				mode="create"
				cancel={cancel}
				groups={[]}
				definitionOptions={mockPracticeDefinitionOptions}
				isPending={false}
				onSubmit={submitSpy()}
			/>,
			"/admin/catalog/new",
		);

		fireEvent.click(screen.getByRole("button", { name: "Create practice" }));
		const name = screen.getByRole("textbox", { name: /Name/u });
		expect(name.getAttribute("aria-invalid")).toBe("true");
		expect(
			screen.getByRole("textbox", { name: /What to look for/u }).getAttribute("aria-describedby"),
		).toBe("practice-criteria-description practice-criteria-error");
		expect(screen.queryByRole("textbox", { name: "Identifier" })).toBeNull();
	});

	it("starts a new practice on the recommended moments rather than an empty occasion", async () => {
		await renderWithRouter(
			<CuratedPracticeForm
				mode="create"
				cancel={cancel}
				groups={[]}
				definitionOptions={mockPracticeDefinitionOptions}
				isPending={false}
				onSubmit={submitSpy()}
			/>,
			"/admin/catalog/new",
		);

		// The server sorts work types alphabetically; which kind leads the picker is presentation.
		expect(
			screen.getByRole("radio", { name: /Pull or merge request/u }).getAttribute("aria-checked"),
		).toBe("true");
		expect(
			occasion()
				.getByRole("checkbox", { name: /^Opened/u })
				.getAttribute("aria-checked"),
		).toBe("true");
	});

	it("asks before discarding an edited draft", async () => {
		await renderWithRouter(
			<CuratedPracticeForm
				mode="create"
				cancel={cancel}
				groups={[]}
				definitionOptions={mockPracticeDefinitionOptions}
				isPending={false}
				onSubmit={submitSpy()}
			/>,
			"/admin/catalog/new",
		);

		fireEvent.change(screen.getByRole("textbox", { name: /Name/u }), {
			target: { value: "A new practice" },
		});
		fireEvent.click(screen.getByRole("link", { name: "Cancel" }));

		await screen.findByRole("alertdialog", { name: "Discard unsaved changes?" });
		fireEvent.click(screen.getByRole("button", { name: "Keep editing" }));
		await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());
		screen.getByDisplayValue("A new practice");
	});

	it("does not call a freshly loaded practice edited", async () => {
		await renderForm();

		// The server sorts a binding's signals and needs on the way in. Loading one and touching
		// nothing must not look like an edit, or every visit would offer to discard a draft.
		fireEvent.click(screen.getByRole("link", { name: "Cancel" }));
		await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());
	});

	it("keeps the draft when the discard prompt is dismissed with Escape", async () => {
		await renderWithRouter(
			<CuratedPracticeForm
				mode="create"
				cancel={cancel}
				groups={[]}
				definitionOptions={mockPracticeDefinitionOptions}
				isPending={false}
				onSubmit={submitSpy()}
			/>,
			"/admin/catalog/new",
		);

		fireEvent.change(screen.getByRole("textbox", { name: /Name/u }), {
			target: { value: "A new practice" },
		});
		fireEvent.click(screen.getByRole("link", { name: "Cancel" }));
		await screen.findByRole("alertdialog", { name: "Discard unsaved changes?" });

		fireEvent.keyDown(document, { key: "Escape" });

		await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());
		screen.getByDisplayValue("A new practice");
	});

	it("preserves the draft and blocks saving after an edit conflict", async () => {
		await renderWithRouter(
			<CuratedPracticeForm
				mode="edit"
				cancel={cancel}
				groups={[]}
				definitionOptions={mockPracticeDefinitionOptions}
				initialData={initialData}
				isPending={false}
				conflict
				onContinueWithDraft={vi.fn<() => void>()}
				onSubmit={submitSpy()}
			/>,
			"/admin/catalog/practices/clear-pr-description",
		);

		screen.getByDisplayValue("Write a clear pull request description");
		expect(screen.getByRole<HTMLButtonElement>("button", { name: "Save changes" }).disabled).toBe(
			true,
		);
		expect(
			screen.getByRole<HTMLButtonElement>("button", { name: "Continue with my draft" }).disabled,
		).toBe(false);
	});

	it("submits the one occasion the practice is reviewed on", async () => {
		const user = userEvent.setup();
		const onSubmit = submitSpy();
		await renderForm({}, onSubmit);

		// Nothing adds a second: a practice that would read different evidence at a different moment is
		// a second practice, which is what the server asks for.
		expect(screen.queryByRole("button", { name: /Add occasion/u })).toBeNull();
		await user.click(occasion().getByRole("checkbox", { name: /^Merged/u }));
		await user.click(screen.getByRole("button", { name: "Save changes" }));

		const submitted = onSubmit.mock.calls[0]?.[0];
		assert(submitted);
		expect(submitted.bindings).toHaveLength(1);
		const [occasionSubmitted] = submitted.bindings;
		// Sorted the way the server stores them, so an untouched practice is not dirty on the way back.
		expect(occasionSubmitted.signals).toStrictEqual(
			["scm.pull_request.merged", ...mockPullRequestBinding.signals].sort(),
		);
	});

	it("sends focus to the moments when none is chosen, not to the top of the form", async () => {
		const user = userEvent.setup();
		const onSubmit = submitSpy();
		await renderForm({}, onSubmit);

		for (const signal of mockPullRequestBinding.signals) {
			await user.click(occasion().getByRole("checkbox", { name: moment(signal) }));
		}
		await user.click(screen.getByRole("button", { name: "Save changes" }));

		expect(onSubmit).not.toHaveBeenCalled();
		// A refusal raises two alerts: the summary at the top of the form listing every one, and this
		// one at the field it is about — hence `getAllByRole` and the `some`. The inline alert is what
		// this test is for; a summary alone would put the message a screenful from the control that
		// fixes it.
		expect(
			screen
				.getAllByRole("alert")
				.some((alert) => alert.textContent === "Choose when this practice is reviewed."),
		).toBe(true);
		// The strip stays on screen with nothing ticked, rather than taking the kind of work — and the
		// only way to tick one again — with it. A message at the bottom of a long form is not the fix.
		expect(occasion().getAllByRole("checkbox")).toHaveLength(6);
		await waitFor(() => expect(document.activeElement?.id).toBe("practice-occasion-signals"));
	});

	it("lets the review claim something is absent from a source it reads whole", async () => {
		const user = userEvent.setup();
		const onSubmit = submitSpy();
		await renderForm({}, onSubmit);

		await user.click(
			within(screen.getByRole("group", { name: "What this review reads" })).getByRole("button", {
				name: "Choose sources",
			}),
		);
		await user.click(
			within(
				screen.getByRole("radiogroup", {
					name: "How Review threads and decisions is used",
				}),
			).getByRole("radio", { name: "Required" }),
		);
		await user.click(
			screen.getByRole("checkbox", { name: /absent from Review threads and decisions/u }),
		);
		await user.click(screen.getByRole("button", { name: "Save changes" }));

		expect(onSubmit.mock.calls[0]?.[0].bindings[0].needs).toContainEqual({
			sourceKind: "scm.review-threads",
			stance: "EXHAUSTIVE",
		});
	});

	it("never offers an absence claim over a source that can never be captured whole", async () => {
		const user = userEvent.setup();
		await renderForm();

		await user.click(
			within(screen.getByRole("group", { name: "What this review reads" })).getByRole("button", {
				name: "Choose sources",
			}),
		);
		await user.click(
			within(screen.getByRole("radiogroup", { name: "How Linked work items is used" })).getByRole(
				"radio",
				{ name: "Required" },
			),
		);

		// Absent rather than present-and-refused on save: the source contract cannot promise a whole
		// capture of the linked work items, so no claim about what is absent from them can rest on it.
		expect(screen.queryByRole("checkbox", { name: /absent from Linked work items/u })).toBeNull();
	});

	it("strips the occasion's evidence when the practice stops being reviewed", async () => {
		const user = userEvent.setup();
		const onSubmit = submitSpy();
		await renderForm({}, onSubmit);

		await user.click(screen.getByRole("radio", { name: /Guidance only/u }));
		await user.click(screen.getByRole("button", { name: "Save changes" }));

		const submitted = onSubmit.mock.calls[0]?.[0];
		assert(submitted);
		expect(submitted.bindings.map((binding) => binding.needs)).toStrictEqual([[]]);
		expect(submitted.automatedReviewPolicy.automatedReview).toStrictEqual({
			mode: "NONE",
			evidenceSufficiency: "NONE",
		});
		expect(submitted.automatedReviewPolicy.knownLimitations).toStrictEqual([]);
		await user.click(screen.getByRole("button", { name: /Technical settings/u }));
		expect(screen.queryByText("Static analysis")).toBeNull();
	});

	it("gives the occasion its evidence back when review resumes", async () => {
		const user = userEvent.setup();
		const onSubmit = submitSpy();
		await renderForm({}, onSubmit);

		await user.click(screen.getByRole("radio", { name: /Guidance only/u }));
		await user.click(screen.getByRole("radio", { name: /AI-supported mentoring/u }));
		await user.click(screen.getByRole("button", { name: "Save changes" }));

		// Saveable, not merely non-empty: a list of purely contextual sources is longer than zero and
		// still refused, so what has to hold is the rule the form itself enforces.
		const submitted = onSubmit.mock.calls[0]?.[0];
		assert(submitted);
		const [resumed] = submitted.bindings;
		expect(
			bindingsProblem(resumed, submitted.automatedReviewPolicy, mockPullRequestWorkType),
		).toBeUndefined();
	});

	it("does not schedule mentoring when a practice needs human review", async () => {
		const user = userEvent.setup();
		const onSubmit = submitSpy();
		await renderForm({ precomputeScript: "export default {};" }, onSubmit);

		await user.click(screen.getByRole("radio", { name: /Human review needed/u }));
		await user.type(
			screen.getByRole("textbox", { name: /Why is human review needed/u }),
			"A mentor must discuss the developer's reasoning.",
		);
		await user.click(screen.getByRole("button", { name: /Technical settings/u }));
		expect(screen.queryByText("Static analysis")).toBeNull();

		await user.click(screen.getByRole("button", { name: "Save changes" }));
		const submitted = onSubmit.mock.calls[0]?.[0];
		assert(submitted);
		expect(submitted.precomputeScript).toBeUndefined();
		expect(submitted.automatedReviewPolicy.automatedReview).toStrictEqual({
			mode: "LANGUAGE_MODEL",
			evidenceSufficiency: "DECLARED_EVIDENCE_INSUFFICIENT",
		});
	});

	it("keeps limitation drafts when the reviewed work type changes", async () => {
		const user = userEvent.setup();
		await renderForm();

		await user.click(screen.getByRole("button", { name: "Add limitation" }));
		const limitationDescription = screen.getByRole("textbox", {
			name: "Description for limitation 2",
		});
		await user.type(limitationDescription, "Keep this limitation");
		await user.click(screen.getByRole("radio", { name: /^Issue/u }));
		await user.click(screen.getByRole("radio", { name: /Pull or merge request/u }));

		screen.getByDisplayValue("Keep this limitation");
	});

	it("keeps the mentoring choice when the reviewed work type changes", async () => {
		const user = userEvent.setup();
		await renderForm();

		await user.click(screen.getByRole("radio", { name: /Guidance only/u }));
		await user.click(screen.getByRole("radio", { name: /^Issue/u }));
		expect(screen.getByRole("radio", { name: /Guidance only/u }).getAttribute("aria-checked")).toBe(
			"true",
		);
	});

	it("keeps each work type's moments to itself", async () => {
		const user = userEvent.setup();
		await renderForm();

		await user.click(screen.getByRole("radio", { name: /^Issue/u }));
		await user.click(occasion().getByRole("checkbox", { name: /^Closed$/u }));
		await user.click(screen.getByRole("radio", { name: /Pull or merge request/u }));
		expect(
			occasion()
				.getByRole("checkbox", { name: /^Opened/u })
				.getAttribute("aria-checked"),
		).toBe("true");
		expect(occasion().queryByRole("checkbox", { name: /^Closed$/u })).toBeNull();

		await user.click(screen.getByRole("radio", { name: /^Issue/u }));
		expect(
			occasion()
				.getByRole("checkbox", { name: /^Closed$/u })
				.getAttribute("aria-checked"),
		).toBe("true");
	});

	it("asks about drafts only where a draft can exist", async () => {
		const user = userEvent.setup();
		await renderForm();

		screen.getByRole("switch", { name: /^Include drafts/u });

		await user.click(screen.getByRole("radio", { name: /^Issue/u }));

		// An issue can never be a draft, so the control for that state is gone rather than inert.
		expect(screen.queryByRole("switch", { name: /^Include drafts/u })).toBeNull();
	});
});
