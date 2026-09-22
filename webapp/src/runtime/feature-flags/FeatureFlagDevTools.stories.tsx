import type { Meta, StoryObj } from "@storybook/react";

import { FeatureFlagDevToolsPanel } from "./FeatureFlagDevTools";
import { mockFeatureFlags } from "./testing";

/**
 * A floating dev-only panel that displays all feature flag states at a glance.
 * Appears in the bottom-right corner and toggles open on click to reveal a
 * sorted list of flags with ON/OFF badges.
 *
 * In production this component renders nothing. The stories demonstrate the
 * presentational inner panel directly.
 */
const meta = {
	component: FeatureFlagDevToolsPanel,
	parameters: {
		layout: "fullscreen",
		docs: {
			description: {
				component:
					"Dev-only floating panel showing all feature flag states. Click the Flags button to expand.",
			},
		},
	},
	tags: ["autodocs"],
	argTypes: {
		flags: {
			description: "Feature flags map — `undefined` renders 'Not authenticated'",
			control: "object",
		},
		isLoading: {
			description: "Whether the flags are currently being fetched",
			control: "boolean",
		},
	},
} satisfies Meta<typeof FeatureFlagDevToolsPanel>;

export default meta;
type Story = StoryObj<typeof meta>;

/**
 * Default collapsed state — only the small "Flags" button is visible.
 * All flags disabled.
 */
export const Collapsed: Story = {
	args: {
		flags: mockFeatureFlags(),
		isLoading: false,
	},
};

/**
 * Mixed flag states — some enabled, some disabled.
 * Demonstrates the green/red badge styling.
 */
export const MixedFlags: Story = {
	args: {
		flags: mockFeatureFlags({
			MENTOR_ACCESS: true,
			ADMIN: true,
			GITLAB_WORKSPACE_CREATION: true,
		}),
		isLoading: false,
	},
};

/**
 * All flags enabled — full green badges.
 */
export const AllEnabled: Story = {
	args: {
		flags: mockFeatureFlags({
			MENTOR_ACCESS: true,
			NOTIFICATION_ACCESS: true,
			ADMIN: true,
			GITLAB_WORKSPACE_CREATION: true,
		}),
		isLoading: false,
	},
};

/**
 * Loading state — shows "Loading..." text when the panel is open.
 */
export const Loading: Story = {
	args: {
		flags: undefined,
		isLoading: true,
	},
};

/**
 * Unauthenticated state — shows "Not authenticated" when flags are undefined
 * and loading is complete.
 */
export const NotAuthenticated: Story = {
	args: {
		flags: undefined,
		isLoading: false,
	},
};
