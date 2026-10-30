package ru.sensoterra.server.repository;

import ru.sensoterra.server.entity.AppUser;
import io.micronaut.data.annotation.Repository;
import io.micronaut.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByUsername(String username);

    List<AppUser> findByIdIn(Collection<Long> ids);

    /** Поиск по внешней личности: пара «провайдер + sub» и есть аккаунт. */
    Optional<AppUser> findByAuthProviderAndAuthSubject(String authProvider, String authSubject);

    Optional<AppUser> findByEmailIgnoreCase(String email);
}