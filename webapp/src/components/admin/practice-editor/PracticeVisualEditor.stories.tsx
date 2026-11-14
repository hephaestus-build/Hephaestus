import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent, waitFor } from "storybook/test";

import { bundledGuidance, unthemedVisual } from "@/stories/practice-guidance-story-mock-data";
import { Stateful } from "@/stories/stateful";

import { NO_VISUAL } from "./practice-guidance-draft";
import { PracticeVisualEditor } from "./PracticeVisualEditor";

/** The ground each theme preview paints, so a story can tell the two themes apart. */
function groundOf(figure: HTMLElement | null): string {
	if (!figure) {
		throw new Error("The preview has no figure for this theme");
	}
	return getComputedStyle(figure).backgroundColor;
}

const meta = {
	component: PracticeVisualEditor,
	parameters: { layout: "padded" },
	decorators: [
		(Story) => (
			<div className="max-w-3xl">
				<Story />
			</div>
		),
	],
	args: { value: NO_VISUAL, onChange: fn() },
	argTypes: {
		// Markup and its description as one record: the story holds it so the editor can change it.
		value: { control: false },
	},
	render: (args) => (
		<Stateful initial={args.value}>
			{(value, setValue) => (
				<PracticeVisualEditor
					{...args}
					value={value}
					onChange={(next) => {
						args.onChange(next);
						setValue(next);
					}}
				/>
			)}
		</Stateful>
	),
	tags: ["autodocs"],
} satisfies Meta<typeof PracticeVisualEditor>;

export default meta;
type Story = StoryObj<typeof meta>;

/** A practice with no visual asks for no description and shows no preview until markup arrives. */
export const Empty: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Upload SVG" })).toBeVisible();
		await expect(canvas.queryByRole("textbox", { name: /Description/u })).toBeNull();
		await expect(canvas.queryByRole("region", { name: "Preview" })).toBeNull();
	},
};

/** The bundled picture, previewed on the light and the dark ground side by side. */
export const WithVisual: Story = {
	args: { value: bundledGuidance.visual },
	play: async ({ canvas }) => {
		const light = canvas.getByRole("figure", { name: "Light theme" });
		const dark = canvas.getByRole("figure", { name: "Dark theme" });
		await expect(canvas.getAllByRole("img", { name: bundledGuidance.visual.alt })).toHaveLength(2);
		await expect(groundOf(light)).not.toBe(groundOf(dark));
	},
};

/** The same previews on a dark page: the light one still paints the light ground. */
export const WithVisualOnADarkPage: Story = {
	...WithVisual,
	globals: { theme: "dark" },
};

export const UploadAnSvg: Story = {
	play: async ({ canvas }) => {
		const file = new File([unthemedVisual.svg], "before-after.svg", { type: "image/svg+xml" });
		await userEvent.upload(canvas.getByLabelText("SVG file for the visual"), file);

		await waitFor(async () => {
			await expect(canvas.getByRole("textbox", { name: "SVG markup" })).toHaveValue(
				unthemedVisual.svg,
			);
		});
		await expect(canvas.getByRole("textbox", { name: "Description *" })).toBeRequired();
		await expect(canvas.getByRole("region", { name: "Preview" })).toBeVisible();
	},
};

export const UploadSomethingElse: Story = {
	play: async ({ canvas }) => {
		const file = new File(["not a picture"], "photo.png", { type: "image/png" });
		await userEvent.upload(canvas.getByLabelText("SVG file for the visual"), file, {
			applyAccept: false,
		});

		await expect(
			await canvas.findByText("“photo.png” is not an SVG file. Choose an SVG file."),
		).toBeVisible();
		await expect(canvas.getByRole("button", { name: "Upload SVG" })).toHaveAccessibleDescription(
			"“photo.png” is not an SVG file. Choose an SVG file.",
		);
		await expect(canvas.getByRole("textbox", { name: "SVG markup" })).toHaveValue("");
	},
};

/** Remove takes the picture and its description away, back to text mode. */
export const Remove: Story = {
	args: { value: bundledGuidance.visual },
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Remove visual" }));

		await expect(canvas.queryByRole("region", { name: "Preview" })).toBeNull();
		await expect(canvas.getByRole("textbox", { name: "SVG markup" })).toHaveValue("");
	},
};

/** A refused save names the missing description beside it. */
export const MissingDescription: Story = {
	args: {
		value: { svg: bundledGuidance.visual.svg, alt: "" },
		altError: "Describe what the visual shows.",
	},
	play: async ({ canvas }) => {
		const description = canvas.getByRole("textbox", { name: "Description *" });
		await expect(description).toHaveAttribute("aria-invalid", "true");
		await expect(description).toHaveAccessibleDescription(/Describe what the visual shows\./u);
	},
};
