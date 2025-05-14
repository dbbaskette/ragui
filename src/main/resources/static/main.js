const root = document.getElementById('root');

function ChatApp() {
    const [input, setInput] = React.useState("");
    const [messages, setMessages] = React.useState([]);
    const [loading, setLoading] = React.useState(false);

    const sendMessage = async (e) => {
        e.preventDefault();
        if (!input.trim()) return;
        setLoading(true);
        setMessages([...messages, { sender: "user", text: input }]);
        try {
            const res = await fetch("/api/chat", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ message: input })
            });
            const data = await res.json();
            setMessages(msgs => [...msgs, { sender: "llm", text: data.response }]);
        } catch (err) {
            setMessages(msgs => [...msgs, { sender: "llm", text: "Error: " + err }]);
        }
        setInput("");
        setLoading(false);
    };

    return (
        React.createElement("div", { className: "chat-container" },
            React.createElement("h2", null, "RAG UI Chat"),
            React.createElement("div", { className: "chat-messages" },
                messages.map((m, i) =>
                    React.createElement("div", {
                        key: i,
                        className: m.sender === "user" ? "msg user" : "msg llm"
                    }, m.sender === "user" ? "You: " : "AI: ", m.text)
                )
            ),
            React.createElement("form", { onSubmit: sendMessage, className: "chat-form" },
                React.createElement("input", {
                    value: input,
                    onChange: e => setInput(e.target.value),
                    disabled: loading,
                    placeholder: "Type your message...",
                    autoFocus: true
                }),
                React.createElement("button", { type: "submit", disabled: loading }, loading ? "..." : "Send")
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
