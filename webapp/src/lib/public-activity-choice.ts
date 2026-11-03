import { toast } from "@/lib/toast";

/** Says what a person's choice does, and when other people see it: on their next load. */
export function announcePublicActivityChoice(visible: boolean) {
	toast.success(
		visible ? "You show on public activity pages" : "You are hidden on public activity pages",
		{
			description: "Other people see the change when they load the page again.",
		},
	);
}
