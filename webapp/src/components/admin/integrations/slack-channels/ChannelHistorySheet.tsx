import { useQuery } from "@tanstack/react-query";
import { format } from "date-fns";
import { ArrowRightIcon, HistoryIcon } from "lucide-react";

import { listSlackChannelConsentEventsOptions } from "@/api/@tanstack/react-query.gen";
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

import { ConsentStateBadge } from "./consent-terms";

export interface ChannelHistorySheetProps {
	workspaceSlug: string;
	channel: SlackMonitoredChannel | null;
	onOpenChange: (open: boolean) => void;
}

export function ChannelHistorySheet({
	workspaceSlug,
	channel,
	onOpenChange,
}: ChannelHistorySheetProps) {
	const open = channel != null;
	const label = channel ? (channel.channelName ?? channel.slackChannelId) : "";

	const { data, isLoading, error, refetch } = useQuery({
		...listSlackChannelConsentEventsOptions({
			path: { workspaceSlug, slackChannelId: channel?.slackChannelId ?? "" },
		}),
		enabled: open,
	});

	const events = data ?? [];

	return (
		<Sheet open={open} onOpenChange={onOpenChange}>
			<SheetContent className="w-full sm:max-w-md">
				<SheetHeader>
					<SheetTitle>Consent history</SheetTitle>
					<SheetDescription>
						Every recorded consent transition for #{label}, newest first.
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

					{!isLoading && error && (
						<QueryErrorAlert
							error={error}
							title="Could not load the consent history"
							onRetry={() => void refetch()}
						/>
					)}

					{!isLoading && !error && events.length === 0 && (
						<Empty>
							<EmptyHeader>
								<EmptyMedia variant="icon">
									<HistoryIcon />
								</EmptyMedia>
								<EmptyTitle>No consent changes recorded yet</EmptyTitle>
								<EmptyDescription>
									Every activation, pause, resume and removal lands here as an immutable audit
									entry.
								</EmptyDescription>
							</EmptyHeader>
						</Empty>
					)}

					{!isLoading && !error && events.length > 0 && (
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
				{event.reason && (
					<ItemDescription className="text-foreground">{event.reason}</ItemDescription>
				)}
			</ItemContent>
		</Item>
	);
}
