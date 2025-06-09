/**
 * main.0.0.107.js
 * 
 * Tanzu RAG UI Chat Frontend Bundle
 * 
 * - Mounts the main React chat app to the DOM
 * - Handles user input, displays chat messages, and manages configuration
 * - Connects to the backend via SSE for job status and streaming responses
 * - Ensures robust error handling and user feedback for connection issues
 * 
 * Key features:
 *   - Displays spinner/waiting messages while awaiting backend
 *   - Removes interruption/timeout messages upon successful completion
 *   - Shows system/configuration info and backend status logs
 *   - Cleanly manages SSE lifecycle and React state
 * 
 * See implementation_details.md for technical notes, and gotchas.md for edge cases.
 */

const root = document.getElementById('root');

// Expand/collapse for constructed prompt system messages
/**
 * Renders a system message showing the constructed prompt sent to the LLM, with expand/collapse for long prompts.
 * @param {{ text: string }} props
 */
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

/**
 * Renders the configuration panel overlay, showing current app config, version, and last prompt sent.
 * @param {{ config: object, version: string, onClose: function, lastPrompt: string }} props
 */
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

/**
 * Shows database plan, chat model, and embedding model info from config.
 * @param {{ config: object }} props
 */
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
/**
 * Shows backend status/progress messages at the bottom of the UI.
 * @param {{ statusLog: string[] }} props
 */
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

/**
 * Main chat application React component.
 * Handles user input, backend job management, SSE lifecycle, and UI state.
 */
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
    /**
     * Effect: Manages SSE connection for backend job updates.
     * Handles spinner, status, error, and completion logic.
     */
    React.useEffect(() => {
        if (!jobId) return;
        setLoading(true);
        setStatusLog([]);
        // Do not remove spinner/progress messages here; only remove after completion.
        // setMessages(msgs => msgs.filter(m => !m.spinner));
        setShowRetry(false);
        let didComplete = false;
        let didFail = false;
        const didCompleteRef = { current: false };
        const didFailRef = { current: false };
        let sseClosed = false;
        // Timeout: If backend does not respond in time, show timeout message.
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
        // Open SSE connection for job status updates
        const es = new EventSource(`/api/events/${jobId}`);
        console.log("[SSE] Connection opened for jobId", jobId);
        /**
         * SSE onopen: Connection established.
         */
        es.onopen = () => {
            console.log("[SSE] onopen");
        };
        /**
         * SSE onmessage: Handles all backend events and job status updates.
         */
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
                // --- PATCH: Set completion flags FIRST and remove interruption/timeout messages on completion ---
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
        /**
         * SSE onerror: Handles connection errors. Only show interruption if job not complete/failed.
         */
        es.onerror = () => {
            if (didCompleteRef.current || didFailRef.current) {
                // Ignore errors after job is done
                return;
            }
            if (timeoutId) { clearTimeout(timeoutId); timeoutId = null; }
            setStatusLog(log => [...log, "SSE connection lost."]);
            setMessages(msgs => [
                ...msgs.filter(m => !m.spinner),
                { sender: "llm", text: "AI response interrupted or connection lost before completion.", spinner: false }
            ]);
            setLoading(false);
            setShowRetry(true);
            sseClosed = true;
            es.close();
        };
        // Interval: Check if SSE closed before job completed. If so, show interruption message.
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
        // Cleanup: Close SSE and clear timeout on unmount or jobId/retryKey change.
        return () => {
            if (timeoutId) clearTimeout(timeoutId);
            es.close();
            sseClosed = true;
        };
    }, [jobId, retryKey]);
    // ... rest of ChatApp ...
}

// Add styles for the config and status panels
const style = document.createElement('style');
// ... rest of the file ...

// Mount the ChatApp to the DOM after DOM is ready
// This prevents blank screens if script loads before #root exists

document.addEventListener('DOMContentLoaded', function() {
    const root = document.getElementById('root');
    if (root && window.ReactDOM && window.React) {
        if (window.ReactDOM.createRoot) {
            window.ReactDOM.createRoot(root).render(window.React.createElement(ChatApp));
        } else {
            window.ReactDOM.render(window.React.createElement(ChatApp), root);
        }
    } else {
        console.error('React, ReactDOM, or #root not found.');
    }
});

