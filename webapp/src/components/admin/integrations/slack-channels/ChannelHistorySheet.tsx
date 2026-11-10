import { format } from "date-fns";
import { ArrowRightIcon, HistoryIcon } from "lucide-react";

import type { SlackChannelConsentEvent, SlackMonitoredChannel } from "@/api/types.gen";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import {
	Empty,
	EmptyDescription,
	EmptyHeader,
	EmptyMedia,
	EmptyTitle,
} from "@/components/ui/empty";
import { Item, ItemContent, ItemDescription, ItemGroup, ItemTitle } from "@/components/ui/item";
import { ScrollArea } from "@/components/ui/scroll-area";
import {
	Sheet,
	SheetContent,
	SheetDescription,
	SheetHeader,
	SheetTitle,
} from "@/components/ui/sheet";
import { Skeleton } from "@/components/ui/skeleton";
import { asDate } from "@/lib/dates";
import { hasText } from "@/lib/text";

import { ConsentStateBadge } from "./consent-terms";

/** The consent history of the channel whose sheet is open, as the route read it. */
export interface ChannelHistory {
	events?: readonly SlackChannelConsentEvent[];
	isLoading: boolean;
	/** The read failed. */
	error?: unknown;
	onRetry: () => void;
}

export interface ChannelHistorySheetProps {
	channel: SlackMonitoredChannel | null;
	history: ChannelHistory;
	onOpenChange: (open: boolean) => void;
}

export function ChannelHistorySheet({ channel, history, onOpenChange }: ChannelHistorySheetProps) {
	const open = channel != null;
	const label = channel ? (channel.channelName ?? channel.slackChannelId) : "";
	const { isLoading, error, onRetry } = history;
	const failed = error !== undefined;
	const events = history.events ?? [];

	return (
		<Sheet open={open} onOpenChange={onOpenChange}>
			<SheetContent className="w-full sm:max-w-md">
				<SheetHeader>
					<SheetTitle>Consent history</SheetTitle>
					<SheetDescription>
						Every recorded consent change for #{label}, newest first.
					</SheetDescription>
				</SheetHeader>

				<ScrollArea className="min-h-0 flex-1 px-4 pb-4">
					{isLoading && (
						<div className="space-y-3">
							<Skeleton className="h-12 w-full" />
							<Skeleton className="h-12 w-full" />
							<Skeleton className="h-12 w-full" />
						</div>
					)}

					{!isLoading && failed && (
						<QueryErrorAlert
							error={error}
							title="We could not load the consent history"
							onRetry={onRetry}
						/>
					)}

					{!isLoading && !failed && events.length === 0 && (
						<Empty>
							<EmptyHeader>
								<EmptyMedia variant="icon">
									<HistoryIcon />
								</EmptyMedia>
								<EmptyTitle>No consent changes recorded yet</EmptyTitle>
								<EmptyDescription>
									Each activation, pause, resume, and removal is recorded here. Entries cannot be
									edited.
								</EmptyDescription>
							</EmptyHeader>
						</Empty>
					)}

					{!isLoading && !failed && events.length > 0 && (
						<ItemGroup className="has-[[data-size=sm]]:gap-0">
							{events.map((event) => (
								<HistoryEntry key={event.id} event={event} />
							))}
						</ItemGroup>
					)}
				</ScrollArea>
			</SheetContent>
		</Sheet>
	);
}

function HistoryEntry({ event }: { event: SlackChannelConsentEvent }) {
	// `null` drops the date and keeps the transition: a stand-in instant would read as a recorded one.
	const createdAt = asDate(event.createdAt);
	return (
		<Item render={<li />} size="sm" variant="row" className="items-start">
			<ItemContent>
				<ItemTitle className="gap-1.5">
					{event.fromState && (
						<>
							<ConsentStateBadge state={event.fromState} />
							<ArrowRightIcon className="size-3.5 text-muted-foreground" aria-hidden />
						</>
					)}
					<ConsentStateBadge state={event.toState} />
				</ItemTitle>
				{createdAt && <ItemDescription>{format(createdAt, "PPpp")}</ItemDescription>}
				{hasText(event.reason) && (
					<ItemDescription className="text-foreground">{event.reason}</ItemDescription>
				)}
			</ItemContent>
		</Item>
	);
}
