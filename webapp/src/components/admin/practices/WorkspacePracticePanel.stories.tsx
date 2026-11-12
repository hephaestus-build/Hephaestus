import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent, within } from "storybook/test";
import { currentDefinition } from "@/components/admin/practices/practice-definition-fields";

import { mockDefinitionOptions } from "@/components/admin/practice-editor/fixtures";
import {
	catalogOrigin,
	inheritedFeedbackMode,
	mockPractices,
	practiceScript,
} from "@/components/admin/practices/fixtures";
import { withPageBehind } from "@/stories/decorators";
import { InLevelStack } from "@/stories/level-stack";
import { expectSettledVisible, settledDrawerPanel } from "@/stories/overlay";
import { expectNoPanelOverflow } from "@/stories/reflow";
import { levelsOpenedBy } from "@/test/detail-stack";
import { precedes } from "@/test/dom";

import { WorkspacePracticePanel, type WorkspacePracticeState } from "./WorkspacePracticePanel";

const [practice] = mockPractices;
if (!practice) {
	throw new Error("The shared practice fixtures no longer hold a practice to show");
}

type ReadyState = Extract<WorkspacePracticeState, { status: "ready" }>;

const { name } = currentDefinition(practice);

const ready = (over: Partial<ReadyState> = {}): ReadyState => ({
	status: "ready",
	practice,
	definitionOptions: mockDefinitionOptions,
	groupName: "Review-ready work",
	...over,
});

const scripted = ready({
	practice: {
		...practice,
		currentVersion: {
			...practice.currentVersion,
			definition: { ...currentDefinition(practice), script: practiceScript() },
		},
	},
});

const meta = {
	component: WorkspacePracticePanel,
	parameters: { layout: "fullscreen" },
	decorators: [withPageBehind],
	args: {
		workspaceSlug: "demo",
		state: ready(),
		path: { behind: [{ label: "Practice setup", depth: 0 }], onClose: fn() },
		onResetToCatalog: fn(),
	},
	argTypes: {
		state: { control: false },
		path: { control: false },
	},
	render: (args) => (
		<InLevelStack entry={{ kind: "practice", id: practice.slug }} path={args.path}>
			{(level) => <WorkspacePracticePanel {...args} {...level} />}
		</InLevelStack>
	),
	tags: ["autodocs"],
} satisfies Meta<typeof WorkspacePracticePanel>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ args }) => {
		const edit = await screen.findByRole("link", { name: "Edit practice" });
		await expectSettledVisible(edit);
		// Editing is another level on top of this one, so leaving the editor lands back on the panel
		// it was opened from rather than on the bare tree.
		await expect(levelsOpenedBy(edit).at(-1)).toBe(`practice-edit:${practice.slug}`);
		// Level 2 is the panel's own title; criteria headings render below it at level 4.
		await expect(screen.getByRole("heading", { name, level: 2 })).toBeVisible();
		await expect(screen.getByText("Version 1")).toBeVisible();
		// The path says where the level sits, and its crumb closes back down to the page.
		const path = screen.getByRole("list", { name: "Path" });
		await expect(path).toHaveTextContent(/Practice setup.*Practice/u);
		await userEvent.click(within(path).getByRole("button", { name: "Practice setup" }));
		await expect(args.path.onClose).toHaveBeenCalledWith(0);
	},
};

export const FromTheCatalog: Story = {
	args: {
		state: ready({ practice: { ...practice, catalogOrigin: catalogOrigin(practice.slug) } }),
	},
	play: async () => {
		// What the badge in the catalog list can only label is spelled out here, in the page it opens.
		await expectSettledVisible(await screen.findByText(/does not edit your copy/u));
		await expect(
			screen.queryByRole("button", { name: "Reset to catalog" }),
		).not.toBeInTheDocument();
	},
};

export const EditedHere: Story = {
	args: {
		state: ready({
			practice: {
				...practice,
				catalogOrigin: catalogOrigin(practice.slug, "EDITED_HERE", ["CRITERIA"]),
			},
		}),
	},
	play: async ({ args }) => {
		const reset = await screen.findByRole("button", { name: "Reset to catalog" });
		await expectSettledVisible(reset);
		await userEvent.click(reset);
		await expect(args.onResetToCatalog).toHaveBeenCalledOnce();
	},
};

export const UpdateWaiting: Story = {
	args: {
		state: ready({
			practice: { ...practice, catalogOrigin: catalogOrigin(practice.slug, "UPDATE_WAITING") },
		}),
	},
	play: async () => {
		// The note says where the update is decided, and the link there carries that page's title.
		const updates = await screen.findByRole("button", { name: "Catalog updates" });
		await expectSettledVisible(updates);
		await expect(updates).toHaveAttribute(
			"href",
			expect.stringMatching(/\/admin\/practices\/releases$/u),
		);
		await expect(screen.getByRole("button", { name: "Reset to catalog" })).toBeVisible();
	},
};

export const InheritedFeedbackMode: Story = {
	args: {
		state: ready({
			practice: { ...practice, feedbackMode: inheritedFeedbackMode("APPROVE_FIRST", "GROUP") },
		}),
	},
	play: async () => {
		// An inherited value says where it came from, so the reader knows where to go to change it.
		await expectSettledVisible(await screen.findByText(/Follows Review-ready work/u));
	},
};

export const Unassigned: Story = {
	args: {
		state: ready({ practice: { ...practice, groupSlug: null }, groupName: undefined }),
	},
};

export const Loading: Story = {
	args: { state: { status: "loading" } },
	play: async () => {
		// A skeleton holding the shape the definition will take, so nothing jumps when it lands.
		// It is `aria-hidden`, so the proof is the DOM, not a role.
		const panel = await settledDrawerPanel();
		await expect(panel.querySelectorAll('[data-slot="skeleton"]').length).toBeGreaterThan(0);
		await expect(screen.queryByRole("status")).not.toBeInTheDocument();
		// The drawer is named by what is loading while its title's shape stands in.
		await expect(screen.getByRole("heading", { level: 2 })).toHaveTextContent("Loading practice");
	},
};

export const FailedToLoad: Story = {
	args: { state: { status: "error", error: new Error("offline"), onRetry: fn() } },
	play: async ({ args }) => {
		const retry = await screen.findByRole("button", { name: "Retry" });
		await expectSettledVisible(retry);
		await userEvent.click(retry);
		const { state } = args;
		await expect(state.status === "error" && state.onRetry).toHaveBeenCalledOnce();
	},
};

export const NarrowViewport: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async () => {
		await expectNoPanelOverflow(await settledDrawerPanel());
	},
};

export const DarkMode: Story = {
	globals: { theme: "dark" },
};

async function openScript() {
	const trigger = await screen.findByRole("button", { name: "Practice script" });
	await expectSettledVisible(trigger);
	await userEvent.click(trigger);
	return trigger;
}

export const NoScript: Story = {
	play: async () => {
		await expectSettledVisible(await screen.findByRole("link", { name: "Edit practice" }));
		await expect(screen.queryByText(/practice script/iu)).not.toBeInTheDocument();
	},
};

export const PracticeScriptSource: Story = {
	args: { state: scripted },
	play: async () => {
		await openScript();
		const source = await screen.findByText(/definePracticeScript/u);
		await expectSettledVisible(source);
		await expect(source.closest("pre")).toHaveAttribute("tabindex", "0");
	},
};

export const DisclosureOrder: Story = {
	args: { state: scripted },
	play: async () => {
		const decides = await screen.findByRole("button", { name: "How it decides" });
		await expectSettledVisible(decides);
		const settings = screen.getByRole("region", { name: "Review settings" });
		const script = screen.getByRole("button", { name: "Practice script" });
		await expect(precedes(settings, decides) && precedes(decides, script)).toBe(true);
	},
};
