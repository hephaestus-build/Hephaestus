import { cva, type VariantProps } from "class-variance-authority";

import { cn } from "cn";
import { hasText } from "@/lib/text";

import { getGroupVisual, UNASSIGNED_GROUP_VISUAL } from "./group-visuals";

/** The group's tinted square, one source for every size a surface names a group or a practice in. */
export const groupPillVariants = cva("flex shrink-0 items-center justify-center", {
	variants: {
		size: {
			sm: "size-4 rounded-sm [&_svg]:size-2.5",
			// Beside a name in a row or a line of text.
			row: "size-5 rounded-sm [&_svg]:size-3",
			md: "size-8 rounded-md [&_svg]:size-4",
			lg: "size-9 rounded-md [&_svg]:size-4",
		},
	},
	defaultVariants: { size: "md" },
});

export interface GroupPillProps extends VariantProps<typeof groupPillVariants> {
	slug?: string | null;
	name?: string | null;
	icon?: string | null;
	color?: string | null;
	srLabel?: boolean;
	className?: string;
}

export function GroupPill({
	slug,
	name,
	icon,
	color,
	srLabel = false,
	size,
	className,
}: GroupPillProps) {
	const { Icon, pill } = hasText(slug) ? getGroupVisual(icon, color) : UNASSIGNED_GROUP_VISUAL;
	const label = name ?? "Unassigned";

	return (
		<span
			className={cn(groupPillVariants({ size }), pill, className)}
			aria-hidden={srLabel ? undefined : true}
			title={srLabel ? label : undefined}
		>
			<Icon aria-hidden />
			{srLabel && <span className="sr-only">{label}</span>}
		</span>
	);
}
