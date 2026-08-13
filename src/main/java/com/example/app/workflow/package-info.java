/**
 * Workflow bounded context, implemented as a Spring Modulith application module.
 *
 * <p>Demonstrates event-driven module communication: this module reacts to the
 * activity module's lifecycle events ({@code activity::events} named interface)
 * and never calls into activity internals. It has no knowledge of the activity
 * module's services, repositories or domain objects.
 */
@ApplicationModule(allowedDependencies = {"activity::events", "shared"})
package com.example.app.workflow;

import org.springframework.modulith.ApplicationModule;
