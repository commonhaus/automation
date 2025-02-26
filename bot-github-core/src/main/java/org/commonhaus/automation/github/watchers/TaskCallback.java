package org.commonhaus.automation.github.watchers;

import java.util.function.Consumer;

public record TaskCallback<T>(
        String taskGroupName,
        Consumer<T> callback) {

    public void run(T update) {
        callback.accept(update);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof TaskCallback) {
            TaskCallback<?> other = (TaskCallback<?>) obj;
            return taskGroupName.equals(other.taskGroupName);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return taskGroupName.hashCode();
    }
}
