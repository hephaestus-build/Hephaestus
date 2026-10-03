import { useQuery } from "@tanstack/react-query";
import { useRouter } from "@tanstack/react-router";
import { copyToClipboard } from "@/lib/clipboard";
import { hasText } from "@/lib/text";
import { useAuth } from "@/runtime/auth/AuthContext";

import { getMemberOnboardingOptions } from "@/api/@tanstack/react-query.gen";
import { Chat } from "@/components/mentor/Chat";
import { Copilot } from "@/components/mentor/Copilot";
import { useActiveWorkspaceSlug } from "@/hooks/use-active-workspace";
import { useMentorChat } from "@/hooks/use-mentor-chat";
import { mentorPreferenceReason } from "@/lib/mentor-preference";

/**
 * Keyed on the workspace so a conversation never carries over into the next workspace, and gated on
 * the member's own AI choice there: "No AI" hides the composer rather than letting a send fail.
 */
export default function GlobalCopilot() {
	const { workspaceSlug } = useActiveWorkspaceSlug();
	const { isAuthenticated, isLoading } = useAuth();
	const preference = useQuery({
		...getMemberOnboardingOptions({ path: { workspaceSlug: workspaceSlug ?? "" } }),
		enabled: Boolean(workspaceSlug),
	});
	if (
		!hasText(workspaceSlug) ||
		isLoading ||
		!isAuthenticated ||
		!preference.isSuccess ||
		mentorPreferenceReason(preference.data)
	) {
		return null;
	}
	return <WorkspaceCopilot key={workspaceSlug} workspaceSlug={workspaceSlug} />;
}

function WorkspaceCopilot({ workspaceSlug }: { workspaceSlug: string }) {
	const mentorChat = useMentorChat({});

	const router = useRouter();

	return (
		<Copilot
			hasMessages={mentorChat.messages.length > 0}
			onNewChat={() => {
				void mentorChat.startNewChat();
			}}
			onOpenFullChat={() => {
				const threadId = mentorChat.currentThreadId ?? mentorChat.id;
				if (threadId && workspaceSlug) {
					void router.navigate({
						to: "/w/$workspaceSlug/mentor/$threadId",
						params: { threadId, workspaceSlug },
					});
				}
			}}
		>
			<Chat
				messages={mentorChat.messages}
				votes={mentorChat.votes}
				turn={mentorChat.turn}
				onMessageSubmit={mentorChat.sendMessage}
				onMessageEdit={mentorChat.editMessage}
				onStop={() => {
					void mentorChat.stop();
				}}
				onReload={mentorChat.retry}
				onCopy={copyToClipboard}
				onVote={mentorChat.voteMessage}
			/>
		</Copilot>
	);
}
