package br.ufms.jogai.data.model;

import br.ufms.jogai.domain.shared.Entidade;
import com.google.cloud.Timestamp;

import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

public interface EntityDocument<E extends Entidade<? extends Serializable>> {

    E toEntity();

//    Map<String, Object> toMap();

    static Timestamp toTimestamp(Instant instant) {
        Objects.requireNonNull(instant);

        return Timestamp.ofTimeSecondsAndNanos(
                instant.getEpochSecond(),
                instant.getNano()
        );
    }

    static Timestamp toTimestamp(LocalDate date) {
        Objects.requireNonNull(date);

        return toTimestamp(
                date.atStartOfDay(ZoneOffset.UTC).toInstant()
        );
    }

    static Instant toInstant(Timestamp timestamp) {
        Objects.requireNonNull(timestamp);

        return Instant.ofEpochSecond(
                timestamp.getSeconds(),
                timestamp.getNanos()
        );
    }

    static LocalDate toLocalDate(Timestamp timestamp) {
        return toInstant(timestamp)
                .atZone(ZoneOffset.UTC)
                .toLocalDate();
    }

    static LocalDateTime toLocalDateTime(Timestamp timestamp) {
        return toInstant(timestamp)
                .atZone(ZoneOffset.UTC)
                .toLocalDateTime();
    }
}
