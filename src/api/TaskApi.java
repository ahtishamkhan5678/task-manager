package api;

import exception.ApiException;
import exception.ConflictException;
import exception.ValidationException;
import model.Task;
import model.TaskRequest;
import query.TaskQuery;
import service.TaskService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The public "REST-like" operations. Every method returns an ApiResponse and never throws:
 * ApiException -> its own status in the error shape, anything else -> 500 in the same shape.
 */
public class TaskApi {
    private final TaskService service;
    private final IdempotencyStore idempotencyStore;

    public TaskApi(TaskService service) {
        this(service, IdempotencyStore.getInstance());
    }

    public TaskApi(TaskService service, IdempotencyStore idempotencyStore) {
        this.service = service;
        this.idempotencyStore = idempotencyStore;
    }

    // ---------------- CRUD ----------------

    public ApiResponse createTask(TaskRequest request, String idempotencyKey) {
        String fingerprint = "createTask|" + (request == null ? "null" : request.fingerprint());
        return idempotent(idempotencyKey, fingerprint,
                () -> new ApiResponse(201, TaskMapper.toMap(service.createTask(request))));
    }

    public ApiResponse getTask(int id) {
        return handle(() -> new ApiResponse(200, TaskMapper.toMap(service.getTask(id))));
    }

    public ApiResponse listTasks(TaskQuery query) {
        return handle(() -> new ApiResponse(200, TaskMapper.toPage(service.listTasks(query))));
    }

    public ApiResponse updateTask(int id, TaskRequest request) {
        return handle(() -> new ApiResponse(200, TaskMapper.toMap(service.updateTask(id, request))));
    }

    public ApiResponse completeTask(int id) {
        return handle(() -> new ApiResponse(200, TaskMapper.toMap(service.completeTask(id))));
    }

    public ApiResponse deleteTask(int id) {
        return handle(() -> {
            List<Task> deleted = service.deleteTask(id);
            List<Integer> ids = new ArrayList<>();
            for (Task t : deleted) {
                ids.add(t.getId());
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("deletedId", id);
            body.put("deletedIds", ids);
            body.put("deletedCount", ids.size());
            return new ApiResponse(200, body);
        });
    }

    // ---------------- Subtasks ----------------

    public ApiResponse createSubtask(int parentId, TaskRequest request) {
        return handle(() -> new ApiResponse(201, TaskMapper.toMap(service.createSubtask(parentId, request))));
    }

    public ApiResponse listSubtasks(int parentId) {
        return handle(() -> {
            List<Task> subtasks = service.listSubtasks(parentId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("parentId", parentId);
            body.put("count", subtasks.size());
            body.put("items", TaskMapper.toList(subtasks));
            return new ApiResponse(200, body);
        });
    }

    // ---------------- AI ----------------

    public ApiResponse createTaskFromText(String text, String idempotencyKey) {
        return idempotent(idempotencyKey, "createTaskFromText|" + text,
                () -> new ApiResponse(201, TaskMapper.toMap(service.createTaskFromText(text))));
    }

    public ApiResponse breakDownTask(int id, String idempotencyKey) {
        return idempotent(idempotencyKey, "breakDownTask|" + id, () -> {
            List<Task> subtasks = service.breakDownTask(id);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("parentId", id);
            body.put("count", subtasks.size());
            body.put("subtasks", TaskMapper.toList(subtasks));
            return new ApiResponse(201, body);
        });
    }

    // ---------------- Query ----------------

    public ApiResponse listByPriority(String level) {
        return handle(() -> {
            List<Task> tasks = service.listByPriority(level);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("priority", level);
            body.put("count", tasks.size());
            body.put("items", TaskMapper.toList(tasks));
            return new ApiResponse(200, body);
        });
    }

    // ---------------- Plumbing ----------------

    /** try/catch around every operation: one place converts exceptions into the error shape. */
    private ApiResponse handle(Supplier<ApiResponse> operation) {
        try {
            return operation.get();
        } catch (ApiException e) {
            return ErrorResponseFactory.from(e);
        } catch (Exception e) {
            return ErrorResponseFactory.internalError(e);
        }
    }

    /**
     * Idempotency rules:
     *  - null key: just run the operation.
     *  - key seen with the same fingerprint: return the stored response, run nothing.
     *  - key seen with a different fingerprint: 422.
     *  - only successful (2xx) responses are stored, so a client can fix bad input and retry with the same key.
     */
    private ApiResponse idempotent(String key, String fingerprint, Supplier<ApiResponse> operation) {
        if (key == null) {
            return handle(operation);
        }
        if (key.isBlank()) {
            return ErrorResponseFactory.from(
                    new ValidationException("idempotencyKey", "must not be blank when provided"));
        }
        synchronized (idempotencyStore) {
            IdempotencyStore.Record existing = idempotencyStore.find(key);
            if (existing != null) {
                if (existing.getFingerprint().equals(fingerprint)) {
                    return existing.getResponse();
                }
                return ErrorResponseFactory.from(new ConflictException(
                        "Idempotency key '" + key + "' was already used with a different request"));
            }
            ApiResponse response = handle(operation);
            if (response.isSuccess()) {
                idempotencyStore.save(key, fingerprint, response);
            }
            return response;
        }
    }
}
