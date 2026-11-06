import { ClipboardCheck, Folder, type LucideIcon } from "lucide-react";

/**
 * Icons as level markers: every practice group wears the same folder, the app's own default group
 * icon, and every practice the same clipboard with a check, the icon the app already gives practices
 * (the admin "Practices" entry, empty practice tables). The icon then says which level a name belongs
 * to; the group's color still tells the groups apart. A module of their own, so a pill that draws one
 * loads nothing else.
 */
export const PracticeGroupIcon: LucideIcon = Folder;
export const PracticeIcon: LucideIcon = ClipboardCheck;
