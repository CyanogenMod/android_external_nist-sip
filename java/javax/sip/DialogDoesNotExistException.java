package javax.sip;

public class DialogDoesNotExistException extends Exception {
    public DialogDoesNotExistException(){
    }

    public DialogDoesNotExistException(String message) {
        super(message);
    }

    public DialogDoesNotExistException(String message, Throwable cause) {
        super(message, cause);
    }
}

