import { PlugZapIcon, PlusIcon, RadioIcon } from "lucide-react";
import { type ReactNode, useState } from "react";

import type {
	SlackChannelCandidate as ApiSlackChannelCandidate,
	SlackMonitoredChannel,
} from "@/api/types.gen";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { TableRowsSkeleton } from "@/components/common/TableRowsSkeleton";
import { Button } from "@/components/ui/button";
import { Card, CardAction, CardContent, CardDescription, CardHeader } from "@/components/ui/card";
import {
	Empty,
	EmptyContent,
	EmptyDescription,
	EmptyHeader,
	EmptyMedia,
	EmptyTitle,
} from "@/components/ui/empty";
import { Table, TableBody, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import type { RefreshFailure } from "@/runtime/tanstack-query/infinite-list";

import { IntegrationCardHeading } from "./IntegrationCardHeading";
import { ActivateChannelDialog } from "./slack-channels/ActivateChannelDialog";
import { AddChannelDialog, type AddChannelDialogProps } from "./slack-channels/AddChannelDialog";
import { type ChannelHistory, ChannelHistorySheet } from "./slack-channels/ChannelHistorySheet";
import type { SlackConsentState } from "./slack-channels/consent-terms";
import { RemoveChannelAlertDialog } from "./slack-channels/RemoveChannelAlertDialog";
import { SlackChannelRow } from "./slack-channels/SlackChannelRow";
import { swallow } from "./swallow";

export type { SlackConsentState } from "./slack-channels/consent-terms";

export type SlackChannelCandidate = ApiSlackChannelCandidate;

/** Shared by the loading and loaded states so the header doesn't materialise on resolve. */
function ChannelsTableHeader() {
	return (
		<TableHeader>
			<TableRow>
				<TableHead>Channel</TableHead>
				<TableHead>Status</TableHead>
				<TableHead className="text-right">Opted out</TableHead>
				<TableHead>Announced</TableHead>
				<TableHead className="w-0 text-right">
					<span className="sr-only">Actions</span>
				</TableHead>
			</TableRow>
		</TableHeader>
	);
}

export interface WorkspaceSlackChannelsSettingsProps {
	/** Whether the workspace has an installed Slack app. Without one, the section is discoverable
	 * but inert: it explains what monitoring does and points at the connect card above. */
	hasSlackConnection: boolean;
	channels: SlackMonitoredChannel[];
	channelCandidates: readonly SlackChannelCandidate[];
	isLoading: boolean;
	/** The channel list could not be read — show why instead of the empty state. */
	isError?: boolean;
	/** The thrown error behind `isError`, when the container has it; feeds the alert's detail. */
	error?: unknown;
	/** Re-run the failed channel list query. */
	onRetry?: () => void;
	/** Reading the channels again failed; the channels last read stay, with the failure above them. */
	refreshFailure?: RefreshFailure;
	/** The channels Slack lists for the picker are still loading. */
	candidatesLoading?: boolean;
	/** The channels Slack lists for the picker could not be read; a pasted reference still works. */
	candidatesFailure?: AddChannelDialogProps["candidatesFailure"];
	/** Allow-list a new channel (lands PENDING). Resolves on success, rejects to keep the dialog open. */
	onRegisterChannel: (input: {
		slackChannelId: string;
		channelName?: string;
	}) => Promise<void> | void;
	/** Drive activate / pause / resume via the target consent state. */
	onUpdateConsent: (input: {
		slackChannelId: string;
		consentState: SlackConsentState;
		reason?: string;
	}) => Promise<void> | void;
	/** Terminal revoke + erase. Resolves on success, rejects to keep the dialog open. */
	onRemoveChannel: (input: { slackChannelId: string; reason?: string }) => Promise<void> | void;
	/** The consent history of the channel whose sheet is open, or was last open. */
	consentHistory: ChannelHistory;
	/** A channel's consent history sheet opens or closes. The route reads it while it is open. */
	onHistoryOpenChange: (slackChannelId: string, open: boolean) => void;
}

/** One open of a channel dialog. Every open is a new object, even for the same channel. */
interface ChannelDialogOpen {
	slackChannelId: string;
	open: boolean;
}

/**
 * A channel dialog's lifetime. The channel is read from the current list: when it leaves the list,
 * as a refused read clears it, the dialog unmounts at once instead of showing the channel through
 * an exit, and its draft and pending request go with it. A later read does not reopen it, and a
 * close from an earlier open, such as that request's answer, cannot close a later one.
 */
function useChannelDialog(channels: readonly SlackMonitoredChannel[]) {
	const [current, setCurrent] = useState<ChannelDialogOpen | null>(null);
	const channel = channels.find((c) => c.slackChannelId === current?.slackChannelId) ?? null;
	if (current !== null && channel === null) {
		setCurrent(null);
	}
	const open = (next: SlackMonitoredChannel) => {
		setCurrent({ slackChannelId: next.slackChannelId, open: true });
	};
	const dialog =
		current === null || channel === null
			? null
			: {
					// A closed dialog stays mounted, so its exit can play.
					channel: current.open ? channel : null,
					onOpenChange: (next: boolean) => {
						if (!next) {
							setCurrent((latest) => (latest === current ? { ...current, open: false } : latest));
						}
					},
				};
	return { open, dialog };
}

/**
 * Admin surface to allow-list Slack channels and drive their per-channel consent lifecycle
 * (PENDING → ACTIVE ⇄ PAUSED → REVOKED + erase). Pure: all data + mutations live in the route
 * container.
 */

export function WorkspaceSlackChannelsSettings({
	hasSlackConnection,
	channels,
	channelCandidates,
	isLoading,
	isError = false,
	error,
	onRetry,
	refreshFailure,
	candidatesLoading = false,
	candidatesFailure,
	onRegisterChannel,
	onUpdateConsent,
	onRemoveChannel,
	consentHistory,
	onHistoryOpenChange,
}: WorkspaceSlackChannelsSettingsProps) {
	const [addOpen, setAddOpen] = useState(false);
	const activate = useChannelDialog(channels);
	const remove = useChannelDialog(channels);
	const history = useChannelDialog(channels);

	return (
		<>
			<Card>
				<CardHeader>
					<IntegrationCardHeading>Slack channel monitoring</IntegrationCardHeading>
					<CardDescription>
						Hephaestus reads the <strong>new</strong> messages in monitored channels and uses AI to
						write practice feedback. The feedback goes to each author’s Practice profile and
						conversation with Hephaestus, <strong>never into the channel</strong>. It reads{" "}
						<strong>forward only</strong> and never reads past history. Each monitored channel gets
						a <strong>visible announcement</strong> in the channel, and any member can{" "}
						<strong>opt out</strong> from the app’s Home tab. Removing a channel{" "}
						<strong>permanently erases</strong> everything collected from it.
					</CardDescription>
					{hasSlackConnection && (
						<CardAction>
							<Button size="sm" onClick={() => setAddOpen(true)}>
								<PlusIcon className="size-4" />
								Add channel
							</Button>
						</CardAction>
					)}
				</CardHeader>

				<CardContent className="space-y-4">
					{hasSlackConnection && (
						<p className="text-sm text-muted-foreground">
							You can also invite Hephaestus from Slack. In the channel, run{" "}
							<code className="rounded bg-muted px-1 py-0.5">/invite @Hephaestus</code>. It appears
							here as <strong>Not started</strong> until an admin activates monitoring.
						</p>
					)}

					<ChannelsContent
						hasSlackConnection={hasSlackConnection}
						isLoading={isLoading}
						isError={isError}
						error={error}
						onRetry={onRetry}
						refreshFailure={refreshFailure}
						hasChannels={channels.length > 0}
						onAddChannel={() => setAddOpen(true)}
					>
						<Table>
							<ChannelsTableHeader />
							<TableBody>
								{channels.map((channel) => (
									<SlackChannelRow
										key={channel.slackChannelId}
										channel={channel}
										onActivate={activate.open}
										onPause={(c) =>
											swallow(
												onUpdateConsent({
													slackChannelId: c.slackChannelId,
													consentState: "PAUSED",
												}),
											)
										}
										onResume={(c) =>
											swallow(
												onUpdateConsent({
													slackChannelId: c.slackChannelId,
													consentState: "ACTIVE",
												}),
											)
										}
										onRemove={remove.open}
										onSetUpAgain={(c) =>
											swallow(
												onRegisterChannel({
													slackChannelId: c.slackChannelId,
													channelName: c.channelName ?? undefined,
												}),
											)
										}
										onViewHistory={(viewed) => {
											history.open(viewed);
											onHistoryOpenChange(viewed.slackChannelId, true);
										}}
									/>
								))}
							</TableBody>
						</Table>
					</ChannelsContent>
				</CardContent>
			</Card>

			<AddChannelDialog
				open={addOpen}
				onOpenChange={setAddOpen}
				candidates={channelCandidates}
				candidatesLoading={candidatesLoading}
				candidatesFailure={candidatesFailure}
				onSubmit={onRegisterChannel}
			/>

			{activate.dialog && (
				<ActivateChannelDialog
					channel={activate.dialog.channel}
					onOpenChange={activate.dialog.onOpenChange}
					onConfirm={async (c) =>
						onUpdateConsent({ slackChannelId: c.slackChannelId, consentState: "ACTIVE" })
					}
				/>
			)}

			{remove.dialog && (
				<RemoveChannelAlertDialog
					channel={remove.dialog.channel}
					onOpenChange={remove.dialog.onOpenChange}
					onConfirm={onRemoveChannel}
				/>
			)}

			{history.dialog && (
				<ChannelHistorySheet
					channel={history.dialog.channel}
					history={consentHistory}
					onOpenChange={(open) => {
						const shown = history.dialog?.channel;
						history.dialog?.onOpenChange(open);
						if (!open && shown) {
							onHistoryOpenChange(shown.slackChannelId, false);
						}
					}}
				/>
			)}
		</>
	);
}

function ChannelsContent({
	hasSlackConnection,
	isLoading,
	isError,
	error,
	onRetry,
	refreshFailure,
	hasChannels,
	onAddChannel,
	children,
}: Pick<
	WorkspaceSlackChannelsSettingsProps,
	"hasSlackConnection" | "isLoading" | "error" | "onRetry" | "refreshFailure"
> & {
	isError: boolean;
	hasChannels: boolean;
	onAddChannel: () => void;
	children: ReactNode;
}): ReactNode {
	if (!hasSlackConnection) {
		return (
			<Empty>
				<EmptyHeader>
					<EmptyMedia variant="icon">
						<PlugZapIcon />
					</EmptyMedia>
					<EmptyTitle>Connect Slack to monitor channels</EmptyTitle>
					<EmptyDescription>
						Channel monitoring needs an installed Slack app. Connect a Slack workspace in the Slack
						integration card above, then add the channels you want feedback from.
					</EmptyDescription>
				</EmptyHeader>
			</Empty>
		);
	}
	if (isLoading) {
		return (
			<Table>
				<ChannelsTableHeader />
				<TableRowsSkeleton columns={["w-32", "w-16", "w-12", "w-20", null]} rows={3} />
			</Table>
		);
	}
	if (isError) {
		return (
			<QueryErrorAlert
				error={error}
				title="We could not load the monitored channels"
				onRetry={onRetry}
			/>
		);
	}
	const refreshAlert = refreshFailure !== undefined && (
		<QueryErrorAlert title="The monitored channels may be out of date" {...refreshFailure} />
	);
	if (!hasChannels) {
		return (
			<>
				{refreshAlert}
				<Empty>
					<EmptyHeader>
						<EmptyMedia variant="icon">
							<RadioIcon />
						</EmptyMedia>
						<EmptyTitle>No channels monitored yet</EmptyTitle>
						<EmptyDescription>
							Add a Slack channel so that Hephaestus reviews its conversations. Their authors get
							the feedback on their Practice profile, not in the channel. Monitoring begins only
							when you activate the channel.
						</EmptyDescription>
					</EmptyHeader>
					<EmptyContent>
						<Button size="sm" onClick={onAddChannel}>
							<PlusIcon className="size-4" />
							Add channel
						</Button>
					</EmptyContent>
				</Empty>
			</>
		);
	}
	return (
		<>
			{refreshAlert}
			{children}
		</>
	);
}
