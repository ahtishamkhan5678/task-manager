package exception;

/** 404 - the requested task does not exist. */
public class NotFoundException extends ApiException {
    public static final String CODE = "NOT_FOUND";

    public NotFoundException(String message) {
        super(404, CODE, message);
    }

    public static NotFoundException task(int id) {
        return new NotFoundException("Task with id " + id + " was not found");
    }
}
