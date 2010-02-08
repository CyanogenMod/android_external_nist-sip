package javax.sip;

public class ProviderDoesNotExistException extends Exception {
    public ProviderDoesNotExistException(){
    }

    public ProviderDoesNotExistException(String message) {
        super(message);
    }

    public ProviderDoesNotExistException(String message, Throwable cause) {
        super(message, cause);
    }
}

