package event;

import model.Task;

/** Observer: gets told when something happens to a task. */
public interface TaskEventListener {
    void onEvent(String eventType, Task task);
}
