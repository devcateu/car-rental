package org.example.carrental.api.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Single error shape for the whole API.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ErrorResponse(

        String code,
        String message,
        List<String> details,

        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
        LocalDateTime timestamp) {

    public ErrorResponse {
        details = details == null ? List.of() : List.copyOf(details);
    }

    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(code, message, List.of(), LocalDateTime.now());
    }

    public static ErrorResponse of(String code, String message, List<String> details) {
        return new ErrorResponse(code, message, details, LocalDateTime.now());
    }
}
