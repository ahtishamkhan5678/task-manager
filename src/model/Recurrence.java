package model;

/** Optional repeat rule produced by the parsers ("every month" -> MONTHLY). */
public enum Recurrence {
    DAILY, WEEKLY, MONTHLY, YEARLY;

    public static boolean isValid(String value) {
        if (value == null) {
            return false;
        }
        for (Recurrence r : values()) {
            if (r.name().equals(value)) {
                return true;
            }
        }
        return false;
    }
}
