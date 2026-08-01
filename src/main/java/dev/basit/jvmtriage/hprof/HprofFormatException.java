package dev.basit.jvmtriage.hprof;

/** Raised when a dump does not conform to the HPROF binary format. */
public class HprofFormatException extends RuntimeException {

    public HprofFormatException(String message) {
        super(message);
    }

    public HprofFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
