package com.example.app.user.application.query;

import com.example.app.user.UserLookup;
import com.example.app.user.domain.exception.UserNotFoundException;
import com.example.app.user.domain.model.User;
import com.example.app.user.domain.model.UserId;
import com.example.app.user.domain.repository.UserRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Read paths of the user module. Implements the cross-module {@link UserLookup}
 * contract and provides the full-user read used by the module's own API.
 */
@Service
public class UserLookupService implements UserLookup {

    private final UserRepository userRepository;

    public UserLookupService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    @Transactional(readOnly = true)
    // Cached in Redis (user-summaries:<userId>, 60s TTL backstop). All writes go through
    // the use cases, which evict this cache. On a Redis outage the cache fails open (miss -> DB).
    @Cacheable(cacheNames = "user-summaries", key = "#userId")
    public Optional<Summary> findById(String userId) {
        return userRepository.findById(UserId.from(java.util.UUID.fromString(userId)))
                .map(user -> new Summary(user.id().value().toString(), user.name()));
    }

    @Transactional(readOnly = true)
    public User getById(UserId id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException(id.value().toString()));
    }
}
