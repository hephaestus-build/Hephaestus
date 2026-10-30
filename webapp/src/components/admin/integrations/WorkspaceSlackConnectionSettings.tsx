import { ExternalLinkIcon } from "lucide-react";
import { useState } from "react";

import { SlackIcon } from "@/components/icons/brand";
import {
	AlertDialog,
	AlertDialogAction,
	AlertDialogCancel,
	AlertDialogContent,
	AlertDialogDescription,
	AlertDialogFooter,
	AlertDialogHeader,
	AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardFooter, CardHeader } from "@/components/ui/card";
import {
	Item,
	ItemActions,
	ItemContent,
	ItemDescription,
	ItemMedia,
	ItemTitle,
} from "@/components/ui/item";
import { Spinner } from "@/components/ui/spinner";

import { IntegrationCardHeading } from "./IntegrationCardHeading";

/** What Slack said about the scopes the token holds; only a reconnect grants a missing one. */
export type SlackScopeCheck =
	| { state: "checking" }
	| { state: "failed"; onRetry: () => void; isRetrying: boolean }
	| { state: "checked"; missingScopes: string[] };

export type WorkspaceSlackConnectionSettingsProps =
	| {
			state: "disconnected";
			/** Starts Slack's OAuth; the page leaves for Slack when it succeeds. */
			onConnect: () => void;
			isConnecting: boolean;
	  }
	| {
			state: "connected";
			credentialsUnreadableSince?: Date;
			scopeCheck: SlackScopeCheck;
			/**
			 * Starts Slack's OAuth again for the same Slack workspace, which replaces the stored token and
			 * keeps the connection, its channels and their data; the page leaves for Slack when it succeeds.
			 */
			onReconnect: () => void;
			isReconnecting: boolean;
			/**
			 * Present when the server names the active connection, which is what a disconnect acts on.
			 * The confirm dialog closes when it resolves and stays open when it rejects.
			 */
			onDisconnect?: () => Promise<void>;
			isDisconnecting: boolean;
	  };

export function WorkspaceSlackConnectionSettings(props: WorkspaceSlackConnectionSettingsProps) {
	return (
		<Card>
			<CardHeader>
				<IntegrationCardHeading className="flex items-center gap-2">
					<SlackIcon className="size-4" />
					Slack integration
				</IntegrationCardHeading>
				<CardDescription>
					Install Hephaestus in Slack to talk with Hephaestus in direct messages, manage privacy
					controls in App Home, and monitor channels you choose. Hephaestus stores no channel
					messages until an admin activates a channel.
				</CardDescription>
			</CardHeader>

			{props.state === "connected" ? (
				<SlackConnected {...props} />
			) : (
				<CardFooter>
					<Button onClick={props.onConnect} disabled={props.isConnecting} className="w-full">
						{props.isConnecting ? (
							<>
								<Spinner />
								Redirecting to Slack…
							</>
						) : (
							<>
								Connect Slack workspace
								<ExternalLinkIcon className="size-3.5" />
							</>
						)}
					</Button>
				</CardFooter>
			)}
		</Card>
	);
}

/** What the connected card says about the token, most urgent first. */
type TokenStatus = "unreadable" | "scopes-missing" | "check-failed" | "checking" | "connected";

function tokenStatusOf(
	credentialsUnreadableSince: Date | undefined,
	scopeCheck: SlackScopeCheck,
): TokenStatus {
	if (credentialsUnreadableSince != null) {
		return "unreadable";
	}
	if (scopeCheck.state === "checked") {
		return scopeCheck.missingScopes.length > 0 ? "scopes-missing" : "connected";
	}
	return scopeCheck.state === "failed" ? "check-failed" : "checking";
}

const TOKEN_BADGE = {
	unreadable: { label: "Token unreadable", variant: "warning" },
	"scopes-missing": { label: "Permissions missing", variant: "warning" },
	"check-failed": { label: "Permissions unknown", variant: "warning" },
	checking: { label: "Checking permissions", variant: "muted" },
	connected: { label: "Connected", variant: "success" },
} as const satisfies Record<
	TokenStatus,
	{ label: string; variant: "warning" | "success" | "muted" }
>;

const TOKEN_DESCRIPTION = {
	unreadable:
		"Hephaestus cannot post with this token. Reconnect Slack to replace it, or restore the original server key.",
	"check-failed":
		"Hephaestus could not ask Slack which permissions this token holds. Check again, or reconnect Slack if it keeps failing.",
	checking: "Hephaestus is asking Slack which permissions this token holds.",
	connected: "Hephaestus is installed and can post in Slack.",
} as const satisfies Record<Exclude<TokenStatus, "scopes-missing">, string>;

function MissingScopes({ scopes }: { scopes: string[] }) {
	return (
		<>
			Slack has not granted Hephaestus every permission it needs, so some of its work in Slack
			fails. Reconnect Slack to grant{" "}
			{scopes.map((scope, index) => (
				<span key={scope}>
					{index > 0 && ", "}
					<code>{scope}</code>
				</span>
			))}
			.
		</>
	);
}

function SlackConnected({
	credentialsUnreadableSince,
	scopeCheck,
	onReconnect,
	isReconnecting,
	onDisconnect,
	isDisconnecting,
}: Extract<WorkspaceSlackConnectionSettingsProps, { state: "connected" }>) {
	const status = tokenStatusOf(credentialsUnreadableSince, scopeCheck);
	const needsReconnect = status === "unreadable" || status === "scopes-missing";
	const missingScopes = scopeCheck.state === "checked" ? scopeCheck.missingScopes : [];
	const [disconnectOpen, setDisconnectOpen] = useState(false);

	const confirmDisconnect = async () => {
		if (!onDisconnect) {
			return;
		}
		try {
			await onDisconnect();
			setDisconnectOpen(false);
		} catch {
			// The dialog stays open to try again; the caller has already said why it failed.
		}
	};

	return (
		<>
			<CardContent>
				<Item variant="outline" size="sm">
					<ItemMedia variant="icon">
						<SlackIcon />
					</ItemMedia>
					<ItemContent>
						<ItemTitle>Slack workspace</ItemTitle>
						<ItemDescription className={status === "connected" ? undefined : "line-clamp-none"}>
							{status === "scopes-missing" ? (
								<MissingScopes scopes={missingScopes} />
							) : (
								TOKEN_DESCRIPTION[status]
							)}
						</ItemDescription>
					</ItemContent>
					<ItemActions>
						<Badge variant={TOKEN_BADGE[status].variant}>{TOKEN_BADGE[status].label}</Badge>
					</ItemActions>
				</Item>
			</CardContent>

			<CardFooter className="justify-end gap-2">
				{scopeCheck.state === "failed" && status === "check-failed" && (
					<Button variant="outline" onClick={scopeCheck.onRetry} disabled={scopeCheck.isRetrying}>
						{scopeCheck.isRetrying ? (
							<>
								<Spinner />
								Checking…
							</>
						) : (
							"Check again"
						)}
					</Button>
				)}
				<Button
					variant={needsReconnect ? "default" : "outline"}
					onClick={onReconnect}
					disabled={isReconnecting || isDisconnecting}
				>
					{isReconnecting ? (
						<>
							<Spinner />
							Redirecting to Slack…
						</>
					) : (
						<>
							Reconnect Slack
							<ExternalLinkIcon className="size-3.5" />
						</>
					)}
				</Button>
				{onDisconnect && (
					<Button
						variant="destructive-outline"
						onClick={() => setDisconnectOpen(true)}
						disabled={isDisconnecting || isReconnecting}
					>
						{isDisconnecting ? "Disconnecting…" : "Disconnect Slack…"}
					</Button>
				)}
			</CardFooter>

			<AlertDialog open={disconnectOpen} onOpenChange={setDisconnectOpen}>
				<AlertDialogContent>
					<AlertDialogHeader>
						<AlertDialogTitle>Disconnect Slack?</AlertDialogTitle>
						<AlertDialogDescription>
							This removes the Slack connection for this workspace. It also erases every stored
							Slack message, thread, and per-channel consent record for this workspace. You cannot
							undo this. Messages already sent in Slack stay there. To use Slack with this workspace
							later, connect it again and activate the channels again. To replace only the stored
							token, use Reconnect Slack instead. It keeps the channels and their data.
						</AlertDialogDescription>
					</AlertDialogHeader>
					<AlertDialogFooter>
						<AlertDialogCancel disabled={isDisconnecting}>Cancel</AlertDialogCancel>
						<AlertDialogAction
							variant="destructive"
							disabled={isDisconnecting}
							onClick={() => {
								void confirmDisconnect();
							}}
						>
							{isDisconnecting ? "Disconnecting…" : "Disconnect Slack"}
						</AlertDialogAction>
					</AlertDialogFooter>
				</AlertDialogContent>
			</AlertDialog>
		</>
	);
}
