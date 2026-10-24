import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fireEvent, fn, waitFor, within } from "storybook/test";

import { DetailDrawerStack } from "@/components/layout/detail-drawer/DetailDrawerStack";
import { composeGroupOverview } from "@/components/practice-profile/compose-overview";
import { PracticeGroupDetailLevel } from "@/components/practice-profile/PracticeGroupDetailLevel";
import { ACROSS_THE_WORKSPACE } from "@/components/practices-across-the-workspace/across-workspace-copy";
import { CAME_FROM_YOUR_PROFILE } from "@/components/practices-across-the-workspace/WorkspaceComparisonTable";
import { WorkspaceGroupLevel } from "@/components/practices-across-the-workspace/WorkspaceGroupLevel";
import { withPageBehind } from "@/stories/decorators";
import { expectSettledVisible, settledDrawerPanel } from "@/stories/overlay";
import { detailPractices } from "@/stories/practice-detail-story-mock-data";
import {
	OVERVIEW_FIXTURE,
	packagingGroup,
	packagingStanding,
} from "@/stories/practice-profile-story-mock-data";
import { PACKAGING_GROUP } from "@/stories/practices-across-the-workspace-story-data";

import { FOCUS_FOOTER_COPY, focusSwitchName, PracticeFocusFooter } from "./PracticeFocusFooter";

const GROUP_ENTRY = [{ kind: "practice-group", id: packagingGroup.slug }];
const noop = () => undefined;

/**
 * The foot of a group or practice level on both practice pages, with the one way to the same
 * group or practice on the other page. The footer stands in the same place on both, so the way there
 * is the way back. Each story mounts the real level in a real drawer over a page.
 */
const meta = {
	component: PracticeFocusFooter,
	parameters: { layout: "fullscreen" },
	decorators: [withPageBehind],
	args: {
		...FOCUS_FOOTER_COPY.profileGroup,
		accessibleLabel: focusSwitchName(FOCUS_FOOTER_COPY.profileGroup.label, packagingGroup.name),
		onSwitch: fn(),
	},
	// The reader's own group level, with the way to the same group across the workspace at its foot.
	render: (args) => (
		<DetailDrawerStack
			stack={GROUP_ENTRY}
			onClose={noop}
			footer={() => <PracticeFocusFooter {...args} />}
		>
			{(_entry, level) => (
				<PracticeGroupDetailLevel
					nested={level.nested}
					path={{ behind: [{ label: "Practice profile", depth: 0 }], onClose: noop }}
					group={packagingGroup}
					standing={packagingStanding}
					practices={detailPractices}
					{...composeGroupOverview(OVERVIEW_FIXTURE, packagingGroup.slug)}
					onOpenPractice={noop}
					isLoading={false}
					onRetry={noop}
				/>
			)}
		</DetailDrawerStack>
	),
	tags: ["autodocs"],
} satisfies Meta<typeof PracticeFocusFooter>;

export default meta;
type Story = StoryObj<typeof meta>;

/** The Practice profile's group level: the way out to the workspace's split of the same group. */
export const ProfileGroupLevel: Story = {
	play: async ({ args }) => {
		const level = within(await settledDrawerPanel());
		const button = level.getByRole("button", {
			name: `See this group across the workspace: ${packagingGroup.name}`,
		});
		await expectSettledVisible(button);
		await expectFullSizeSwitch();
		// An outline button at rest, as the admin Practice reviews' row actions are: not the dark
		// primary fill, which only a press shows.
		await expect(getComputedStyle(button).color).toBe(
			getComputedStyle(level.getByRole("heading", { name: "Practices in this group" })).color,
		);
		// On hover and keyboard focus, and while pressed by key as by pointer, it takes the dark primary
		// fill. Synthetic events reach no :hover, so the hover is read off the classes; a real browser
		// shows it (screens under catalog-feedback).
		await expect(button.className).toContain("hover:bg-primary");
		await expect(button.className).toContain("focus-visible:bg-primary");
		const rest = getComputedStyle(button).backgroundColor;
		await fireEvent.keyDown(button, { key: "Enter" });
		await waitFor(async () => {
			await expect(getComputedStyle(button).backgroundColor).not.toBe(rest);
		});
		await expect(getComputedStyle(button).color).not.toBe(
			getComputedStyle(level.getByRole("heading", { name: "Practices in this group" })).color,
		);
		await fireEvent.keyUp(button, { key: "Enter" });
		await fireEvent.pointerDown(button);
		await waitFor(async () => {
			await expect(getComputedStyle(button).backgroundColor).not.toBe(rest);
		});
		await fireEvent.pointerUp(button);
		await waitFor(async () => {
			await expect(getComputedStyle(button).backgroundColor).toBe(rest);
		});
		button.click();
		await expect(args.onSwitch).toHaveBeenCalledOnce();
	},
};

/**
 * The workspace's group level, reached from the reader's practice: that practice's row is marked,
 * and the foot has the way back to the reader's own group. The link above the table is gone, so the
 * level has one way to the profile's group, not two.
 */
export const WorkspaceGroupLevelWithMarkedPractice: Story = {
	args: {
		...FOCUS_FOOTER_COPY.workspaceGroup,
		accessibleLabel: focusSwitchName(
			FOCUS_FOOTER_COPY.workspaceGroup.label,
			PACKAGING_GROUP.groupName,
		),
	},
	render: (args) => (
		<DetailDrawerStack
			stack={GROUP_ENTRY}
			onClose={noop}
			footer={() => <PracticeFocusFooter {...args} />}
		>
			{(_entry, level) => (
				<WorkspaceGroupLevel
					nested={level.nested}
					path={{ behind: [{ label: ACROSS_THE_WORKSPACE, depth: 0 }], onClose: noop }}
					state={{ status: "ready", group: PACKAGING_GROUP }}
					onGoToPractice={noop}
					focusPracticeSlug="explain-the-change"
				/>
			)}
		</DetailDrawerStack>
	),
	play: async () => {
		const level = within(await settledDrawerPanel());
		await expectSettledVisible(
			level.getByRole("button", {
				name: `See your own standing: ${PACKAGING_GROUP.groupName}`,
			}),
		);
		// One way to the profile's group: the footer's, not also a link above the table.
		const toGroup = `Open in your Practice profile ${PACKAGING_GROUP.groupName}`;
		await expect([
			...level.queryAllByRole("link", { name: toGroup }),
			...level.queryAllByRole("button", { name: toGroup }),
		]).toHaveLength(0);
		// Marked in words too, and not as an open level.
		const marked = level.getByText("Explain what the change does and why").closest("tr");
		await expect(marked).toHaveAttribute("aria-current", "true");
		await expect(marked).toHaveTextContent(CAME_FROM_YOUR_PROFILE);
		await expect(marked).not.toHaveAttribute("data-state", "open");
	},
};

/** The switch is a drawer action of full size: at least 32 px high, so well past 24 by 24. */
async function expectFullSizeSwitch() {
	const button = within(await settledDrawerPanel()).getByRole("button", {
		name: /^See this group across the workspace/u,
	});
	const { width, height } = button.getBoundingClientRect();
	await expect(height).toBeGreaterThanOrEqual(32);
	await expect(width).toBeGreaterThanOrEqual(24);
	return { button, width };
}

/** The profile's group level at 390 px: the footer stacks and the button takes its full width. */
export const Phone: Story = {
	globals: { viewport: { value: "phone390" } },
	play: async () => {
		const { button, width } = await expectFullSizeSwitch();
		const footer = button.parentElement;
		await expect(width).toBeGreaterThan((footer?.clientWidth ?? 0) - 64);
	},
};

export const PhoneDark: Story = {
	globals: { viewport: { value: "phone390" }, theme: "dark" },
	play: async () => {
		await expectFullSizeSwitch();
	},
};

export const Dark: Story = { globals: { theme: "dark" } };
