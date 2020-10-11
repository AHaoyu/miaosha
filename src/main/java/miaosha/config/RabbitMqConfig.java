package miaosha.config;

import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


@Configuration
public class RabbitMqConfig {

    @Bean
    public Queue DelCacheQueue() {
        return new Queue("delCache");
    }

    @Bean
    public Queue OrderQueue() {
        return new Queue("orderQueue");
    }

}
