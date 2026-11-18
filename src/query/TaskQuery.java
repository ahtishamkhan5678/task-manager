package query;

import java.util.ArrayList;
import java.util.List;

/**
 * Options for listTasks. Every field is optional, so it is built with a Builder:
 * TaskQuery.builder().completed(false).priority("HIGH").search("rent").sortBy("dueDate").order("desc").page(1).pageSize(5).build()
 *
 * Values arrive as raw input (priority, sortBy, order are Strings) and are validated by the service.
 */
public final class TaskQuery {
    public static final int DEFAULT_PAGE_SIZE = 10;

    private final Boolean completed;
    private final String priority;
    private final String search;
    private final String sortBy;
    private final String order;
    private final int page;
    private final int pageSize;
    private final List<TaskFilter> extraFilters;

    private TaskQuery(Builder b) {
        this.completed = b.completed;
        this.priority = b.priority;
        this.search = b.search;
        this.sortBy = b.sortBy;
        this.order = b.order;
        this.page = b.page;
        this.pageSize = b.pageSize;
        this.extraFilters = List.copyOf(b.extraFilters);
    }

    public static Builder builder() {
        return new Builder();
    }

    public Boolean getCompleted() {
        return completed;
    }

    public String getPriority() {
        return priority;
    }

    public String getSearch() {
        return search;
    }

    public String getSortBy() {
        return sortBy;
    }

    public String getOrder() {
        return order;
    }

    public int getPage() {
        return page;
    }

    public int getPageSize() {
        return pageSize;
    }

    public List<TaskFilter> getExtraFilters() {
        return extraFilters;
    }

    public static final class Builder {
        private Boolean completed;
        private String priority;
        private String search;
        private String sortBy = "createdAt";
        private String order = "asc";
        private int page = 1;
        private int pageSize = DEFAULT_PAGE_SIZE;
        private final List<TaskFilter> extraFilters = new ArrayList<>();

        private Builder() {
        }

        public Builder completed(Boolean completed) {
            this.completed = completed;
            return this;
        }

        public Builder priority(String priority) {
            this.priority = priority;
            return this;
        }

        public Builder search(String search) {
            this.search = search;
            return this;
        }

        public Builder sortBy(String sortBy) {
            this.sortBy = sortBy;
            return this;
        }

        public Builder order(String order) {
            this.order = order;
            return this;
        }

        public Builder page(int page) {
            this.page = page;
            return this;
        }

        public Builder pageSize(int pageSize) {
            this.pageSize = pageSize;
            return this;
        }

        /** Plug in any extra TaskFilter without changing TaskQuery or TaskService. */
        public Builder filter(TaskFilter filter) {
            this.extraFilters.add(filter);
            return this;
        }

        public TaskQuery build() {
            return new TaskQuery(this);
        }
    }
}
