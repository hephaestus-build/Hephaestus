import { Link } from "@tanstack/react-router";
import { act, fireEvent, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import {
	mockPracticeDefinitionOptions,
	mockPullRequestReviewFields,
	mockPullRequestPolicy,
} from "@/mocks/fixtures/practice";
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

describe("the gate field", () => {
	it("does not save malformed JSON while keeping the draft visible", async () => {
		const onSubmit = vi.fn();
		await renderCreateForm(onSubmit);
		fillValidDraft();
		fireEvent.change(screen.getByRole("textbox", { name: "Only review when" }), {
			target: { value: "{" },
		});
		fireEvent.click(screen.getByRole("button", { name: "Create practice" }));
		expect(onSubmit).not.toHaveBeenCalled();
		expect(screen.getByRole("textbox", { name: "Only review when" })).toHaveProperty("value", "{");
		expect(screen.getAllByText("Enter valid JSON for the gate.").length).toBeGreaterThan(0);
	});
});

it("restores a work-type draft without a gate instead of submitting another draft’s gate", async () => {
	const onSubmit = vi.fn();
	await renderCreateForm(onSubmit);
	fillValidDraft();
	fireEvent.click(screen.getByRole("radio", { name: /^Issue/u }));
	fireEvent.change(screen.getByRole("textbox", { name: "Only review when" }), {
		target: {
			value: '{"skipReason":"No matching paths","anyOf":[{"changedPathMatches":["**/*.ts"]}]}',
		},
	});
	fireEvent.click(screen.getByRole("radio", { name: /^Pull or merge request/u }));
	expect(screen.getByRole("textbox", { name: "Only review when" })).toHaveProperty("value", "");
	fireEvent.click(screen.getByRole("button", { name: "Create practice" }));
	await waitFor(() =>
		expect(onSubmit).toHaveBeenCalledWith(
			expect.objectContaining({ name: "Explain what changed and why" }),
		),
	);
	expect(onSubmit.mock.calls[0]?.[0]).not.toHaveProperty("precondition");
});

it("restores each gate draft and refuses an invalid restored draft", async () => {
	const onSubmit = vi.fn();
	await renderCreateForm(onSubmit);
	fillValidDraft();
	const gate = '{"skipReason":"No matching paths","anyOf":[{"changedPathMatches":["**/*.ts"]}]}';
	fireEvent.click(screen.getByRole("radio", { name: /^Issue/u }));
	fireEvent.change(screen.getByRole("textbox", { name: "Only review when" }), {
		target: { value: gate },
	});
	fireEvent.click(screen.getByRole("radio", { name: /^Pull or merge request/u }));
	fireEvent.click(screen.getByRole("radio", { name: /^Issue/u }));
	expect(screen.getByRole("textbox", { name: "Only review when" })).toHaveProperty("value", gate);
	fireEvent.change(screen.getByRole("textbox", { name: "Only review when" }), {
		target: { value: "{" },
	});
	fireEvent.click(screen.getByRole("radio", { name: /^Pull or merge request/u }));
	fireEvent.click(screen.getByRole("radio", { name: /^Issue/u }));
	expect(screen.getByRole("textbox", { name: "Only review when" })).toHaveProperty("value", "{");
	fireEvent.click(screen.getByRole("button", { name: "Create practice" }));
	expect(onSubmit).not.toHaveBeenCalled();
	expect(screen.getAllByText("Enter valid JSON for the gate.").length).toBeGreaterThan(0);
});

describe("the identifier of an existing practice", () => {
	it("does not change when the practice is renamed", async () => {
		const onSubmit = vi.fn();
		await renderWithRouter(
			<PracticeDefinitionForm
				mode="edit"
				groups={[]}
				definitionOptions={mockPracticeDefinitionOptions}
				initialData={{
					...mockPullRequestReviewFields,
					slug: "reviewable-diffs",
					name: "Small changes",
					criteria: "Changes must remain reviewable.",
					automatedReviewPolicy: mockPullRequestPolicy,
				}}
				isPending={false}
				cancelAction={<Link to="/">Cancel</Link>}
				onSubmit={onSubmit}
			/>,
			"/admin/practices/new",
		);
		await openTechnicalSettings();
		fireEvent.change(nameField(), { target: { value: "Small, reviewable changes" } });
		expect(slugField().value).toBe("reviewable-diffs");
		fireEvent.click(screen.getByRole("button", { name: "Save changes" }));
		await waitFor(() =>
			expect(onSubmit).toHaveBeenCalledWith(
				expect.objectContaining({ slug: "reviewable-diffs", name: "Small, reviewable changes" }),
			),
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

describe("the questions and rules", () => {
	it("sends the starting questions with a practice Hephaestus reviews", async () => {
		const onSubmit = vi.fn();
		await renderCreateForm(onSubmit);
		fillValidDraft();
		fireEvent.click(screen.getByRole("button", { name: "Create practice" }));
		await waitFor(() =>
			expect(onSubmit).toHaveBeenCalledWith(
				expect.objectContaining({ judgment: mockPracticeDefinitionOptions.startingJudgment }),
			),
		);
	});

	it("sends no questions for a practice that is guidance only", async () => {
		const onSubmit = vi.fn<(value: PracticeDefinitionValue) => void>();
		await renderCreateForm(onSubmit);
		fillValidDraft();
		fireEvent.click(screen.getByRole("radio", { name: /Guidance only/u }));
		expect(screen.queryByRole("heading", { name: "How the review decides" })).toBeNull();
		fireEvent.click(screen.getByRole("button", { name: "Create practice" }));
		await waitFor(() =>
			expect(onSubmit).toHaveBeenCalledWith(
				expect.objectContaining({ name: "Explain what changed and why" }),
			),
		);
		expect(onSubmit.mock.calls[0]?.[0]).not.toHaveProperty("judgment");
	});

	it("refuses a save while a rule can never decide, and says why", async () => {
		const onSubmit = vi.fn();
		await renderCreateForm(onSubmit);
		fillValidDraft();
		fireEvent.click(screen.getByRole("button", { name: "Add rule" }));
		fireEvent.click(screen.getByRole("button", { name: "Create practice" }));
		expect(onSubmit).not.toHaveBeenCalled();
		screen.getByText("Rule 5 needs a reason of 10–200 characters: one sentence about the work.");
	});
});
