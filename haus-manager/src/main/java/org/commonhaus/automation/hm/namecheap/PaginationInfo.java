package org.commonhaus.automation.hm.namecheap;

public record PaginationInfo(
        int totalItems,
        int currentPage,
        int pageSize) {

    public boolean hasMorePages() {
        return currentPage * pageSize < totalItems;
    }

    public int nextPage() {
        return currentPage + 1;
    }
}