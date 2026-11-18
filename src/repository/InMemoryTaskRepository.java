package repository;

import model.Task;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory TaskRepository, written as a Singleton: the whole program shares one task store.
 * Methods are synchronized so the store stays consistent if several threads use it.
 */
public final class InMemoryTaskRepository implements TaskRepository {

    private static InMemoryTaskRepository instance;

    private final Map<Integer, Task> tasks = new LinkedHashMap<>();
    private int nextId = 1;

    private InMemoryTaskRepository() {
    }

    public static synchronized InMemoryTaskRepository getInstance() {
        if (instance == null) {
            instance = new InMemoryTaskRepository();
        }
        return instance;
    }

    @Override
    public synchronized Task save(Task task) {
        Task toStore = task;
        if (task.getId() <= 0) {
            toStore = task.toBuilder().id(nextId++).build();
        }
        tasks.put(toStore.getId(), toStore);
        return toStore;
    }

    @Override
    public synchronized Optional<Task> findById(int id) {
        return Optional.ofNullable(tasks.get(id));
    }

    @Override
    public synchronized List<Task> findAll() {
        return new ArrayList<>(tasks.values());
    }

    @Override
    public synchronized boolean delete(int id) {
        return tasks.remove(id) != null;
    }

    @Override
    public synchronized void clear() {
        tasks.clear();
        nextId = 1;
    }
}
