import { cn } from "cn";
import { Avatar, AvatarFallback } from "@/components/ui/avatar";

import { HephIcon } from "@/components/brand/HephIcon";

interface MentorAvatarProps {
	/** Size of the avatar */
	size?: "default" | "sm" | "lg";
	/** Optional CSS class name */
	className?: string;
	/** Whether the assistant is currently streaming */
	streaming?: boolean;
}

export function MentorAvatar({ className, streaming = false }: MentorAvatarProps) {
	return (
		<Avatar className={cn("-m-4 size-16 after:border-0", className)}>
			<AvatarFallback className="size-16 bg-transparent text-muted-foreground">
				<HephIcon size={64} pad={8} streaming={streaming} />
			</AvatarFallback>
		</Avatar>
	);
}
