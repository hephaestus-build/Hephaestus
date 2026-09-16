import { Link, useLocation } from "@tanstack/react-router";

import type { ChatThreadSummary } from "@/api/types.gen";
import { useNow } from "@/components/common/use-now";
import {
	SidebarGroup,
	SidebarGroupContent,
	SidebarGroupLabel,
	SidebarMenu,
	SidebarMenuButton,
	SidebarMenuItem,
} from "@/components/ui/sidebar";
import { hasText } from "@/lib/text";

export interface NavMentorThreadsProps {
	threads: ChatThreadSummary[];
	isLoading?: boolean;
	error?: string;
	workspaceSlug: string;
}

interface ThreadGroupData {
	groupName: string;
	threads: ChatThreadSummary[];
}

type BucketName = "Today" | "Yesterday" | "Last 7 days" | "Last 30 days" | "Older";

/**
 * Group thread summaries by their createdAt bucket: Today, Yesterday,
 * Last 7 days, Last 30 days, Older. Preserves arrival order (newest first
 * within each bucket).
 *
 * The Pi mentor returns a flat list, so we bucket locally for the same UX.
 */
function bucketThreads(threads: ChatThreadSummary[], now: number): ThreadGroupData[] {
	const buckets: Record<BucketName, ChatThreadSummary[]> = {
		Today: [],
		Yesterday: [],
		"Last 7 days": [],
		"Last 30 days": [],
		Older: [],
	};

	const day = 24 * 60 * 60 * 1000;

	for (const thread of threads) {
		const createdAt = thread.createdAt ? new Date(thread.createdAt).getTime() : now;
		const ageDays = (now - createdAt) / day;
		let bucket: BucketName;
		if (ageDays < 1) {
			bucket = "Today";
		} else if (ageDays < 2) {
			bucket = "Yesterday";
		} else if (ageDays < 7) {
			bucket = "Last 7 days";
		} else if (ageDays < 30) {
			bucket = "Last 30 days";
		} else {
			bucket = "Older";
		}
		buckets[bucket].push(thread);
	}

	return Object.entries(buckets)
		.filter(([, group]) => group.length > 0)
		.map(([groupName, group]) => ({ groupName, threads: group }));
}

/**
 * Presentational component for displaying chat thread history in mentor mode.
 * This component only handles display logic and receives all data via props.
 */
export function NavMentorThreads({
	threads,
	isLoading,
	error,
	workspaceSlug,
}: NavMentorThreadsProps) {
	const now = useNow();
	const threadGroups = bucketThreads(threads, now);

	if (isLoading === true) {
		return (
			<SidebarGroup>
				<SidebarGroupLabel>Chat history</SidebarGroupLabel>
				<SidebarGroupContent>
					<div className="p-2 text-sm text-muted-foreground">Loading...</div>
				</SidebarGroupContent>
			</SidebarGroup>
		);
	}

	if (hasText(error)) {
		return (
			<SidebarGroup>
				<SidebarGroupLabel>Chat history</SidebarGroupLabel>
				<SidebarGroupContent>
					<div className="p-2 text-sm text-destructive">{error}</div>
				</SidebarGroupContent>
			</SidebarGroup>
		);
	}

	if (threadGroups.length === 0) {
		return (
			<SidebarGroup>
				<SidebarGroupLabel>Chat history</SidebarGroupLabel>
				<SidebarGroupContent>
					<div className="p-2 text-sm text-muted-foreground">No conversations yet</div>
				</SidebarGroupContent>
			</SidebarGroup>
		);
	}

	return (
		<>
			{threadGroups.map((group) => (
				<ThreadGroup
					key={group.groupName}
					title={group.groupName}
					threads={group.threads}
					workspaceSlug={workspaceSlug}
				/>
			))}
		</>
	);
}

function ThreadGroup({
	title,
	threads,
	workspaceSlug,
}: {
	title: string;
	threads: ChatThreadSummary[];
	workspaceSlug: string;
}) {
	const location = useLocation();

	if (threads.length === 0) {
		return null;
	}

	return (
		<SidebarGroup>
			<SidebarGroupLabel>{title}</SidebarGroupLabel>
			<SidebarGroupContent>
				<SidebarMenu>
					{threads.map((thread) => {
						const threadPath = `/w/${workspaceSlug}/mentor/${thread.id}`;
						const isActive = location.pathname === threadPath;

						return (
							<SidebarMenuItem key={thread.id}>
								<SidebarMenuButton
									isActive={isActive}
									render={
										<Link
											to="/w/$workspaceSlug/mentor/$threadId"
											params={{ workspaceSlug, threadId: thread.id ?? "" }}
										/>
									}
								>
									{thread.title}
								</SidebarMenuButton>
							</SidebarMenuItem>
						);
					})}
				</SidebarMenu>
			</SidebarGroupContent>
		</SidebarGroup>
	);
}
