# RAGUI: Retrieval-Augmented Generation UI Front End

[![Deploy to Cloud Foundry](https://www.herokucdn.com/deploy/button.png)](https://cloud.gov/pages/)

RAGUI is a Spring Boot service that acts as a front end (API gateway and orchestration layer) for your Retrieval-Augmented Generation (RAG) environment. It can be deployed to Cloud Foundry platforms like Tanzu Application Service (TAS).

RAGUI is a Spring Boot service that acts as a front end (API gateway and orchestration layer) for your Retrieval-Augmented Generation (RAG) environment. It exposes a chat interface powered by Spring AI, Ollama LLM, and a PgVector vector store. RAGUI enables users or client applications to interact with your RAG stack, sending questions and receiving answers based on both retrieved vector context and the language model's own knowledge, with robust fallback logic.

## Features

- **Retrieval-Augmented Generation (RAG):** Uses a vector store (PgVector) to retrieve relevant context for user queries.
- **Multiple Response Modes:** Choose from three response strategies:
  - **RAG Only:** Only uses context from the vector store
  - **RAG with LLM Fallback:** Uses vector store first, falls back to LLM if needed
  - **Pure LLM:** Bypasses the vector store entirely, using only the LLM's knowledge
- **Spring AI Integration:** Leverages Spring AI's advisor and client abstractions for clean, modular code.
- **Configurable Retrieval:** Easily adjust similarity thresholds and top-K retrieval parameters.
- **RESTful API:** Designed for easy integration with frontends or other services.

## How It Works

1. **User sends a chat request** to the backend, optionally specifying `includeLlmFallback`.
2. **RagService** attempts to answer using context retrieved from the vector store via the `RetrievalAugmentationAdvisor` (RAG).
3. If the advisor returns no relevant answer (e.g., "I don't know"), and LLM fallback is enabled, the service automatically **falls back to the base LLM** to generate an answer using its own knowledge.

## Response Modes

### 1. RAG Only
- Uses only the context retrieved from the vector store
- Returns answers only if relevant context is found
- Most accurate but may not answer all questions

### 2. RAG with LLM Fallback (Default)
- First tries to answer using vector store context
- If no relevant context is found, falls back to the LLM
- Provides the best balance of accuracy and coverage

### 3. Pure LLM
- Bypasses the vector store entirely
- Uses only the LLM's pre-trained knowledge
- Fastest response time but lacks your custom data context

### UI Controls
- The chat interface includes radio buttons to select the desired response mode
- The selected mode is clearly indicated
- Response source (RAG or LLM) is shown with each answer

### API Usage
Control the behavior via the API using these request body parameters:
- `usePureLlm`: Set to `true` to bypass RAG and use only the LLM
- `includeLlmFallback`: Set to `true` to allow fallback to LLM when using RAG mode

## Screenshot

![Chat UI with Response Mode Selection](docs/screenshot-ui-response-modes.png)

## Cloud Foundry Deployment

### Prerequisites

1. Install the [Cloud Foundry CLI](https://docs.cloudfoundry.org/cf-cli/install-go-cli.html)
2. Log in to your Cloud Foundry instance:
   ```bash
   cf login -a <API-ENDPOINT> -u <USERNAME> -o <ORG> -s <SPACE>
   ```

### Required Services

1. **Database Service** (PostgreSQL with pgvector):
   ```bash
   cf create-service <postgres-service> <plan> ragui-db
   ```

2. **AI Service** (Ollama or compatible LLM service):
   ```bash
   cf create-user-provided-service ai-service -p '{"url":"<ollama-url>","model":"llama2","embedding-model":"nomic-embed-text"}'
   ```

### Deploying the Application

1. Build the application:
   ```bash
   ./mvnw clean package -DskipTests
   ```

2. Push the application to Cloud Foundry:
   ```bash
   cf push
   ```

3. (Optional) Set environment variables:
   ```bash
   cf set-env ragui SPRING_PROFILES_ACTIVE cloud
   cf set-env ragui JBP_CONFIG_OPEN_JDK_JRE '{ jre: { version: 21.+ } }'
   ```

4. Restart the application:
   ```bash
   cf restart ragui
   ```

### Verifying the Deployment

1. Check the application logs:
   ```bash
   cf logs ragui --recent
   ```

2. Access the application URL:
   ```bash
   cf app ragui
   ```
   Then open the URL shown in the `routes` field.

## Local Development

To run the application locally, you'll need:

1. Java 21 or later
2. Maven
3. PostgreSQL with pgvector extension
4. Ollama or compatible LLM service

### Running Locally

1. Set up your database and update the `application.properties` with your database credentials
2. Start the Ollama service
3. Run the application:
   ```bash
   ./mvnw spring-boot:run
   ```
4. Access the application at `http://localhost:8080`
*The chat interface showing the three response mode options*

## Key Classes

### `RagService`
- Handles chat requests.
- Uses both RAG and LLM fallback logic for robust responses.
- See inline Javadoc for details.

### `AiConfig`
- Configures Spring AI beans, including `ChatClient` and the LLM model.
- Setup for Ollama and PgVector.

## Configuration

Set the following properties in your `application.properties` or as environment variables:

```
spring.ai.ollama.base-url=${SPRING_AI_OLLAMA_BASE_URL:http://localhost:32434}
spring.ai.ollama.chat.options.model=${SPRING_AI_OLLAMA_CHAT_MODEL:phi3}

spring.datasource.url=${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:30432/scdf-rag}
spring.datasource.username=${SPRING_DATASOURCE_USERNAME:user}
spring.datasource.password=${SPRING_DATASOURCE_PASSWORD:bitnami}

spring.ai.vectorstore.pgvector.table=vector_store
spring.ai.vectorstore.pgvector.dimensions=768
spring.ai.vectorstore.pgvector.index-type=HNSW
spring.ai.vectorstore.pgvector.distance-type=COSINE
```

> **Note:** Some Spring AI properties may show as unknown in IDEs due to RC version tooling lag.

## Running the App

1. Ensure you have a running PostgreSQL instance with the `vector_store` table and PgVector extension enabled.
2. Start your Ollama LLM server.
3. Build and run the Spring Boot application:
   ```sh
   ./mvnw spring-boot:run
   ```
4. Send chat requests to the backend API (see your controller for endpoint details).

## Customization

- **Retrieval Parameters:** Adjust similarity threshold and top-K in `AiConfig` or directly in `RagService`.
- **Prompt Templates:** You can further customize the prompt used by the LLM if needed.

## Code Documentation

All major classes and methods are documented with Javadoc for clarity. See the source code for details on each component's responsibilities and logic.

## Contributing

Contributions are welcome! Please open issues or pull requests on [GitHub](https://github.com/dbbaskette/ragui).

## License

MIT License
