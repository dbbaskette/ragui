package com.baskettecase.ragui.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ configuration for embedProc monitoring.
 * Only activated when embedProc monitoring is enabled via properties.
 */
@Configuration
@EnableRabbit
@ConditionalOnProperty(
    value = "app.monitoring.rabbitmq.enabled", 
    havingValue = "true", 
    matchIfMissing = true
)
public class RabbitMQConfig {
    
    private static final Logger logger = LoggerFactory.getLogger(RabbitMQConfig.class);
    
    @Value("${app.monitoring.rabbitmq.queue-name:embedproc.metrics}")
    private String queueName;
    
    /**
     * Declare the embedProc metrics queue.
     * Queue will be durable and non-exclusive.
     */
    @Bean
    public Queue embedProcMetricsQueue() {
        logger.info("Declaring RabbitMQ queue: {}", queueName);
        return QueueBuilder
            .durable(queueName)
            .build();
    }
    
    /**
     * Configure Jackson JSON message converter for RabbitMQ.
     * This enables automatic JSON serialization/deserialization.
     */
    @Bean
    public Jackson2JsonMessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
    
    /**
     * Configure RabbitTemplate with JSON message converter.
     */
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter());
        return template;
    }
}