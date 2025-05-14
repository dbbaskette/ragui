package com.baskettecase.ragui.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;



/**
 * AiConfig configures beans for Spring AI integration, including the ChatClient and related models.
 * <p>
 * This configuration enables the use of Ollama LLM and vector store retrieval for RAG workflows.
 */
@Configuration
public class AiConfig {

    /**
     * Creates a ChatClient bean backed by the provided OllamaChatModel.
     *
     * @param ollamaChatModel the Ollama LLM chat model
     * @return a configured ChatClient instance
     */
    @Bean
    public ChatClient chatClient(OllamaChatModel ollamaChatModel) {
        return ChatClient.create(ollamaChatModel);
    }

//     @Bean
//     public QuestionAnswerAdvisor questionAnswerAdvisor(VectorStore vectorStore) {
//         return QuestionAnswerAdvisor.builder(vectorStore)
//             .searchRequest(SearchRequest.builder().similarityThreshold(0.8d).topK(6).build())
//             .build();
//    }
}
