package query;

import model.Task;

import java.util.List;

/** One page of results plus the numbers a client needs to page through. */
public final class PageResult {
    private final List<Task> items;
    private final int page;
    private final int pageSize;
    private final int totalItems;

    public PageResult(List<Task> items, int page, int pageSize, int totalItems) {
        this.items = List.copyOf(items);
        this.page = page;
        this.pageSize = pageSize;
        this.totalItems = totalItems;
    }

    public List<Task> getItems() {
        return items;
    }

    public int getPage() {
        return page;
    }

    public int getPageSize() {
        return pageSize;
    }

    public int getTotalItems() {
        return totalItems;
    }

    public int getTotalPages() {
        return (totalItems + pageSize - 1) / pageSize;
    }
}
