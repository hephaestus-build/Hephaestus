import { useQuery } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";
import { MessageSquareWarning } from "lucide-react";
import { useState } from "react";

import { getMemberOnboardingOptions } from "@/api/@tanstack/react-query.gen";
import { EmptyState } from "@/components/common/EmptyState";
import { Chat } from "@/components/mentor/Chat";
import { ChatSkeleton } from "@/components/mentor/ChatSkeleton";
import { Button } from "@/components/ui/button";
import { mentorThreadOptions, useMentorChat } from "@/hooks/use-mentor-chat";
import { parseThreadMessages } from "@/lib/chat-validation";
import { copyToClipboard } from "@/lib/clipboard";
import { mentorPreferenceReason } from "@/lib/mentor-preference";
import type { ChatMessage } from "@/lib/types";
import { useAuth } from "@/runtime/auth/AuthContext";

export const Route = createFileRoute("/_authenticated/w/$workspaceSlug/mentor/$threadId")({
	remountDeps: ({ params }) => params,
	component: ThreadContainer,
});

/** The conversation is read before the chat mounts, so it opens on its saved messages. */
function ThreadContainer() {
	const { threadId, workspaceSlug } = Route.useParams();
	const thread = useQuery({
		...mentorThreadOptions(workspaceSlug, threadId),
		select: (detail) => parseThreadMessages(detail.messages) ?? null,
	});
	// The chat owns the conversation once it opens, so a later refetch, failed or not, leaves it be.
	const [opened, setOpened] = useState<ChatMessage[]>();
	if (opened === undefined && thread.data) {
		setOpened(thread.data);
	}

	if (opened) {
		return <ThreadChat threadId={threadId} workspaceSlug={workspaceSlug} messages={opened} />;
	}
	if (thread.isPending) {
		return (
			<div className="flex min-h-0 flex-1 flex-col">
				<ChatSkeleton />
			</div>
		);
	}
	return (
		<div className="flex h-full items-center justify-center p-6">
			<EmptyState
				icon={<MessageSquareWarning />}
				title="This conversation could not be opened"
				headingLevel={1}
				description="Loading it failed, or it no longer exists for you. Try again, or start a new chat."
				action={
					<Button
						variant="outline"
						onClick={() => {
							void thread.refetch();
						}}
					>
						Try again
					</Button>
				}
			/>
		</div>
	);
}

function ThreadChat({
	threadId,
	workspaceSlug,
	messages,
}: {
	threadId: string;
	workspaceSlug: string;
	messages: ChatMessage[];
}) {
	const viewing = useAuth().userView !== undefined;
	// The saved AI choice belongs to the signed-in account, so a user view never asks for it.
	const preference = useQuery({
		...getMemberOnboardingOptions({ path: { workspaceSlug } }),
		enabled: !viewing,
	});
	const readonly = viewing || !preference.data || Boolean(mentorPreferenceReason(preference.data));

	const mentorChat = useMentorChat({ threadId, initialMessages: messages });

	return (
		<div className="flex min-h-0 flex-1 flex-col">
			<h1 className="sr-only">Conversation with Heph</h1>
			<Chat
				messages={mentorChat.messages}
				votes={mentorChat.votes}
				turn={mentorChat.turn}
				readonly={readonly}
				onMessageSubmit={mentorChat.sendMessage}
				onMessageEdit={readonly ? undefined : mentorChat.editMessage}
				onStop={() => {
					void mentorChat.stop();
				}}
				onReload={readonly ? undefined : mentorChat.retry}
				onCopy={copyToClipboard}
				onVote={viewing ? undefined : mentorChat.voteMessage}
				inputPlaceholder="Continue the conversation…"
			/>
		</div>
	);
}
