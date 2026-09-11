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
		<Avatar className={cn("size-16 -m-4 after:border-0", className)}>
			<AvatarFallback className="bg-transparent text-muted-foreground size-16">
				<HephIcon size={64} pad={8} streaming={streaming} />
			</AvatarFallback>
		</Avatar>
	);
}
