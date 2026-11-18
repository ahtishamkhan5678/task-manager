package exception;

/** One problem with one input field, e.g. field "title", issue "must not be empty". */
public final class FieldIssue {
    private final String field;
    private final String issue;

    public FieldIssue(String field, String issue) {
        this.field = field;
        this.issue = issue;
    }

    public String getField() {
        return field;
    }

    public String getIssue() {
        return issue;
    }

    @Override
    public String toString() {
        return field + ": " + issue;
    }
}
