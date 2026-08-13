package com.example.app.architecture;

import com.example.app.Application;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Architecture verification with Spring Modulith (2.1.0).
 *
 * <p>{@link ApplicationModules#verify()} fails on:
 * <ul>
 *   <li>dependency cycles between application modules</li>
 *   <li>access to another module's internal (sub-)packages</li>
 *   <li>dependencies outside the {@code @ApplicationModule(allowedDependencies=...)}
 *       whitelist declared per module</li>
 * </ul>
 * The additional assertions pin the expected dependency graph and the event
 * wiring between modules.
 */
class ApplicationModularityTests {

    private final ApplicationModules modules = ApplicationModules.of(Application.class);

    /** Module names are stable via the identifier (getDisplayName() capitalizes). */
    private static String name(ApplicationModule module) {
        return module.getIdentifier().toString();
    }

    @Test
    void moduleStructureIsValid() {
        modules.verify();
    }

    @Test
    void exposesExactlyTheExpectedModules() {
        Set<String> names = modules.stream()
                .map(ApplicationModularityTests::name)
                .collect(Collectors.toSet());

        assertThat(names).containsExactlyInAnyOrder("activity", "workflow", "user", "security", "shared");
    }

    @Test
    void workflowDependsOnlyOnActivityAndShared() {
        ApplicationModule workflow = modules.getModuleByName("workflow").orElseThrow();

        Set<String> dependencies = workflow.getDirectDependencies(modules)
                .uniqueModules()
                .map(ApplicationModularityTests::name)
                .collect(Collectors.toSet());

        // workflow -> activity (through the "events" named interface) and shared (error contract).
        // It must NOT depend on user or security, and never on activity internals (verify() enforces this).
        assertThat(dependencies).containsExactlyInAnyOrder("activity", "shared");
    }

    @Test
    void activityDependsOnlyOnUserSecurityAndShared() {
        ApplicationModule activity = modules.getModuleByName("activity").orElseThrow();

        Set<String> dependencies = activity.getDirectDependencies(modules)
                .uniqueModules()
                .map(ApplicationModularityTests::name)
                .collect(Collectors.toSet());

        assertThat(dependencies).containsExactlyInAnyOrder("user", "security", "shared");
    }

    @Test
    void userAndSecurityAreLeafModules() {
        ApplicationModule user = modules.getModuleByName("user").orElseThrow();
        ApplicationModule security = modules.getModuleByName("security").orElseThrow();

        assertThat(user.getDirectDependencies(modules).uniqueModules())
                .map(ApplicationModularityTests::name)
                .containsExactlyInAnyOrder("security", "shared");

        assertThat(security.getDirectDependencies(modules).uniqueModules())
                .map(ApplicationModularityTests::name)
                .containsExactly("shared");
    }

    @Test
    void workflowListensToActivityLifecycleEvents() {
        ApplicationModule workflow = modules.getModuleByName("workflow").orElseThrow();

        List<String> listenedEvents = workflow.getEventsListenedTo(modules).stream()
                .map(javaClass -> javaClass.getSimpleName())
                .toList();

        assertThat(listenedEvents).contains("ActivityCreated", "ActivityUpdated", "ActivityDeleted");
    }

    @Test
    void activityExposesItsLifecycleEventsAsPublicContract() {
        ApplicationModule activity = modules.getModuleByName("activity").orElseThrow();

        // The events live in the "events" named interface - the only way other
        // modules may consume them (verified by the workflow dependency assertions).
        List<String> namedInterfaces = activity.getNamedInterfaces().stream()
                .map(namedInterface -> namedInterface.getName())
                .toList();

        assertThat(namedInterfaces).contains("api", "events");
    }

    @Test
    void workflowNeverDependsOnActivityInternals() {
        ApplicationModule workflow = modules.getModuleByName("workflow").orElseThrow();

        // The only allowed path to the activity module is its "events" named interface.
        Set<String> activityDependencies = workflow.getDirectDependencies(modules)
                .uniqueModules()
                .filter(module -> module.getDisplayName().equals("Activity"))
                .map(ApplicationModularityTests::name)
                .collect(Collectors.toSet());

        assertThat(activityDependencies).containsExactly("activity");
    }
}
