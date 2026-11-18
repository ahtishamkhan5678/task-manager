package parser;

import model.Task;
import model.TaskRequest;

import java.util.List;

/**
 * Strategy interface: splits a big task into subtask requests.
 * The requests are NOT trusted; TaskService validates every one before saving any.
 */
public interface BreakdownStrategy {

    List<TaskRequest> breakDown(Task parent);

    /** Helper: a subtask request that inherits the parent's due date and priority. */
    static TaskRequest subtaskRequest(Task parent, String title, String description) {
        return new TaskRequest(title, description, "false", parent.getDueDate(), parent.getPriority().name());
    }
}
