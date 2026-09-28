import type { UserInfo } from "@/api/types.gen";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import { getInitials } from "@/lib/avatar";

/** A member's face beside their name; the name is always written next to it, so the whole avatar is hidden. */
export function MemberAvatar({
	user,
	size = "default",
}: {
	user: Pick<UserInfo, "avatarUrl" | "name" | "login">;
	size?: "sm" | "default" | "lg";
}) {
	return (
		<Avatar size={size} aria-hidden>
			<AvatarImage src={user.avatarUrl} />
			<AvatarFallback>{getInitials(user.name, user.login)}</AvatarFallback>
		</Avatar>
	);
}
