package javax.sip;

public class PeerUnavailableException extends Exception {
    public PeerUnavailableException() {
    }

    public PeerUnavailableException(String message) {
        super(message);
    }

    public PeerUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}

