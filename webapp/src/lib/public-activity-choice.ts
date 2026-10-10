import { toast } from "sonner";

/** Says what a person's choice does, and when other people see it: a shared cache can keep the page a minute. */
export function announcePublicActivityChoice(visible: boolean) {
	toast.success(
		visible ? "You show on public activity pages" : "You are hidden on public activity pages",
		{ description: "Other people may see the change within a minute." },
	);
}
