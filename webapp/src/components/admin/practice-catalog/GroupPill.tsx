import { cva, type VariantProps } from "class-variance-authority";
import { CircleDashed } from "lucide-react";

import { cn } from "cn";
import { getGroupVisual } from "@/components/admin/practice-catalog/group-visuals";

const groupPillVariants = cva("flex shrink-0 items-center justify-center", {
	variants: {
		size: {
			sm: "size-4 rounded-sm [&_svg]:size-2.5",
			md: "size-8 rounded-md [&_svg]:size-4",
			lg: "size-9 rounded-md [&_svg]:size-4",
		},
	},
	defaultVariants: { size: "md" },
});

export interface GroupPillProps extends VariantProps<typeof groupPillVariants> {
	slug?: string;
	name?: string;
	icon?: string;
	color?: string;
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
	const visual = slug
		? getGroupVisual(icon, color)
		: { Icon: CircleDashed, pill: "bg-muted text-muted-foreground" };
	const { Icon, pill } = visual;
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
