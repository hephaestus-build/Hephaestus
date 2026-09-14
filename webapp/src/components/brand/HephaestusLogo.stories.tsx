import type { Meta, StoryObj } from "@storybook/react";

import { HephMark, HephaestusLogo, HephaestusWordmark } from "./HephaestusLogo";

const meta = {
	component: HephaestusLogo,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
} satisfies Meta<typeof HephaestusLogo>;

export default meta;
type Story = StoryObj<typeof meta>;

export const LightAndDark: Story = {
	render: () => (
		<div className="grid overflow-hidden rounded-2xl border sm:grid-cols-2">
			<div className="flex min-h-48 items-center bg-white p-10 text-[#17191f]">
				<HephaestusLogo markClassName="size-12" wordmarkClassName="text-3xl" />
			</div>
			<div className="dark flex min-h-48 items-center bg-[#111318] p-10 text-white">
				<HephaestusLogo markClassName="size-12" wordmarkClassName="text-3xl" />
			</div>
		</div>
	),
};

export const PlatformFit: Story = {
	render: () => (
		<div className="flex flex-wrap items-end gap-8">
			{[
				{ label: "Slack mobile · 36px", shape: "size-9 rounded-lg" },
				{ label: "GitHub profile · circle", shape: "size-24 rounded-full" },
				{ label: "App icon · squircle", shape: "size-24 rounded-[22%]" },
			].map(({ label, shape }) => (
				<div key={label} className="text-center">
					<div className={`mx-auto overflow-hidden ${shape}`}>
						<HephMark className="size-full" />
					</div>
					<p className="mt-3 text-xs text-muted-foreground">{label}</p>
				</div>
			))}
		</div>
	),
};

export const MarkScale: Story = {
	render: () => (
		<div className="flex items-end gap-6">
			{[
				{ size: 16, className: "size-4" },
				{ size: 24, className: "size-6" },
				{ size: 32, className: "size-8" },
				{ size: 48, className: "size-12" },
				{ size: 64, className: "size-16" },
			].map(({ size, className }) => (
				<div key={size} className="text-center">
					<span className={`mx-auto block ${className}`}>
						<HephMark className="size-full" />
					</span>
					<p className="mt-2 text-xs text-muted-foreground">{size}px</p>
				</div>
			))}
		</div>
	),
};

export const Wordmark: Story = {
	render: () => <HephaestusWordmark className="text-4xl font-semibold tracking-tight" />,
};
