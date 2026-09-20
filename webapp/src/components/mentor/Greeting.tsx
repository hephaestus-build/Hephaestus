import { motion } from "motion/react";

import { HephIcon } from "@/components/brand/HephIcon";

export function Greeting() {
	return (
		<div
			key="overview"
			className="mx-auto flex size-full max-w-3xl flex-col justify-center px-4 sm:px-8 md:mt-20"
		>
			<div className="mb-4 flex items-center gap-4">
				<motion.div
					className="text-muted-foreground"
					initial={{ opacity: 0, scale: 0.8 }}
					animate={{ opacity: 1, scale: 1 }}
					exit={{ opacity: 0, scale: 0.8 }}
					transition={{ delay: 0.3 }}
				>
					<HephIcon size={80} />
				</motion.div>
				<div className="flex flex-col">
					<motion.div
						initial={{ opacity: 0, y: 10 }}
						animate={{ opacity: 1, y: 0 }}
						exit={{ opacity: 0, y: 10 }}
						transition={{ delay: 0.5 }}
						className="text-2xl font-semibold"
					>
						Hello there!
					</motion.div>
					<motion.div
						initial={{ opacity: 0, y: 10 }}
						animate={{ opacity: 1, y: 0 }}
						exit={{ opacity: 0, y: 10 }}
						transition={{ delay: 0.6 }}
						className="text-2xl text-muted-foreground"
					>
						How can I help you today?
					</motion.div>
				</div>
			</div>
		</div>
	);
}
