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
					Install Hephaestus in Slack to talk with Heph in direct messages, manage privacy controls
					in App Home, and monitor channels you choose. Hephaestus stores no channel messages until
					an admin activates a channel.
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

function SlackConnected({
	credentialsUnreadableSince,
	onReconnect,
	isReconnecting,
	onDisconnect,
	isDisconnecting,
}: Extract<WorkspaceSlackConnectionSettingsProps, { state: "connected" }>) {
	const credentialUnreadable = credentialsUnreadableSince != null;
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
						<ItemDescription className={credentialUnreadable ? "line-clamp-none" : undefined}>
							{credentialUnreadable
								? "Hephaestus cannot post with this token. Reconnect Slack to replace it, or restore the original server key."
								: "Hephaestus is installed and can post in Slack."}
						</ItemDescription>
					</ItemContent>
					<ItemActions>
						{credentialUnreadable ? (
							<Badge variant="warning">Token unreadable</Badge>
						) : (
							<Badge variant="success">Connected</Badge>
						)}
					</ItemActions>
				</Item>
			</CardContent>

			<CardFooter className="justify-end gap-2">
				<Button
					variant={credentialUnreadable ? "default" : "outline"}
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
							Slack message, thread, and per-channel consent record for this workspace. Messages
							already sent in Slack stay there. To use Slack with this workspace again, authorize
							through OAuth and activate the channels again. To replace only the stored token, use
							Reconnect Slack instead. It keeps the channels and their data.
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
