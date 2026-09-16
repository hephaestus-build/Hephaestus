import {
	Activity,
	AlertTriangle,
	Archive,
	Award,
	Bell,
	Bookmark,
	BookOpen,
	BookText,
	Box,
	Boxes,
	Braces,
	Bug,
	Calendar,
	CheckCheck,
	CircleAlert,
	CircleCheck,
	Clipboard,
	ClipboardCheck,
	ClipboardList,
	Clock,
	Cloud,
	Code,
	Code2,
	Cog,
	Compass,
	Cpu,
	Database,
	Eye,
	FileCheck,
	FileCode,
	FileText,
	Filter,
	Flag,
	FlaskConical,
	Folder,
	FolderGit2,
	GitBranch,
	GitCommitHorizontal,
	GitCommitVertical,
	GitMerge,
	GitPullRequest,
	Globe,
	Hammer,
	Heart,
	Inbox,
	Key,
	KeyRound,
	Layers,
	LayoutDashboard,
	Lightbulb,
	ListChecks,
	ListTodo,
	Lock,
	type LucideIcon,
	Mail,
	MapPin,
	Megaphone,
	MessageCircle,
	MessageSquare,
	MessageSquareReply,
	Microscope,
	Milestone,
	Monitor,
	Network,
	Package,
	Pencil,
	PenTool,
	PieChart,
	Pin,
	Puzzle,
	Radar,
	Rocket,
	Ruler,
	Scale,
	Search,
	Server,
	Settings,
	Shield,
	ShieldAlert,
	ShieldCheck,
	ShieldX,
	Signpost,
	Siren,
	Sparkles,
	Star,
	Tag,
	Target,
	Terminal,
	TestTube,
	TestTubeDiagonal,
	ThumbsUp,
	Timer,
	TrendingUp,
	Trophy,
	Users,
	Wand2,
	Workflow,
	Wrench,
	Zap,
} from "lucide-react";

import { humanizeToken } from "@/lib/humanize";
import { hasText } from "@/lib/text";

export interface GroupVisual {
	Icon: LucideIcon;
	pill: string;
}

const SLATE_PILL = "bg-slate-100 text-slate-700 dark:bg-slate-800 dark:text-slate-200";

// Tailwind requires complete class names in source to include them in the generated CSS.
export const PILL: Record<string, string> = {
	red: "bg-red-100 text-red-700 dark:bg-red-950 dark:text-red-200",
	orange: "bg-orange-100 text-orange-800 dark:bg-orange-950 dark:text-orange-200",
	amber: "bg-amber-100 text-amber-800 dark:bg-amber-950 dark:text-amber-200",
	yellow: "bg-yellow-100 text-yellow-800 dark:bg-yellow-950 dark:text-yellow-200",
	lime: "bg-lime-100 text-lime-800 dark:bg-lime-950 dark:text-lime-200",
	green: "bg-green-100 text-green-700 dark:bg-green-950 dark:text-green-200",
	emerald: "bg-emerald-100 text-emerald-700 dark:bg-emerald-950 dark:text-emerald-200",
	teal: "bg-teal-100 text-teal-700 dark:bg-teal-950 dark:text-teal-200",
	cyan: "bg-cyan-100 text-cyan-800 dark:bg-cyan-950 dark:text-cyan-200",
	sky: "bg-sky-100 text-sky-700 dark:bg-sky-950 dark:text-sky-200",
	blue: "bg-blue-100 text-blue-700 dark:bg-blue-950 dark:text-blue-200",
	indigo: "bg-indigo-100 text-indigo-700 dark:bg-indigo-950 dark:text-indigo-200",
	violet: "bg-violet-100 text-violet-700 dark:bg-violet-950 dark:text-violet-200",
	purple: "bg-purple-100 text-purple-700 dark:bg-purple-950 dark:text-purple-200",
	fuchsia: "bg-fuchsia-100 text-fuchsia-700 dark:bg-fuchsia-950 dark:text-fuchsia-200",
	pink: "bg-pink-100 text-pink-700 dark:bg-pink-950 dark:text-pink-200",
	rose: "bg-rose-100 text-rose-700 dark:bg-rose-950 dark:text-rose-200",
	slate: SLATE_PILL,
	gray: "bg-gray-100 text-gray-700 dark:bg-gray-800 dark:text-gray-200",
	zinc: "bg-zinc-100 text-zinc-700 dark:bg-zinc-800 dark:text-zinc-200",
	stone: "bg-stone-100 text-stone-700 dark:bg-stone-800 dark:text-stone-200",
};

export const COLOR_KEYS = Object.keys(PILL);

export const ICON_COMPONENTS: Record<string, LucideIcon> = {
	ShieldAlert,
	ShieldCheck,
	ShieldX,
	Shield,
	Lock,
	Key,
	KeyRound,
	Siren,
	AlertTriangle,
	CircleAlert,
	Bug,
	TestTube,
	TestTubeDiagonal,
	FlaskConical,
	Microscope,
	CheckCheck,
	CircleCheck,
	FileCheck,
	ClipboardCheck,
	ClipboardList,
	Clipboard,
	ListChecks,
	ListTodo,
	Flag,
	Milestone,
	Target,
	Signpost,
	Compass,
	Radar,
	Search,
	GitPullRequest,
	GitBranch,
	GitMerge,
	GitCommitHorizontal,
	GitCommitVertical,
	FolderGit2,
	MessageSquareReply,
	MessageSquare,
	MessageCircle,
	Megaphone,
	Mail,
	Inbox,
	Bell,
	Eye,
	Users,
	ThumbsUp,
	Heart,
	Award,
	Trophy,
	Star,
	Sparkles,
	Wand2,
	Lightbulb,
	Wrench,
	Hammer,
	Cog,
	Settings,
	Workflow,
	Puzzle,
	Layers,
	Boxes,
	Box,
	Package,
	Archive,
	Code,
	Code2,
	Braces,
	Terminal,
	FileCode,
	PenTool,
	Pencil,
	FileText,
	BookText,
	BookOpen,
	Bookmark,
	Ruler,
	Scale,
	Filter,
	Tag,
	Pin,
	MapPin,
	Calendar,
	Clock,
	Timer,
	Activity,
	TrendingUp,
	PieChart,
	LayoutDashboard,
	Database,
	Server,
	Cpu,
	Cloud,
	Network,
	Globe,
	Monitor,
	Rocket,
	Zap,
	Folder,
};

export const ICON_NAMES = Object.keys(ICON_COMPONENTS);

export function iconLabel(name: string): string {
	return humanizeToken(name);
}

export function iconSearchText(name: string): string {
	return iconLabel(name).toLowerCase();
}

function resolveIcon(name?: string | null): LucideIcon | undefined {
	return hasText(name) ? ICON_COMPONENTS[name] : undefined;
}

export function iconComponent(name: string): LucideIcon {
	return ICON_COMPONENTS[name] ?? Folder;
}

function pillFor(color?: string | null): string | undefined {
	return hasText(color) ? PILL[color] : undefined;
}

export function pillClasses(color?: string | null): string {
	return pillFor(color) ?? SLATE_PILL;
}

export const DEFAULT_GROUP_ICON = "Folder";
export const DEFAULT_GROUP_COLOR = "slate";

export function getGroupVisual(icon?: string | null, color?: string | null): GroupVisual {
	const Icon = resolveIcon(icon) ?? Folder;
	const pill = pillFor(color) ?? SLATE_PILL;
	return { Icon, pill };
}
