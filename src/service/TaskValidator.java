package service;

import exception.FieldIssue;
import exception.ValidationException;
import model.Priority;
import model.Recurrence;
import model.Task;
import model.TaskRequest;

import java.util.ArrayList;
import java.util.List;

/**
 * All input rules in one place (Single Responsibility).
 * validate() checks every field, collects ALL problems, and throws one ValidationException.
 * On success it returns a Task.Builder already filled with the converted values.
 */
public class TaskValidator {

    public Task.Builder validate(TaskRequest request) {
        if (request == null) {
            throw new ValidationException("request", "must not be null");
        }
        List<FieldIssue> issues = new ArrayList<>();

        if (isBlank(request.getTitle())) {
            issues.add(new FieldIssue("title", "must not be empty"));
        }
        if (isBlank(request.getDescription())) {
            issues.add(new FieldIssue("description", "must not be empty"));
        }

        boolean completed = false;
        String completedRaw = request.getCompleted();
        if (completedRaw != null) {
            if (completedRaw.equals("true")) {
                completed = true;
            } else if (!completedRaw.equals("false")) {
                issues.add(new FieldIssue("completed", "must be exactly \"true\" or \"false\""));
            }
        }

        String dueDate = request.getDueDate();
        if (dueDate != null && !isValidDate(dueDate)) {
            issues.add(new FieldIssue("dueDate", "must be a real date in YYYY-MM-DD format"));
        }

        Priority priority = Priority.MEDIUM;
        String priorityRaw = request.getPriority();
        if (priorityRaw != null) {
            if (Priority.isValid(priorityRaw)) {
                priority = Priority.valueOf(priorityRaw);
            } else {
                issues.add(new FieldIssue("priority", "must be LOW, MEDIUM, or HIGH"));
            }
        }

        Recurrence recurrence = null;
        String recurrenceRaw = request.getRecurrence();
        if (recurrenceRaw != null) {
            if (Recurrence.isValid(recurrenceRaw)) {
                recurrence = Recurrence.valueOf(recurrenceRaw);
            } else {
                issues.add(new FieldIssue("recurrence", "must be DAILY, WEEKLY, MONTHLY, or YEARLY"));
            }
        }

        if (!issues.isEmpty()) {
            throw new ValidationException(issues);
        }

        return new Task.Builder(request.getTitle().trim(), request.getDescription().trim(), completed)
                .dueDate(dueDate)
                .priority(priority)
                .recurrence(recurrence);
    }

    /** Checks the priority string used by listByPriority and TaskQuery. */
    public Priority validatePriority(String field, String value) {
        if (!Priority.isValid(value)) {
            throw new ValidationException(field, "must be LOW, MEDIUM, or HIGH");
        }
        return Priority.valueOf(value);
    }

    /**
     * True for a real calendar date written as YYYY-MM-DD.
     * Month and day are checked by hand, including leap years, so 2026-02-30 and 2025-02-29 fail.
     */
    public static boolean isValidDate(String value) {
        if (value == null || value.length() != 10 || value.charAt(4) != '-' || value.charAt(7) != '-') {
            return false;
        }
        String yearPart = value.substring(0, 4);
        String monthPart = value.substring(5, 7);
        String dayPart = value.substring(8, 10);
        if (!isDigits(yearPart) || !isDigits(monthPart) || !isDigits(dayPart)) {
            return false;
        }
        int year = Integer.parseInt(yearPart);
        int month = Integer.parseInt(monthPart);
        int day = Integer.parseInt(dayPart);
        if (year < 1 || month < 1 || month > 12 || day < 1) {
            return false;
        }
        return day <= daysInMonth(year, month);
    }

    public static boolean isLeapYear(int year) {
        return (year % 4 == 0 && year % 100 != 0) || year % 400 == 0;
    }

    private static int daysInMonth(int year, int month) {
        return switch (month) {
            case 2 -> isLeapYear(year) ? 29 : 28;
            case 4, 6, 9, 11 -> 30;
            default -> 31;
        };
    }

    private static boolean isDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
