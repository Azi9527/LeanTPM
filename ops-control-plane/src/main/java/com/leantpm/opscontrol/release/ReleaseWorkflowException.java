package com.leantpm.opscontrol.release;

public final class ReleaseWorkflowException extends RuntimeException {

    public ReleaseWorkflowException(String message) {
        super(message);
    }

    public ReleaseWorkflowException(String message, Throwable cause) {
        super(message, cause);
    }
}
