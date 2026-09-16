import { Link } from "@tanstack/react-router";
import { ChevronRightIcon } from "lucide-react";

import type { TracedArtifact } from "@/api/types.gen";
import { RelativeTime } from "@/components/common/RelativeTime";
import { Item, ItemActions, ItemContent, ItemDescription, ItemTitle } from "@/components/ui/item";
import { artifactKindIcon, artifactKindLabel } from "@/lib/artifact-kinds";

import { signalCountsLabel } from "./trace-format";

export interface TracedArtifactRowProps {
	workspaceSlug: string;
	artifact: TracedArtifact;
}

/** One piece of work in the review-activity list, and how much of it turned into review. */
export function TracedArtifactRow({ workspaceSlug, artifact }: TracedArtifactRowProps) {
	const KindIcon = artifactKindIcon(artifact.artifactKind);

	return (
		<div role="listitem">
			<Item
				variant="outline"
				className="items-start"
				render={
					<Link
						to="/w/$workspaceSlug/reviews/$artifactKind/$artifactId"
						params={{
							workspaceSlug,
							artifactKind: artifact.artifactKind,
							artifactId: String(artifact.artifactId),
						}}
					/>
				}
			>
				<span className="mt-0.5 shrink-0 text-muted-foreground [&>svg]:size-4">
					<KindIcon aria-hidden />
					<span className="sr-only">{artifactKindLabel(artifact.artifactKind)}</span>
				</span>
				<ItemContent className="min-w-0">
					<ItemTitle className="line-clamp-none w-full min-w-0 break-words">
						{artifact.title}
						{artifact.number != null && (
							<span className="ml-1 font-normal text-muted-foreground tabular-nums">
								#{artifact.number}
							</span>
						)}
					</ItemTitle>
					{artifact.container && (
						<ItemDescription className="break-words">{artifact.container}</ItemDescription>
					)}
					<div className="mt-1 flex flex-wrap items-center gap-x-2 gap-y-1 text-sm text-muted-foreground">
						<RelativeTime value={artifact.lastSignalAt} />
						<span aria-hidden>·</span>
						<span>{signalCountsLabel(artifact.signalCount, artifact.reviewedSignalCount)}</span>
					</div>
				</ItemContent>
				<ItemActions>
					<ChevronRightIcon className="size-4 text-muted-foreground" aria-hidden />
				</ItemActions>
			</Item>
		</div>
	);
}
