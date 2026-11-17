package io.iwork.conversation;

import io.iwork.identity.Caller;

/** Runs outside database transactions; only TaskService may publish business results. */
public interface TaskRunner {
    record Input(
            String taskId,
            String conversationId,
            Caller caller,
            String modelId,
            String message,
            String outputMode,
            String instructions) {
        public Input(
                String taskId,
                String conversationId,
                Caller caller,
                String modelId,
                String message,
                String outputMode) {
            this(taskId, conversationId, caller, modelId, message, outputMode, "");
        }

        public Input(
                String taskId,
                String conversationId,
                Caller caller,
                String modelId,
                String message) {
            this(taskId, conversationId, caller, modelId, message, "TEXT");
        }
    }

    String execute(Input input) throws Exception;
}
