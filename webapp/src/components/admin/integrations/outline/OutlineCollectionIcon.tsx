import { cn } from "cn";
import {
	Beaker,
	Bike,
	BookMarked,
	Boxes,
	Bug,
	Camera,
	CheckCircle2,
	Clock,
	Cloud,
	Code,
	Coins,
	Database,
	Eye,
	Flame,
	Globe,
	GraduationCap,
	Hash,
	IceCream,
	Image,
	Info,
	Languages,
	Leaf,
	Library,
	Lightbulb,
	LineChart,
	Lock,
	type LucideIcon,
	Mail,
	MessageSquare,
	Moon,
	Notebook,
	Palette,
	Pencil,
	Plane,
	Server,
	Shapes,
	Sigma,
	Smile,
	Sun,
	Target,
	Terminal,
	ThumbsUp,
	Trophy,
	Truck,
	Users,
	Wrench,
	Zap,
} from "lucide-react";

import { hasText } from "@/lib/text";

/**
 * Outline stores a collection icon as ONE string that is either a named icon from its own registry,
 * a raw emoji character, or the UUID of a custom uploaded emoji. There is no type discriminator on
 * the wire and no endpoint that serves the named icons, so a consumer must classify the string the
 * same way Outline's client does: registry match wins, then UUID, then emoji as the fallback.
 *
 * Named icons resolve to the closest Lucide equivalent, tinted with the collection's Outline colour.
 * Anything unresolvable degrades to the colour dot — the raw identifier is never rendered as text.
 */
const NAMED_ICONS: Record<string, LucideIcon> = {
	academicCap: GraduationCap,
	beaker: Beaker,
	bicycle: Bike,
	bookmark: BookMarked,
	buildingBlocks: Boxes,
	camera: Camera,
	clock: Clock,
	cloud: Cloud,
	code: Code,
	coins: Coins,
	collection: Library,
	database: Database,
	done: CheckCircle2,
	email: Mail,
	eye: Eye,
	feedback: MessageSquare,
	flame: Flame,
	globe: Globe,
	graph: LineChart,
	hashtag: Hash,
	icecream: IceCream,
	image: Image,
	info: Info,
	internet: Globe,
	leaf: Leaf,
	letter: Languages,
	library: Library,
	lightbulb: Lightbulb,
	lightning: Zap,
	math: Sigma,
	moon: Moon,
	notepad: Notebook,
	padlock: Lock,
	palette: Palette,
	pencil: Pencil,
	plane: Plane,
	promote: Target,
	server: Server,
	shapes: Shapes,
	smiley: Smile,
	sun: Sun,
	target: Target,
	team: Users,
	terminal: Terminal,
	thumbsup: ThumbsUp,
	tools: Wrench,
	trophy: Trophy,
	truck: Truck,
	vehicle: Truck,
	warning: Bug,
};

/**
 * Outline's own classifier treats "not a known name, not a UUID" as an emoji, which would print an
 * unmapped name as literal text. We require the string to actually contain a pictograph instead, so
 * an icon name we do not map degrades to the colour dot rather than leaking its identifier.
 */
const EMOJI_PATTERN = /\p{Extended_Pictographic}/u;

export interface OutlineCollectionIconProps {
	icon?: string | null;
	color?: string | null;
	className?: string;
}

export function OutlineCollectionIcon({ icon, color, className }: OutlineCollectionIconProps) {
	const tint = color ?? "var(--muted-foreground)";

	if (hasText(icon)) {
		const NamedIcon = NAMED_ICONS[icon];
		if (NamedIcon) {
			return (
				<NamedIcon
					aria-hidden
					className={cn("text-(--tint)", className ?? "size-4 shrink-0")}
					style={{ "--tint": tint }}
				/>
			);
		}
		if (EMOJI_PATTERN.test(icon)) {
			return (
				<span aria-hidden className="shrink-0 text-base leading-none">
					{icon}
				</span>
			);
		}
	}

	return (
		<span
			aria-hidden
			className="size-2.5 shrink-0 rounded-full bg-(--tint)"
			style={{ "--tint": tint }}
		/>
	);
}
