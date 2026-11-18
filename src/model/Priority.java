package model;

/** Task priority. Default is MEDIUM. */
public enum Priority {
    LOW, MEDIUM, HIGH;

    /** True only for the exact names LOW, MEDIUM, HIGH. */
    public static boolean isValid(String value) {
        if (value == null) {
            return false;
        }
        for (Priority p : values()) {
            if (p.name().equals(value)) {
                return true;
            }
        }
        return false;
    }
}
