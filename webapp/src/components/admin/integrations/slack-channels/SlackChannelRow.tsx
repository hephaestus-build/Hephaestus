import {
	HistoryIcon,
	MoreHorizontalIcon,
	PauseIcon,
	PlayIcon,
	RotateCcwIcon,
	Trash2Icon,
} from "lucide-react";

import type { SlackMonitoredChannel } from "@/api/types.gen";
import { RelativeTime } from "@/components/common/RelativeTime";
import { Button } from "@/components/ui/button";
import {
	DropdownMenu,
	DropdownMenuContent,
	DropdownMenuItem,
	DropdownMenuSeparator,
	DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { TableCell, TableRow } from "@/components/ui/table";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";

import { ConsentStateBadge } from "./consent-terms";

export interface SlackChannelRowProps {
	channel: SlackMonitoredChannel;
	/** Open the activation confirmation dialog for this channel (PENDING only). */
	onActivate: (channel: SlackMonitoredChannel) => void;
	/** Pause an ACTIVE channel (reversible, no confirm). */
	onPause: (channel: SlackMonitoredChannel) => void;
	/** Resume a PAUSED channel (reversible, no confirm). */
	onResume: (channel: SlackMonitoredChannel) => void;
	/** Open the type-to-confirm remove & erase dialog. */
	onRemove: (channel: SlackMonitoredChannel) => void;
	/** Set up a revoked channel for a new consent cycle. */
	onSetUpAgain: (channel: SlackMonitoredChannel) => void;
	/** Open the consent-history sheet. */
	onViewHistory: (channel: SlackMonitoredChannel) => void;
}

/**
 * One allow-listed Slack channel: name/id, status badge, opted-out signal, announcement
 * time and a state-gated row action menu. Pure — every transition is delegated upward.
 */
export function SlackChannelRow({
	channel,
	onActivate,
	onPause,
	onResume,
	onRemove,
	onSetUpAgain,
	onViewHistory,
}: SlackChannelRowProps) {
	const label = channel.channelName ?? channel.slackChannelId;
	const isTerminal = channel.consentState === "REVOKED";

	return (
		<TableRow>
			<TableCell>
				<div className="font-medium">#{label}</div>
				<div className="text-muted-foreground font-mono text-xs">{channel.slackChannelId}</div>
			</TableCell>

			<TableCell>
				<ConsentStateBadge state={channel.consentState} />
			</TableCell>

			<TableCell numeric className="text-right">
				{channel.optedOutMemberCount > 0 ? (
					<Tooltip>
						{/* Default TooltipTrigger renders a real <button>, so it's reachable by keyboard
						and screen-reader users — a bare <span> trigger has no focus stop. */}
						<TooltipTrigger className="cursor-help underline decoration-dotted underline-offset-2">
							{channel.optedOutMemberCount}
						</TooltipTrigger>
						<TooltipContent>
							{channel.optedOutMemberCount} workspace member
							{channel.optedOutMemberCount === 1 ? "" : "s"} opted out (App Home)
						</TooltipContent>
					</Tooltip>
				) : (
					<span className="text-muted-foreground">0</span>
				)}
			</TableCell>

			<TableCell className="text-muted-foreground text-sm">
				<RelativeTime value={channel.consentAnnouncedAt} fallback="Never" />
			</TableCell>

			<TableCell className="text-right">
				<DropdownMenu>
					<DropdownMenuTrigger
						render={
							<Button variant="ghost" size="icon-sm" aria-label={`Actions for ${label}`}>
								<MoreHorizontalIcon className="size-4" />
							</Button>
						}
					/>
					<DropdownMenuContent align="end">
						{channel.consentState === "PENDING" && (
							<DropdownMenuItem onClick={() => onActivate(channel)}>
								<PlayIcon className="size-4" />
								Activate monitoring…
							</DropdownMenuItem>
						)}
						{channel.consentState === "ACTIVE" && (
							<DropdownMenuItem onClick={() => onPause(channel)}>
								<PauseIcon className="size-4" />
								Pause
							</DropdownMenuItem>
						)}
						{channel.consentState === "PAUSED" && (
							<DropdownMenuItem onClick={() => onResume(channel)}>
								<PlayIcon className="size-4" />
								Resume
							</DropdownMenuItem>
						)}
						{isTerminal && (
							<DropdownMenuItem onClick={() => onSetUpAgain(channel)}>
								<RotateCcwIcon className="size-4" />
								Set up again
							</DropdownMenuItem>
						)}
						{channel.consentState !== "PENDING" && (
							<DropdownMenuItem onClick={() => onViewHistory(channel)}>
								<HistoryIcon className="size-4" />
								View history
							</DropdownMenuItem>
						)}
						{!isTerminal && (
							<>
								<DropdownMenuSeparator />
								<DropdownMenuItem variant="destructive" onClick={() => onRemove(channel)}>
									<Trash2Icon className="size-4" />
									Remove &amp; erase…
								</DropdownMenuItem>
							</>
						)}
					</DropdownMenuContent>
				</DropdownMenu>
			</TableCell>
		</TableRow>
	);
}
