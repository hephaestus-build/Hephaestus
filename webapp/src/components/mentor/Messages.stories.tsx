import type { Meta, StoryObj } from "@storybook/react";
import { useEffect, useState } from "react";
import { fn } from "storybook/test";

import type { ChatMessageVote } from "@/api/types.gen";
import type { ChatMessage } from "@/lib/types";

import { Messages } from "./Messages";

/**
 * Messages component displays a list of chat messages with scroll management.
 * Designed to be pure and presentational - all behavior is controlled via props and callbacks.
 */
const meta = {
	component: Messages,
	parameters: {
		layout: "fullscreen",
	},
	tags: ["autodocs"],
	argTypes: {
		messages: {
			description: "Array of chat messages to display",
			control: "object",
		},
		status: {
			description: "Current chat status",
			control: "select",
			options: ["ready", "streaming", "submitted", "error"],
		},
		readonly: {
			description: "Whether the interface is in readonly mode",
			control: "boolean",
		},
		showThinking: {
			description: "Whether to show thinking message for submissions",
			control: "boolean",
		},
		showGreeting: {
			description: "Whether to show greeting when no messages",
			control: "boolean",
		},
		variant: {
			description: "Layout variant for different contexts",
			control: "select",
			options: ["default", "artifact"],
		},
	},
	args: {
		readonly: false,
		showThinking: true,
		showGreeting: true,
		variant: "default",
		onMessageEdit: fn(),
		onCopy: fn(),
		onVote: fn(),
	},
} satisfies Meta<typeof Messages>;

export default meta;
type Story = StoryObj<typeof meta>;

// Sample messages for testing
const openingQuestion: ChatMessage = {
	id: "msg-1",
	role: "user",
	parts: [
		{
			type: "text",
			text: "Hello! Can you help me understand React components?",
		},
	],
};

const sampleMessages: ChatMessage[] = [
	openingQuestion,
	{
		id: "msg-2",
		role: "assistant",
		parts: [
			{
				type: "text",
				text: "I'd be happy to help you understand React components! React components are the building blocks of React applications.\n\n## Functional Components\n\nFunctional components are JavaScript functions that return JSX:\n\n```jsx\nfunction Welcome(props) {\n  return <h1>Hello, {props.name}!</h1>;\n}\n```",
			},
		],
	},
];

// Messages with reasoning for assistant responses
const messagesWithReasoning: ChatMessage[] = [
	{
		id: "msg-1",
		role: "user",
		parts: [
			{
				type: "text",
				text: "How should I structure a React application for best maintainability?",
			},
		],
	},
	{
		id: "msg-2",
		role: "assistant",
		parts: [
			{
				type: "reasoning",
				text: "The user is asking about React application structure. I should consider various architectural patterns, component organization strategies, and best practices. I'll recommend a feature-based structure over a file-type based structure since it scales better for larger applications.",
			},
			{
				type: "text",
				text: "Great question! For maintainable React applications, I recommend a **feature-based folder structure**:\n\n```\nsrc/\n  components/     # Shared/reusable components\n  features/       # Feature-specific modules\n    auth/\n      components/\n      hooks/\n      services/\n    dashboard/\n      components/\n      hooks/\n      services/\n  hooks/          # Shared custom hooks\n  utils/          # Utility functions\n```\n\nThis approach keeps related code together and makes it easier to understand and modify features.",
			},
		],
	},
];

// Multi-turn conversation with various features
const multiTurnMessages: ChatMessage[] = [
	{
		id: "msg-1",
		role: "user",
		parts: [
			{
				type: "text",
				text: "I'm building a todo app with React. Can you help me get started?",
			},
		],
	},
	{
		id: "msg-2",
		role: "assistant",
		parts: [
			{
				type: "reasoning",
				text: "The user wants to build a todo app with React. This is a common learning project. I should provide a comprehensive example that includes state management, component structure, and key React patterns like useState, useEffect, and proper component composition.",
			},
			{
				type: "text",
				text: "I'd be happy to help you build a todo app! Let's start with a simple but well-structured approach.\n\n## Basic Todo Component Structure\n\n```jsx\nimport { useState } from 'react';\n\nfunction TodoApp() {\n  const [todos, setTodos] = useState([]);\n  const [inputValue, setInputValue] = useState('');\n\n  const addTodo = () => {\n    if (inputValue.trim()) {\n      setTodos([...todos, { \n        id: Date.now(), \n        text: inputValue, \n        completed: false \n      }]);\n      setInputValue('');\n    }\n  };\n\n  return (\n    <div>\n      <input \n        value={inputValue}\n        onChange={(e) => setInputValue(e.target.value)}\n        placeholder=\"Add a todo...\"\n      />\n      <button onClick={addTodo}>Add</button>\n      {/* Todo list will go here */}\n    </div>\n  );\n}\n```\n\nWould you like me to show you how to add the todo list display and toggle functionality?",
			},
		],
	},
	{
		id: "msg-3",
		role: "user",
		parts: [
			{
				type: "text",
				text: "Yes! And can you also show how to delete todos and mark them as complete?",
			},
		],
	},
	{
		id: "msg-4",
		role: "assistant",
		parts: [
			{
				type: "text",
				text: "Perfect! Let's extend the todo app with complete, delete, and display functionality:\n\n```jsx\nfunction TodoApp() {\n  const [todos, setTodos] = useState([]);\n  const [inputValue, setInputValue] = useState('');\n\n  const addTodo = () => {\n    if (inputValue.trim()) {\n      setTodos([...todos, { \n        id: Date.now(), \n        text: inputValue, \n        completed: false \n      }]);\n      setInputValue('');\n    }\n  };\n\n  const toggleTodo = (id) => {\n    setTodos(todos.map(todo => \n      todo.id === id \n        ? { ...todo, completed: !todo.completed }\n        : todo\n    ));\n  };\n\n  const deleteTodo = (id) => {\n    setTodos(todos.filter(todo => todo.id !== id));\n  };\n\n  return (\n    <div>\n      <input \n        value={inputValue}\n        onChange={(e) => setInputValue(e.target.value)}\n        placeholder=\"Add a todo...\"\n        onKeyPress={(e) => e.key === 'Enter' && addTodo()}\n      />\n      <button onClick={addTodo}>Add</button>\n      \n      <ul>\n        {todos.map(todo => (\n          <li key={todo.id}>\n            <input\n              type=\"checkbox\"\n              checked={todo.completed}\n              onChange={() => toggleTodo(todo.id)}\n            />\n            <span style={{ \n              textDecoration: todo.completed ? 'line-through' : 'none' \n            }}>\n              {todo.text}\n            </span>\n            <button onClick={() => deleteTodo(todo.id)}>Delete</button>\n          </li>\n        ))}\n      </ul>\n    </div>\n  );\n}\n```\n\n## Key React Patterns Used:\n- **State management** with `useState`\n- **Immutable updates** with spread operators\n- **Event handling** with proper callback functions\n- **Conditional rendering** for completed todos\n- **List rendering** with `map()` and proper `key` props",
			},
		],
	},
];

// User message for thinking state
const userSubmittedMessage: ChatMessage[] = [
	{
		id: "msg-1",
		role: "user",
		parts: [
			{
				type: "text",
				text: "Can you explain the useEffect hook and when to use it?",
			},
		],
	},
];

const sampleVotes: ChatMessageVote[] = [
	{
		messageId: "msg-2",
		isUpvoted: true,
		updatedAt: new Date("2024-01-15T10:05:00Z"),
	},
	{
		messageId: "msg-4",
		isUpvoted: true,
		updatedAt: new Date("2024-01-15T10:05:00Z"),
	},
];

/**
 * Empty state when no messages are present.
 */
export const Empty: Story = {
	args: {
		messages: [],
		status: "ready",
	},
};

/**
 * Basic conversation between user and assistant.
 */
export const Default: Story = {
	args: {
		messages: sampleMessages,
		status: "ready",
	},
};

/**
 * Messages with voting functionality enabled.
 */
export const WithVotes: Story = {
	args: {
		messages: sampleMessages,
		votes: sampleVotes,
		status: "ready",
	},
};

/**
 * Assistant currently streaming a response with live text updates.
 */
export const Streaming: Story = {
	args: {
		messages: [openingQuestion],
		status: "streaming",
	},
	render: (args) => {
		const StreamingDemo = () => {
			const [streamingMessage, setStreamingMessage] = useState("");

			const fullResponse = `Great question! The **useEffect hook** is one of the most important React hooks for managing side effects in functional components.

## What is useEffect?

useEffect lets you perform side effects in functional components. It serves the same purpose as \`componentDidMount\`, \`componentDidUpdate\`, and \`componentWillUnmount\` combined in class components.

## Basic Syntax

\`\`\`jsx
import { useEffect, useState } from 'react';

function MyComponent() {
  const [count, setCount] = useState(0);

  // Effect runs after every render
  useEffect(() => {
    document.title = \`Count: \${count}\`;
  });

  return <div>Count: {count}</div>;
}
\`\`\`

## Common Use Cases

### 1. Data Fetching
\`\`\`jsx
useEffect(() => {
  fetch('/api/users')
    .then(response => response.json())
    .then(data => setUsers(data));
}, []); // Empty dependency array = run once on mount
\`\`\`

### 2. Setting up Subscriptions
\`\`\`jsx
useEffect(() => {
  const subscription = someAPI.subscribe(handleData);
  
  // Cleanup function
  return () => {
    subscription.unsubscribe();
  };
}, []);
\`\`\`

## Dependency Array Rules
- **No dependency array**: Runs after every render
- **Empty array []**: Runs once on mount only  
- **With dependencies [value]**: Runs when dependencies change

The key is to include all values from component scope that are used inside the effect!`;

			useEffect(() => {
				let currentIndex = 0;
				const interval = setInterval(() => {
					if (currentIndex < fullResponse.length) {
						setStreamingMessage(fullResponse.slice(0, currentIndex + 1));
						currentIndex++;
					} else {
						// Reset and start over
						currentIndex = 0;
						setStreamingMessage("");
					}
				}, 20);

				return () => clearInterval(interval);
			}, [fullResponse]);

			const messages: ChatMessage[] = [
				...args.messages,
				{
					id: "streaming-msg",
					role: "assistant",
					parts: [
						{
							type: "text",
							text: streamingMessage,
						},
					],
				},
			];

			return <Messages {...args} messages={messages} status="streaming" />;
		};

		return <StreamingDemo />;
	},
};

/**
 * Thinking state when user submits a message.
 */
export const Thinking: Story = {
	args: {
		messages: userSubmittedMessage,
		status: "submitted",
		showThinking: true,
	},
};

/**
 * Multi-turn conversation with reasoning, voting, and various message types.
 */
export const MultiTurn: Story = {
	args: {
		messages: multiTurnMessages,
		votes: sampleVotes,
		status: "ready",
	},
};

/**
 * Messages showing assistant reasoning process.
 */
export const WithReasoning: Story = {
	args: {
		messages: messagesWithReasoning,
		status: "ready",
	},
};

/**
 * Readonly mode - all interactions disabled.
 */
export const Readonly: Story = {
	args: {
		messages: multiTurnMessages,
		status: "ready",
		readonly: true,
	},
};

/**
 * Artifact variant - specialized layout for artifact context.
 */
export const ArtifactVariant: Story = {
	args: {
		messages: multiTurnMessages,
		status: "ready",
		variant: "artifact",
		showGreeting: false,
	},
	parameters: {
		layout: "centered",
	},
	decorators: [
		(Story) => (
			<div className="h-[600px] w-[500px] overflow-hidden rounded-lg border border-border bg-background">
				<Story />
			</div>
		),
	],
};

/**
 * Artifact variant with empty state.
 */
export const ArtifactEmpty: Story = {
	args: {
		messages: [],
		status: "ready",
		variant: "artifact",
		showGreeting: false,
	},
	parameters: {
		layout: "centered",
	},
	decorators: [
		(Story) => (
			<div className="h-[400px] w-[400px] overflow-hidden rounded-lg border border-border bg-background">
				<Story />
			</div>
		),
	],
};

/**
 * Artifact variant streaming state.
 */
export const ArtifactStreaming: Story = {
	args: {
		messages: multiTurnMessages.slice(0, 2), // Use first 2 messages for a cleaner demo
		status: "streaming",
		variant: "artifact",
		showGreeting: false,
	},
	parameters: {
		layout: "centered",
	},
	decorators: [
		(Story) => (
			<div className="h-[600px] w-[500px] overflow-hidden rounded-lg border border-border bg-background">
				<Story />
			</div>
		),
	],
};
