package javax.sip;

public class TransactionAlreadyExistsException extends Exception {
    public TransactionAlreadyExistsException(){
    }

    public TransactionAlreadyExistsException(String message) {
        super(message);
    }

    public TransactionAlreadyExistsException(String message, Throwable cause) {
        super(message, cause);
    }
}

