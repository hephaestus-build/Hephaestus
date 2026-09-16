import { createFileRoute } from "@tanstack/react-router";
import { toast } from "sonner";

import { Chat } from "@/components/mentor/Chat";
import { defaultPartRenderers } from "@/components/mentor/renderers";
import { Skeleton } from "@/components/ui/skeleton";
import { useMentorChat } from "@/hooks/use-mentor-chat";

export const Route = createFileRoute("/_authenticated/w/$workspaceSlug/mentor/$threadId")({
	component: ThreadContainer,
});

function ThreadContainer() {
	const { threadId } = Route.useParams();

	// No `onError`: `Chat` renders `status === "error"` inside the transcript, where the reader
	// already is, rather than as a toast away from the conversation that failed.
	const mentorChat = useMentorChat({ threadId });

	const handleMessageSubmit = ({ text }: { text: string }) => {
		if (!text.trim()) return;
		mentorChat.sendMessage(text);
	};

	const handleVote = (messageId: string, isUpvote: boolean) => {
		mentorChat.voteMessage(messageId, isUpvote);
	};

	const handleCopy = (content: string) => {
		navigator.clipboard.writeText(content).catch(() => {
			toast.error("Couldn't copy that to the clipboard.");
		});
	};

	const handleMessageEdit = (messageId: string, content: string) => {
		const idx = mentorChat.messages.findIndex((m) => m.id === messageId);
		if (idx === -1) return;
		mentorChat.setMessages(mentorChat.messages.slice(0, idx));
		mentorChat.sendMessage(content);
	};

	if (mentorChat.isThreadLoading) {
		return (
			<div className="flex min-h-0 flex-1 flex-col">
				<div className="relative flex min-h-0 flex-1 flex-col">
					<div className="flex-1 overflow-y-auto p-4 sm:p-6">
						<div className="relative mx-auto flex w-full min-w-0 flex-1 flex-col gap-8 pt-4 pb-16 md:max-w-3xl">
							<div className="flex items-start justify-end gap-3">
								<div className="max-w-[75%] space-y-2 text-right">
									<Skeleton className="ml-auto h-4 w-56" />
									<Skeleton className="ml-auto h-4 w-28" />
								</div>
							</div>

							<div className="flex items-start gap-3">
								<Skeleton className="h-8 w-8 rounded-full" />
								<div className="max-w-[75%] space-y-2">
									<Skeleton className="h-4 w-40" />
									<Skeleton className="h-4 w-64" />
									<Skeleton className="h-4 w-32" />
								</div>
							</div>

							<div className="flex items-start justify-end gap-3">
								<div className="max-w-[75%] space-y-2 text-right">
									<Skeleton className="ml-auto h-4 w-75" />
									<Skeleton className="ml-auto h-4 w-34" />
									<Skeleton className="ml-auto h-4 w-53" />
								</div>
							</div>

							<div className="flex items-start gap-3">
								<Skeleton className="h-8 w-8 rounded-full" />
								<div className="max-w-[75%] space-y-2">
									<Skeleton className="h-4 w-72" />
									<Skeleton className="h-4 w-52" />
									<Skeleton className="h-4 w-24" />
								</div>
							</div>
						</div>
					</div>

					<div className="relative z-10 -mt-20 flex w-full flex-col items-center gap-2 bg-gradient-to-t from-muted from-60% to-transparent px-4 pt-8 pb-2 dark:from-background/30">
						<div className="w-full max-w-3xl space-y-2">
							<Skeleton className="h-20 flex-1" />
						</div>
						<Skeleton className="h-3 w-64" />
					</div>
				</div>
			</div>
		);
	}

	if (mentorChat.threadError) {
		return (
			<div className="flex h-full items-center justify-center p-6">
				<div className="text-center">
					<p className="mb-4 text-destructive">
						Failed to load conversation. Thread may not exist or you don't have access to it.
					</p>
					<p className="text-sm text-muted-foreground">
						Try refreshing the page or go back to the main chat.
					</p>
				</div>
			</div>
		);
	}

	if (!mentorChat.threadDetail) {
		return (
			<div className="flex h-full items-center justify-center p-6">
				<div className="text-center">
					<p className="text-muted-foreground">Conversation not found.</p>
				</div>
			</div>
		);
	}

	return (
		<div className="flex min-h-0 flex-1 flex-col">
			<Chat
				messages={mentorChat.messages}
				votes={mentorChat.votes}
				status={mentorChat.status}
				readonly={false}
				attachments={[]}
				onMessageSubmit={handleMessageSubmit}
				onMessageEdit={handleMessageEdit}
				onStop={() => void mentorChat.stop()}
				onReload={() => {
					mentorChat.clearError();
					void mentorChat.regenerate();
				}}
				onFileUpload={() => Promise.resolve([])}
				onAttachmentsChange={() => {}}
				onCopy={handleCopy}
				onVote={handleVote}
				inputPlaceholder="Continue the conversation..."
				disableAttachments
				className="h-full"
				partRenderers={defaultPartRenderers}
			/>
		</div>
	);
}
