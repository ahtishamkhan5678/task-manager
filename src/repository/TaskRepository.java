package repository;

import model.Task;

import java.util.List;
import java.util.Optional;

/** Storage abstraction. The service depends on this interface, never on a concrete class (DIP). */
public interface TaskRepository {

    /** Saves a task. A task with id 0 is new and gets the next id (starting at 1). Returns the stored task. */
    Task save(Task task);

    Optional<Task> findById(int id);

    List<Task> findAll();

    /** Returns true if a task was removed. */
    boolean delete(int id);

    /** Removes everything and restarts ids at 1 (used by tests and demos). */
    void clear();
}
