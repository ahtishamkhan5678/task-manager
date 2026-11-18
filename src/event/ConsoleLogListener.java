package event;

import model.Task;

/** Prints every event to the console. */
public class ConsoleLogListener implements TaskEventListener {
    @Override
    public void onEvent(String eventType, Task task) {
        System.out.println("  [event] " + eventType + " -> id=" + task.getId() + " \"" + task.getTitle() + "\"");
    }
}
