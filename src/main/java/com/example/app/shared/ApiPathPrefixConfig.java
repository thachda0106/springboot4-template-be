package com.example.app.shared;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerTypePredicate;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Applies the global API version prefix {@code /api/v1} to every
 * {@link RestController}. Controllers declare resource-relative base paths
 * (e.g. {@code /activities}) and the prefix is added centrally here, so new
 * controllers get it for free. Non-controller endpoints (actuator, error
 * dispatch, static resources) are not affected.
 */
@Configuration(proxyBeanMethods = false)
public class ApiPathPrefixConfig implements WebMvcConfigurer {

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        configurer.addPathPrefix("/api/v1",
                HandlerTypePredicate.forAnnotation(RestController.class));
    }
}
