import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, userEvent } from "storybook/test";

import type { ConfigurationFact } from "@/api/types.gen";

import { InstanceConfigurationReadinessCard } from "./InstanceConfigurationReadinessCard";

const onRetry = fn();
const GUIDE = "https://docs.hephaestus.build/admin/configuration-readiness";

function fact(
	id: string,
	subject: string,
	anchor: string,
	overrides: Partial<ConfigurationFact> = {},
): ConfigurationFact {
	return {
		id,
		subject,
		roles: ["SERVER"],
		requirement: "REQUIRED",
		status: "SATISFIED",
		explanation: `${subject} is checked.`,
		documentationUrl: `${GUIDE}#${anchor}`,
		...overrides,
	};
}

const satisfied = [
	fact("database.url", "spring.datasource.url", "database", {
		roles: ["SERVER", "WORKER", "WEBHOOK"],
		explanation: "A PostgreSQL JDBC URL is required.",
	}),
	fact("external.base-url", "hephaestus.host-url", "external-url", {
		explanation: "A root HTTPS origin without credentials, query, or fragment is required.",
	}),
	fact("webhook.shared-secret", "hephaestus.webhook.secret", "webhooks", {
		roles: ["SERVER", "WEBHOOK"],
		explanation: "A webhook secret of at least 32 printable ASCII characters is required.",
	}),
];

const emailNotConfigured = fact("notification.email", "spring.mail.host", "email", {
	requirement: "OPTIONAL",
	status: "NOT_CONFIGURED",
	explanation:
		"Email is optional. If you set a mail server host, hephaestus.email.from must name the sender address.",
});

const noLoginProvider = fact("auth.login-provider", "login-provider capability", "login", {
	status: "ACTION_REQUIRED",
	explanation: "At least one enabled sign-in provider is required.",
});

const notApplicable = fact(
	"llm.proxy-egress",
	"hephaestus.llm.egress.allow-loopback",
	"llm-proxy",
	{
		roles: ["WORKER"],
		status: "NOT_APPLICABLE",
		explanation: "Loopback LLM provider egress must be disabled in production.",
	},
);

const meta = {
	component: InstanceConfigurationReadinessCard,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		state: {
			status: "ready",
			facts: [...satisfied, noLoginProvider, emailNotConfigured, notApplicable],
		},
	},
} satisfies Meta<typeof InstanceConfigurationReadinessCard>;

export default meta;
type Story = StoryObj<typeof meta>;

/** A missing required setting leads the optional and passed settings. */
export const ActionRequired: Story = {
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText("1 setting needs action. 1 setting not configured."),
		).toBeVisible();
		const [first] = canvas.getAllByRole("code");
		await expect(first).toHaveTextContent("login-provider capability");
		await expect(
			canvas.getByRole("link", { name: /Read the guide for login-provider capability/u }),
		).toHaveAttribute("href", `${GUIDE}#login`);
		// The page's `<h1>` sits above this card, so each status group is a level-2 heading.
		await expect(canvas.getAllByRole("heading", { level: 2 })).not.toHaveLength(0);
		await expect(canvas.queryAllByRole("heading", { level: 3 })).toHaveLength(0);
	},
};

export const AllSatisfied: Story = {
	args: { state: { status: "ready", facts: satisfied } },
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText("Every check that applies to this instance passes."),
		).toBeVisible();
		await expect(canvas.queryByText("Action required")).toBeNull();
		// Folded, but one press away.
		await expect(canvas.queryByText("spring.datasource.url")).toBeNull();
		await userEvent.click(canvas.getByRole("button", { name: /Passed/u }));
		await expect(canvas.getByText("spring.datasource.url")).toBeVisible();
	},
};

/** An absent optional setting is optional, never a failure. */
export const OptionalNotConfigured: Story = {
	args: {
		state: { status: "ready", facts: [...satisfied, emailNotConfigured] },
	},
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText(
				"Every check that applies to this instance passes. 1 setting not configured.",
			),
		).toBeVisible();
		await expect(canvas.queryByText("Action required")).toBeNull();
		await expect(canvas.getAllByText("Optional")).toHaveLength(1);
		await expect(canvas.getByText("spring.mail.host")).toBeVisible();
	},
};

/** A failed refresh keeps the last facts on screen and says they may be out of date. */
export const RefreshFailed: Story = {
	args: {
		state: {
			status: "ready",
			facts: satisfied,
			refreshFailure: { error: new Error("Network Error"), onRetry, pending: false },
		},
	},
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText("We could not refresh. This card shows the last successful check."),
		).toBeVisible();
		await expect(
			canvas.getByText("Every check that applies to this instance passes."),
		).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: /retry/iu }));
		await expect(onRetry).toHaveBeenCalledOnce();
	},
};

export const RefreshRetrying: Story = {
	args: {
		state: {
			status: "ready",
			facts: satisfied,
			refreshFailure: { error: { status: 503 }, onRetry, pending: true },
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Retrying…" })).toBeDisabled();
		await expect(
			canvas.getByText("Every check that applies to this instance passes."),
		).toBeVisible();
	},
};

export const Loading: Story = {
	args: { state: { status: "loading" } },
};

export const Empty: Story = {
	args: { state: { status: "empty" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("No configuration checks reported")).toBeVisible();
	},
};

/** The last read reported no checks and the refresh failed: both facts are stated. */
export const EmptyRefreshFailed: Story = {
	args: {
		state: {
			status: "empty",
			refreshFailure: { error: { status: 503 }, onRetry, pending: false },
		},
	},
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText("We could not refresh. This card shows the last successful check."),
		).toBeVisible();
		await expect(canvas.getByText("No configuration checks reported")).toBeVisible();
	},
};

/** A refused read shows no facts and offers no retry that would be refused again. */
export const Refused: Story = {
	args: { state: { status: "error", error: { status: 403 }, onRetry } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Configuration readiness is unavailable")).toBeVisible();
		await expect(canvas.getByText(/ask a workspace admin or instance admin/iu)).toBeVisible();
		await expect(canvas.queryByRole("button", { name: /retry/iu })).toBeNull();
	},
};

export const Unavailable: Story = {
	args: { state: { status: "error", error: new Error("Network Error"), onRetry } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Configuration readiness is unavailable")).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: /retry/iu }));
		await expect(onRetry).toHaveBeenCalledOnce();
	},
};

export const OnAPhone: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async ({ canvasElement }) => {
		await expect(canvasElement.scrollWidth).toBeLessThanOrEqual(canvasElement.clientWidth);
	},
};
