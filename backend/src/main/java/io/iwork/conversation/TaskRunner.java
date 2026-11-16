package io.iwork.conversation;

import io.iwork.identity.Caller;

/** Runs outside database transactions; only TaskService may publish business results. */
public interface TaskRunner {
    record Input(String taskId, String conversationId, Caller caller, String modelId, String message) {}
    String execute(Input input) throws Exception;
}
