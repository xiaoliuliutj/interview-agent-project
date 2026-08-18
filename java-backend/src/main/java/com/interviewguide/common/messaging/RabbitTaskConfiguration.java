package com.interviewguide.common.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/** RabbitMQ topology for concrete Agent work only: resume analysis and knowledge-base indexing. */
@Configuration
public class RabbitTaskConfiguration {
    public static final String EXCHANGE = "interview.agent.work";
    public static final String AGENT_WORK_QUEUE = "interview.agent.work.execute";
    public static final String AGENT_WORK_ROUTING_KEY = "agent.work.execute";
    public static final String DEAD_LETTER_EXCHANGE = "interview.agent.work.dlx";
    public static final String AGENT_WORK_DEAD_LETTER_QUEUE = "interview.agent.work.execute.dlq";

    @Bean
    DirectExchange agentWorkExchange() { return new DirectExchange(EXCHANGE, true, false); }

    @Bean
    Queue agentWorkQueue() {
        return new Queue(AGENT_WORK_QUEUE, true, false, false,
                Map.of("x-dead-letter-exchange", DEAD_LETTER_EXCHANGE,
                        "x-dead-letter-routing-key", AGENT_WORK_ROUTING_KEY));
    }

    @Bean
    Binding agentWorkBinding(@Qualifier("agentWorkQueue") Queue queue,
                             @Qualifier("agentWorkExchange") DirectExchange exchange) {
        return BindingBuilder.bind(queue).to(exchange).with(AGENT_WORK_ROUTING_KEY);
    }

    @Bean
    DirectExchange agentWorkDeadLetterExchange() { return new DirectExchange(DEAD_LETTER_EXCHANGE, true, false); }

    @Bean
    Queue agentWorkDeadLetterQueue() { return new Queue(AGENT_WORK_DEAD_LETTER_QUEUE, true); }

    @Bean
    Binding agentWorkDeadLetterBinding(@Qualifier("agentWorkDeadLetterQueue") Queue queue,
                                       @Qualifier("agentWorkDeadLetterExchange") DirectExchange exchange) {
        return BindingBuilder.bind(queue).to(exchange).with(AGENT_WORK_ROUTING_KEY);
    }

    @Bean
    Jackson2JsonMessageConverter rabbitMessageConverter() { return new Jackson2JsonMessageConverter(); }

    @Bean
    RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, Jackson2JsonMessageConverter converter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(converter);
        return template;
    }
}
