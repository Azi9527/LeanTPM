package com.leantpm.opscontrol.api;

import com.leantpm.opscontrol.release.ReleaseWorkflowException;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class OpsApiExceptionHandler {

    @ExceptionHandler(ReleaseWorkflowException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError workflow(ReleaseWorkflowException exception) {
        return new ApiError("OPS_REQUEST_REJECTED", exception.getMessage(), Instant.now());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError validation() {
        return new ApiError("OPS_REQUEST_INVALID", "Request validation failed", Instant.now());
    }

    public record ApiError(String code, String message, Instant timestamp) {
    }
}
