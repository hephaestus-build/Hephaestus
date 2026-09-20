import { CircleAlertIcon, CircleDashedIcon, KeyRoundIcon, PlugZapIcon } from "lucide-react";
import type * as React from "react";

import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";

import type { ConnectionState } from "./sync-format";

interface StateCopy {
	icon: React.ReactNode;
	title: string;
	/** Written per state, and phrased around what the reader does next rather than what the enum is. */
	describe: (displayName: string) => string;
	variant: "default" | "warning";
}

/**
 * What each non-ACTIVE connection state means, in the admin's terms. Lowercasing the wire enum yields
 * machine tokens dressed as sentences ("Slack is uninstalled.") that name the state but explain
 * neither why sync stopped nor what to do, so every blocking state gets written copy and a next action.
 *
 * ACTIVE is absent: the lookup returning `undefined` is what makes {@link ConnectionStateNotice}
 * render nothing for the steady state.
 */
const STATE_COPY: Partial<Record<ConnectionState, StateCopy>> = {
	PENDING: {
		icon: <CircleDashedIcon />,
		title: "Finishing setup",
		describe: (name) => `${name} isn't live yet. Sync controls unlock once setup completes.`,
		// Benign and self-resolving — no action is owed, so this must not shout.
		variant: "default",
	},
	SUSPENDED: {
		icon: <CircleAlertIcon />,
		title: "Syncing is paused",
		describe: (name) =>
			`${name} was suspended by the provider, so nothing is syncing. Reconnect to resume.`,
		variant: "warning",
	},
	UNINSTALLED: {
		icon: <PlugZapIcon />,
		title: "The app was removed",
		describe: (name) =>
			`${name} was uninstalled from this workspace, so nothing is syncing. Reconnect to resume.`,
		variant: "warning",
	},
};

export interface ConnectionStateNoticeProps {
	connectionState?: ConnectionState;
	/**
	 * When the stored credential was first found unreadable with the server's encryption key. Set on
	 * an ACTIVE connection as much as on any other, which is why it is not a state of its own: the
	 * connection is fine, the key that wrote its credential is gone.
	 */
	credentialsUnreadableSince?: Date | null;
	/**
	 * How this integration's credential gets replaced, as one sentence without a trailing period: the
	 * doors differ by provider, so a page that knows its provider names the one that exists, and the
	 * overview, which does not, points at the page.
	 */
	credentialRecovery?: string;
	/** The integration as the admin knows it — "Slack", "GitHub", "Outline". */
	displayName: string;
	className?: string;
}

/**
 * The one place a non-ACTIVE connection state is explained to an admin. Every integration renders it,
 * so a suspended Slack and a suspended Outline read identically. Severity tracks consequence:
 * SUSPENDED/UNINSTALLED mean sync is stopped, so they warn; PENDING resolves on its own and stays quiet.
 *
 * A credential the server's key cannot read is explained here too, on top of whatever state the
 * connection is in: it is the one condition that leaves a connection ACTIVE while everything that
 * needs its token fails, so it must be said wherever the state would be.
 *
 * Renders nothing for an ACTIVE connection whose credential reads, or for a connection that doesn't
 * exist — neither has anything to explain.
 */
export function ConnectionStateNotice({
	connectionState,
	credentialsUnreadableSince,
	credentialRecovery = "The connection's page says how to replace it",
	displayName,
	className,
}: ConnectionStateNoticeProps) {
	const copy = connectionState ? STATE_COPY[connectionState] : undefined;
	if (!copy && !credentialsUnreadableSince) {
		return null;
	}
	return (
		<div className={className}>
			{credentialsUnreadableSince && (
				<Alert variant="warning">
					<KeyRoundIcon />
					<AlertTitle>The stored token can’t be read</AlertTitle>
					<AlertDescription>
						{`${displayName}'s stored token can't be read with this server's current keys — usually after a key change, or a database restored under another key — so nothing that needs it can run. ${credentialRecovery}, or restore the key it was written with if that was changed by mistake.`}
					</AlertDescription>
				</Alert>
			)}
			{copy && (
				<Alert variant={copy.variant} className={credentialsUnreadableSince ? "mt-3" : undefined}>
					{copy.icon}
					<AlertTitle>{copy.title}</AlertTitle>
					<AlertDescription>{copy.describe(displayName)}</AlertDescription>
				</Alert>
			)}
		</div>
	);
}
