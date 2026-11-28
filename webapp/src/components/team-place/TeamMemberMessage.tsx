import type { ReactNode } from "react";

import { ConversationMessage } from "@/components/conversation/ConversationMessage";

import { memberName, type TeamMemberMessageData } from "./team-place-entries";

export interface TeamMemberMessageProps {
	message: TeamMemberMessageData;
	/** Shown under the words, such as where Hephaestus's answer to this question stands. */
	children?: ReactNode;
}

/**
 * A member's message in a team place thread, under that member's own name and avatar. An erased member's message
 * keeps its place as "Former member". It renders through the personal conversation's message, so both kinds of
 * conversation share one layout.
 */
export function TeamMemberMessage({ message, children }: TeamMemberMessageProps) {
	const { author } = message;
	return (
		<ConversationMessage
			message={{
				kind: "USER",
				surface: "APP",
				text: message.deleted ? "" : (message.text ?? ""),
				createdAt: message.createdAt,
				deleted: message.deleted,
				links: [],
			}}
			developer={{
				name: memberName(author),
				avatarUrl: author.former ? undefined : author.avatarUrl,
			}}
		>
			{children}
		</ConversationMessage>
	);
}
