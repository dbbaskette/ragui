# RAGUI: Retrieval-Augmented Generation UI Backend

RAGUI is a Spring Boot backend that provides a chat interface powered by Retrieval-Augmented Generation (RAG) using Spring AI, Ollama LLM, and a PgVector vector store. It enables users to ask questions and receive answers based on both retrieved context from a vector database and the language model's own knowledge, with robust fallback logic.

## Features

- **Retrieval-Augmented Generation (RAG):** Uses a vector store (PgVector) to retrieve relevant context for user queries.
- **LLM Fallback:** If no relevant context is found, the system falls back to the base LLM (Ollama) for a general answer.
- **Spring AI Integration:** Leverages Spring AI's advisor and client abstractions for clean, modular code.
- **Configurable Retrieval:** Easily adjust similarity thresholds and top-K retrieval parameters.
- **RESTful API:** Designed for easy integration with frontends or other services.

## How It Works

1. **User sends a chat request** to the backend.
2. **RagService** attempts to answer using context retrieved from the vector store via the `QuestionAnswerAdvisor` (RAG).
3. If the advisor returns no relevant answer (e.g., "I don't know"), the service automatically **falls back to the base LLM** to generate an answer using its own knowledge.

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
