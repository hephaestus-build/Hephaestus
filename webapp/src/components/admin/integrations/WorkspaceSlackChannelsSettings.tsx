import { PlugZapIcon, PlusIcon, RadioIcon } from "lucide-react";
import { type ReactNode, useState } from "react";

import type {
	SlackChannelCandidate as ApiSlackChannelCandidate,
	SlackMonitoredChannel,
} from "@/api/types.gen";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
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

import { IntegrationCardHeading } from "./IntegrationCardHeading";
import { ActivateChannelDialog } from "./slack-channels/ActivateChannelDialog";
import { AddChannelDialog } from "./slack-channels/AddChannelDialog";
import { ChannelHistorySheet } from "./slack-channels/ChannelHistorySheet";
import type { SlackConsentState } from "./slack-channels/consent-terms";
import { RemoveChannelAlertDialog } from "./slack-channels/RemoveChannelAlertDialog";
import { SlackChannelRow } from "./slack-channels/SlackChannelRow";
import { swallow } from "./swallow";
import { TableRowsSkeleton } from "./TableRowsSkeleton";

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
	workspaceSlug: string;
	/** Whether the workspace has an installed Slack app. Without one, the section is discoverable
	 * but inert: it explains what monitoring does and points at the connect card above. */
	hasSlackConnection: boolean;
	channels: SlackMonitoredChannel[];
	channelCandidates: readonly SlackChannelCandidate[];
	isLoading: boolean;
	/** The channel list query failed — show a retry panel instead of the empty state. */
	isError?: boolean;
	/** The thrown error behind `isError`, when the container has it; feeds the alert's detail. */
	error?: unknown;
	/** Re-run the failed channel list query. */
	onRetry?: () => void;
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
}

/**
 * Admin surface to allow-list Slack channels and drive their per-channel consent lifecycle
 * (PENDING → ACTIVE ⇄ PAUSED → REVOKED + erase). Pure: all data + mutations live in the route
 * container.
 */

export function WorkspaceSlackChannelsSettings({
	workspaceSlug,
	hasSlackConnection,
	channels,
	channelCandidates,
	isLoading,
	isError = false,
	error,
	onRetry,
	onRegisterChannel,
	onUpdateConsent,
	onRemoveChannel,
}: WorkspaceSlackChannelsSettingsProps) {
	const [addOpen, setAddOpen] = useState(false);
	const [activateChannel, setActivateChannel] = useState<SlackMonitoredChannel | null>(null);
	const [removeChannel, setRemoveChannel] = useState<SlackMonitoredChannel | null>(null);
	const [historyChannel, setHistoryChannel] = useState<SlackMonitoredChannel | null>(null);

	const hasChannels = channels.length > 0;

	let content: ReactNode;
	if (!hasSlackConnection) {
		content = (
			<Empty>
				<EmptyHeader>
					<EmptyMedia variant="icon">
						<PlugZapIcon />
					</EmptyMedia>
					<EmptyTitle>Connect Slack to monitor channels</EmptyTitle>
					<EmptyDescription>
						Channel monitoring needs an installed Slack app. Connect a Slack workspace in the Slack
						integration card above, then allow-list the channels you want feedback from.
					</EmptyDescription>
				</EmptyHeader>
			</Empty>
		);
	} else if (isLoading) {
		content = (
			<Table>
				<ChannelsTableHeader />
				<TableRowsSkeleton columns={["w-32", "w-16", "w-12", "w-20", null]} rows={3} />
			</Table>
		);
	} else if (isError) {
		content = (
			<QueryErrorAlert
				error={error}
				title="We couldn't load the monitored channels"
				onRetry={onRetry}
			/>
		);
	} else if (hasChannels) {
		content = (
			<Table>
				<ChannelsTableHeader />
				<TableBody>
					{channels.map((channel) => (
						<SlackChannelRow
							key={channel.slackChannelId}
							channel={channel}
							onActivate={setActivateChannel}
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
							onRemove={setRemoveChannel}
							onSetUpAgain={(c) =>
								swallow(
									onRegisterChannel({
										slackChannelId: c.slackChannelId,
										channelName: c.channelName,
									}),
								)
							}
							onViewHistory={setHistoryChannel}
						/>
					))}
				</TableBody>
			</Table>
		);
	} else {
		content = (
			<Empty>
				<EmptyHeader>
					<EmptyMedia variant="icon">
						<RadioIcon />
					</EmptyMedia>
					<EmptyTitle>No channels monitored yet</EmptyTitle>
					<EmptyDescription>
						Allow-list a Slack channel to start generating AI practice feedback from its
						conversations. You choose exactly when monitoring begins.
					</EmptyDescription>
				</EmptyHeader>
				<EmptyContent>
					<Button size="sm" onClick={() => setAddOpen(true)}>
						<PlusIcon className="size-4" />
						Add channel
					</Button>
				</EmptyContent>
			</Empty>
		);
	}

	return (
		<>
			<Card>
				<CardHeader>
					<IntegrationCardHeading>Slack channel monitoring</IntegrationCardHeading>
					<CardDescription>
						Monitored channels have their <strong>new</strong> messages read to generate AI practice
						feedback. Reading is <strong>forward-only</strong> (never past history), each monitored
						channel gets a <strong>visible in-channel announcement</strong>, and any member can{" "}
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
							<code className="rounded bg-muted px-1 py-0.5">/invite @Hephaestus</code>; it appears
							here as <strong>Not started</strong> until an admin activates monitoring.
						</p>
					)}

					{content}
				</CardContent>
			</Card>

			<AddChannelDialog
				open={addOpen}
				onOpenChange={setAddOpen}
				candidates={channelCandidates}
				onSubmit={onRegisterChannel}
			/>

			<ActivateChannelDialog
				channel={activateChannel}
				onOpenChange={(open) => {
					if (!open) {
						setActivateChannel(null);
					}
				}}
				onConfirm={async (c) =>
					onUpdateConsent({ slackChannelId: c.slackChannelId, consentState: "ACTIVE" })
				}
			/>

			<RemoveChannelAlertDialog
				channel={removeChannel}
				onOpenChange={(open) => {
					if (!open) {
						setRemoveChannel(null);
					}
				}}
				onConfirm={onRemoveChannel}
			/>

			<ChannelHistorySheet
				workspaceSlug={workspaceSlug}
				channel={historyChannel}
				onOpenChange={(open) => {
					if (!open) {
						setHistoryChannel(null);
					}
				}}
			/>
		</>
	);
}
