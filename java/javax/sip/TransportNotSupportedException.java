package javax.sip;

public class TransportNotSupportedException extends Exception {
    public TransportNotSupportedException() {
    }

    public TransportNotSupportedException(String message) {
        super(message);
    }

    public TransportNotSupportedException(String message, Throwable cause) {
        super(message, cause);
    }
}

