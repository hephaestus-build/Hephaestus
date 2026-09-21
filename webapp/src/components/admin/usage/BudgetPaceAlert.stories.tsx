import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import type { FxRateInfo } from "@/api/types.gen";

import { BudgetPaceAlert } from "./BudgetPaceAlert";

const eur: FxRateInfo = {
	currencyCode: "EUR",
	ratePerUsd: 0.88,
	rateDate: new Date("2026-07-24"),
	source: "ECB",
};

/** Both consoles render it, so only the subject of the sentence changes — never the figures. */
const meta = {
	component: BudgetPaceAlert,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		scope: "provider",
		percent: 84,
		spendUsd: 42,
		capUsd: 50,
		projection: {
			projectedMonthEndUsd: 62.4,
			reachedOn: new Date(Date.UTC(2026, 6, 27)),
		},
		fx: eur,
	},
} satisfies Meta<typeof BudgetPaceAlert>;

export default meta;
type Story = StoryObj<typeof meta>;

export const OwnProviderCap: Story = {
	play: async ({ canvas }) => {
		await expect(await canvas.findByText(/You've used 84% of your provider cap/u)).toBeVisible();
	},
};

export const NamedSubject: Story = {
	args: { subjectName: "Acme" },
	play: async ({ canvas }) => {
		await expect(await canvas.findByText(/Acme has used 84% of its provider cap/u)).toBeVisible();
	},
};

export const SharedModelBudget: Story = {
	args: { scope: "shared", subjectName: "Acme" },
	play: async ({ canvas }) => {
		await expect(await canvas.findByText(/shared-model budget/u)).toBeVisible();
	},
};

/** A projection is withheld rather than guessed; the share consumed is still reported. */
export const NoProjection: Story = {
	args: { projection: null },
};

export const PaceStaysUnderCap: Story = {
	args: { projection: { projectedMonthEndUsd: 47.5, reachedOn: null } },
};

export const UsdOnly: Story = {
	args: { fx: null },
};

export const AtTheCap: Story = {
	args: {
		percent: 100,
		spendUsd: 50,
		projection: { projectedMonthEndUsd: 74, reachedOn: new Date(Date.UTC(2026, 6, 22)) },
	},
};
