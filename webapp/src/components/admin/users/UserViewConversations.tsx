import { MessagesSquareIcon } from "lucide-react";

import type { ChatThreadSummary } from "@/api/types.gen";
import type { PanelState } from "@/components/common/panel-state";
import { RelativeTime } from "@/components/common/RelativeTime";
import { TablePagination } from "@/components/common/TablePagination";
import { Button } from "@/components/ui/button";
import {
	Empty,
	EmptyDescription,
	EmptyHeader,
	EmptyMedia,
	EmptyTitle,
} from "@/components/ui/empty";
import {
	Item,
	ItemActions,
	ItemContent,
	ItemDescription,
	ItemGroup,
	ItemTitle,
} from "@/components/ui/item";
import { Skeleton } from "@/components/ui/skeleton";

import { UserViewErrorAlert } from "./UserViewErrorAlert";

export type UserViewConversationsState = PanelState<{
	threads: ChatThreadSummary[];
	totalPages: number;
}>;

export interface UserViewConversationsProps {
	state: UserViewConversationsState;
	page: number;
	onPageChange: (page: number) => void;
	onOpen: (threadId: string) => void;
}

const SKELETON_ROWS = 5;

function titleOf(thread: { title?: string | null }): string {
	return thread.title?.trim() ? thread.title.trim() : "Untitled conversation";
}

export function UserViewConversations({
	state,
	page,
	onPageChange,
	onOpen,
}: UserViewConversationsProps) {
	if (state.status === "loading") {
		return (
			<ItemGroup aria-hidden>
				{Array.from({ length: SKELETON_ROWS }, (_, index) => (
					<div key={index} role="listitem">
						<Item variant="outline">
							<ItemContent>
								<Skeleton className="h-4 w-full max-w-64" />
								<Skeleton className="h-3 w-full max-w-24" />
							</ItemContent>
						</Item>
					</div>
				))}
			</ItemGroup>
		);
	}
	if (state.status === "error") {
		return <UserViewErrorAlert error={state.error} onRetry={state.onRetry} />;
	}
	if (state.threads.length === 0) {
		return (
			<Empty className="border border-dashed">
				<EmptyHeader>
					<EmptyMedia variant="icon">
						<MessagesSquareIcon />
					</EmptyMedia>
					<EmptyTitle>No existing conversations</EmptyTitle>
					<EmptyDescription>This user has not talked to Heph in this workspace.</EmptyDescription>
				</EmptyHeader>
			</Empty>
		);
	}
	return (
		<div className="space-y-3">
			<ItemGroup aria-label="Private conversations">
				{state.threads.map((thread, index) => (
					<div key={thread.id ?? index} role="listitem">
						<Item variant="outline">
							<ItemContent className="min-w-0">
								<ItemTitle className="break-words">{titleOf(thread)}</ItemTitle>
								{thread.createdAt && (
									<ItemDescription>
										Started <RelativeTime value={thread.createdAt} />
									</ItemDescription>
								)}
							</ItemContent>
							<ItemActions>
								<Button
									variant="outline"
									size="sm"
									aria-label={`Open conversation: ${titleOf(thread)}`}
									onClick={() => thread.id && onOpen(thread.id)}
								>
									Open
								</Button>
							</ItemActions>
						</Item>
					</div>
				))}
			</ItemGroup>
			<TablePagination
				className="justify-end"
				page={page}
				totalPages={state.totalPages}
				onPageChange={onPageChange}
			/>
		</div>
	);
}
