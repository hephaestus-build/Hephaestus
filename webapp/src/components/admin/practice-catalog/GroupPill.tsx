import { cva, type VariantProps } from "class-variance-authority";
import { CircleDashed } from "lucide-react";

import { cn } from "cn";
import { getGroupVisual } from "@/components/practice-vocabulary/group-visuals";
import { hasText } from "@/lib/text";

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
	const visual = hasText(slug)
		? getGroupVisual(icon, color)
		: // oxlint-disable-next-line shadcn/no-unknown-classes -- The plugin mistakes this destructured record for a cn class map.
			{ Icon: CircleDashed, pill: "bg-muted text-muted-foreground" };
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
