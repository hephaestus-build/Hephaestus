import { ChevronsUpDownIcon, LockIcon } from "lucide-react";
import type { ReactNode } from "react";

import { cn } from "cn";
import type { SlackChannelCandidate } from "@/api/types.gen";
import { Badge } from "@/components/ui/badge";
import {
	Combobox,
	ComboboxContent,
	ComboboxEmpty,
	ComboboxIcon,
	ComboboxItem,
	ComboboxItemIndicator,
	ComboboxList,
	ComboboxSearchInput,
	ComboboxTrigger,
	useComboboxFilter,
} from "@/components/ui/combobox";

export interface SlackChannelComboboxProps {
	id?: string;
	candidates: SlackChannelCandidate[];
	selectedChannelId?: string;
	selectedChannelName?: string;
	onSelect: (candidate: SlackChannelCandidate) => void;
	disabled?: boolean;
	invalid?: boolean;
	getDisabledReason?: (candidate: SlackChannelCandidate) => string | undefined;
	renderBadges?: (candidate: SlackChannelCandidate) => ReactNode;
	placeholder?: string;
	className?: string;
	"aria-label"?: string;
}

function searchTextOf(candidate: SlackChannelCandidate) {
	return `${candidate.channelName} ${candidate.slackChannelId}`;
}

export function SlackChannelCombobox({
	id,
	candidates,
	selectedChannelId,
	selectedChannelName,
	onSelect,
	disabled = false,
	invalid = false,
	getDisabledReason,
	renderBadges,
	placeholder = "Select a channel…",
	className,
	"aria-label": ariaLabel = "Search Slack channels",
}: SlackChannelComboboxProps) {
	const { contains } = useComboboxFilter({ sensitivity: "base" });

	const selected = candidates.find((candidate) => candidate.slackChannelId === selectedChannelId);
	const selectedLabel = selected?.channelName ?? selectedChannelName;

	return (
		<Combobox
			items={candidates}
			value={selected ?? null}
			onValueChange={(candidate) => {
				if (candidate) onSelect(candidate);
			}}
			disabled={disabled}
			filter={(candidate, query) => contains(candidate, query, searchTextOf)}
			itemToStringLabel={(candidate) => candidate.channelName}
		>
			<ComboboxTrigger
				id={id}
				aria-label={id ? undefined : "Slack channel"}
				aria-invalid={invalid}
				className={cn("w-full justify-between font-normal", className)}
			>
				{selectedLabel ? (
					<span className="truncate">#{selectedLabel}</span>
				) : selectedChannelId ? (
					<span className="text-muted-foreground truncate">
						Channel <span className="font-mono">{selectedChannelId}</span>
					</span>
				) : (
					<span className="text-muted-foreground truncate">{placeholder}</span>
				)}
				<ComboboxIcon render={<ChevronsUpDownIcon className="size-4 shrink-0 opacity-50" />} />
			</ComboboxTrigger>

			<ComboboxContent align="start" className="min-w-72" aria-label="Slack channels">
				<ComboboxSearchInput placeholder="Search channels…" aria-label={ariaLabel} />
				<ComboboxEmpty>No channels found.</ComboboxEmpty>
				<ComboboxList aria-label="Slack channels">
					{(candidate: SlackChannelCandidate) => {
						const disabledReason = getDisabledReason?.(candidate);
						return (
							<ComboboxItem
								key={candidate.slackChannelId}
								value={candidate}
								disabled={disabledReason != null}
							>
								<div className="min-w-0 flex-1">
									<div className="flex flex-wrap items-center gap-2">
										<span className="truncate font-medium">#{candidate.channelName}</span>
										{candidate.privateChannel && (
											<LockIcon className="size-3.5" role="img" aria-label="Private" />
										)}
										{renderBadges?.(candidate)}
										{disabledReason && <Badge variant="outline">{disabledReason}</Badge>}
									</div>
									<div className="text-muted-foreground font-mono text-xs">
										{candidate.slackChannelId}
									</div>
								</div>
								<ComboboxItemIndicator />
							</ComboboxItem>
						);
					}}
				</ComboboxList>
			</ComboboxContent>
		</Combobox>
	);
}
