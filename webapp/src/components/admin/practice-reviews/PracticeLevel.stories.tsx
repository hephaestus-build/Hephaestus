import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, within } from "storybook/test";

import { mockPracticeDefinitionOptions } from "@/mocks/fixtures/practice";
import { withPageBehind } from "@/stories/decorators";
import { InLevelStack } from "@/stories/level-stack";
import { settledDrawerPanel } from "@/stories/overlay";
import { expectNoPanelOverflow } from "@/stories/reflow";
import {
	expectSectionHeadings,
	expectSectionLines,
	expectSectionsFold,
} from "@/stories/section-lines";

import { practiceVersions, workspacePractices } from "./fixtures";
import { forgetLevelFolds } from "./level-folds";
import { PracticeLevel } from "./PracticeLevel";
import { SHEET_SECTIONS } from "./RecordSheet";
import { practiceLevel } from "./review-levels";

const [practice] = workspacePractices;
const retryPractice = fn();

const meta = {
	component: PracticeLevel,
	parameters: { layout: "fullscreen" },
	decorators: [withPageBehind],
	tags: ["autodocs"],
	args: {
		workspaceSlug: "demo",
		path: { behind: [{ label: "Practice reviews", depth: 0 }], onClose: fn() },
		practice: {
			status: "ready",
			practice,
			definitionOptions: mockPracticeDefinitionOptions,
			groupName: "Review-ready work",
		},
		versions: { status: "ready", versions: practiceVersions },
	},
	argTypes: { path: { control: false } },
	render: (args) => (
		<InLevelStack entry={practiceLevel(String(practice?.slug))} path={args.path}>
			{(level) => <PracticeLevel {...args} {...level} />}
		</InLevelStack>
	),
	// A fold is kept in this browser, so every story starts with all sections open.
	beforeEach: () => {
		forgetLevelFolds("practice");
		return () => forgetLevelFolds("practice");
	},
} satisfies Meta<typeof PracticeLevel>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ userEvent }) => {
		const panelElement = await settledDrawerPanel();
		const panel = within(panelElement);
		await expect(
			panel.getAllByRole("heading", { level: 3 }).map((heading) => heading.textContent),
		).toEqual(
			expect.arrayContaining([SHEET_SECTIONS.what, SHEET_SECTIONS.state, SHEET_SECTIONS.technical]),
		);
		// One line between each two sections, one section size, and every section folds.
		await expectSectionLines(panelElement, 2);
		// The definition is the one Practice setup shows; its labels and folds step down under the
		// section, so only the sheet's title is an h2.
		await expectSectionHeadings(panelElement);
		await expect(panel.getAllByRole("heading", { level: 2 })).toHaveLength(1);
		panel.getByRole("heading", { level: 4, name: "What good looks like" });
		panel.getByRole("heading", { level: 4, name: "How it decides" });
		await expectSectionsFold(panelElement, userEvent);
		// No counts: the Practices table's row has them.
		await expect(panel.queryByText(/^Counts/u)).toBeNull();
		await expect(panel.queryByText("Feedback delivered")).toBeNull();
		await expect(panelElement.querySelectorAll("[data-sheet-status]")).toHaveLength(1);
		await expect(panel.getByRole("link", { name: "Edit in Practice setup" })).toHaveAttribute(
			"href",
			expect.stringContaining("practice-edit"),
		);
	},
};

/** One section folds by its heading button and opens again; header and title stay. */
export const FoldsASection: Story = {
	play: async ({ userEvent }) => {
		const panel = within(await settledDrawerPanel());
		const button = panel.getByRole("button", { name: SHEET_SECTIONS.state });
		const content = document.getElementById(String(button.getAttribute("aria-controls")));
		await expect(button).toHaveAttribute("aria-expanded", "true");
		await expect(content).not.toHaveAttribute("hidden");
		await userEvent.click(button);
		await expect(button).toHaveAttribute("aria-expanded", "false");
		await expect(content).toHaveAttribute("hidden");
		await userEvent.click(button);
		await expect(button).toHaveAttribute("aria-expanded", "true");
		await expect(content).not.toHaveAttribute("hidden");
	},
};

/** Every version of the definition in its history: what changed, and when what it checks changed. */
export const Versions: Story = {
	play: async () => {
		const panel = within(await settledDrawerPanel());
		const history = within(panel.getByRole("list", { name: "History" }));
		history.getByText("Version 1");
		history.getByText("The first version of the definition.");
		history.getByText("Changed: Guidance.");
		history.getByText("Changed: Review criteria, Reads.");
		// Only the version that changed what a review checks says so.
		await expect(history.getAllByText(/What a review checks changed/u)).toHaveLength(1);
		panel.getByText("Current version");
	},
};

/**
 * A practice edited in the practice repository: each version read from it names the commit, and the
 * link to Practice setup opens the practice, which says why it has no editor there.
 */
export const EditedInRepository: Story = {
	args: {
		practice: {
			status: "ready",
			practice: practice && { ...practice, editedIn: "REPOSITORY" },
			definitionOptions: mockPracticeDefinitionOptions,
			groupName: "Review-ready work",
		},
		versions: {
			status: "ready",
			versions: practiceVersions.map((version) =>
				version.number === 3
					? { ...version, sourceCommit: "4f2a9c1e8b7d6a5f4e3d2c1b0a9f8e7d6c5b4a39" }
					: version,
			),
		},
	},
	play: async () => {
		const panel = within(await settledDrawerPanel());
		const history = within(panel.getByRole("list", { name: "History" }));
		await expect(history.getByText("4f2a9c1")).toBeVisible();
		// Only the version read from the repository names a commit.
		await expect(history.getAllByText(/Read from the practice repository at commit/u)).toHaveLength(
			1,
		);
		const open = panel.getByRole("link", { name: "Open in Practice setup" });
		await expect(open.getAttribute("href")).not.toContain("practice-edit");
		await expect(panel.queryByRole("link", { name: "Edit in Practice setup" })).toBeNull();
	},
};

/** The versions could not be read: the definition still stands. */
export const VersionsFailed: Story = {
	args: { versions: { status: "error", error: new Error("Network down"), onRetry: fn() } },
	play: async () => {
		const panel = within(await settledDrawerPanel());
		panel.getByText(/the versions of this practice/u);
		panel.getByRole("heading", { name: SHEET_SECTIONS.what });
	},
};

/** A saved history fold does not hide a failed version read. */
export const FoldedVersionsFail: Story = {
	play: async ({ args, mount, userEvent }) => {
		await mount();
		let panel = within(await settledDrawerPanel());
		await userEvent.click(panel.getByRole("button", { name: SHEET_SECTIONS.state }));
		await expect(panel.getByRole("button", { name: SHEET_SECTIONS.state })).toHaveAttribute(
			"aria-expanded",
			"false",
		);
		const retry = fn();
		await mount(
			<InLevelStack entry={practiceLevel(String(practice?.slug))} path={args.path}>
				{(level) => (
					<PracticeLevel
						{...args}
						{...level}
						versions={{ status: "error", error: new Error("Network down"), onRetry: retry }}
					/>
				)}
			</InLevelStack>,
		);
		panel = within(await settledDrawerPanel());
		await expect(panel.getByText("We could not load the versions of this practice")).toBeVisible();
		await expect(panel.getByRole("button", { name: "Retry" })).toBeVisible();
		await expect(panel.getByRole("button", { name: "Retry" })).toBeEnabled();
		await mount(
			<InLevelStack entry={practiceLevel(String(practice?.slug))} path={args.path}>
				{(level) => <PracticeLevel {...args} {...level} />}
			</InLevelStack>,
		);
		panel = within(await settledDrawerPanel());
		const restored = panel.getByRole("button", { name: SHEET_SECTIONS.state });
		await expect(restored).toHaveAttribute("aria-expanded", "false");
		await userEvent.click(restored);
		await expect(restored).toHaveAttribute("aria-expanded", "true");
	},
};

/** A practice the workspace no longer has says so. */
export const NoLongerInTheWorkspace: Story = {
	args: {
		practice: {
			status: "ready",
			practice: undefined,
			definitionOptions: mockPracticeDefinitionOptions,
		},
	},
	play: async () => {
		const panel = within(await settledDrawerPanel());
		panel.getByText("This workspace no longer has this practice.");
	},
};

export const Loading: Story = {
	args: { practice: { status: "loading" }, versions: { status: "loading" } },
	play: async () => {
		const panel = within(await settledDrawerPanel());
		await expect(panel.queryByRole("heading", { name: SHEET_SECTIONS.what })).toBeNull();
	},
};

export const LoadFailed: Story = {
	args: { practice: { status: "error", error: new Error("Network down"), onRetry: retryPractice } },
	play: async ({ userEvent }) => {
		const panel = within(await settledDrawerPanel());
		await userEvent.click(panel.getByRole("button", { name: "Retry" }));
		await expect(retryPractice).toHaveBeenCalled();
	},
};

export const Reflow: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async () => {
		await expectNoPanelOverflow(await settledDrawerPanel());
	},
};
