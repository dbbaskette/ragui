const root = document.getElementById('root');

// Expand/collapse for constructed prompt system messages
function ConstructedPromptMessage({ text }) {
    const [expanded, setExpanded] = React.useState(false);
    // Remove the prefix for display
    const prompt = text.replace(/^Prompt sent to LLM: /, "");
    const preview = prompt.length > 80 ? prompt.slice(0, 80) + "..." : prompt;
    return React.createElement("div", { style: { fontStyle: "italic", background: "#f7f7f7", color: "#444", padding: "4px 8px", borderRadius: 4, marginBottom: 4, maxWidth: 600 } },
        React.createElement("div", null,
            React.createElement("span", { style: { fontWeight: 500 } }, "Prompt sent to LLM: "),
            expanded ?
                React.createElement("span", { style: { whiteSpace: "pre-wrap" } }, prompt)
                : React.createElement("span", null, preview)
        ),
        prompt.length > 80 && React.createElement("button", {
            style: { marginLeft: 8, fontSize: "0.9em", padding: "2px 10px", borderRadius: 4, border: "1px solid #bbb", background: "#f2f2f2", cursor: "pointer" },
            onClick: () => setExpanded(e => !e)
        }, expanded ? "Hide prompt" : "Show full prompt")
    );
}

function ConfigPanel({ config, version, onClose, lastPrompt }) {
    if (!config) return null;
    // Exclude app.version from the config entries since it's shown in the header
    const configEntries = Object.entries(config).filter(([k]) => k !== "app.version");
    return React.createElement("div", { className: "config-panel-overlay" },
        React.createElement("div", { className: "config-panel" },
            React.createElement("div", { className: "config-header" },
                React.createElement("h3", null, "Configuration"),
                React.createElement("button", { 
                    onClick: onClose, 
                    className: "close-btn"
                }, "×")
            ),
            React.createElement("div", { className: "config-version" },
                React.createElement("strong", null, "App Version: "),
                React.createElement("span", { className: "config-value" }, (config && config["app.version"]) || version || "unknown")
            ),
            React.createElement("div", { className: "config-last-prompt" },
                React.createElement("strong", null, "Last prompt: "),
                React.createElement("span", { className: "config-value" }, lastPrompt || "(none)")
            ),
            React.createElement("div", { className: "config-content" },
                configEntries.map(([key, value]) =>
                    React.createElement("div", { key: key, className: "config-item" },
                        React.createElement("span", { className: "config-key" }, key + ":"),
                        React.createElement("span", { className: "config-value" }, String(value))
                    )
                )
            )
        )
    );
}

function ConnectionInfoBar({ config }) {
    if (!config) return null;
    // Use new config keys
    let dbPlan = config["embed-db.plan"] || "?";
    let chatModel = config["chat-model.model_name"] || "?";
    let embedModel = config["embed-model.model_name"] || "?";
    return React.createElement("div", { className: "connection-info-bar" },
        React.createElement("div", { className: "connection-info-item" }, `Database Plan: ${dbPlan}`),
        React.createElement("div", { className: "connection-info-item" }, `Chat Model: ${chatModel}`),
        React.createElement("div", { className: "connection-info-item" }, `Embedding Model: ${embedModel}`)
    );
}

// StatusLogPanel: shows backend status/progress messages at the bottom
function StatusLogPanel({ statusLog }) {
    if (!statusLog || statusLog.length === 0) return null;
    return React.createElement(
        "div",
        { className: "status-log-panel" },
        statusLog.map((msg, i) =>
            React.createElement(
                "div",
                { key: i, className: "status-log-entry" },
                msg
            )
        )
    );
}

function ChatApp() {
    // Always show app title at the top
    const appTitleBar = React.createElement("div", { className: "chat-header" },
        React.createElement("h2", null, "Tanzu RAG Chat"),
        React.createElement("button", {
            className: "config-btn",
            onClick: () => setShowConfig(true)
        }, "⚙")
    );
    const [statusLog, setStatusLog] = React.useState([]);
    const [jobId, setJobId] = React.useState(null); // Track current jobId for SSE

    const [input, setInput] = React.useState("");
    const [messages, setMessages] = React.useState([]);
    const [loading, setLoading] = React.useState(false);
    const [llmMode, setLlmMode] = React.useState('rag-with-fallback'); // 'rag-only', 'rag-with-fallback', 'pure-llm', 'raw-rag'
    const [showConfig, setShowConfig] = React.useState(false);
    const [config, setConfig] = React.useState(null);
    const [version, setVersion] = React.useState(null);
    const [retryKey, setRetryKey] = React.useState(0);
    const [showRetry, setShowRetry] = React.useState(false);
    const [lastPrompt, setLastPrompt] = React.useState("");
    // SSE subscription for backend status updates
    React.useEffect(() => {
        if (!jobId) return;
        setLoading(true);
        setStatusLog([]);
        // Only remove spinner/progress messages, keep user prompt
        // Do not remove spinner/progress messages here; only remove after completion.
        // setMessages(msgs => msgs.filter(m => !m.spinner));
        setShowRetry(false);
        let didComplete = false;
        let didFail = false;
        const didCompleteRef = { current: false };
        const didFailRef = { current: false };
        let sseClosed = false;
        let timeoutId = setTimeout(() => {
            if (didCompleteRef.current || didFailRef.current) {
                console.log("[SSE] Timeout fired, but already complete/failed; ignoring.");
                return;
            }
            setStatusLog(log => [...log, "Timed out waiting for response from backend."]);
            setMessages(msgs => [
                ...msgs.filter(m => !m.spinner),
                { sender: "llm", text: "Timed out waiting for response from backend.", spinner: false }
            ]);
            setLoading(false);
            setShowRetry(true);
            console.log("[SSE] Timeout fired (frontend)");
        }, 185000); // 185 seconds (3 min 5 sec)
        const es = new EventSource(`/api/events/${jobId}`);
        console.log("[SSE] Connection opened for jobId", jobId);
        es.onopen = () => {
            console.log("[SSE] onopen");
        };
        es.onmessage = (event) => {
            console.log("[SSE] onmessage", event.data);
            try {
                const data = JSON.parse(event.data);
                // Prefer statusMessage if present, else fallback to status
                if (data.statusMessage || data.status_message) {
                    setStatusLog(log => [...log, data.statusMessage || data.status_message]);
                } else if (data.status) {
                    setStatusLog(log => [...log, data.status]);
                }
                // Show constructed prompt as a system message if present
                if (data.response && data.response.prompt) {
                    setMessages(msgs => [
                        ...msgs,
                        { sender: "system", text: "Prompt sent to LLM: " + data.response.prompt, system: true }
                    ]);
                    setLastPrompt(data.response.prompt);
                }
                if (data.prompt) {
                    setMessages(msgs => [
                        ...msgs,
                        { sender: "system", text: "Prompt sent to LLM: " + data.prompt, system: true }
                    ]);
                    setLastPrompt(data.prompt);
                }
                // Show AI response if present (either as 'message' or as 'response.answer')
                if (data.message) {
                    setMessages(msgs => {
                        const updated = [...msgs.filter(m => !m.spinner), { sender: data.sender || "llm", text: data.message, spinner: false }];
                        console.log("After AI message (message):", updated);
                        return updated;
                    });
                } else if (data.response && (data.response.answer || data.response.text)) {
                    setMessages(msgs => {
                        const answer = data.response.answer || data.response.text;
                        const updated = [...msgs.filter(m => !m.spinner), { sender: "llm", text: answer, spinner: false }];
                        console.log("After AI message (response):", updated);
                        return updated;
                    });
                }
                if (data.complete || data.failed || data.status === "COMPLETE" || data.status === "FAILED") {
            // Set completion flags FIRST
            didComplete = true;
            didCompleteRef.current = true;
            if (data.status === "FAILED") {
                didFail = true;
                didFailRef.current = true;
            }
            clearTimeout(timeoutId);
            setLoading(false);
            setShowRetry(false); // Remove retry UI
            // Remove spinner/progress and any retry/timeout/interruption error messages from chat
            setMessages(msgs =>
                msgs.filter(
                    m =>
                        !m.spinner &&
                        m.text !== "AI response interrupted or connection lost before completion." &&
                        m.text !== "Timed out waiting for response from backend."
                )
            );
            // Immediately close the SSE connection and mark as closed
            es.close();
            sseClosed = true;
        }
    } catch (err) {
        setStatusLog(log => [...log, "Malformed SSE message: " + event.data]);
        console.log("[SSE] Malformed SSE message", event.data);
    }
};
        es.onerror = () => {
            if (didCompleteRef.current || didFailRef.current) {
                // Ignore errors after job is done
                return;
            }
            if (timeoutId) { clearTimeout(timeoutId); timeoutId = null; }
            setStatusLog(log => [...log, "SSE connection lost."]);
            setMessages(msgs => {
                // Only show interruption message if last LLM message is still a spinner
                const filtered = msgs.filter(m => !m.spinner);
                const lastMsg = msgs[msgs.length - 1];
                if (lastMsg && lastMsg.spinner) {
                    return [
                        ...filtered,
                        { sender: "llm", text: "AI response interrupted or connection lost before completion.", spinner: false }
                    ];
                } else {
                    return filtered;
                }
            });
            setLoading(false);
            setShowRetry(true);
            sseClosed = true;
            es.close();
        };

        // Custom close detection (not native in EventSource)
        const closeCheck = setInterval(() => {
            if (didCompleteRef.current || didFailRef.current) {
                clearInterval(closeCheck);
                return;
            }
            if (sseClosed && !didCompleteRef.current && !didFailRef.current) {
                setStatusLog(log => [...log, "AI response interrupted or connection lost before completion."]);
                setMessages(msgs => [
                    ...msgs.filter(m => !m.spinner),
                    { sender: "llm", text: "AI response interrupted or connection lost before completion.", spinner: false }
                ]);
                setLoading(false);
                setShowRetry(true);
                clearInterval(closeCheck);
            }
        }, 1000);
        return () => {
            if (timeoutId) { clearTimeout(timeoutId); timeoutId = null; }
            clearInterval(closeCheck);
            es.close();
        };
    }, [jobId, retryKey]);

    const handleRetry = () => {
        setRetryKey(prev => prev + 1);
        setShowRetry(false);
        setStatusLog(log => [...log, "Retrying SSE connection..."]);
        setMessages(msgs => [
            ...msgs.filter(m => !m.spinner),
            { sender: "llm", text: "AI is thinking...", spinner: true }
        ]);
        setLoading(true);
    };


    // Set page title and fetch configuration/version when component mounts
    React.useEffect(() => {
        document.title = "Tanzu RAG Chat";
        Promise.all([
            fetch("/api/config/properties").then(res => res.json()),
            fetch("/api/version").then(res => res.json()).then(data => data.version)
        ]).then(([config, version]) => {
            setConfig(config);
            setVersion(version);
        }).catch(err => console.error("Failed to load config/version:", err));
    }, []);

    // Send chat message and trigger SSE job status updates
    const sendMessage = async (e) => {
        e.preventDefault();
        if (!input.trim()) return;
        setLoading(true);
        setStatusLog([]); // Clear status log for new job
        setMessages(msgs => {
            const updated = [
                ...msgs,
                { sender: "user", text: input },
                { sender: "llm", text: "AI is thinking...", spinner: true }
            ];
            console.log("After user message:", updated);
            return updated;
        });
        setLastPrompt(input); // Track last user prompt
        setInput("");
        try {
            // Submit job to new backend API
            const response = await fetch("/api/job", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({
                    message: input,
                    includeLlmFallback: llmMode === "rag-with-fallback",
                    usePureLlm: llmMode === "pure-llm",
                    rawRag: llmMode === "raw-rag"
                })
            });
            if (!response.ok) throw new Error("Backend error: " + response.status);
            const data = await response.json();
            if (!data.jobId) {
                throw new Error("No jobId returned from backend");
            }
            setJobId(data.jobId); // Triggers SSE useEffect to handle status/progress
            // (No need to add extra spinner message here; already added above)

        } catch (err) {
            setMessages(msgs => [
                ...msgs,
                { sender: "llm", text: `Error: Could not reach backend. ${err && err.message ? err.message : ''}` }
            ]);
            setLoading(false);
        }
    };

    React.useEffect(() => {
        const el = document.querySelector('.response-box');
        if (el) el.scrollTop = el.scrollHeight;
    }, [messages]);

    // Add version to config for display in the config list
    const configWithVersion = config ? { ...config, "app.version": version } : config;
    return React.createElement("div", { className: "app-container" },
        React.createElement(ConnectionInfoBar, { config }),
        React.createElement(StatusLogPanel, { statusLog }),
        showConfig && React.createElement(ConfigPanel, { config: configWithVersion, version, onClose: () => setShowConfig(false), lastPrompt }),
        React.createElement("div", { className: "chat-container" },
            React.createElement("div", { className: "chat-header" },
                React.createElement("h1", { style: { display: 'flex', alignItems: 'center', gap: 8, margin: 0 } },
                    React.createElement("img", {
                        src: "images/tanzu.png",
                        alt: "",
                        'aria-label': "Tanzu Logo",
                        style: { height: 24, width: 'auto', display: 'inline-block', margin: 0, padding: 0 }
                    }),
                    React.createElement("span", { style: { display: 'inline-block', verticalAlign: 'middle' } }, "Tanzu RAG UI Chat")
                ),
                React.createElement("button", { 
                    className: "config-btn",
                    onClick: () => setShowConfig(true),
                    title: "Show Configuration"
                }, "⚙️")
            ),

            React.createElement("div", { className: "response-box" },
                messages.length === 0
                    ? React.createElement("div", { className: "placeholder" }, "Responses will appear here.")
                    : messages.map((m, i) =>
                        React.createElement("div", {
                            key: i,
                            className: "msg " + (
                                m.system ? "system" : (m.sender === "user" ? "user" : "llm")
                            )
                        },
                            m.system && m.text && m.text.startsWith("Prompt sent to LLM: ")
                                ? React.createElement(ConstructedPromptMessage, { text: m.text })
                                : m.system
                                    ? React.createElement("span", { style: { fontStyle: "italic", background: "#f7f7f7", color: "#444", padding: "4px 8px", borderRadius: 4, display: "inline-block", marginBottom: 4 } }, m.text)
                                    : m.sender === "user"
                                        ? ["You: ", m.text]
                                        : [
                                            "AI: ",
                                            m.spinner
                                                ? React.createElement("span", { className: "spinner", style: { marginLeft: 4 }, "aria-label": "Loading..." },
                                                    React.createElement("span", { className: "dot dot1" }),
                                                    React.createElement("span", { className: "dot dot2" }),
                                                    React.createElement("span", { className: "dot dot3" })
                                                )
                                                : null,
                                            m.spinner ? " AI is thinking..." : m.text
                                        ]
                        )
                    )
            ),
            showRetry && React.createElement("div", { style: { margin: "16px 0", textAlign: "center" } },
                React.createElement("div", { style: { marginBottom: 8, color: "#b00", fontWeight: 500 } },
                    "The connection was lost or interrupted before a complete response was received. You can retry to attempt to reconnect to this job."
                ),
                React.createElement("button", {
                    onClick: handleRetry,
                    style: { padding: "8px 20px", fontSize: "1.1em", borderRadius: 6, background: "#f2f2f2", border: "1px solid #bbb", cursor: "pointer" }
                }, "Retry Connection")
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
                    React.createElement("label", { style: { display: "block", marginBottom: 6, fontSize: '0.96em' } },
                        React.createElement("input", {
                            type: "radio",
                            name: "llmMode",
                            checked: llmMode === 'raw-rag',
                            onChange: () => setLlmMode('raw-rag'),
                            style: { marginRight: 4 }
                        }),
                        "Raw RAG (Concatenated DB Results)"
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

// Add styles for the config and status panels
const style = document.createElement('style');

// Mount the ChatApp to the DOM
(function ensureReactAndRender() {
    if (!window.React || !window.ReactDOM) {
        const reactScript = document.createElement('script');
        reactScript.src = 'https://unpkg.com/react@18/umd/react.development.js';
        reactScript.onload = () => {
            const domScript = document.createElement('script');
            domScript.src = 'https://unpkg.com/react-dom@18/umd/react-dom.development.js';
            domScript.onload = () => {
                ReactDOM.createRoot(root).render(React.createElement(ChatApp));
            };
            document.body.appendChild(domScript);
        };
        document.body.appendChild(reactScript);
    } else {
        ReactDOM.createRoot(root).render(React.createElement(ChatApp));
    }
})();

style.textContent = `
    .status-log-panel {
        position: fixed;
        bottom: 0;
        left: 0;
        width: 100vw;
        background: #222;
        color: #fff;
        padding: 8px 16px;
        z-index: 1000;
        max-height: 150px;
        overflow-y: auto;
        font-size: 0.95em;
        box-shadow: 0 -2px 8px rgba(0,0,0,0.2);
    }
    .status-log-entry {
        padding: 2px 0;
        border-bottom: 1px solid #333;
        font-family: monospace;
    }
    body, html {
        background: linear-gradient(135deg, #e3ecfc 0%, #f7f8fa 100%);
        min-height: 100vh;
        font-family: 'Segoe UI', 'Roboto', Arial, sans-serif;
    }
    .app-container {
        background: #fff;
        border-radius: 14px;
        box-shadow: 0 4px 24px rgba(60,80,180,0.07);
        margin: 30px auto;
        max-width: 780px;
        padding: 0 0 20px 0;
        border: 1.5px solid #e2e6f0;
    }
    .chat-header {
        background: linear-gradient(90deg, #4b6cb7 0%, #182848 100%);
        color: #fff;
        border-radius: 14px 14px 0 0;
        padding: 22px 32px 15px 32px;
        display: flex;
        justify-content: space-between;
        align-items: center;
        box-shadow: 0 2px 8px rgba(60,80,180,0.06);
    }
    .chat-header h1 {
        font-size: 2.1em;
        font-weight: 700;
        margin: 0;
        letter-spacing: 0.5px;
    }
    .config-btn {
        background: #fff;
        color: #4b6cb7;
        border: none;
        border-radius: 50%;
        font-size: 1.5em;
        width: 38px;
        height: 38px;
        cursor: pointer;
        transition: background 0.2s, color 0.2s;
        box-shadow: 0 1px 4px rgba(60,80,180,0.09);
    }
    .config-btn:hover {
        background: #e3ecfc;
        color: #182848;
    }
    .chat-container {
        padding: 0 32px;
    }
    .response-box {
        background: #f0f4fa;
        border-radius: 8px;
        min-height: 170px;
        max-height: 320px;
        overflow-y: auto;
        margin: 18px 0 10px 0;
        padding: 16px 18px;
        box-shadow: 0 1px 4px rgba(60,80,180,0.06);
        border: 1px solid #e2e6f0;
        font-size: 1.08em;
        color: #1a2236;
        transition: box-shadow 0.2s;
    }
    .response-box .msg.user {
        color: #4b6cb7;
        font-weight: 600;
    }
    .response-box .msg.llm {
        color: #182848;
    }
    .response-box .placeholder {
        color: #b0b8c9;
        font-style: italic;
    }
    .chat-form input[type="text"] {
        background: #f7faff;
        border: 1px solid #c7d0e0;
        color: #222;
        border-radius: 6px;
        padding: 10px;
        font-size: 1.08em;
        margin-bottom: 0;
        outline: none;
        transition: border 0.2s;
    }
    .chat-form input[type="text"]:focus {
        border: 1.5px solid #4b6cb7;
    }
    .chat-form button {
        background: linear-gradient(90deg, #4b6cb7 0%, #182848 100%);
        color: #fff;
        border: none;
        border-radius: 6px;
        padding: 10px 20px;
        font-size: 1.08em;
        font-weight: 600;
        cursor: pointer;
        margin-top: 8px;
        transition: background 0.2s, box-shadow 0.2s;
        box-shadow: 0 1px 4px rgba(60,80,180,0.07);
    }
    .chat-form button:hover {
        background: linear-gradient(90deg, #182848 0%, #4b6cb7 100%);
    }
    .llm-options-box {
        background: #e3ecfc;
        border-radius: 8px;
        padding: 16px 18px;
        margin-left: 18px;
        box-shadow: 0 1px 4px rgba(60,80,180,0.05);
        border: 1px solid #e2e6f0;
        min-width: 220px;
    }
    .llm-options-box label {
        color: #182848;
    }
    .status-panel {
        background: #f6f8fa;
        border-bottom: 1px solid #e1e4e8;
        padding: 10px 20px;
        font-size: 1rem;
        display: flex;
        flex-direction: row;
        gap: 24px;
        align-items: center;
}
.status-panel .ok { color: #22863a; font-weight: bold; }
.status-panel .error { color: #d73a49; font-weight: bold; }
.status-panel .status-section { margin-right: 24px; }
.status-panel.loading, .status-panel.error { color: #6a737d; }

.app-container {
        position: relative;
        height: 100vh;
    }
    .config-panel-overlay {
        position: fixed;
        top: 0;
        left: 0;
        right: 0;
        bottom: 0;
        background: rgba(0, 0, 0, 0.5);
        display: flex;
        justify-content: center;
        align-items: center;
        z-index: 1000;
    }
    .config-panel {
        background: linear-gradient(135deg, #f7f7fa 0%, #dde6f7 100%);
        border: 2.5px solid #4b6cb7;
        box-shadow: 0 4px 32px rgba(60,80,180,0.21), 0 1.5px 8px #0001;
        padding: 28px 28px 18px 28px;
        border-radius: 16px;
        width: 84%;
        max-width: 620px;
        max-height: 82vh;
        overflow-y: auto;
        margin-top: 12px;
    }
    .config-header {
        display: flex;
        justify-content: space-between;
        align-items: center;
        margin-bottom: 15px;
    }
    .close-btn {
        background: none;
        border: none;
        font-size: 24px;
        cursor: pointer;
    }
    .config-item {
        margin: 10px 0;
        padding: 10px;
        background: #f5f5f5;
        border-radius: 4px;
        word-break: break-all;
    }
    .config-key {
        font-weight: bold;
        margin-right: 10px;
    }
    .config-btn {
        background: none;
        border: none;
        font-size: 20px;
        cursor: pointer;
        padding: 5px 10px;
        border-radius: 4px;
    }
    .config-btn:hover {
        background: #f0f0f0;
    }
    .connection-info-bar {
        background: linear-gradient(90deg, #dbeafe 0%, #f1f7ff 100%);
        border: 2px solid #4b6cb7;
        box-shadow: 0 2px 14px #bcd6f7cc, 0 1px 6px #4b6cb733;
        border-radius: 12px;
        padding: 14px 20px 10px 20px;
        margin: 12px 0 20px 0;
        font-size: 0.97em;
        display: flex;
        flex-direction: column;
        gap: 3px;
        max-width: 98vw;
    }
    .connection-info-item {
        font-size: 0.97em;
        line-height: 1.5;
        color: #253f5d;
        padding-bottom: 1px;
        word-break: break-word;
    }
`;
document.head.appendChild(style);

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