package ru.sensoterra.server.repository;

import io.micronaut.data.annotation.Repository;
import io.micronaut.data.jpa.repository.JpaRepository;
import ru.sensoterra.server.entity.Alarm;

import java.util.List;
import java.util.Optional;

@Repository
public interface AlarmRepository extends JpaRepository<Alarm, Long> {

    Optional<Alarm> findFirstByChipIdAndAlarmTypeAndEndedAtIsNull(Long chipId, String alarmType);

    List<Alarm> findByAlarmTypeAndEndedAtIsNull(String alarmType);
}
