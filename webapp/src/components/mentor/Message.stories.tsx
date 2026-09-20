import type { Meta, StoryObj } from "@storybook/react";
import { fn } from "storybook/test";

import type { ChatMessage } from "@/lib/types";
import { STORY_NOW } from "@/stories/story-clock";

import { PreviewMessage, ThinkingMessage } from "./Message";

/**
 * Message component displays chat messages from both users and assistants with rich content support.
 * Handles text, reasoning, file attachments, voting, and editing functionality.
 * Features smooth animations, edit mode for user messages, and comprehensive action handling.
 */
const meta = {
	component: PreviewMessage,
	tags: ["autodocs"],
	argTypes: {
		message: {
			description: "The chat message to display",
			control: "object",
		},
		vote: {
			description: "Current vote state for this message",
			control: "object",
		},
		isLoading: {
			description: "Whether the message is currently being processed",
			control: "boolean",
		},
		readonly: {
			description: "Whether the message is in readonly mode (disables actions)",
			control: "boolean",
		},
		initialEditMode: {
			description: "Whether to show the edit mode initially",
			control: "boolean",
		},
		onMessageEdit: {
			description: "Handler for message editing submission",
			control: false,
		},
		onCopy: {
			description: "Handler for copying message content",
			control: false,
		},
		onVote: {
			description: "Handler for voting on messages",
			control: false,
		},
		className: {
			description: "Optional CSS class name",
			control: "text",
		},
	},
	args: {
		isLoading: false,
		readonly: false,
		initialEditMode: false,
		onMessageEdit: fn(),
		onCopy: fn(),
		onVote: fn(),
	},
	decorators: [
		(Story) => (
			<div className="w-full max-w-4xl bg-background p-6">
				<Story />
			</div>
		),
	],
} satisfies Meta<typeof PreviewMessage>;

export default meta;
type Story = StoryObj<typeof meta>;

// Sample messages for different scenarios
const createUserMessage = (text: string, id = "msg-1"): ChatMessage => ({
	id,
	role: "user",
	parts: [{ type: "text", text }],
});

const createAssistantMessage = (text: string, id = "msg-2"): ChatMessage => ({
	id,
	role: "assistant",
	parts: [{ type: "text", text }],
});

const createMessageWithReasoning = (
	text: string,
	reasoning: string,
	id = "msg-3",
): ChatMessage => ({
	id,
	role: "assistant",
	parts: [
		{ type: "reasoning", text: reasoning },
		{ type: "text", text },
	],
});

const createMessageWithAttachments = (text: string, id = "msg-4"): ChatMessage => ({
	id,
	role: "user",
	parts: [
		{
			type: "file",
			url: "https://picsum.photos/seed/image1/200/150",
			filename: "screenshot.png",
			mediaType: "image/png",
		},
		{
			type: "file",
			url: "https://example.com/document.pdf",
			filename: "project-proposal.pdf",
			mediaType: "application/pdf",
		},
		{ type: "text", text },
	],
});

/**
 * Simple user message with plain text content.
 */
export const UserMessage: Story = {
	args: {
		message: createUserMessage("Can you help me understand React component patterns?"),
	},
};

/**
 * Assistant message with helpful response.
 */
export const AssistantMessage: Story = {
	args: {
		message: createAssistantMessage(
			`I'd be happy to help you understand React component patterns! Here are the key patterns you should know:

## Functional Components with Hooks
Modern React development primarily uses functional components with hooks for state management and side effects.

## Component Composition
Breaking down complex UIs into smaller, reusable components that can be composed together.

## Props Pattern
Passing data and callbacks through props to maintain unidirectional data flow.

Would you like me to dive deeper into any of these patterns?`,
		),
	},
};

/**
 * Assistant message with reasoning process visible.
 */
export const MessageWithReasoning: Story = {
	args: {
		message: createMessageWithReasoning(
			`Based on your question about React patterns, I'll focus on the most practical and commonly used patterns that will help you build maintainable applications.

Let me break this down into digestible concepts:

**1. Functional Components with Hooks**
- useState for local state management
- useEffect for side effects and lifecycle events
- Custom hooks for reusable logic

**2. Component Composition**
- Container vs Presentational components
- Higher-Order Components (HOCs)
- Render props pattern

**3. Props and State Management**
- Props drilling and when to avoid it
- Context API for global state
- When to reach for external state management

Would you like specific examples of any of these patterns?`,
			"The user is asking about React component patterns. I should provide a comprehensive overview that covers the most important and practical patterns they'll encounter. I'll start with functional components since that's the modern standard, then cover composition patterns, and finally state management approaches. I should offer to provide specific examples to make this more actionable.",
		),
	},
};

/**
 * User message with file attachments.
 */
export const MessageWithAttachments: Story = {
	args: {
		message: createMessageWithAttachments(
			"I've attached a screenshot of the issue I'm seeing and the project requirements. Can you help me debug this?",
		),
	},
};

/**
 * Message with upvote applied.
 */
export const UpvotedMessage: Story = {
	args: {
		message: createAssistantMessage(
			"Great question! The key difference between `useState` and `useReducer` is complexity management. Use `useState` for simple state values and `useReducer` when you have complex state logic with multiple sub-values or when the next state depends on the previous one.",
		),
		vote: {
			messageId: "msg-2",
			isUpvoted: true,
			updatedAt: new Date(STORY_NOW),
		},
	},
};

/**
 * Message with downvote applied.
 */
export const DownvotedMessage: Story = {
	args: {
		message: createAssistantMessage(
			"You should always use classes for React components because they're more powerful than functional components.",
		),
		vote: {
			messageId: "msg-2",
			isUpvoted: false,
			updatedAt: new Date(STORY_NOW),
		},
	},
};

/**
 * Message currently being processed/loading.
 */
export const LoadingMessage: Story = {
	args: {
		message: createAssistantMessage("Let me analyze your code..."),
		isLoading: true,
	},
};

/**
 * Readonly message with actions disabled.
 */
export const ReadonlyMessage: Story = {
	args: {
		message: createAssistantMessage(
			"This is a historical message from a past conversation. Actions are disabled in readonly mode.",
		),
		readonly: true,
	},
};

/**
 * User message in edit mode for content modification.
 */
export const EditModeMessage: Story = {
	args: {
		message: createUserMessage("How do I optimize React performance in large applications?"),
		initialEditMode: true,
	},
};

/**
 * Long message content demonstrating text wrapping and layout.
 */
export const LongMessage: Story = {
	args: {
		message: createAssistantMessage(`# Complete Guide to React Performance Optimization

React performance optimization is crucial for building scalable applications. Here's a comprehensive guide covering all the essential techniques:

## 1. React.memo for Component Memoization

\`React.memo\` is a higher-order component that memoizes the result of a component. It only re-renders if its props have changed.

\`\`\`jsx
const MyComponent = React.memo(({ name, count }) => {
  return <div>{name}: {count}</div>;
});
\`\`\`

## 2. useMemo for Expensive Calculations

Use \`useMemo\` to memoize expensive calculations that don't need to run on every render:

\`\`\`jsx
const ExpensiveComponent = ({ items }) => {
  const expensiveValue = useMemo(() => {
    return items.reduce((sum, item) => sum + item.value, 0);
  }, [items]);

  return <div>Total: {expensiveValue}</div>;
};
\`\`\`

## 3. useCallback for Function Memoization

\`useCallback\` memoizes functions to prevent unnecessary re-renders of child components:

\`\`\`jsx
const Parent = ({ items }) => {
  const handleClick = useCallback((id) => {
    // Handle click logic
  }, []);

  return items.map(item => 
    <Child key={item.id} item={item} onClick={handleClick} />
  );
};
\`\`\`

## 4. Code Splitting with React.lazy

Split your bundle to load code only when needed:

\`\`\`jsx
const LazyComponent = React.lazy(() => import('./LazyComponent'));

function App() {
  return (
    <Suspense fallback={<div>Loading...</div>}>
      <LazyComponent />
    </Suspense>
  );
}
\`\`\`

## 5. Virtual Scrolling for Large Lists

For lists with thousands of items, implement virtual scrolling to only render visible items.

## 6. Profiling and Debugging

Use React DevTools Profiler to identify performance bottlenecks and optimize accordingly.

These techniques will significantly improve your React application's performance when applied correctly.`),
	},
};

/**
 * The ThinkingMessage component for loading states.
 */
export const ThinkingLoadingState: StoryObj<typeof ThinkingMessage> = {
	render: () => <ThinkingMessage />,
	parameters: {
		docs: {
			description: {
				story: "Loading state shown while the assistant is thinking or processing a request.",
			},
		},
	},
};
