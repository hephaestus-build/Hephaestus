import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, screen, userEvent } from "storybook/test";

import { CookieConsentBanner } from "@/components/layout/CookieConsentBanner";
import { CONSENT_STORAGE_KEY, closeConsentReopen, setStoredConsent } from "@/runtime/consent";

import { CookiePreferencesSection } from "./CookiePreferencesSection";

/**
 * Privacy settings row. Rendered alongside the consent banner so the edit path
 * (GDPR Art. 7(3)) is reviewable end-to-end: clicking "Change cookie choices" re-opens the banner
 * (pre-seeded, cancelable) so a prior choice can be adjusted or withdrawn.
 */
const meta = {
	component: CookiePreferencesSection,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	// Seed a prior decision so the section shows a summary and the banner starts hidden. Teardown
	// resets the stored decision + reopen flag so nothing leaks into the next story.
	beforeEach: () => {
		setStoredConsent({ errorMonitoring: false });
		return () => {
			localStorage.removeItem(CONSENT_STORAGE_KEY);
			closeConsentReopen();
		};
	},
	render: () => (
		<>
			<CookiePreferencesSection />
			<CookieConsentBanner />
		</>
	),
} satisfies Meta<typeof CookiePreferencesSection>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Shows the current choice and re-opens the consent banner on demand. */
export const Default: Story = {
	play: async ({ canvas }) => {
		canvas.getByRole("heading", { name: /^privacy$/iu });
		// The banner is hidden while a decision is stored.
		await expect(screen.queryByRole("region", { name: /your privacy/iu })).not.toBeInTheDocument();

		await userEvent.click(canvas.getByRole("button", { name: /change cookie choices/iu }));
		// A user-initiated reopen surfaces the banner and moves focus to it (keyboard/AT parity).
		await expect(await screen.findByRole("region", { name: /your privacy/iu })).toBeVisible();
		await expect(screen.getByRole("region", { name: /your privacy/iu })).toHaveFocus();
	},
};
