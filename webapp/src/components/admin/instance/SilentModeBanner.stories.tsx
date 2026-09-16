import type { Meta, StoryObj } from "@storybook/react";
import { Gauge } from "lucide-react";

import type { InstanceSettings } from "@/api/types.gen";
import { minutesBefore } from "@/components/common/story-clock";
import { PageHeader } from "@/components/core/PageHeader";

import { SilentModeBanner } from "./SilentModeBanner";

const engaged: InstanceSettings = {
	etag: '"0"',
	silentModeEngaged: true,
	silentModeReason: "Investigating incident #42 — bad feedback going out",
	silentModeChangedAt: minutesBefore(45),
	silentModeChangedBy: "felixtjdietrich",
};

const meta = {
	component: SilentModeBanner,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: { settings: engaged },
} satisfies Meta<typeof SilentModeBanner>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Engaged: Story = {
	play: async ({ canvas }) => {
		canvas.getByText(/silent mode is engaged/iu, { exact: false });
		canvas.getByText(/incident #42/iu, { exact: false });
		canvas.getByRole("link", { name: /manage/iu });
	},
};

export const WithoutMetadata: Story = {
	args: {
		settings: { etag: '"0"', silentModeEngaged: true },
	},
};

export const AbovePageContentOnReflow: Story = {
	parameters: {
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320] },
	},
	args: {
		settings: {
			...engaged,
			silentModeChangedBy: "an-instance-administrator-with-a-long-identity@example.invalid",
			silentModeReason:
				"https://status.example.invalid/incidents/delivery-suppression-investigation-without-convenient-breakpoints",
		},
	},
	render: (args) => (
		<>
			<div className="mb-6">
				<SilentModeBanner {...args} />
			</div>
			<PageHeader
				icon={<Gauge />}
				title="Instance overview"
				description="What is running, and what changed recently on this instance."
			/>
		</>
	),
};
