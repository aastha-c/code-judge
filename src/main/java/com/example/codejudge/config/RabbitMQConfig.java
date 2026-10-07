package com.example.codejudge.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    @Value("${judge.rabbitmq.queue:submissionQueue}")
    private String queueName;

    @Value("${judge.rabbitmq.exchange:submissionExchange}")
    private String exchangeName;

    @Value("${judge.rabbitmq.routing-key:submissionRoutingKey}")
    private String routingKey;

    @Bean
    public Queue submissionQueue() {
        return QueueBuilder.durable(queueName).build();
    }

    @Bean
    public DirectExchange submissionExchange() {
        return new DirectExchange(exchangeName);
    }

    @Bean
    public Binding binding(Queue submissionQueue, DirectExchange submissionExchange) {
        return BindingBuilder.bind(submissionQueue).to(submissionExchange).with(routingKey);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
