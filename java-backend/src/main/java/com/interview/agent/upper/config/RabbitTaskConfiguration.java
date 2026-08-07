package com.interview.agent.upper.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Qualifier;
import java.util.Map;

@Configuration
public class RabbitTaskConfiguration {
    public static final String EXCHANGE = "interview.agent.tasks";
    public static final String QUEUE = "interview.agent.interview.create";
    public static final String ROUTING_KEY = "interview.create";
    public static final String DEAD_LETTER_EXCHANGE = "interview.agent.dlx";
    public static final String DEAD_LETTER_QUEUE = "interview.agent.interview.create.dlq";
    public static final String AGENT_WORK_QUEUE = "interview.agent.work.execute";
    public static final String AGENT_WORK_ROUTING_KEY = "agent.work.execute";
    public static final String AGENT_WORK_DEAD_LETTER_QUEUE = "interview.agent.work.execute.dlq";

    @Bean
    DirectExchange interviewTaskExchange() { return new DirectExchange(EXCHANGE, true, false); }

    @Bean
    Queue interviewTaskQueue() {
        return new Queue(QUEUE, true, false, false,
                Map.of("x-dead-letter-exchange", DEAD_LETTER_EXCHANGE,
                        "x-dead-letter-routing-key", ROUTING_KEY));
    }

    @Bean
    Binding interviewTaskBinding(@Qualifier("interviewTaskQueue") Queue interviewTaskQueue,
                                 @Qualifier("interviewTaskExchange") DirectExchange interviewTaskExchange) {
        return BindingBuilder.bind(interviewTaskQueue).to(interviewTaskExchange).with(ROUTING_KEY);
    }

    @Bean
    DirectExchange interviewDeadLetterExchange() { return new DirectExchange(DEAD_LETTER_EXCHANGE, true, false); }

    @Bean
    Queue interviewDeadLetterQueue() { return new Queue(DEAD_LETTER_QUEUE, true); }

    @Bean
    Binding interviewDeadLetterBinding(@Qualifier("interviewDeadLetterQueue") Queue interviewDeadLetterQueue,
                                       @Qualifier("interviewDeadLetterExchange") DirectExchange interviewDeadLetterExchange) {
        return BindingBuilder.bind(interviewDeadLetterQueue).to(interviewDeadLetterExchange).with(ROUTING_KEY);
    }

    @Bean
    Queue agentWorkQueue() {
        return new Queue(AGENT_WORK_QUEUE, true, false, false,
                Map.of("x-dead-letter-exchange", DEAD_LETTER_EXCHANGE,
                        "x-dead-letter-routing-key", AGENT_WORK_ROUTING_KEY));
    }

    @Bean
    Binding agentWorkBinding(@Qualifier("agentWorkQueue") Queue agentWorkQueue,
                             @Qualifier("interviewTaskExchange") DirectExchange interviewTaskExchange) {
        return BindingBuilder.bind(agentWorkQueue).to(interviewTaskExchange).with(AGENT_WORK_ROUTING_KEY);
    }

    @Bean
    Queue agentWorkDeadLetterQueue() { return new Queue(AGENT_WORK_DEAD_LETTER_QUEUE, true); }

    @Bean
    Binding agentWorkDeadLetterBinding(@Qualifier("agentWorkDeadLetterQueue") Queue agentWorkDeadLetterQueue,
                                       @Qualifier("interviewDeadLetterExchange") DirectExchange interviewDeadLetterExchange) {
        return BindingBuilder.bind(agentWorkDeadLetterQueue).to(interviewDeadLetterExchange).with(AGENT_WORK_ROUTING_KEY);
    }

    @Bean
    Jackson2JsonMessageConverter rabbitMessageConverter() { return new Jackson2JsonMessageConverter(); }

    @Bean
    RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                  Jackson2JsonMessageConverter converter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(converter);
        return template;
    }
}
