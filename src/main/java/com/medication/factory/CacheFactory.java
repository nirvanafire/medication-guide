package com.medication.factory;

import com.medication.config.CacheConfig;
import com.medication.service.ICacheService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Optional;

@Configuration
public class CacheFactory {

    @Autowired
    private CacheConfig cacheConfig;

    @Bean
    public ICacheService cacheService(Optional<ICacheService> cacheService) {
        return cacheService.orElse(null);
    }
}
