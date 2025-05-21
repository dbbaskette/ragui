const root = document.getElementById('root');

function ChatApp() {
    const [input, setInput] = React.useState("");
    const [messages, setMessages] = React.useState([]);
    const [loading, setLoading] = React.useState(false);
    const [llmMode, setLlmMode] = React.useState('rag-with-fallback'); // 'rag-only', 'rag-with-fallback', 'pure-llm'

    const sendMessage = async (e) => {
        e.preventDefault();
        if (!input.trim()) return;
        setLoading(true);
        setMessages([...messages, { sender: "user", text: input }]);
        try {
            const res = await fetch("/api/chat", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ 
                    message: input, 
                    includeLlmFallback: llmMode === 'rag-with-fallback',
                    usePureLlm: llmMode === 'pure-llm'
                })
            });
            const data = await res.json();
            setMessages(msgs => [
                ...msgs,
                {
                    sender: "llm",
                    text: data.answer + (data.source ? ` (source: ${data.source})` : "")
                }
            ]);
        } catch (err) {
            setMessages(msgs => [...msgs, { sender: "llm", text: "Error: " + err }]);
        }
        setInput("");
        setLoading(false);
    };

    React.useEffect(() => {
        const el = document.querySelector('.chat-messages');
        if (el) el.scrollTop = el.scrollHeight;
    }, [messages]);

    return (
        React.createElement("div", { className: "chat-container" },
            React.createElement("div", { className: "response-box" },
                messages.length === 0
                    ? React.createElement("div", { className: "placeholder" }, "Responses will appear here.")
                    : messages.map((m, i) =>
                        React.createElement("div", {
                            key: i,
                            className: "msg " + (m.sender === "user" ? "user" : "llm")
                        }, m.sender === "user" ? "You: " : "AI: ", m.text)
                    )
            ),
            React.createElement("div", { style: { display: "flex", gap: 16, marginTop: 10 } },
                React.createElement("div", { style: { flex: 3, display: "flex", flexDirection: "column" } },
                    React.createElement("form", { onSubmit: sendMessage, className: "chat-form" },
                        React.createElement("input", {
                            type: "text",
                            value: input,
                            onChange: e => setInput(e.target.value),
                            disabled: loading,
                            placeholder: "Type your message...",
                            autoFocus: true,
                            style: { width: "100%", fontSize: "1.08em", padding: "10px", borderRadius: "6px", border: "1px solid #ccc", marginBottom: 0 }
                        }),
                        React.createElement("button", { type: "submit", disabled: loading, style: { marginTop: 8 } }, loading ? "..." : "Send")
                    )
                ),
                React.createElement("div", { className: "llm-options-box" },
                    React.createElement("div", { style: { fontWeight: 600, marginBottom: 8 } }, "Response Mode"),
                    React.createElement("label", { style: { display: "block", marginBottom: 6, fontSize: '0.96em' } },
                        React.createElement("input", {
                            type: "radio",
                            name: "llmMode",
                            checked: llmMode === 'rag-only',
                            onChange: () => setLlmMode('rag-only'),
                            style: { marginRight: 4 }
                        }),
                        "RAG Only"
                    ),
                    React.createElement("label", { style: { display: "block", marginBottom: 6, fontSize: '0.96em' } },
                        React.createElement("input", {
                            type: "radio",
                            name: "llmMode",
                            checked: llmMode === 'rag-with-fallback',
                            onChange: () => setLlmMode('rag-with-fallback'),
                            style: { marginRight: 4 }
                        }),
                        "RAG with LLM Fallback"
                    ),
                    React.createElement("label", { style: { display: "block", fontSize: '0.96em' } },
                        React.createElement("input", {
                            type: "radio",
                            name: "llmMode",
                            checked: llmMode === 'pure-llm',
                            onChange: () => setLlmMode('pure-llm'),
                            style: { marginRight: 4 }
                        }),
                        "Pure LLM (No RAG)"
                    )
                )
            )
        )
    );
}

// Load React from CDN if not present
(function loadReact() {
    if (!window.React || !window.ReactDOM) {
        const reactScript = document.createElement('script');
        reactScript.src = 'https://unpkg.com/react@18/umd/react.development.js';
        reactScript.onload = () => {
            const domScript = document.createElement('script');
            domScript.src = 'https://unpkg.com/react-dom@18/umd/react-dom.development.js';
            domScript.onload = () => ReactDOM.createRoot(root).render(React.createElement(ChatApp));
            document.body.appendChild(domScript);
        };
        document.body.appendChild(reactScript);
    } else {
        ReactDOM.createRoot(root).render(React.createElement(ChatApp));
    }
})();
