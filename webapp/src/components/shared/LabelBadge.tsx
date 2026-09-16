import { cn } from "cn";
import { Badge } from "@/components/ui/badge";

interface LabelBadgeProps extends React.ComponentPropsWithoutRef<typeof Badge> {
	label: string;
	color?: string;
}

const HEX_COLOR = /^[0-9a-f]{6}$/i;

function relativeLuminance(hex: string): number {
	const linearise = (offset: number) => {
		const channel = Number.parseInt(hex.slice(offset, offset + 2), 16) / 255;
		return channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4;
	};
	return linearise(0) * 0.2126 + linearise(2) * 0.7152 + linearise(4) * 0.0722;
}

function labelPaint(color?: string): { label: string; foreground: string } | undefined {
	const hex = color?.replace(/^#/, "");
	if (!hex || !HEX_COLOR.test(hex)) return undefined;

	const luminance = relativeLuminance(hex);
	const foreground = (luminance + 0.05) / 0.05 >= 1.05 / (luminance + 0.05) ? "#000" : "#fff";
	return { label: `#${hex}`, foreground };
}

export function LabelBadge({ label, color, className, style, ...props }: LabelBadgeProps) {
	const paint = labelPaint(color);
	const labelStyle = {
		...style,
		"--label": paint?.label,
		"--label-foreground": paint?.foreground,
	} satisfies React.CSSProperties & Record<"--label" | "--label-foreground", string | undefined>;
	return (
		<Badge
			variant={paint ? "label" : "outline"}
			className={cn("max-w-full", className)}
			style={labelStyle}
			{...props}
		>
			<span className="truncate">{label}</span>
		</Badge>
	);
}
