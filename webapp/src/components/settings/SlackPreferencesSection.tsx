import { type ReactNode, useState } from "react";

import type { SlackUserWorkspacePreferences } from "@/api/types.gen";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
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
import { Field, FieldContent, FieldDescription, FieldLabel } from "@/components/ui/field";
import {
	Item,
	ItemActions,
	ItemContent,
	ItemDescription,
	ItemGroup,
	ItemMedia,
	ItemTitle,
} from "@/components/ui/item";
import { Skeleton } from "@/components/ui/skeleton";
import { Switch } from "@/components/ui/switch";

export interface SlackPreferencesSectionProps {
	workspaces: SlackUserWorkspacePreferences[];
	isSlackLinked: boolean;
	canConnectSlack: boolean;
	onConnectSlack: () => void;
	onToggleChannelMessages: (workspaceSlug: string, channelMessagesAllowed: boolean) => void;
	/** The workspaces whose own write is still out, each one's switch held until it finishes. */
	updatingWorkspaceSlugs?: ReadonlySet<string>;
	isLoading?: boolean;
	isError?: boolean;
	/** The thrown query error behind `isError`. */
	error?: unknown;
	onRetry?: () => void;
}

const NO_WORKSPACES: ReadonlySet<string> = new Set();

export function SlackPreferencesSection({
	workspaces,
	isSlackLinked,
	canConnectSlack,
	onConnectSlack,
	onToggleChannelMessages,
	updatingWorkspaceSlugs = NO_WORKSPACES,
	isLoading = false,
	isError = false,
	error,
	onRetry,
}: SlackPreferencesSectionProps) {
	let body: ReactNode;
	if (isLoading) {
		body = <WorkspacePreferenceSkeleton />;
	} else if (isError) {
		body = (
			<QueryErrorAlert
				error={error}
				title="We could not load your Slack preferences"
				onRetry={onRetry}
			/>
		);
	} else if (!isSlackLinked) {
		body = (
			<ItemGroup>
				<Item variant="outline" role="listitem">
					<ItemMedia variant="icon">
						<SlackIcon />
					</ItemMedia>
					<ItemContent>
						<ItemTitle>Slack is not connected</ItemTitle>
						<ItemDescription>
							Connect Slack to manage your channel-message preference from Hephaestus.
						</ItemDescription>
					</ItemContent>
					<ItemActions>
						{canConnectSlack ? (
							<Button variant="outline" size="sm" onClick={onConnectSlack}>
								<SlackIcon className="mr-1.5 size-3.5" />
								Connect Slack
							</Button>
						) : (
							<Badge variant="secondary">Not available</Badge>
						)}
					</ItemActions>
				</Item>
			</ItemGroup>
		);
	} else if (workspaces.length === 0) {
		body = (
			<ItemGroup>
				<Item variant="outline" role="listitem">
					<ItemMedia variant="icon">
						<SlackIcon />
					</ItemMedia>
					<ItemContent>
						<ItemTitle>
							Slack is connected
							<Badge variant="success">Connected</Badge>
						</ItemTitle>
						<ItemDescription>
							No linked Hephaestus workspace currently has this Slack workspace installed.
						</ItemDescription>
					</ItemContent>
				</Item>
			</ItemGroup>
		);
	} else {
		body = (
			<div className="space-y-3">
				{workspaces.map((workspace) => (
					<WorkspacePreferenceRow
						key={workspace.workspaceSlug}
						workspace={workspace}
						isUpdating={updatingWorkspaceSlugs.has(workspace.workspaceSlug)}
						onToggleChannelMessages={onToggleChannelMessages}
					/>
				))}
			</div>
		);
	}

	return (
		<section className="space-y-4" aria-labelledby="slack-preferences-heading">
			<div className="space-y-1">
				<div className="flex items-center gap-2">
					<SlackIcon className="size-5" />
					<h2 id="slack-preferences-heading" className="text-xl font-semibold">
						Slack
					</h2>
				</div>
				<p className="text-sm text-muted-foreground">
					Connect your Slack account, and choose whether Hephaestus can use your new messages in
					monitored channels as context for your feedback and Hephaestus’s answers.
				</p>
			</div>

			{body}
		</section>
	);
}

/**
 * One workspace's preference group in the shape it loads into. How many workspaces there are is not
 * known yet, so one stands for them; it claims no connection, status or workspace.
 */
function WorkspacePreferenceSkeleton() {
	return (
		<div
			role="group"
			aria-label="Loading Slack preferences"
			aria-busy="true"
			className="space-y-4 rounded-lg border p-4"
		>
			<div className="space-y-1">
				<div className="flex flex-wrap items-center gap-2">
					<Skeleton className="h-5 w-32" />
					<Skeleton className="h-5 w-24" />
				</div>
				<Skeleton className="h-4 w-48" />
				<Skeleton className="h-4 w-40" />
			</div>
			<div className="flex items-start justify-between gap-4">
				<div className="min-w-0 flex-1 space-y-2">
					<Skeleton className="h-4 w-56 max-w-full" />
					<Skeleton className="h-12 w-full" />
				</div>
				<Skeleton className="h-5 w-9 shrink-0" />
			</div>
		</div>
	);
}

interface WorkspacePreferenceRowProps {
	workspace: SlackUserWorkspacePreferences;
	isUpdating: boolean;
	onToggleChannelMessages: (workspaceSlug: string, channelMessagesAllowed: boolean) => void;
}

function WorkspacePreferenceRow({
	workspace,
	isUpdating,
	onToggleChannelMessages,
}: WorkspacePreferenceRowProps) {
	const switchId = `slack-channel-messages-${workspace.workspaceSlug}`;
	const channelMessagesAllowed = workspace.channelMessagesAllowed ?? true;

	// Turning the toggle OFF *deletes* already-collected channel messages — irreversible, so it is
	// confirmed. Turning it ON only starts collecting from now on, so it stays instant.
	const [confirmingOff, setConfirmingOff] = useState(false);

	const handleCheckedChange = (checked: boolean) => {
		if (checked) {
			onToggleChannelMessages(workspace.workspaceSlug, true);
			return;
		}
		setConfirmingOff(true);
	};

	return (
		<div
			role="group"
			aria-label={`${workspace.workspaceName} Slack preferences`}
			className="space-y-4 rounded-lg border p-4"
		>
			<div className="space-y-1">
				<div className="flex flex-wrap items-center gap-2">
					<h3 className="text-sm font-medium">{workspace.workspaceName}</h3>
					<Badge variant={channelMessagesAllowed ? "success" : "outline"}>
						{channelMessagesAllowed ? "Message use on" : "Message use off"}
					</Badge>
				</div>
				<p className="text-xs text-muted-foreground">
					Slack workspace {workspace.slackTeamName ?? workspace.slackTeamId}
				</p>
				<p className="text-xs text-muted-foreground">
					{channelCountText(workspace.activeMonitoredChannelCount)}
				</p>
			</div>

			<Field orientation="horizontal">
				<FieldContent>
					<FieldLabel htmlFor={switchId}>Use your new channel messages</FieldLabel>
					<FieldDescription>
						When this is on, new messages you send in monitored Slack channels can be used as
						context for your private conversations with Hephaestus. Turning it off deletes the
						channel-message data already collected from you in this workspace.
					</FieldDescription>
				</FieldContent>
				<Switch
					id={switchId}
					checked={channelMessagesAllowed}
					onCheckedChange={handleCheckedChange}
					disabled={isUpdating}
					aria-busy={isUpdating}
				/>
			</Field>

			<AlertDialog
				open={confirmingOff}
				onOpenChange={(open) => {
					if (!open && !isUpdating) {
						setConfirmingOff(false);
					}
				}}
			>
				<AlertDialogContent>
					<AlertDialogHeader>
						<AlertDialogTitle>Stop using your channel messages?</AlertDialogTitle>
						<AlertDialogDescription>
							Hephaestus will stop reading your new messages in monitored channels of{" "}
							{workspace.workspaceName} and{" "}
							<strong>
								permanently delete the channel-message data already collected from you
							</strong>{" "}
							in this workspace. Your messages in Slack itself are untouched. You cannot undo this.
						</AlertDialogDescription>
					</AlertDialogHeader>
					<AlertDialogFooter>
						<AlertDialogCancel disabled={isUpdating}>Cancel</AlertDialogCancel>
						<AlertDialogAction
							variant="destructive"
							disabled={isUpdating}
							onClick={() => {
								onToggleChannelMessages(workspace.workspaceSlug, false);
								setConfirmingOff(false);
							}}
						>
							Stop using and delete
						</AlertDialogAction>
					</AlertDialogFooter>
				</AlertDialogContent>
			</AlertDialog>
		</div>
	);
}

function channelCountText(count = 0): string {
	return count === 1 ? "1 active monitored channel" : `${count} active monitored channels`;
}
