package javax.sip;

public class ObjectInUseException extends Exception {
    public ObjectInUseException() {
    }

    public ObjectInUseException(String message) {
        super(message);
    }

    public ObjectInUseException(String message, Throwable cause) {
        super(message, cause);
    }
}

