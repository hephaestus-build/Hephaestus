import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen } from "storybook/test";

import type { ReviewArtifact } from "@/api/types.gen";
import { isKnownArtifactKind } from "@/lib/artifact-kinds";
import { expectNoPageOverflow } from "@/test/reflow";

import { REVIEW_PREVIEW_SIZE, type ReviewSectionState } from "./ReviewOutputSections";
import { ReviewTargetPage } from "./ReviewTargetPage";
import {
	gitlabMergeRequest,
	outlineDocument,
	reviewArtifact,
	reviewFeedback,
	reviewObservations,
	slackConversation,
	workspacePractices,
} from "./story-mock-data";

/**
 * The page shows one work's review output, so a story names the work and takes the rows the
 * endpoints would return for it: hand-picked rows can put one work's observation on another's page.
 * The section shows a preview and links on to the full list, so `total` is the whole count while
 * `items` is only the first page of it.
 */
const outputFor = <T extends { artifact?: ReviewArtifact }>(
	rows: T[],
	artifact: ReviewArtifact,
): ReviewSectionState<T> => {
	const matching = rows.filter((row) => row.artifact?.id === artifact.id);
	return {
		status: "ready",
		items: matching.slice(0, REVIEW_PREVIEW_SIZE),
		total: matching.length,
	};
};

const empty = <T,>(): ReviewSectionState<T> => ({ status: "ready", items: [], total: 0 });

/** The practice one of this work's observations names, and the one the card is read on. */
const THIN_CONTROLLERS = workspacePractices.find((p) => p.slug === "thin-controllers");
if (!THIN_CONTROLLERS) {
	throw new Error("The practice fixtures no longer cover thin-controllers");
}

const argsFor = (artifact: ReviewArtifact) => {
	if (!isKnownArtifactKind(artifact.type)) {
		throw new Error(`Fixture artifact ${artifact.id} has an unlabelled kind: ${artifact.type}`);
	}
	return {
		artifactKind: artifact.type,
		artifactId: artifact.id,
		feedback: outputFor(reviewFeedback, artifact),
		observations: outputFor(reviewObservations, artifact),
	};
};

const meta = {
	title: "Workspace admin/Practice reviews/Reviewed work",
	component: ReviewTargetPage,
	parameters: { layout: "padded", chromatic: { viewports: [320, 768, 1440] } },
	tags: ["autodocs"],
	args: {
		workspaceSlug: "demo",
		...argsFor(reviewArtifact),
		// An observation row names its practice but carries none of its prose, so the screen is handed
		// the list and each row reads its own record out of it. See `ReviewPracticeLink`.
		practices: workspacePractices,
	},
} satisfies Meta<typeof ReviewTargetPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const PullRequest: Story = {
	parameters: { viewport: { defaultViewport: "reflow" } },
	play: async ({ canvas }) => {
		await canvas.findByRole("heading", {
			name: "Cache the workspace member lookup on the review path",
			level: 2,
		});
		// Nothing labels the page as "Reviewed work": the breadcrumb stops before the heading, and the
		// work names itself.
		await expect(canvas.queryAllByText("Reviewed work")).toHaveLength(0);
		await expect(canvas.getAllByText("ls1intum/Hephaestus · PR #1423").length).toBeGreaterThan(0);
		await expectNoPageOverflow();
	},
};

/**
 * A practice named on an observation row does two things here as well: it opens the practice, and it
 * says what the practice is without leaving this work. The card is the half that goes quiet on its
 * own — a row that stops being handed its practice record still renders a perfectly good link.
 */
export const PracticeOpensItsDefinition: Story = {
	parameters: { chromatic: { disableSnapshot: true } },
	play: async ({ canvas, userEvent }) => {
		const link = await canvas.findByRole("link", { name: /Thin controllers/u });
		await expect(link).toHaveAttribute("href", "/w/demo/admin/practices/thin-controllers");
		// The card is a portal, so it is looked for on the whole screen rather than in the canvas.
		await userEvent.hover(link);
		await screen.findByText(THIN_CONTROLLERS.whyItMatters ?? "");
		await screen.findByText(THIN_CONTROLLERS.whatGoodLooksLike ?? "");
	},
};

/** A merge request is the same kind of work on another provider, and says so in its own words. */
export const MergeRequest: Story = {
	args: argsFor(gitlabMergeRequest),
	parameters: { chromatic: { viewports: [1440] } },
	play: async ({ canvas }) => {
		await canvas.findByRole("heading", {
			name: "Move invoice numbering behind the billing boundary",
			level: 2,
		});
		await expect(canvas.getAllByText("platform/billing-service · MR !88").length).toBeGreaterThan(
			0,
		);
	},
};

export const Conversation: Story = {
	args: argsFor(slackConversation),
	parameters: { chromatic: { viewports: [1440] } },
	play: async ({ canvas }) => {
		await canvas.findByRole("heading", {
			name: "How should we roll back the pricing migration?",
			level: 2,
		});
		await expect(canvas.getAllByText("#engineering").length).toBeGreaterThan(0);
	},
};

/** A document's label is its kind, because it has no number to be known by. */
export const Document: Story = {
	args: argsFor(outlineDocument),
	parameters: { chromatic: { viewports: [1440] } },
	play: async ({ canvas }) => {
		await canvas.findByRole("heading", {
			name: "Runbook: restoring a workspace from backup",
			level: 2,
		});
	},
};

/**
 * Both sections answered and both are empty. Nothing here names the work, because nothing that ever
 * looked at it came back — the route knows an id and a kind, and says only that much.
 */
export const NothingReviewed: Story = {
	args: { artifactId: 45, feedback: empty(), observations: empty() },
	parameters: { chromatic: { viewports: [1440] } },
	play: async ({ canvas }) => {
		await canvas.findByText("Nothing has been reviewed on this work");
	},
};

/** Neither section has answered yet: the heading is a skeleton rather than a guess at the title. */
export const Loading: Story = {
	args: { feedback: { status: "loading" }, observations: { status: "loading" } },
	parameters: { chromatic: { viewports: [1440] } },
	play: async ({ canvas }) => {
		await expect(
			canvas.queryByText("Nothing has been reviewed on this work"),
		).not.toBeInTheDocument();
		await canvas.findByText("Loading feedback");
		await canvas.findByText("Loading observations");
	},
};

/**
 * One section failing does not take the other down with it, and — the reason the empty state is
 * gated on both — a failure is never read as "nothing was found".
 */
export const ObservationsFailed: Story = {
	args: {
		observations: { status: "error", error: { status: 500 }, onRetry: fn() },
	},
	parameters: { chromatic: { viewports: [1440] } },
	play: async ({ canvas }) => {
		await canvas.findByRole("heading", {
			name: "Cache the workspace member lookup on the review path",
			level: 2,
		});
		await canvas.findByText("Couldn't load observations");
		await expect(
			canvas.queryByText("Nothing has been reviewed on this work"),
		).not.toBeInTheDocument();
	},
};
