import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen } from "storybook/test";

import type { ReviewObservation } from "@/api/types.gen";
import { expectNoPageOverflow } from "@/test/reflow";

import { ObservationResults } from "./ObservationResults";
import { reviewObservations, workspacePractices } from "./story-mock-data";

const [firstObservation] = reviewObservations;
if (!firstObservation) throw new Error("The review fixtures must contain an observation");

const longContent = {
	...firstObservation,
	id: "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
	summary:
		"The review keeps every boundary visible even when the observation needs enough words to wrap across a narrow screen twice over",
	subject: {
		id: 10,
		login: "alexandria-occasional-contributor",
		name: "Alexandria Catherine Montgomery-Worthington",
	},
} satisfies ReviewObservation;

/** Storybook resets a spy that appears in `args` between runs, so one instance is enough. */
const clearFilters = fn();

/** The practice several of the fixture's observations name, and the one the hover card is read on. */
const THIN_CONTROLLERS = workspacePractices.find((p) => p.slug === "thin-controllers");
if (!THIN_CONTROLLERS) throw new Error("The practice fixtures no longer cover thin-controllers");

const meta = {
	title: "Workspace admin/Practice reviews/Building blocks/Observation results",
	component: ObservationResults,
	parameters: {
		layout: "padded",
		chromatic: { viewports: [320, 768, 1440] },
	},
	tags: ["autodocs"],
	args: {
		workspaceSlug: "demo",
		state: { status: "ready", observations: reviewObservations },
		// A row names its practice but carries none of its prose, so the screen hands the list down and
		// each row reads its own record out of it. Handed over as a prop, not fetched: see
		// `ReviewPracticeLink`.
		practices: workspacePractices,
	},
} satisfies Meta<typeof ObservationResults>;

export default meta;
type Story = StoryObj<typeof meta>;

/** The mixed observation list from the review fixtures. */
export const Default: Story = {
	play: async ({ canvas }) => {
		// Negative outcomes show severity; unassessed observations do not imply an outcome.
		await expect(canvas.getAllByText("Negative outcome")).toHaveLength(7);
		canvas.getByText("Critical");
		canvas.getByText("Informational");
		await expect(canvas.getAllByText("Minor")).toHaveLength(3);
		canvas.getByText("Not applicable");
		await expect(canvas.queryByText("Undetermined")).not.toBeInTheDocument();
		await expect(canvas.getAllByText("From a review of past work")).toHaveLength(2);
		await expect(canvas.getAllByText("Requested by hand")).toHaveLength(2);
		await expect(canvas.queryAllByText("No result")).toHaveLength(0);
	},
};

/** Captured evidence can remain ambiguous without implying a positive or negative outcome. */
export const Undetermined: Story = {
	args: {
		state: {
			status: "ready",
			observations: [
				{
					...firstObservation,
					assessmentStatus: "UNDETERMINED",
					presence: undefined,
					assessment: undefined,
					outcome: undefined,
					severity: undefined,
					summary: "The captured discussion leaves the chosen approach ambiguous",
				},
			],
		},
	},
	play: async ({ canvas }) => {
		canvas.getByText("Undetermined");
		await expect(canvas.queryByText("Positive outcome")).not.toBeInTheDocument();
		await expect(canvas.queryByText("Negative outcome")).not.toBeInTheDocument();
		await expect(canvas.queryByText("Critical")).not.toBeInTheDocument();
	},
};

/**
 * The practice on a row does two things: it opens the practice, and it says what the practice is
 * without leaving the list. Both are checked, because the card is the half that goes quiet on its
 * own — a row that stops being handed its practice record still renders a perfectly good link.
 */
export const PracticeOpensItsDefinition: Story = {
	parameters: { chromatic: { disableSnapshot: true } },
	play: async ({ canvas, userEvent }) => {
		// Several observations name this practice, and every one of them reaches the same definition.
		const links = await canvas.findAllByRole("link", { name: /Thin controllers/ });
		for (const link of links) {
			await expect(link).toHaveAttribute("href", "/w/demo/admin/practices/thin-controllers");
		}
		// The card is a portal, so it is looked for on the whole screen rather than in the canvas.
		const [firstLink] = links;
		if (!firstLink) throw new Error("No row named the practice");
		await userEvent.hover(firstLink);
		await screen.findByText(THIN_CONTROLLERS.whyItMatters ?? "");
		await screen.findByText(THIN_CONTROLLERS.whatGoodLooksLike ?? "");
	},
};

export const WithoutPracticeRecords: Story = {
	args: { practices: undefined },
	parameters: { chromatic: { disableSnapshot: true } },
	play: async ({ canvas, userEvent }) => {
		const [link] = await canvas.findAllByRole("link", { name: /Thin controllers/ });
		if (!link) throw new Error("No row named the practice");
		await expect(link).toHaveAttribute("href", "/w/demo/admin/practices/thin-controllers");
		await userEvent.hover(link);
		await expect(screen.queryByText(THIN_CONTROLLERS.whyItMatters ?? "")).not.toBeInTheDocument();
	},
};

export const Mobile: Story = {
	parameters: {
		chromatic: { disableSnapshot: true },
		viewport: { defaultViewport: "reflow" },
	},
	play: async ({ canvas }) => {
		canvas.getByRole("link", {
			name: "The queue is called the outbox everywhere except in the config",
		});
		await expectNoPageOverflow();
	},
};

export const LongContent: Story = {
	args: { state: { status: "ready", observations: [longContent] } },
	parameters: {
		chromatic: { viewports: [320] },
		viewport: { defaultViewport: "reflow" },
	},
	play: async () => {
		await expectNoPageOverflow();
	},
};

export const Loading: Story = {
	args: { state: { status: "loading" } },
	parameters: { chromatic: { viewports: [1440] } },
};

export const Empty: Story = {
	args: { state: { status: "empty", filtered: false } },
	parameters: { chromatic: { viewports: [1440] } },
};

export const FilteredToNothing: Story = {
	args: { state: { status: "empty", filtered: true, onClearFilters: clearFilters } },
	parameters: { chromatic: { viewports: [1440] } },
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Clear all filters" }));
		await expect(clearFilters).toHaveBeenCalled();
	},
};
