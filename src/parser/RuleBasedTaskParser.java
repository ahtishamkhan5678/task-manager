package parser;

import model.ParsedTask;

import java.time.DayOfWeek;
import java.time.LocalDate;

/**
 * Offline TaskParser built only from String methods. Understands:
 * "today", "tomorrow", "next friday" / "on monday", ordinal days like "1st" or "15th",
 * and repeats like "every day/week/month/year", "daily", "weekly", "monthly", "yearly".
 */
public class RuleBasedTaskParser implements TaskParser {

    private static final String[] PREFIXES = {
            "remind me to ", "remind me ", "please ", "i need to ", "i have to ", "i must ", "todo: ", "todo "
    };
    private static final String[] CUT_WORDS = {
            " on the ", " every ", " tomorrow", " today", " next ", " on ", " by ", " daily", " weekly",
            " monthly", " yearly", " each "
    };

    @Override
    public ParsedTask parse(String text, String today) {
        String original = text.trim();
        String lower = original.toLowerCase();
        LocalDate base = LocalDate.parse(today);

        String recurrence = findRecurrence(lower);
        LocalDate due = findDueDate(lower, base);

        return new ParsedTask(
                buildTitle(original),
                original,
                due == null ? null : due.toString(),
                recurrence);
    }

    private String findRecurrence(String lower) {
        if (lower.contains("every day") || lower.contains("daily")) {
            return "DAILY";
        }
        if (lower.contains("every week") || lower.contains("weekly")) {
            return "WEEKLY";
        }
        if (lower.contains("every month") || lower.contains("monthly")) {
            return "MONTHLY";
        }
        if (lower.contains("every year") || lower.contains("yearly") || lower.contains("annually")) {
            return "YEARLY";
        }
        return null;
    }

    private LocalDate findDueDate(String lower, LocalDate base) {
        if (lower.contains("tomorrow")) {
            return base.plusDays(1);
        }
        if (lower.contains("today") || lower.contains("tonight")) {
            return base;
        }
        Integer day = findOrdinalDay(lower);
        if (day != null) {
            return nextDayOfMonth(base, day);
        }
        for (DayOfWeek dow : DayOfWeek.values()) {
            String name = dow.name().toLowerCase();
            if (lower.contains("next " + name) || lower.contains("on " + name) || lower.contains("this " + name)) {
                LocalDate d = base.plusDays(1);
                while (d.getDayOfWeek() != dow) {
                    d = d.plusDays(1);
                }
                return d;
            }
        }
        return null;
    }

    /** Finds a word like "1st", "2nd", "23rd", "15th" and returns the number, using String methods only. */
    private Integer findOrdinalDay(String lower) {
        for (String raw : lower.split(" ")) {
            String word = raw.replace(",", "").replace(".", "");
            if (word.length() < 3) {
                continue;
            }
            String suffix = word.substring(word.length() - 2);
            String digits = word.substring(0, word.length() - 2);
            boolean isOrdinal = suffix.equals("st") || suffix.equals("nd") || suffix.equals("rd") || suffix.equals("th");
            if (isOrdinal && !digits.isEmpty() && digits.length() <= 2 && digits.chars().allMatch(Character::isDigit)) {
                int day = Integer.parseInt(digits);
                if (day >= 1 && day <= 31) {
                    return day;
                }
            }
        }
        return null;
    }

    /** Next date (today or later) whose day-of-month is {@code day}; skips months that are too short. */
    private LocalDate nextDayOfMonth(LocalDate base, int day) {
        LocalDate month = base.withDayOfMonth(1);
        for (int i = 0; i < 24; i++) {
            if (day <= month.lengthOfMonth()) {
                LocalDate candidate = month.withDayOfMonth(day);
                if (!candidate.isBefore(base)) {
                    return candidate;
                }
            }
            month = month.plusMonths(1);
        }
        return null;
    }

    private String buildTitle(String original) {
        String title = original;
        String lower = title.toLowerCase();
        for (String prefix : PREFIXES) {
            if (lower.startsWith(prefix)) {
                title = title.substring(prefix.length());
                lower = title.toLowerCase();
                break;
            }
        }
        int cut = title.length();
        for (String word : CUT_WORDS) {
            int index = lower.indexOf(word);
            if (index > 0 && index < cut) {
                cut = index;
            }
        }
        title = title.substring(0, cut).trim();
        while (title.endsWith(".") || title.endsWith(",") || title.endsWith("!")) {
            title = title.substring(0, title.length() - 1).trim();
        }
        if (title.isEmpty()) {
            return title;
        }
        return Character.toUpperCase(title.charAt(0)) + title.substring(1);
    }
}
