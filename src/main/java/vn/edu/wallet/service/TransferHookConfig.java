package vn.edu.wallet.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TransferHookConfig {
    @Bean
    @ConditionalOnMissingBean(TransferWriteHook.class)
    TransferWriteHook transferWriteHook() { return () -> {}; }
}
