import { Spinner } from "@/components/ui/spinner";
import type { Attachment } from "@/lib/types";

export const PreviewAttachment = ({
	attachment,
	isUploading = false,
}: {
	attachment: Attachment;
	isUploading?: boolean;
}) => {
	const { name, url, contentType } = attachment;

	return (
		<div className="flex flex-col gap-2">
			<div className="relative flex aspect-video h-16 w-20 flex-col items-center justify-center rounded-md bg-muted">
				{contentType ? (
					contentType.startsWith("image") ? (
						<img
							key={url}
							src={url}
							alt={name || "An image attachment"}
							className="size-full rounded-md object-cover"
						/>
					) : (
						<div />
					)
				) : (
					<div />
				)}

				{isUploading && (
					<div className="absolute text-muted-foreground">
						<Spinner />
					</div>
				)}
			</div>
			<div className="max-w-16 truncate text-xs text-muted-foreground">{name}</div>
		</div>
	);
};
