const MAIN_JS_VERSION = "0.3.12";
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
    console.log("[ConfigPanel] Rendering. Props:", { config, version, lastPrompt });
    if (!config) {
        console.log("[ConfigPanel] config is falsy, returning null.");
        return null;
    }
    // Exclude app.version from the config entries since it's shown in the header
    const configEntries = Object.entries(config).filter(([key]) =>
        !['app.version', 'MAIN_JS_VERSION', 'last_prompt'].includes(key)
    );
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
                React.createElement("span", { className: "config-value" }, (config && config["app.version"]) || version || "unknown"),
                React.createElement("span", { style: { marginLeft: 12 } }, "Main.js Version: "),
                React.createElement("span", { className: "config-value" }, typeof MAIN_JS_VERSION !== "undefined" ? MAIN_JS_VERSION : "unknown")
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
    // Move all state and ref declarations to the top
    const [statusLog, setStatusLog] = React.useState([]);
    const [currentStatus, setCurrentStatus] = React.useState("");
    const [progress, setProgress] = React.useState(null);
    const [jobId, setJobId] = React.useState(null); // Track current jobId for SSE
    const [input, setInput] = React.useState("");
    const inputRef = React.useRef(null);
    const [messages, setMessages] = React.useState([]);
    const [loading, setLoading] = React.useState(false);
    const [llmMode, setLlmMode] = React.useState('rag-only'); // Default to 'RAG Only' mode
    const [showConfig, setShowConfig] = React.useState(false);
    const [config, setConfig] = React.useState(null);
    // const [version, setVersion] = React.useState(null); // App version from /api/version will be merged into config state
    const [retryKey, setRetryKey] = React.useState(0);
    const [showRetry, setShowRetry] = React.useState(false);
    const [lastPrompt, setLastPrompt] = React.useState("");
    // Track if an AI answer has been received for this job
    const aiAnswerReceivedRef = React.useRef(false);

    // Always show app title at the top
    const appTitleBar = React.createElement("div", { className: "chat-header" },
        React.createElement("h2", null, "Tanzu RAG Chat"),
        React.createElement("button", {
            className: "config-btn",
            onClick: () => setShowConfig(true)
        }, "\u2699"),
        currentStatus && React.createElement("div", {
            style: {
                marginTop: 10, fontWeight: 500, fontSize: "1.1em", color: "#1a73e8", background: "#f2f8fd", padding: "6px 12px", borderRadius: 6, maxWidth: 700
            }
        }, currentStatus),
        (progress !== null && progress > 0 && progress < 100) && React.createElement("div", {
            style: {
                margin: "8px 0 0 0", width: 220, height: 8, background: "#e0e0e0", borderRadius: 4, overflow: "hidden"
            }
        },
            React.createElement("div", {
                style: {
                    width: progress + "%", height: "100%", background: "#1a73e8", borderRadius: 4, transition: "width 0.3s"
                }
            })
        )
    );
    // SSE subscription using fetch for raw stream access
    React.useEffect(() => {
        if (!jobId) return;

        const controller = new AbortController();
        const signal = controller.signal;

        const timeoutId = setTimeout(() => {
            console.log("[Fetch SSE] Timeout fired (frontend)");
            setStatusLog(log => [...log, "Timed out waiting for response from backend."]);
            setMessages(msgs => [
                ...msgs.filter(m => !m.spinner),
                { sender: "llm", text: "Timed out waiting for response from backend.", spinner: false }
            ]);
            setLoading(false);
            setShowRetry(true);
            controller.abort(); // Abort the fetch on timeout
        }, 185000); // 185 seconds

        async function processStream() {
            setLoading(true);
            setStatusLog([]);
            setShowRetry(false);
            aiAnswerReceivedRef.current = false;

            try {
                const response = await fetch(`/api/events/${jobId}`, { signal });
                if (!response.ok) throw new Error(`HTTP error! status: ${response.status}`);

                const reader = response.body.getReader();
                const decoder = new TextDecoder();
                let buffer = '';

                while (true) {
                    const { done, value } = await reader.read();
                    if (done) {
                        console.log("[Fetch SSE] Stream finished.");
                        break;
                    }

                    buffer += decoder.decode(value, { stream: true });
                    const lines = buffer.split('\n');
                    buffer = lines.pop(); // Keep potential partial line in buffer

                    for (const line of lines) {
                        if (line.trim() === '') continue; // Ignore empty lines

                        // We now display the raw line, but still need to check if it's a control message
                        const payload = line.startsWith('data:') ? line.substring(5).trim() : line;

                        try {
                            const data = JSON.parse(payload);
                            // --- JSON: This is a status/control message, handle it silently ---
                            if (data.statusMessage) {
                                setStatusLog(log => (log.length === 0 || log[log.length - 1] !== data.statusMessage) ? [...log, data.statusMessage] : log);
                            }
                            if (typeof data.progress === "number") {
                                setProgress(data.progress);
                            }
                            if (data.status === 'COMPLETED' || data.status === 'FAILED') {
                                console.log(`[Fetch SSE] Received terminal status: ${data.status}`);
                                if (timeoutId) clearTimeout(timeoutId);
                                setLoading(false);
                                setProgress(100);
                                setMessages(msgs => msgs.map(m => m.streaming ? { ...m, streaming: false } : m));
                                setTimeout(() => { if (inputRef.current) inputRef.current.focus(); }, 0);
                                // Stream will close, causing the loop to break.
                            }
                        } catch (e) {
                            // --- PLAIN TEXT: This is an LLM chunk ---
                            // Strip "data:" prefix but preserve all subsequent characters (including spaces)
                            const chunkToDisplay = line.startsWith('data:') ? line.substring(5) : line;

                            if (!aiAnswerReceivedRef.current) {
                                // First chunk: replace the 'thinking' spinner
                                aiAnswerReceivedRef.current = true;
                                setMessages(msgs => {
                                    const updated = msgs.filter(m => !m.spinner);
                                    updated.push({ sender: 'llm', text: chunkToDisplay, spinner: false, streaming: true, className: 'llm-message' });
                                    return updated;
                                });
                            } else {
                                // Subsequent chunks: append to the existing streaming message
                                setMessages(msgs => {
                                    const updated = [...msgs];
                                    if (updated.length > 0 && updated[updated.length - 1].streaming) {
                                        const last = updated[updated.length - 1];
                                        updated[updated.length - 1] = { ...last, text: (last.text || "") + chunkToDisplay };
                                    }
                                    return updated;
                                });
                            }
                        }
                    }
                }
            } catch (error) {
                if (error.name !== 'AbortError') {
                    console.error('[Fetch SSE] Connection error:', error);
                    if (timeoutId) clearTimeout(timeoutId);
                    setStatusLog(log => [...log, "SSE connection failed."]);
                    setMessages(msgs => {
                        const filtered = msgs.filter(m => !m.spinner);
                        return !aiAnswerReceivedRef.current ? [...filtered, { sender: "llm", text: "AI response connection failed.", spinner: false }] : filtered;
                    });
                    setLoading(false);
                    setShowRetry(true);
                }
            }
        }

        processStream();

        return () => {
            if (timeoutId) clearTimeout(timeoutId);
            controller.abort();
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
        aiAnswerReceivedRef.current = false; // Reset for each new job
        document.title = "Tanzu RAG Chat";
        Promise.all([
            fetch("/api/config/properties").then(res => res.json()),
            fetch("/api/version").then(res => res.json()) // Keep full response to get data.version
        ]).then(([configData, versionResponse]) => {
            const appVersion = versionResponse && versionResponse.version;
            console.log("[INIT] Fetched /api/config/properties:", configData);
            console.log("[INIT] Fetched /api/version (version value):", appVersion);
            const combinedConfig = { ...configData, "app.version": appVersion };
            setConfig(combinedConfig);
            // setVersion(appVersion); // No longer using separate version state in ChatApp if merged into config
        }).catch(err => console.error("Failed to load initial config/version:", err));
    }, []); // Empty dependency array: fetch only once on mount

    // Send chat message and trigger SSE job status updates
    const sendMessage = async (e) => {
        e.preventDefault();
        if (!input.trim()) return;
        aiAnswerReceivedRef.current = false; // Reset for new prompt
        setLoading(true);
        setProgress(null); // Reset progress for new job
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
        // Note: setInput("") will be called after successful job submission
        try {
            const response = await fetch("/api/job", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({
                    message: input, // use the original 'input' state here
                    includeLlmFallback: llmMode === "rag-with-fallback",
                    usePureLlm: llmMode === "pure-llm",
                    rawRag: llmMode === "raw-rag"
                })
            });
            if (!response.ok) {
                const errorText = await response.text();
                throw new Error(`Backend error: ${response.status} ${errorText}`);
            }
            const data = await response.json();

            if (llmMode === "raw-rag") {
                // Raw RAG mode: response is the final answer, not a jobId
                console.log("Raw RAG response data:", data);
                aiAnswerReceivedRef.current = true; // Mark that we have a response
                setMessages(msgs => {
                    const updated = msgs.filter(m => !m.spinner);
                    if (data.bubbles && Array.isArray(data.bubbles)) {
                        const newBubbles = data.bubbles.map(bubbleText => ({
                            sender: 'llm',
                            text: bubbleText,
                            spinner: false
                        }));
                        return [...updated, ...newBubbles];
                    } else {
                        // Fallback for single answer or error
                        updated.push({ sender: 'llm', text: data.answer, spinner: false });
                        return updated;
                    }
                });
                setLoading(false);
                setInput(""); // Clear input on successful submission
            } else {
                // Streaming modes: expect a jobId
                if (!data.jobId) {
                    throw new Error("No jobId received from backend for streaming mode.");
                }
                setJobId(data.jobId); // Triggers SSE useEffect to handle status/progress
                setInput(""); // Clear input only after successful job submission
            }
        } catch (error) {
            console.error("Failed to send message or create job:", error);
            setMessages(msgs => [
                ...msgs.filter(m => !m.spinner), // Remove the 'AI is thinking...' spinner
                { sender: "llm", text: `Error: Could not start job. ${error.message}`, spinner: false }
            ]);
            setLoading(false); // Ensure loading is false on initial send error
            setTimeout(() => { if (inputRef.current) inputRef.current.focus(); }, 0); // Attempt to focus
        }
    };

    React.useEffect(() => {
        const el = document.querySelector('.response-box');
        if (el) el.scrollTop = el.scrollHeight;
    }, [messages]);

    // const configWithVersion = config ? { ...config, "app.version": version } : config; // Removed: app.version is now directly in 'config' state
    console.log("[ChatApp] Rendering. State: showConfig:", showConfig, "config loaded:", !!config);
    return React.createElement("div", { className: "app-container" },
        // ConfigPanel is rendered here if showConfig is true
        showConfig && React.createElement(ConfigPanel, {
            config: config, // Pass the combined config (which includes app.version)
            version: MAIN_JS_VERSION, // This is for 'Main.js Version'
            onClose: () => setShowConfig(false),
            lastPrompt: lastPrompt
        }),
        React.createElement("div", { className: "main-content-row" },
            React.createElement("div", { className: "chat-container" },
                React.createElement("div", { className: "chat-header" },
                    React.createElement("h1", { style: { display: 'flex', alignItems: 'center', gap: 8, margin: 0 } },
                        React.createElement("img", {
                            src: "images/tanzu.png",
                            alt: "",
                            'aria-label': "Tanzu Logo",
                            style: { height: 24, width: 'auto', display: 'inline-block', margin: 0, padding: 0 }
                        }),
                        React.createElement("span", { style: { display: 'inline-block', verticalAlign: 'middle' } }, "Tanzu RAG Chat")
                    ),
                    React.createElement("button", { 
                        className: "config-btn",
                        onClick: () => {
                        console.log('[ChatApp] Gear icon clicked. Current showConfig:', showConfig);
                        setShowConfig(true);
                        // Note: The actual state update is asynchronous, so logging showConfig immediately after setShowConfig(true)
                        // might still show the old value. The re-render log will show the new value.
                        console.log('[ChatApp] Gear icon: setShowConfig(true) called.');
                    },
                        title: "Show Configuration"
                    }, "⚙️")
                ),
                React.createElement("div", { className: "response-box" },
                    messages.length === 0
                        ? React.createElement("div", { className: "placeholder" }, "Responses will appear here.")
                        : messages.map((m, i) =>
                            React.createElement("div", {
                                key: i,
                                className: m.system ? "msg system" : m.sender === "user" ? "message-bubble user" : m.spinner ? "message-bubble llm spinner" : "message-bubble llm"
                            },
                                m.system && m.text && m.text.startsWith("Prompt sent to LLM: ")
                                    ? React.createElement(ConstructedPromptMessage, { text: m.text })
                                    : m.system
                                        ? React.createElement("span", { style: { fontStyle: "italic", background: "#f7f7f7", color: "#444", padding: "4px 8px", borderRadius: 4, display: "inline-block", marginBottom: 4 } }, m.text)
                                        : m.sender === "user"
                                            ? m.text
                                            : m.spinner
                                                ? React.createElement(React.Fragment, null,
                                                    React.createElement("span", { className: "spinner", style: { marginRight: '8px' } },
                                                        React.createElement("span", { className: "dot dot1" }),
                                                        React.createElement("span", { className: "dot dot2" }),
                                                        React.createElement("span", { className: "dot dot3" })
                                                    ),
                                                    "AI is thinking..."
                                                  )
                                                : (m.text ? React.createElement('div', { dangerouslySetInnerHTML: { __html: m.text } }) : null)
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
                React.createElement("form", { onSubmit: sendMessage, className: "chat-form" },
                    React.createElement("input", {
                        type: "text",
                        value: input,
                        onChange: e => setInput(e.target.value),
                        disabled: loading,
                        placeholder: loading ? "Waiting for response..." : "Type your question here and press Enter",
                        style: { marginRight: 10, maxWidth: "none" }, // Rely on flexbox for width
                        ref: inputRef
                    }),
                    React.createElement("button", { type: "submit", disabled: loading, style: { padding: '10px 15px'} }, loading ? "..." : "Send")
                ),
                /* Progress bar moved here, above options */
                (progress !== null && progress > 0) && React.createElement("div", { className: "progress-bar-container", style: { marginTop: '15px' } },
                    React.createElement("div", {
                        className: "progress-bar-fill",
                        style: { width: `${progress}%` }
                    })
                ),
                /* LLM Options Box moved here, below the form */
                React.createElement("div", { className: "llm-options-box", style: { marginTop: '15px', width: 'fit-content', margin: '15px auto 0 auto' } }, // Centered and fit to content
                    React.createElement("div", { style: { fontWeight: 600, marginBottom: 8 } }, "Response Mode"),
                    React.createElement("label", { style: { display: "block", marginBottom: 6, fontSize: '0.85em' } },
                        React.createElement("input", {
                            type: "radio",
                            name: "llmMode",
                            checked: llmMode === 'rag-only',
                            onChange: () => setLlmMode('rag-only'),
                            style: { marginRight: 4 }
                        }),
                        "RAG Only"
                    ),
                    React.createElement("label", { style: { display: "block", marginBottom: 6, fontSize: '0.85em' } },
                        React.createElement("input", {
                            type: "radio",
                            name: "llmMode",
                            checked: llmMode === 'rag-with-fallback',
                            onChange: () => setLlmMode('rag-with-fallback'),
                            style: { marginRight: 4 }
                        }),
                        "RAG with LLM Fallback"
                    ),
                    React.createElement("label", { style: { display: "block", marginBottom: 6, fontSize: '0.85em' } },
                        React.createElement("input", {
                            type: "radio",
                            name: "llmMode",
                            checked: llmMode === 'raw-rag',
                            onChange: () => setLlmMode('raw-rag'),
                            style: { marginRight: 4 }
                        }),
                        "Raw RAG (Concatenated DB Results)"
                    ),
                    React.createElement("label", { style: { display: "block", fontSize: '0.85em' } },
                        React.createElement("input", {
                            type: "radio",
                            name: "llmMode",
                            checked: llmMode === 'pure-llm',
                            onChange: () => setLlmMode('pure-llm'),
                            style: { marginRight: 4 }
                        }),
                        "Pure LLM (No RAG)"
                    )
                ) // Closes llm-options-box
            ) // Closes chat-container
        ), // Closes main-content-row
        // Always show status log at the bottom
        React.createElement("div", { className: "status-container" },
            React.createElement(StatusLogPanel, { statusLog: statusLog })
        )
    );

}

// Add styles for the config and status panels
const style = document.createElement('style');

// Mount the ChatApp to the DOM
function mountChatApp() {
    if (window.__raguiChatAppMounted) return;
    window.__raguiChatAppMounted = true;
    ReactDOM.createRoot(root).render(React.createElement(ChatApp));
}

(function ensureReactAndRender() {
    if (!window.React || !window.ReactDOM) {
        const reactScript = document.createElement('script');
        reactScript.src = 'https://unpkg.com/react@18/umd/react.development.js';
        reactScript.onload = () => {
            const domScript = document.createElement('script');
            domScript.src = 'https://unpkg.com/react-dom@18/umd/react-dom.development.js';
            domScript.onload = mountChatApp;
            document.body.appendChild(domScript);
        };
        document.body.appendChild(reactScript);
    } else {
        mountChatApp();
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
        max-width: 1200px;
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
        height: 460px;
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
        font-size: 1em;
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
        font-size: 1em;
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
    .message-bubble {
        padding: 10px 15px;
        border-radius: 18px;
        margin-bottom: 8px;
        max-width: 80%;
        word-wrap: break-word;
        white-space: pre-wrap; /* Preserve spaces and wrap text */
        font-size: 0.8em;
    }
    .message-bubble.user {
        background-color: #007AFF; /* iMessage Blue */
        color: white;
        align-self: flex-end;
    }
    .message-bubble.llm {
        background-color: #34C759; /* SMS Green */
        color: white;
        align-self: flex-start;
    }
    .message-bubble.llm.spinner {
        background-color: #E5E5EA; /* iMessage Gray */
        color: #1c1c1e;
        align-self: flex-start;
    }
`;
document.head.appendChild(style);

// Load React from CDN if not present

