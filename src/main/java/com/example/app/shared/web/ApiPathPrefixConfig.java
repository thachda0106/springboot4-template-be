package com.example.app.shared.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Applies the global API version prefix {@code /api/v1} to every
 * {@link RestController} inside the application packages
 * ({@code com.example.app}). Controllers declare resource-relative base paths
 * (e.g. {@code /activities}) and the prefix is added centrally here, so new
 * controllers get it for free. Non-controller endpoints (actuator, error
 * dispatch, static resources) are not affected.
 *
 * <p>The prefix is deliberately restricted to {@code com.example.app}:
 * library controllers (e.g. springdoc's {@code /v3/api-docs} resource) must
 * keep their framework-defined paths. A single {@link HandlerTypePredicate}
 * cannot express the required AND of package + annotation — since Framework 7
 * its selectors are combined with OR — so a plain predicate is used.
 */
@Configuration(proxyBeanMethods = false)
public class ApiPathPrefixConfig implements WebMvcConfigurer {

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        configurer.addPathPrefix("/api/v1", this::isApplicationRestController);
    }

    private boolean isApplicationRestController(Class<?> handlerType) {
        return handlerType.getPackageName().startsWith("com.example.app.")
                && AnnotatedElementUtils.hasAnnotation(handlerType, RestController.class);
    }
}
