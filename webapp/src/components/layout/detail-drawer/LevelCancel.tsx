import { Button } from "@/components/ui/button";
import { DrawerClose } from "@/components/ui/drawer";

/**
 * Leaving an editor level without saving. A `DrawerClose` so it takes the same path as the header's
 * control rather than a second one that can drift — and so a guarded level, which ignores Escape and
 * an outside press, still lets it through.
 */
export function LevelCancel() {
	return <DrawerClose render={<Button type="button" variant="outline" />}>Cancel</DrawerClose>;
}
