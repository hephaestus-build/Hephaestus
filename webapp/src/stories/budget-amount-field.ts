import { expect, screen, userEvent, within } from "storybook/test";

/**
 * Both halves of a refusal: nothing was submitted, *and* the field says why. The submit button
 * stays enabled precisely so pressing it explains the rejection instead of doing nothing.
 */
export async function expectAmountRejected(options: {
	fieldLabel: RegExp;
	submitLabel: RegExp;
	typed: string;
	reason: RegExp;
	onSubmit: unknown;
}) {
	const dialog = within(await screen.findByRole("dialog"));
	const input = dialog.getByLabelText(options.fieldLabel);
	await userEvent.clear(input);
	if (options.typed !== "") {
		await userEvent.type(input, options.typed);
	}
	await userEvent.click(dialog.getByRole("button", { name: options.submitLabel }));

	await expect(dialog.getByRole("alert")).toHaveTextContent(options.reason);
	await expect(options.onSubmit).not.toHaveBeenCalled();
}
