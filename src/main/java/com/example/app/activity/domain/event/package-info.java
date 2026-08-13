/**
 * Lifecycle events of the activity bounded context, exposed to other modules
 * through the named interface {@code events}.
 *
 * <p>Events carry simple types only (UUID, String) - never domain objects - so
 * consumers depend on the event contract, not on activity internals. They are
 * deliberately implemented as plain Spring events: in-process, transactional,
 * no durable delivery (see docs/event-driven.md for the evolution path).
 */
@NamedInterface("events")
package com.example.app.activity.domain.event;

import org.springframework.modulith.NamedInterface;
